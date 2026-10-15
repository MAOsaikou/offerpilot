<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  confirmCareerAssessment,
  getCareerAssessment,
  inferCareerAssessment,
  resetCareerAssessment,
  saveAssessmentAnswer,
  type AssessmentTrait,
  type CareerAssessment,
  type RiasecLetter,
} from '@/shared/api/career'

const emit = defineEmits<{ confirmed: [city: string] }>()
const LETTERS: RiasecLetter[] = ['R', 'I', 'A', 'S', 'E', 'C']
const LABELS: Record<RiasecLetter, string> = {
  R: '实际型 · 动手做出东西',
  I: '研究型 · 钻研弄懂原理',
  A: '艺术型 · 创作与表达',
  S: '社会型 · 帮助与影响他人',
  E: '企业型 · 组织推动与说服',
  C: '常规型 · 有序规范与细致',
}
const MIN_ANSWERS = 4

const state = ref<CareerAssessment>()
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const step = ref(0)
const draft = ref('')
const editing = ref(false)
const scores = ref<Record<RiasecLetter, number>>({ R: 0, I: 0, A: 0, S: 0, E: 0, C: 0 })
const traits = ref<AssessmentTrait[]>([])
const newTrait = ref('')
const constraints = ref({ city: '', salary: '', overtime: '' })
let disposed = false
onBeforeUnmount(() => (disposed = true))

const answered = computed(() => state.value?.answers.filter((a) => a.trim()).length ?? 0)
const questions = computed(() => state.value?.questions ?? [])
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

function apply(next: CareerAssessment) {
  state.value = next
  const first = next.answers.findIndex((a) => !a.trim())
  step.value = first === -1 ? next.questions.length : first
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
      const later = next.answers.findIndex((a, i) => i > index && !a.trim())
      step.value = later === -1 ? next.questions.length : later
      draft.value = next.answers[step.value] ?? ''
    },
  )
}

function skip() {
  step.value += 1
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
    () =>
      confirmCareerAssessment({
        riasec: scores.value,
        traits: traits.value.map((t) => t.name),
        ...constraints.value,
      }),
    () => {
      editing.value = false
      emit('confirmed', constraints.value.city.trim())
    },
  )
}

function restart() {
  if (!window.confirm('清空测评回答和画像，重新开始？')) return
  editing.value = false
  chatting.value = false
  void run(resetCareerAssessment)
}
</script>

<template>
  <section class="panel form-stack assessment" aria-labelledby="assessment-title">
    <div class="assessment-head">
      <h2 id="assessment-title">对话式职业测评</h2>
      <span v-if="state" class="tag">{{ answered }} / {{ questions.length }} 题</span>
    </div>
    <p v-if="loading" role="status" class="subtle">正在读取测评…</p>
    <div v-if="error" role="alert" class="error-banner">
      {{ error }}
      <button v-if="!state" class="btn text" @click="load">重试</button>
    </div>

    <template v-if="phase === 'chat'">
      <p class="subtle">
        不用做 60 道题。回答几个关于你的问题（每次一个），AI 会推断你的 Holland
        兴趣与性格特质，并请你确认、修正。
      </p>
      <ol class="transcript">
        <li v-for="(question, index) in questions.slice(0, step)" :key="index">
          <p class="bubble ask">{{ question }}</p>
          <p v-if="state!.answers[index]" class="bubble reply">
            {{ state!.answers[index] }}
            <button type="button" class="btn text" :disabled="busy" @click="revise(index)">
              修改
            </button>
          </p>
          <p v-else class="subtle">已跳过</p>
        </li>
      </ol>
      <form v-if="!finishedQuestions" class="form-stack" @submit.prevent="send">
        <p class="bubble ask current">{{ questions[step] }}</p>
        <label class="field"
          >你的回答<textarea
            v-model="draft"
            rows="3"
            maxlength="1000"
            :disabled="busy"
            placeholder="说说具体的事，比一个标签更有用。"
          />
        </label>
        <div class="card-footer">
          <button type="button" class="btn" :disabled="busy" @click="skip">跳过这题</button>
          <button class="btn primary" :disabled="busy || !draft.trim()">
            {{ busy ? '正在保存…' : '发送' }}
          </button>
        </div>
      </form>
      <div class="card-footer">
        <span class="subtle">{{
          answered < MIN_ANSWERS ? `至少回答 ${MIN_ANSWERS} 题后可生成画像` : '可以生成画像了'
        }}</span>
        <button
          type="button"
          class="btn primary"
          :disabled="busy || answered < MIN_ANSWERS"
          :aria-busy="busy"
          @click="infer"
        >
          {{ busy ? '正在分析…' : '生成我的画像' }}
        </button>
      </div>
    </template>

    <template v-else-if="phase === 'confirm'">
      <p class="helper-note">
        {{
          state?.inference?.source === 'rule'
            ? 'AI 暂不可用，下面是按关键词规则得出的初步画像，请重点核对。'
            : 'AI 根据你的原话推断了下面的画像。它可能不准，请拖动分数、删改特质后再确认。'
        }}
      </p>
      <p v-if="state?.inference?.summary" class="assessment-summary">
        {{ state.inference.summary }}
      </p>
      <h3>兴趣倾向 · 当前兴趣码 {{ code }}</h3>
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
      <h3>性格与工作风格</h3>
      <ul class="trait-list">
        <li v-for="(trait, index) in traits" :key="trait.name" class="trait">
          <strong>{{ trait.name }}</strong>
          <span class="subtle">{{
            trait.evidence ? `置信度 ${Math.round(trait.confidence * 100)}%` : '自己补充'
          }}</span>
          <button
            type="button"
            class="btn text"
            :aria-label="`删除特质 ${trait.name}`"
            :disabled="busy"
            @click="traits.splice(index, 1)"
          >
            删除
          </button>
          <p v-if="trait.evidence" class="evidence">依据：「{{ trait.evidence }}」</p>
        </li>
      </ul>
      <form class="inline-form" @submit.prevent="addTrait">
        <input
          v-model="newTrait"
          maxlength="12"
          placeholder="补充一个特质，如「抗压」"
          aria-label="补充特质"
          :disabled="busy || traits.length >= 8"
        />
        <button class="btn" :disabled="busy || !newTrait.trim() || traits.length >= 8">添加</button>
      </form>
      <h3>现实条件</h3>
      <div class="form-grid">
        <label class="field"
          >意向城市<input v-model="constraints.city" maxlength="50" :disabled="busy"
        /></label>
        <label class="field"
          >期望月薪<input v-model="constraints.salary" maxlength="50" :disabled="busy"
        /></label>
        <label class="field"
          >加班接受度<input v-model="constraints.overtime" maxlength="50" :disabled="busy"
        /></label>
      </div>
      <div class="card-footer">
        <button type="button" class="btn" :disabled="busy" @click="revise(0)">回去改回答</button>
        <button type="button" class="btn primary" :disabled="busy" @click="confirm">
          {{ busy ? '正在保存…' : '确认画像' }}
        </button>
      </div>
    </template>

    <template v-else-if="phase === 'done' && state?.confirmed">
      <p class="success-banner" role="status">
        画像已确认：兴趣码 <strong>{{ state.confirmed.code }}</strong
        >。职业推荐会优先使用这份画像。
      </p>
      <p class="subtle">
        {{ state.confirmed.traits.map((t) => t.name).join(' · ') }}
      </p>
      <div class="card-footer">
        <button type="button" class="btn text" :disabled="busy" @click="restart">重新测评</button>
        <button type="button" class="btn" :disabled="busy" @click="startCorrection('confirmed')">
          修正画像
        </button>
        <RouterLink
          class="btn primary"
          :to="{
            path: '/chat',
            query: { mode: 'CAREER_DISCOVERY', prompt: '根据我确认的测评画像推荐职业方向' },
          }"
          >查看适合我的职业</RouterLink
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
