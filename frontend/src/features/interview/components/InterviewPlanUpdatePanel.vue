<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  confirmInterviewPlanUpdate,
  previewInterviewPlanUpdates,
  type LearningPlanUpdate,
} from '@/shared/api/learningPlans'
const props = defineProps<{ reportId: string }>()
const online = ref(navigator.onLine)
const controller = new AbortController()
function connection() {
  online.value = navigator.onLine
}
window.addEventListener('online', connection)
window.addEventListener('offline', connection)
onBeforeUnmount(() => {
  controller.abort()
  window.removeEventListener('online', connection)
  window.removeEventListener('offline', connection)
})
const cache = useQueryClient()
const proposals = useQuery({
  queryKey: computed(() => ['interview-plan-updates', props.reportId]),
  queryFn: ({ signal }) => previewInterviewPlanUpdates(props.reportId, signal),
  retry: false,
})
const busy = ref('')
const error = ref('')
const applied = ref<string[]>([])
async function confirm(proposal: LearningPlanUpdate) {
  if (busy.value || !online.value) return
  busy.value = proposal.planId
  error.value = ''
  try {
    await confirmInterviewPlanUpdate(proposal, controller.signal)
    if (controller.signal.aborted) return
    applied.value = [...applied.value, proposal.planId]
    void cache.invalidateQueries({ queryKey: ['learning-plans'] })
  } catch (cause) {
    if (!controller.signal.aborted) error.value = (cause as Error).message
  } finally {
    busy.value = ''
  }
}
</script>
<template>
  <section class="form-stack plan-updates" aria-label="计划更新建议">
    <h3>计划更新建议</h3>
    <p class="helper-note">
      根据本次报告短板补充同目标岗位的已有计划。确认后才写入，新增任务均为未完成。
    </p>
    <p v-if="!online" role="status">当前离线，联网后可重新读取并确认建议。</p>
    <p v-if="proposals.isPending.value" role="status">正在查找同目标岗位的学习计划…</p>
    <div v-else-if="proposals.isError.value" role="alert">
      <p>{{ proposals.error.value?.message }}</p>
      <button class="btn" :disabled="!online" @click="proposals.refetch()">重试更新建议</button>
    </div>
    <p v-else-if="!proposals.data.value?.length" role="status">
      暂无可更新的同目标岗位计划：可能尚未创建、没有新短板，或本报告建议已采纳。
    </p>
    <article v-for="proposal in proposals.data.value" :key="proposal.planId" class="form-stack">
      <RouterLink :to="{ path: '/career', query: { plan: proposal.planId } }">
        查看已有计划 · {{ proposal.targetRole }}
      </RouterLink>
      <template v-if="!applied.includes(proposal.planId)">
        <ul>
          <template v-for="week in proposal.additions" :key="week.index">
            <li v-for="item in week.items" :key="item.id">
              第 {{ week.index }} 周：{{ item.title }} · {{ item.hours }} 小时
              <p v-for="resource in item.resources" :key="resource" class="helper-note">
                {{ resource }}
              </p>
            </li>
          </template>
        </ul>
        <button class="btn primary" :disabled="Boolean(busy) || !online" @click="confirm(proposal)">
          {{ busy === proposal.planId ? '正在更新…' : '确认更新此计划' }}
        </button>
      </template>
      <p v-else role="status">计划已更新，新增任务待学习。已有任务和完成记录已保留。</p>
    </article>
    <div v-if="error" role="alert">
      <p>{{ error }}</p>
      <button class="btn" :disabled="Boolean(busy) || !online" @click="proposals.refetch()">
        重新读取更新建议
      </button>
    </div>
  </section>
</template>
<style scoped>
.plan-updates {
  min-width: 0;
  overflow-wrap: anywhere;
}
button {
  white-space: normal;
}
</style>
