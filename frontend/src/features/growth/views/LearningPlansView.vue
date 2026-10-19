<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import RoadmapCard from '../components/RoadmapCard.vue'
import { listLearningPlans, exportLearningCalendar } from '@/shared/api/learningPlans'
const route = useRoute()
const plans = useQuery({
  queryKey: ['learning-plans'],
  queryFn: ({ signal }) => listLearningPlans(signal),
  retry: false,
})
const busy = ref(''),
  error = ref('')
const controller = new AbortController()
onBeforeUnmount(() => controller.abort())
async function download(id: string) {
  if (busy.value) return
  busy.value = id
  error.value = ''
  try {
    await exportLearningCalendar(id, controller.signal)
  } catch (e) {
    if (!controller.signal.aborted) error.value = (e as Error).message
  } finally {
    busy.value = ''
  }
}
</script>
<template>
  <section class="panel form-stack" aria-label="面试学习计划">
    <h2>从面试复盘到每周行动</h2>
    <p v-if="plans.isPending.value" role="status">正在读取学习计划…</p>
    <p v-else-if="plans.isError.value" role="alert">
      {{ plans.error.value?.message }}
      <button class="btn" @click="plans.refetch()">重试计划</button>
    </p>
    <p v-else-if="!plans.data.value?.length">还没有面试学习计划。完成面试后，在复盘页一键生成。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <article
      v-for="plan in plans.data.value"
      :id="'plan-' + plan.id"
      :key="plan.id"
      class="form-stack"
      :aria-label="route.query.plan === plan.id ? '刚生成的学习计划' : '已保存学习计划'"
    >
      <p>
        开始日期：{{ plan.startDate }} ·
        <RouterLink :to="'/interviews/' + plan.reportId">来源面试报告</RouterLink>
      </p>
      <RoadmapCard :payload="plan.roadmap" :start-date="plan.startDate" />
      <p class="helper-note">
        按开始日期安排每周任务，默认上海时间 19:00；下载后导入日历，可自行调整时间。
      </p>
      <button class="btn" :disabled="Boolean(busy)" @click="download(plan.id)">
        {{ busy === plan.id ? '正在导出…' : '加入日程（下载 .ics）' }}
      </button>
    </article>
  </section>
</template>
