<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import InterviewReportCard from './InterviewReportCard.vue'
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
    (task.value?.status === 'FAILED' ? task.value.error || '报告生成失败' : ''),
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
  <section class="panel form-stack interview-report" aria-label="面试报告与学习计划">
    <h2>面试报告</h2>
    <p v-if="restoring" role="status">正在恢复报告任务…</p>
    <div v-else-if="restoreError" role="alert">
      <p>报告任务读取失败：{{ restoreError }}</p>
      <button class="btn" @click="restore">重新读取任务</button>
    </div>
    <div v-else-if="inProgress" role="status" aria-live="polite">
      <h3>正在生成面试报告</h3>
      <progress aria-label="面试报告生成进度" />
      <p>{{ task?.stage || '正在提交报告任务' }}</p>
      <p class="helper-note">可以离开页面，后台会继续；完成后报告会自动显示，并保存到面试历史。</p>
    </div>
    <div v-else-if="failure" role="alert">
      <h3>报告生成失败</h3>
      <p>{{ failure }}</p>
      <p class="helper-note">回答已保存，可重试生成报告。</p>
      <button v-if="canGenerate" class="btn primary" @click="generate">重试生成报告</button>
    </div>
    <div v-else-if="task?.status === 'CANCELLED' && !hasReport" role="status">
      <p>报告生成已取消，回答已保存。</p>
      <button v-if="canGenerate" class="btn primary" @click="generate">重新生成报告</button>
    </div>
    <p v-else-if="report.isPending.value" role="status">正在读取面试报告…</p>
    <p v-else-if="report.isError.value" role="alert">
      {{ report.error.value?.message }}
      <button class="btn" @click="report.refetch()">重试报告</button>
    </p>
    <template v-else-if="report.data.value && (hasReport || !canGenerate)">
      <InterviewReportCard :payload="report.data.value" inline />
      <p class="helper-note">报告已保存，可随时从面试历史查看。</p>
      <div class="report-actions">
        <button
          class="btn primary"
          :disabled="busy || !report.data.value.recommendedLearning.length"
          @click="createPlan"
        >
          {{ busy ? '正在生成…' : planId ? '学习计划已生成' : '一键生成学习计划' }}
        </button>
        <slot />
      </div>
      <p class="helper-note">
        将报告短板保存为四周学习计划，保留报告来源。重复点击会返回同一计划。
      </p>
      <p v-if="!report.data.value.recommendedLearning.length" role="status">
        报告暂无可转为学习任务的短板。
      </p>
      <RouterLink v-if="planId" class="btn" :to="{ path: '/career', query: { plan: planId } }"
        >查看学习计划</RouterLink
      >
      <p v-if="error" role="alert">{{ error }}</p>
    </template>
    <div v-else>
      <p>报告尚未生成，原始回答已保存。</p>
      <button v-if="canGenerate" class="btn primary" @click="generate">生成面试报告</button>
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
