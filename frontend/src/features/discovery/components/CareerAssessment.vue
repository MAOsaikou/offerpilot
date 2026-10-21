<script setup lang="ts">
import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import BackgroundTaskStatus from '@/shared/components/BackgroundTaskStatus.vue'

import { computed, onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  confirmCareerAssessment,
  getCareerAssessment,
  inferCareerAssessment,
  resetCareerAssessment,
  saveAssessmentAnswer,
  type AssessmentTrait,
  type AssessmentConfirmed,
  type CareerAssessment,
  type RiasecLetter,
} from '@/shared/api/career'

const props = defineProps<{ beforeConfirm?: () => Promise<void> }>()
const emit = defineEmits<{
  confirmed: [city: string]
  changed: [assessment: AssessmentConfirmed | null]
}>()
const LETTERS: RiasecLetter[] = ['R', 'I', 'A', 'S', 'E', 'C']
const LABELS = computed<Record<RiasecLetter, string>>(() => ({
  R: uiText('Realistic · Build things hands-on'),
  I: uiText('Investigative · Explore how things work'),
  A: uiText('Artistic · Create and express yourself'),
  S: uiText('Social · Help and influence others'),
  E: uiText('Enterprising · Organize, lead and persuade'),
  C: uiText('Conventional · Work with order and precision'),
}))
const MIN_ANSWERS = 4

const state = ref<CareerAssessment>()
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const step = ref(0)
const skipped = ref(new Set<number>())
const draft = ref('')
const editing = ref(false)
const scores = ref<Record<RiasecLetter, number>>({ R: 0, I: 0, A: 0, S: 0, E: 0, C: 0 })
const traits = ref<AssessmentTrait[]>([])
const newTrait = ref('')
const constraints = ref({ city: '', salary: '', overtime: '' })
let disposed = false
onBeforeUnmount(() => (disposed = true))

const answered = computed(
  () => state.value?.answers.slice(0, 7).filter((a) => a.trim()).length ?? 0,
)
const questions = computed(() => state.value?.questions ?? [])
const transcript = computed(() =>
  questions.value
    .map((question, index) => ({ question, index }))
    .filter(
      ({ index }) =>
        index !== step.value && (state.value?.answers[index]?.trim() || skipped.value.has(index)),
    ),
)
const finishedQuestions = computed(() => step.value >= questions.value.length)
const chatting = ref(false)
const phase = computed(() => {
  if (!state.value) return 'loading'
  if (editing.value) return 'confirm'
  if (chatting.value || !state.value.confirmed) return 'chat'
  return 'done'
})
const code = computed(() =>
  [...LETTERS]
    .sort((a, b) => scores.value[b] - scores.value[a] || LETTERS.indexOf(a) - LETTERS.indexOf(b))
    .slice(0, 3)
    .join(''),
)

function nextStep(next: CareerAssessment, exclude = -1) {
  const pending = (a: string, i: number) => !a.trim() && i !== exclude && !skipped.value.has(i)
  const extra = next.answers.findIndex((a, i) => i >= 7 && pending(a, i))
  const base = next.answers.findIndex(pending)
  return extra >= 0 ? extra : base < 0 ? next.questions.length : base
}

function apply(next: CareerAssessment) {
  state.value = next
  step.value = nextStep(next)
  draft.value = next.answers[step.value] ?? ''
  // An unconfirmed inference (e.g. after reload) goes straight to the confirmation card.
  if (next.inference && !next.confirmed && !editing.value && !chatting.value)
    startCorrection('inference')
}

async function run(
  action: () => Promise<CareerAssessment>,
  after?: (next: CareerAssessment) => void,
) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const next = await action()
    if (disposed) return
    apply(next)
    after?.(next)
  } catch (e) {
    if (!disposed) error.value = (e as Error).message
  } finally {
    if (!disposed) busy.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const next = await getCareerAssessment()
    if (disposed) return
    apply(next)
  } catch (e) {
    if (!disposed) error.value = (e as Error).message
  } finally {
    if (!disposed) loading.value = false
  }
}
void load()

function send() {
  const index = step.value
  const text = draft.value.trim()
  if (!text) return
  void run(
    () => saveAssessmentAnswer(index, text),
    (next) => {
      // Continue with the next question even when the user is revising earlier answers.
      skipped.value.delete(index)
      step.value = nextStep(next, index)
      draft.value = next.answers[step.value] ?? ''
    },
  )
}

function skip() {
  skipped.value.add(step.value)
  step.value = state.value ? nextStep(state.value) : step.value + 1
  draft.value = state.value?.answers[step.value] ?? ''
}

function revise(index: number) {
  editing.value = false
  chatting.value = true
  step.value = index
  draft.value = state.value?.answers[index] ?? ''
}

function startCorrection(source: 'inference' | 'confirmed') {
  const from = source === 'inference' ? state.value?.inference : state.value?.confirmed
  if (!from) return
  scores.value = { ...from.riasec }
  traits.value = from.traits.map((t) => ({ ...t }))
  constraints.value = { ...from.constraints }
  editing.value = true
}

function infer() {
  void run(inferCareerAssessment, () => {
    chatting.value = false
    startCorrection('inference')
  })
}

function addTrait() {
  const name = newTrait.value.trim()
  if (!name || traits.value.length >= 8 || traits.value.some((t) => t.name === name)) return
  traits.value.push({ name, confidence: 1, evidence: '' })
  newTrait.value = ''
}

function confirm() {
  void run(
    async () => {
      await props.beforeConfirm?.()
      return confirmCareerAssessment({
        riasec: scores.value,
        traits: traits.value.map((t) => t.name),
        ...constraints.value,
      })
    },
    (next) => {
      editing.value = false
      emit('confirmed', constraints.value.city.trim())
      emit('changed', next.confirmed)
    },
  )
}

function restart() {
  if (!window.confirm(uiText('Clear your assessment answers and profile and start again?'))) return
  editing.value = false
  chatting.value = false
  void run(resetCareerAssessment, () => emit('changed', null))
}
async function restoreTask() {
  if (!busy.value) await load()
}
</script>

<template>
  <section class="panel form-stack assessment" aria-labelledby="assessment-title">
    <BackgroundTaskStatus
      kind="CAREER_ASSESSMENT"
      :entity-id="'current'"
      @completed="restoreTask"
    />
    <div class="assessment-head">
      <h2 id="assessment-title">{{ uiText('Conversational career assessment') }}</h2>
      <span v-if="state" class="tag">{{
        uiText('Question {0} of {1}', [answered, questions.length])
      }}</span>
    </div>
    <p v-if="loading" role="status" class="subtle">{{ uiText('Loading assessment…') }}</p>
    <div v-if="error" role="alert" class="error-banner">
      {{ error }}
      <button v-if="!state" class="btn text" @click="load">{{ uiText('Retry') }}</button>
    </div>

    <template v-if="phase === 'chat'">
      <p class="subtle">
        {{
          uiText(
            'No need to answer 60 questions. Answer a few questions about yourself, one at a time. AI will suggest Holland interests and personality traits for you to confirm or adjust.',
          )
        }}
      </p>
      <p class="subtle">
        {{
          uiText(
            'Seven core questions, with up to five follow-ups for brief or unclear answers. No more than 12 exchanges in total.',
          )
        }}
      </p>
      <ol class="transcript">
        <li v-for="{ question, index } in transcript" :key="index">
          <p class="bubble ask">{{ question }}</p>
          <p v-if="state!.answers[index]" class="bubble reply">
            {{ formatDisplay(state!.answers[index]) }}
            <button type="button" class="btn text" :disabled="busy" @click="revise(index)">
              {{ uiText('Edit') }}
            </button>
          </p>
          <p v-else class="subtle">{{ uiText('Skipped') }}</p>
        </li>
      </ol>
      <form v-if="!finishedQuestions" class="form-stack" @submit.prevent="send">
        <p class="bubble ask current">{{ questions[step] }}</p>
        <label class="field"
          >{{ uiText('Your answer')
          }}<textarea
            v-model="draft"
            rows="3"
            maxlength="1000"
            :disabled="busy"
            :placeholder="uiText('A specific experience says more than a label.')"
          />
        </label>
        <div class="card-footer">
          <button type="button" class="btn" :disabled="busy" @click="skip">
            {{ uiText('Skip this question') }}
          </button>
          <button class="btn primary" :disabled="busy || !draft.trim()">
            {{ busy ? uiText('Saving…') : uiText('Send') }}
          </button>
        </div>
      </form>
      <div class="card-footer">
        <span class="subtle">{{
          answered < MIN_ANSWERS
            ? uiText('Answer at least {0} questions to generate a profile', [MIN_ANSWERS])
            : uiText('Your profile is ready to generate')
        }}</span>
        <button
          type="button"
          class="btn primary"
          :disabled="busy || answered < MIN_ANSWERS"
          :aria-busy="busy"
          @click="infer"
        >
          {{ busy ? uiText('Analyzing…') : uiText('Generate my profile') }}
        </button>
      </div>
    </template>

    <template v-else-if="phase === 'confirm'">
      <p class="helper-note">
        {{
          state?.inference?.source === 'rule'
            ? uiText(
                'AI is unavailable. This preliminary profile uses keyword rules. Please review it carefully.',
              )
            : uiText(
                'AI inferred this profile from your words. It may be inaccurate. Adjust scores and edit traits before confirming.',
              )
        }}
      </p>
      <p v-if="state?.inference?.summary" class="assessment-summary">
        {{ state.inference.summary }}
      </p>
      <h3>{{ uiText('Interests · Current interest code: {0}', [code]) }}</h3>
      <div v-for="letter in LETTERS" :key="letter" class="riasec-row">
        <label :for="`riasec-${letter}`">{{ letter }} {{ LABELS[letter] }}</label>
        <input
          :id="`riasec-${letter}`"
          v-model.number="scores[letter]"
          type="range"
          min="0"
          max="100"
          step="5"
          :disabled="busy"
        />
        <output>{{ scores[letter] }}</output>
        <p v-if="state?.inference?.riasecEvidence?.[letter]" class="evidence">
          「{{ state.inference.riasecEvidence[letter] }}」
        </p>
      </div>
      <h3>{{ uiText('Personality and work style') }}</h3>
      <ul class="trait-list">
        <li v-for="(trait, index) in traits" :key="trait.name" class="trait">
          <strong>{{ trait.name }}</strong>
          <span class="subtle">{{
            trait.evidence
              ? uiText('Confidence: {0}%', [Math.round(trait.confidence * 100)])
              : uiText('Add your own')
          }}</span>
          <button
            type="button"
            class="btn text"
            :aria-label="uiText('Delete trait {0}', [trait.name])"
            :disabled="busy"
            @click="traits.splice(index, 1)"
          >
            {{ uiText('Delete') }}
          </button>
          <p v-if="trait.evidence" class="evidence">
            {{ uiText('Evidence: “{0}”', [trait.evidence]) }}
          </p>
        </li>
      </ul>
      <form class="inline-form" @submit.prevent="addTrait">
        <input
          v-model="newTrait"
          maxlength="12"
          :placeholder="uiText('Add a trait, such as resilience')"
          :aria-label="uiText('Add a trait')"
          :disabled="busy || traits.length >= 8"
        />
        <button class="btn" :disabled="busy || !newTrait.trim() || traits.length >= 8">
          {{ uiText('Add') }}
        </button>
      </form>
      <h3>{{ uiText('Practical constraints') }}</h3>
      <div class="form-grid">
        <label class="field"
          >{{ uiText('Preferred city')
          }}<input v-model="constraints.city" maxlength="50" :disabled="busy"
        /></label>
        <label class="field"
          >{{ uiText('Expected monthly salary')
          }}<input v-model="constraints.salary" maxlength="50" :disabled="busy"
        /></label>
        <label class="field"
          >{{ uiText('Overtime preference')
          }}<input v-model="constraints.overtime" maxlength="50" :disabled="busy"
        /></label>
      </div>
      <div class="card-footer">
        <button type="button" class="btn" :disabled="busy" @click="revise(0)">
          {{ uiText('Edit my answers') }}
        </button>
        <button type="button" class="btn primary" :disabled="busy" @click="confirm">
          {{ busy ? uiText('Saving…') : uiText('Confirm profile') }}
        </button>
      </div>
    </template>

    <template v-else-if="phase === 'done' && state?.confirmed">
      <p class="success-banner" role="status">
        {{ uiText('Profile confirmed. Interest code:') }}
        <strong>{{ state.confirmed.code || uiText('Undifferentiated') }}</strong
        >。{{
          state.confirmed.code
            ? uiText('Career recommendations will prioritize this profile.')
            : uiText('Add your interests before requesting career recommendations.')
        }}
      </p>
      <p class="subtle">
        {{ state.confirmed.traits.map((t) => t.name).join(' · ') }}
      </p>
      <div class="card-footer">
        <button type="button" class="btn text" :disabled="busy" @click="restart">
          {{ uiText('Retake assessment') }}
        </button>
        <button type="button" class="btn" :disabled="busy" @click="startCorrection('confirmed')">
          {{ uiText('Edit profile') }}
        </button>
        <RouterLink
          class="btn primary"
          :to="{
            path: '/chat',
            query: {
              mode: 'CAREER_DISCOVERY',
              prompt: uiText(
                'Recommend career directions based on my confirmed assessment profile',
              ),
            },
          }"
          >{{ uiText('Explore suitable careers') }}</RouterLink
        >
      </div>
    </template>
  </section>
</template>

<style scoped>
.assessment-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.transcript {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 8px;
}
.bubble {
  margin: 0;
  padding: 10px 14px;
  border-radius: 12px;
  max-width: 90%;
  overflow-wrap: anywhere;
}
.bubble.ask {
  background: var(--surface-muted, rgba(0, 0, 0, 0.04));
}
.bubble.ask.current {
  border-left: 3px solid var(--primary);
  font-weight: 600;
}
.bubble.reply {
  margin-left: auto;
  background: color-mix(in srgb, var(--primary) 10%, transparent);
}
.riasec-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(120px, 2fr) 3ch;
  align-items: center;
  gap: 8px 12px;
}
.riasec-row input {
  accent-color: var(--primary);
}
.riasec-row .evidence {
  grid-column: 1 / -1;
}
.evidence {
  margin: 0;
  font-size: 0.875rem;
  color: var(--text-muted, #666);
  overflow-wrap: anywhere;
}
.trait-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 8px;
}
.trait {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
}
.trait .evidence {
  flex-basis: 100%;
}
.inline-form {
  display: flex;
  gap: 8px;
}
.inline-form input {
  flex: 1;
  min-width: 0;
}
.assessment-summary {
  font-weight: 600;
}
@media (max-width: 600px) {
  .riasec-row {
    grid-template-columns: minmax(0, 1fr) 3ch;
  }
  .riasec-row input {
    grid-column: 1 / -1;
    grid-row: 2;
  }
}
</style>
