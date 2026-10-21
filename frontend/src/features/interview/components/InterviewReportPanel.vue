<script setup lang="ts">
import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import InterviewReportCard from './InterviewReportCard.vue'
import InterviewPlanUpdatePanel from './InterviewPlanUpdatePanel.vue'
import { generateInterviewReport, getInterviewReport } from '@/shared/api/interviews'
import { createReportLearningPlan } from '@/shared/api/learningPlans'
import { useGenerationTasks } from '@/shared/stores/generationTasks'
import { isActiveTask } from '@/shared/api/generationTasks'
const props = defineProps<{ sessionId: string; canGenerate?: boolean; autoGenerate?: boolean }>()
const cache = useQueryClient()
const tasks = useGenerationTasks()
const task = computed(() => tasks.latest('INTERVIEW_REPORT', props.sessionId))
const restoring = ref(true)
const generating = ref(false)
const generationError = ref('')
const restoreError = ref('')
const inProgress = computed(
  () => generating.value || Boolean(task.value && isActiveTask(task.value)),
)
const failure = computed(
  () =>
    generationError.value ||
    (task.value?.status === 'FAILED' ? task.value.error || uiText("Report generation failed") : ''),
)
const report = useQuery({
  queryKey: computed(() => ['interview-report', props.sessionId]),
  queryFn: ({ signal }) => getInterviewReport(props.sessionId, signal),
  retry: false,
})
// A saved report can contain only unscored turns. Do not mistake null scores for a missing report.
const hasReport = computed(() => Boolean(report.data.value?.turns.length))
const busy = ref(false),
  error = ref(''),
  planId = ref('')
const controller = new AbortController()
onBeforeUnmount(() => controller.abort())
async function restore() {
  restoring.value = true
  restoreError.value = ''
  try {
    await tasks.restore('INTERVIEW_REPORT', props.sessionId)
  } catch (cause) {
    if (!controller.signal.aborted) restoreError.value = (cause as Error).message
  } finally {
    if (!controller.signal.aborted) restoring.value = false
  }
}
onMounted(async () => {
  await restore()
  if (!controller.signal.aborted && !restoreError.value && props.autoGenerate && !inProgress.value)
    void generate()
})
watch(
  () => task.value?.status,
  (status) => {
    if (status === 'SUCCEEDED') void report.refetch()
  },
)
async function generate() {
  if (!props.canGenerate || inProgress.value || restoring.value || restoreError.value) return
  generating.value = true
  generationError.value = ''
  try {
    await generateInterviewReport(props.sessionId, controller.signal)
    if (!controller.signal.aborted) await report.refetch()
  } catch (cause) {
    if (!controller.signal.aborted) generationError.value = (cause as Error).message
  } finally {
    if (!controller.signal.aborted) generating.value = false
  }
}
async function createPlan() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const plan = await createReportLearningPlan(props.sessionId, controller.signal)
    if (controller.signal.aborted) return
    planId.value = plan.id
    void cache.invalidateQueries({ queryKey: ['learning-plans'] })
  } catch (e) {
    if (!controller.signal.aborted) error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <section class="panel form-stack interview-report" :aria-label="uiText('Interview report and learning plan')">
    <h2>{{ uiText("Interview report") }}</h2>
    <p v-if="restoring" role="status">{{ uiText("Restoring report task…") }}</p>
    <div v-else-if="restoreError" role="alert">
      <p>{{ uiText("Could not load report task: {0}", [restoreError]) }}</p>
      <button class="btn" @click="restore">{{ uiText("Reload task") }}</button>
    </div>
    <div v-else-if="inProgress" role="status" aria-live="polite">
      <h3>{{ uiText("Generating interview report") }}</h3>
      <progress :aria-label="uiText('Interview report progress')" />
      <p>{{ task?.stage || uiText("Submitting report task") }}</p>
      <p class="helper-note">{{ uiText("You can leave this page. Generation continues in the background. The report will appear automatically and be saved to interview history.") }}</p>
    </div>
    <div v-else-if="failure" role="alert">
      <h3>{{ uiText("Report generation failed") }}</h3>
      <p>{{ failure }}</p>
      <p class="helper-note">{{ uiText("Answers are saved. You can retry report generation.") }}</p>
      <button v-if="canGenerate" class="btn primary" @click="generate">{{ uiText("Retry report generation") }}</button>
    </div>
    <div v-else-if="task?.status === 'CANCELLED' && !hasReport" role="status">
      <p>{{ uiText("Report generation was canceled. Answers are saved.") }}</p>
      <button v-if="canGenerate" class="btn primary" @click="generate">{{ uiText("Regenerate report") }}</button>
    </div>
    <p v-else-if="report.isPending.value" role="status">{{ uiText("Loading interview report…") }}</p>
    <p v-else-if="report.isError.value" role="alert">
      {{ formatDisplay(report.error.value?.message) }}
      <button class="btn" @click="report.refetch()">{{ uiText("Retry report") }}</button>
    </p>
    <template v-else-if="report.data.value && (hasReport || !canGenerate)">
      <InterviewReportCard :payload="report.data.value" inline />
      <p class="helper-note">{{ uiText("Your report is saved and available in interview history.") }}</p>
      <InterviewPlanUpdatePanel :key="sessionId" :report-id="sessionId" />
      <div class="report-actions">
        <button
          class="btn primary"
          :disabled="busy || !report.data.value.recommendedLearning.length"
          @click="createPlan"
        >
          {{ busy ? uiText("Generating…") : planId ? uiText("Learning plan generated") : uiText("Generate a learning plan") }}
        </button>
        <slot />
      </div>
      <p class="helper-note"> {{ uiText("Save report gaps as a four-week learning plan with a link to this report. Repeated requests return the same plan.") }} </p>
      <p v-if="!report.data.value.recommendedLearning.length" role="status"> {{ uiText("This report has no gaps that can become learning tasks.") }} </p>
      <RouterLink v-if="planId" class="btn" :to="{ path: '/career', query: { plan: planId } }"
        >{{ uiText("View learning plan") }}</RouterLink
      >
      <p v-if="error" role="alert">{{ error }}</p>
    </template>
    <div v-else>
      <p>{{ uiText("The report has not been generated. Original answers are saved.") }}</p>
      <button v-if="canGenerate" class="btn primary" @click="generate">{{ uiText("Generate interview report") }}</button>
    </div>
  </section>
</template>
<style scoped>
.interview-report {
  min-width: 0;
  overflow-wrap: anywhere;
}
.report-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
progress {
  width: 100%;
  accent-color: var(--primary);
}
</style>
