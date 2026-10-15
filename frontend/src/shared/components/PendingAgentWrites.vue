<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  boardApprovalActions,
  decidePendingAgentApproval,
  listPendingAgentApprovals,
  type PendingAgentApproval,
} from '@/shared/api/agentApprovals'
import { applicationStages, type ApplicationStatus } from '@/shared/types/board'

/** board: only board writes (on the board page); all: every agent write (workbench). */
const props = withDefaults(defineProps<{ scope?: 'board' | 'all' }>(), { scope: 'all' })
const client = useQueryClient()
const query = useQuery({
  queryKey: ['agent-approvals', 'pending'],
  queryFn: ({ signal }) => listPendingAgentApprovals(signal),
  // The server notices a dropped chat stream at its next heartbeat (≤15 s), so keep checking briefly.
  refetchInterval: 15_000,
})
const isBoard = (item: PendingAgentApproval) =>
  (boardApprovalActions as readonly string[]).includes(item.action)
const items = computed(() =>
  (query.data.value ?? []).filter((item) => props.scope === 'all' || isBoard(item)),
)
const busy = ref('')
const error = ref('')
const done = ref('')

const text = (value: unknown) => (typeof value === 'string' ? value : '')
const stage = (status?: unknown) =>
  text(status) ? (applicationStages[text(status) as ApplicationStatus] ?? text(status)) : ''
const target = (item: PendingAgentApproval) =>
  item.company || item.title
    ? `「${[item.company, item.title].filter(Boolean).join(' · ')}」`
    : '一条已不存在的记录'
function describe(item: PendingAgentApproval) {
  if (!isBoard(item)) return item.summary ?? '执行 Agent 提议的操作'
  const changes: string[] = []
  if (item.action === 'createApplication')
    changes.push(`加入看板，阶段「${stage(item.args.status ?? 'TODO')}」`)
  else if (item.args.status)
    changes.push(`阶段从「${stage(item.currentStatus)}」改为「${stage(item.args.status)}」`)
  if (item.args.note) changes.push(`备注改为「${text(item.args.note)}」`)
  if (item.args.nextAction) changes.push(`下一步：${text(item.args.nextAction)}`)
  if (item.args.nextActionAt) changes.push(`日期：${text(item.args.nextActionAt)}`)
  return `${target(item)}：${changes.join('；')}`
}
const minutesLeft = (item: PendingAgentApproval) =>
  Math.max(0, Math.ceil((Date.parse(item.expiresAt) - Date.now()) / 60_000))

async function decide(item: PendingAgentApproval, decision: 'APPROVE' | 'REJECT') {
  busy.value = item.id
  error.value = ''
  done.value = ''
  try {
    const result = await decidePendingAgentApproval(item.id, decision)
    if (decision === 'APPROVE') {
      done.value = result.summary ?? ''
      // A board write refreshes the board; other tools touch resumes, interviews, career or memory.
      await client.invalidateQueries(isBoard(item) ? { queryKey: ['applications'] } : undefined)
    }
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '操作失败，请重试'
  } finally {
    busy.value = ''
    await client.invalidateQueries({ queryKey: ['agent-approvals', 'pending'] })
  }
}
</script>
<template>
  <section
    v-if="items.length || done"
    class="panel pending-writes"
    aria-label="待确认的 Agent 操作"
  >
    <h2>{{ scope === 'board' ? '待你确认的 Agent 看板操作' : '待你确认的 Agent 操作' }}</h2>
    <p class="subtle">
      对话在你确认前中断了。批准后才会{{
        scope === 'board' ? '写入看板' : '执行'
      }}，拒绝或超时都不会改动。
    </p>
    <ul>
      <li v-for="item in items" :key="item.id">
        <span>{{ describe(item) }}</span>
        <small class="subtle">{{ minutesLeft(item) }} 分钟内有效</small>
        <span class="actions">
          <button
            class="btn primary"
            type="button"
            :disabled="busy !== ''"
            @click="decide(item, 'APPROVE')"
          >
            批准
          </button>
          <button class="btn" type="button" :disabled="busy !== ''" @click="decide(item, 'REJECT')">
            拒绝
          </button>
        </span>
      </li>
    </ul>
    <p v-if="done" class="subtle" role="status">{{ done }}</p>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
  </section>
</template>
<style scoped>
.pending-writes ul {
  display: grid;
  gap: 0.75rem;
  margin: 0.75rem 0 0;
  padding: 0;
  list-style: none;
}
.pending-writes li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 1rem;
}
.pending-writes li > span:first-child {
  flex: 1 1 18rem;
}
.pending-writes .actions {
  display: flex;
  gap: 0.5rem;
}
</style>
