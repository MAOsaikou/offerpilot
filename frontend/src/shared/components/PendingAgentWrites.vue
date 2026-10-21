<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

import { computed, ref, onUnmounted } from 'vue'
import ApprovalFields from './ApprovalFields.vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  boardApprovalActions,
  decidePendingAgentApproval,
  listPendingAgentApprovals,
  type PendingAgentApproval,
} from '@/shared/api/agentApprovals'
import { applicationStageLabels as applicationStages, type ApplicationStatus } from '@/shared/types/board'

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
const now = ref(Date.now())
const timer = setInterval(() => {
  now.value = Date.now()
}, 1000)
onUnmounted(() => clearInterval(timer))
const edits = ref<Record<string, Record<string, unknown>>>({})
const editErrors = ref<Record<string, string>>({})
const busy = ref('')
const error = localizedText()
const done = ref('')

const text = (value: unknown) => (typeof value === 'string' ? value : '')
const stage = (status?: unknown) =>
  text(status) ? (applicationStages[text(status) as ApplicationStatus] ?? text(status)) : ''
const target = (item: PendingAgentApproval) =>
  item.company || item.title
    ? `「${[item.company, item.title].filter(Boolean).join(' · ')}」`
    : uiText("a record that no longer exists")
function describe(item: PendingAgentApproval) {
  if (!isBoard(item)) return item.summary ?? uiText("Perform the action proposed by the agent")
  const changes: string[] = []
  if (item.action === 'createApplication')
    changes.push(uiText("Add to board at stage “{0}”", [stage(item.args.status ?? 'TODO')]))
  else if (item.args.status)
    changes.push(uiText("Change stage from “{0}” to “{1}”", [stage(item.currentStatus), stage(item.args.status)]))
  if (item.args.note) changes.push(uiText("Change notes to “{0}”", [text(item.args.note)]))
  if (item.args.nextAction) changes.push(uiText("Next action: {0}", [text(item.args.nextAction)]))
  if (item.args.nextActionAt) changes.push(uiText("Date: {0}", [text(item.args.nextActionAt)]))
  return `${target(item)}：${changes.join('；')}`
}
const secondsLeft = (item: PendingAgentApproval) =>
  Math.max(0, Math.ceil((Date.parse(item.expiresAt) - now.value) / 1000))

async function decide(item: PendingAgentApproval, decision: 'APPROVE' | 'REJECT') {
  if (secondsLeft(item) === 0 || busy.value) return
  if (decision === 'APPROVE' && editErrors.value[item.id]) {
    error.value = editErrors.value[item.id]!
    return
  }
  busy.value = item.id
  error.value = ''
  done.value = ''
  try {
    const args = decision === 'APPROVE' ? edits.value[item.id] : undefined
    const result = await (args && Object.keys(args).length
      ? decidePendingAgentApproval(item.id, decision, args)
      : decidePendingAgentApproval(item.id, decision))
    done.value = result.summary ?? ''
    if (decision === 'APPROVE') {
      // A board write refreshes the board; other tools touch resumes, interviews, career or memory.
      await client.invalidateQueries(isBoard(item) ? { queryKey: ['applications'] } : undefined)
    }
  } catch (failure) {
    error.value = () => (failure instanceof Error ? failure.message : uiText("Operation failed. Please retry. #2"))
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
    :aria-label="uiText('Pending agent actions')"
  >
    <h2>{{ scope === 'board' ? uiText("Agent board changes awaiting your confirmation") : uiText("Agent actions awaiting your confirmation") }}</h2>
    <p class="subtle"> {{ uiText("The conversation ended before your confirmation. Approval is required to {0}. Rejection or timeout makes no changes.", [scope === 'board' ? uiText("update the board") : uiText("execute the action")]) }} </p>
    <ul>
      <li v-for="item in items" :key="item.id">
        <span>{{ describe(item) }}</span>
        <small class="subtle">{{
          secondsLeft(item)
            ? uiText("{0} seconds remaining · Valid for {1} minutes", [secondsLeft(item), Math.ceil(secondsLeft(item) / 60)])
            : uiText("Automatically rejected after timeout. Review the proposal and request it again if you want to save it.")
        }}</small>
        <details v-if="item.editableFields?.length && secondsLeft(item) > 0" class="w-full min-w-0">
          <summary>{{ uiText("Edit parameters and approve") }}</summary>
          <ApprovalFields
            :fields="item.editableFields"
            :args="item.args"
            :disabled="busy !== '' || secondsLeft(item) === 0"
            @change="
              (args, failure) => {
                edits[item.id] = args
                editErrors[item.id] = failure
              }
            "
          />
          <p v-if="editErrors[item.id]" role="alert">{{ editErrors[item.id] }}</p>
        </details>
        <span class="actions">
          <button
            class="btn primary"
            type="button"
            :disabled="busy !== '' || secondsLeft(item) === 0"
            @click="decide(item, 'APPROVE')"
          > {{ uiText("Approve") }} </button>
          <button
            class="btn"
            type="button"
            :disabled="busy !== '' || secondsLeft(item) === 0"
            @click="decide(item, 'REJECT')"
          > {{ uiText("Reject") }} </button>
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
