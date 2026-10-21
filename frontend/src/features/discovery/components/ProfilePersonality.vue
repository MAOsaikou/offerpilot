<script setup lang="ts">
import { computed } from 'vue'

import { localizedText, translate as uiText } from '@/shared/utils/language'

import { ref, watch, onBeforeUnmount } from 'vue'
import {
  confirmCareerAssessment,
  type AssessmentConfirmed,
  type RiasecLetter,
} from '@/shared/api/career'

const props = defineProps<{
  assessment: AssessmentConfirmed | null
  beforeConfirm: () => Promise<void>
}>()
const emit = defineEmits<{ confirmed: [assessment: AssessmentConfirmed] }>()
const dimensions = computed<{ key: RiasecLetter; label: string }[]>(() => [
  { key: 'R', label: uiText('Realistic') },
  { key: 'I', label: uiText('Investigative') },
  { key: 'A', label: uiText('Artistic') },
  { key: 'S', label: uiText('Social') },
  { key: 'E', label: uiText('Enterprising') },
  { key: 'C', label: uiText('Conventional') },
])
const draft = ref<AssessmentConfirmed | null>(null)
const editing = ref(false)
const busy = ref(false)
const error = localizedText()
const newTrait = ref('')
let disposed = false
onBeforeUnmount(() => (disposed = true))
watch(
  () => props.assessment,
  () => {
    editing.value = false
    error.value = ''
  },
  { deep: true },
)
function startEdit() {
  if (!props.assessment) return
  draft.value = JSON.parse(JSON.stringify(props.assessment)) as AssessmentConfirmed
  newTrait.value = ''
  editing.value = true
}
function addTrait() {
  const name = newTrait.value.trim()
  if (!draft.value || !name || draft.value.traits.length >= 8) return
  if (!draft.value.traits.some((t) => t.name === name))
    draft.value.traits.push({ name, confidence: 1, evidence: '' })
  newTrait.value = ''
}
async function save() {
  if (!draft.value || busy.value) return
  addTrait()
  busy.value = true
  error.value = ''
  try {
    await props.beforeConfirm()
    const result = await confirmCareerAssessment({
      riasec: { ...draft.value.riasec },
      traits: draft.value.traits.map((t) => t.name),
      ...draft.value.constraints,
    })
    if (disposed) return
    if (!result.confirmed) throw new Error(uiText('Confirmation returned no result. Please retry.'))
    emit('confirmed', result.confirmed)
    editing.value = false
  } catch (cause) {
    if (!disposed)
      error.value = () =>
        cause instanceof Error ? cause.message : uiText('Could not save. Please retry.')
  } finally {
    if (!disposed) busy.value = false
  }
}
</script>

<template>
  <div v-if="!assessment" class="form-stack">
    <p class="subtle">
      {{
        uiText(
          'No personality and interest profile has been confirmed. Answer a few questions, then confirm or adjust the results.',
        )
      }}
    </p>
    <a class="btn" href="#career-assessment">{{ uiText('Start or resume career assessment') }}</a>
  </div>
  <form v-else-if="editing && draft" class="form-stack" @submit.prevent="save">
    <p class="subtle">
      {{
        uiText(
          'Interest dimensions range from 0 to 100 and cannot all be equal. Saving changes reconfirms the assessment profile.',
        )
      }}
    </p>
    <div class="score-grid">
      <label v-for="dimension in dimensions" :key="dimension.key" class="field">
        {{ dimension.key }} · {{ dimension.label }}
        <input
          v-model.number="draft.riasec[dimension.key]"
          type="number"
          min="0"
          max="100"
          step="1"
          required
          :disabled="busy"
          :name="`riasec-${dimension.key}`"
        />
      </label>
    </div>
    <div class="chips">
      <button
        v-for="(trait, index) in draft.traits"
        :key="trait.name"
        type="button"
        class="btn"
        :disabled="busy"
        :aria-label="uiText('Remove trait {0}', [trait.name])"
        @click="draft.traits.splice(index, 1)"
      >
        {{ trait.name }} ×
      </button>
    </div>
    <div class="trait-input">
      <label class="field"
        >{{ uiText('Add traits (up to 8)')
        }}<input
          v-model="newTrait"
          maxlength="12"
          :disabled="busy || draft.traits.length >= 8"
          @keydown.enter.prevent="addTrait"
      /></label>
      <button
        type="button"
        class="btn"
        :disabled="busy || !newTrait.trim() || draft.traits.length >= 8"
        @click="addTrait"
      >
        {{ uiText('Add trait') }}
      </button>
    </div>
    <div class="score-grid">
      <label class="field"
        >{{ uiText('Preferred city constraint')
        }}<input v-model="draft.constraints.city" maxlength="50" :disabled="busy"
      /></label>
      <label class="field"
        >{{ uiText('Expected monthly salary')
        }}<input v-model="draft.constraints.salary" maxlength="50" :disabled="busy"
      /></label>
      <label class="field"
        >{{ uiText('Overtime preference')
        }}<input v-model="draft.constraints.overtime" maxlength="50" :disabled="busy"
      /></label>
    </div>
    <p v-if="error" role="alert" class="error-banner">
      {{ uiText('{0} (draft retained)', [error]) }}
    </p>
    <div class="card-footer">
      <button class="btn primary" :disabled="busy">
        {{ busy ? uiText('Confirming…') : uiText('Save personality profile') }}
      </button>
      <button type="button" class="btn" :disabled="busy" @click="editing = false">
        {{ uiText('Cancel') }}
      </button>
    </div>
  </form>
  <div v-else class="form-stack">
    <div class="score-grid">
      <label v-for="dimension in dimensions" :key="dimension.key" class="field"
        >{{ dimension.key }} · {{ dimension.label }} · {{ assessment.riasec[dimension.key] }}
        <progress
          :value="assessment.riasec[dimension.key]"
          max="100"
          :aria-label="dimension.label"
        />
      </label>
    </div>
    <ul v-if="assessment.traits.length" class="trait-list">
      <li v-for="trait in assessment.traits" :key="trait.name">
        <strong>{{ trait.name }}</strong>
        <p class="subtle">
          {{
            trait.evidence
              ? uiText('Evidence: {0}', [trait.evidence])
              : uiText('Self-reported trait; no supporting quote yet')
          }}
        </p>
      </li>
    </ul>
    <p v-else class="subtle">
      {{ uiText('No traits added yet. Add one based on your experience.') }}
    </p>
    <p class="helper-note">
      {{
        uiText('Constraints: City {0} · Monthly salary {1} · Overtime {2}', [
          assessment.constraints.city || uiText('Not provided'),
          assessment.constraints.salary || uiText('Not provided'),
          assessment.constraints.overtime || uiText('Not provided'),
        ])
      }}
    </p>
    <p class="subtle">
      {{
        uiText(
          'Conversational inferences are for exploration, not a standardized psychological assessment. Your adjustments inform future career recommendations.',
        )
      }}
    </p>
    <button type="button" class="btn" @click="startEdit">
      {{ uiText('Edit personality and constraints') }}
    </button>
  </div>
</template>

<style scoped>
.score-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}
.score-grid > *,
.trait-input > * {
  min-width: 0;
}
progress {
  width: 100%;
  accent-color: var(--primary);
}
.trait-list {
  padding-left: 20px;
}
.trait-list li + li {
  margin-top: 12px;
}
.trait-input {
  display: flex;
  gap: 12px;
  align-items: end;
  flex-wrap: wrap;
}
.trait-input .field {
  flex: 1 1 180px;
}
@media (max-width: 700px) {
  .score-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
