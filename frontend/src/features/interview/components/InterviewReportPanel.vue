<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import InterviewReportCard from './InterviewReportCard.vue'
import { getInterviewReport } from '@/shared/api/interviews'
import { createReportLearningPlan } from '@/shared/api/learningPlans'
const props = defineProps<{ sessionId: string }>()
const cache = useQueryClient()
const report = useQuery({
  queryKey: computed(() => ['interview-report', props.sessionId]),
  queryFn: ({ signal }) => getInterviewReport(props.sessionId, signal),
  retry: false,
})
const busy = ref(false),
  error = ref(''),
  planId = ref('')
const controller = new AbortController()
onBeforeUnmount(() => controller.abort())
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
  <section class="panel form-stack" aria-label="面试报告与学习计划">
    <p v-if="report.isPending.value" role="status">正在读取文字规则报告…</p>
    <p v-else-if="report.isError.value" role="alert">
      {{ report.error.value?.message }}
      <button class="btn" @click="report.refetch()">重试报告</button>
    </p>
    <template v-else-if="report.data.value">
      <InterviewReportCard :payload="report.data.value" />
      <p class="helper-note">
        将报告观察到的表达短板保存为四周学习计划，保留报告来源。重复点击会返回同一计划；不会标记任务已完成。
      </p>
      <button
        class="btn primary"
        :disabled="busy || !report.data.value.recommendedLearning.length"
        @click="createPlan"
      >
        {{ busy ? '正在生成…' : planId ? '学习计划已生成' : '一键生成学习计划' }}
      </button>
      <p v-if="!report.data.value.recommendedLearning.length" role="status">
        报告暂无可转为学习任务的短板。
      </p>
      <RouterLink v-if="planId" class="btn" :to="{ path: '/career', query: { plan: planId } }"
        >查看学习计划</RouterLink
      >
      <p v-if="error" role="alert">{{ error }}</p>
    </template>
  </section>
</template>
