<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

import { computed, onBeforeUnmount, ref } from 'vue'
import {
  getQuestionnaire,
  saveQuestionnaireAnswer,
  completeQuestionnaire,
  getCareerProfile,
  type CareerQuestionnaire,
  type AssessmentConfirmed,
} from '@/shared/api/career'
const props = defineProps<{ beforeConfirm?: () => Promise<void> }>()
const emit = defineEmits<{ confirmed: [assessment: AssessmentConfirmed] }>()
const state = ref<CareerQuestionnaire>()
const step = ref(0)
const busy = ref(false)
const error = localizedText()
const online = ref(navigator.onLine)
const updateOnline = () => {
  online.value = navigator.onLine
}
window.addEventListener('online', updateOnline)
window.addEventListener('offline', updateOnline)
let disposed = false
onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('online', updateOnline)
  window.removeEventListener('offline', updateOnline)
})
const count = computed(() => Object.keys(state.value?.answers ?? {}).length)
const question = computed(() => state.value?.questions[step.value])
const choices = computed(() => [uiText("Strongly dislike"), uiText("Dislike"), uiText("Unsure"), uiText("Like"), uiText("Strongly like")])
async function load() {
  busy.value = true
  error.value = ''
  try {
    const next = await getQuestionnaire()
    if (disposed) return
    state.value = next
    const missing = next.questions.findIndex((q) => next.answers[q.id] == null)
    step.value = missing < 0 ? next.questions.length : missing
  } catch (cause) {
    if (!disposed) error.value = () => (cause instanceof Error ? cause.message : uiText("Could not load. Please retry."))
  } finally {
    if (!disposed) busy.value = false
  }
}
async function answer(value: number) {
  if (busy.value || !question.value || !online.value) return
  busy.value = true
  error.value = ''
  try {
    const next = await saveQuestionnaireAnswer(question.value.id, value)
    if (disposed) return
    state.value = next
    step.value++
  } catch (cause) {
    if (!disposed) error.value = () => (cause instanceof Error ? cause.message : uiText("Could not save. Please retry."))
  } finally {
    if (!disposed) busy.value = false
  }
}
async function complete() {
  if (!state.value || busy.value || !online.value) return
  busy.value = true
  error.value = ''
  try {
    await props.beforeConfirm?.()
    const next = await completeQuestionnaire(state.value.revision)
    if (disposed) return
    state.value = next
    const profile = await getCareerProfile()
    if (!disposed && profile.assessment) emit('confirmed', profile.assessment)
  } catch (cause) {
    if (!disposed) error.value = () => (cause instanceof Error ? cause.message : uiText("Could not submit. Please retry."))
  } finally {
    if (!disposed) busy.value = false
  }
}
void load()
</script>
<template>
  <section class="panel form-stack" aria-labelledby="questionnaire-title">
    <h2 id="questionnaire-title">{{ uiText("Quick interest questionnaire · 30 questions") }}</h2>
    <p class="subtle"> {{ uiText("Original activity questions based on the RIASEC framework used by O*NET, with five questions per dimension. This is not an official translation or a validated psychological scale. Rate your interests, not your abilities. Takes about five minutes.") }} </p>
    <p class="helper-note"> {{ uiText("Each answer is saved to your account so you can resume later. Confirmation replaces only the six interest scores and interest code, keeping confirmed traits and constraints. A later conversational assessment can update interests again; the latest explicit confirmation takes precedence.") }} </p>
    <p v-if="!online" role="status">{{ uiText("You are offline. Saved answers are retained. Continue when connected.") }}</p>
    <p v-if="busy" role="status">{{ uiText("Syncing questionnaire…") }}</p>
    <p v-if="error" role="alert">
      {{ error }} <button class="btn" :disabled="busy" @click="load">{{ uiText("Reload") }}</button>
    </p>
    <template v-if="state">
      <p role="status">{{ uiText("{0} of 30 answers saved", [count]) }}</p>
      <article v-if="question" class="form-stack" :aria-label="uiText('Questionnaire answer card')">
        <h3>{{ step + 1 }}. {{ question.text }}</h3>
        <div class="flex flex-wrap gap-2" role="group" :aria-label="uiText('Interest level')">
          <button
            v-for="(label, index) in choices"
            :key="label"
            class="btn"
            :aria-pressed="state.answers[question.id] === index + 1"
            :disabled="busy || !online"
            @click="answer(index + 1)"
          >
            {{ label }}
          </button>
        </div>
      </article>
      <p v-else-if="!state.completed"> {{ uiText("All 30 questions are answered. Confirm to update your profile. Equal scores are saved as undifferentiated without forcing an interest code.") }} </p>
      <p v-if="state.completed" role="status"> {{ uiText("Profile updated. Interest code: {0}. Tied scores appear in R, I, A, S, E, C order and do not imply meaningful differences.", [state.code || uiText("Undifferentiated")]) }} </p>
      <div class="flex flex-wrap gap-2">
        <button class="btn" :disabled="busy || step === 0" @click="step--">{{ uiText("Previous question") }}</button>
        <button
          class="btn"
          :disabled="
            busy ||
            step >= state.questions.length ||
            !question ||
            state.answers[question.id] == null
          "
          @click="step++"
        > {{ uiText("Next question") }} </button>
        <button
          v-if="count === 30"
          class="btn primary"
          :disabled="busy || !online || state.completed"
          @click="complete"
        > {{ uiText("Confirm interests and update profile") }} </button>
      </div>
    </template>
  </section>
</template>
