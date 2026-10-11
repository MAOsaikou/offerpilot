<script setup lang="ts">
import { toolArguments } from '@/shared/utils/toolPresentation'
import { computed, onUnmounted, ref, watch } from 'vue'
import { submitApproval } from '@/shared/api/agent'
import { memoryKinds, type MemoryKind } from '@/shared/api/memories'
import { queryClient } from '@/shared/api/queryClient'
import {
  applicationStages,
  type ApplicationStatus,
  type ApplicationPatch,
} from '@/shared/types/board'
import type { ApprovalStatus, ApprovalView } from '@/shared/stores/agentRun'

const props = defineProps<{
  approvals: ApprovalView[]
  runId: string
  running: boolean
}>()

const now = ref(Date.now())
const timer = setInterval(() => (now.value = Date.now()), 1000)
onUnmounted(() => clearInterval(timer))
const edits = ref<
  Record<
    string,
    { status: ApplicationStatus | ''; note: string; nextAction: string; nextActionAt: string }
  >
>({})
const busy = ref<Record<string, boolean>>({})
const errors = ref<Record<string, string>>({})
const submitted = ref<Record<string, ApprovalStatus>>({})
watch(
  () => [props.runId, props.approvals] as const,
  () => {
    for (const approval of props.approvals) {
      const key = keyOf(approval)
      if (edits.value[key]) continue
      const args = approval.args as ApplicationPatch | null
      edits.value[key] = {
        status: args?.status ?? '',
        note: args?.note ?? '',
        nextAction: args?.nextAction ?? '',
        nextActionAt: args?.nextActionAt ?? '',
      }
    }
  },
  { immediate: true, deep: true },
)
const statuses = Object.entries(applicationStages)
const labelOf: Record<ApprovalStatus, string> = {
  PENDING: '等待你确认',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  TIMEOUT: '已超时',
}
function memoryArgs(approval: ApprovalView) {
  if (approval.name !== 'saveUserFact' || !approval.args || typeof approval.args !== 'object')
    return null
  const args = approval.args as { kind?: string; fact?: string; source?: string }
  if (typeof args.fact !== 'string') return null
  return {
    fact: args.fact,
    kind: memoryKinds[args.kind as MemoryKind] ?? '其他',
    suggested: args.source === 'SUGGESTED_ACCEPTED',
  }
}
function keyOf(approval: ApprovalView) {
  return `${props.runId}:${approval.approvalId}`
}
function remaining(approval: ApprovalView) {
  const expires = Date.parse(approval.expiresAt)
  return Number.isFinite(expires) ? Math.max(0, Math.ceil((expires - now.value) / 1000)) : 0
}
function statusOf(approval: ApprovalView): ApprovalStatus {
  if (approval.status !== 'PENDING') return approval.status
  return submitted.value[keyOf(approval)] ?? (remaining(approval) === 0 ? 'TIMEOUT' : 'PENDING')
}
const canSubmit = computed(() => props.running && props.runId.length > 0)
async function decide(approval: ApprovalView, decision: 'APPROVE' | 'REJECT') {
  const key = keyOf(approval)
  if (!canSubmit.value || busy.value[key] || statusOf(approval) !== 'PENDING') return
  const runId = props.runId
  busy.value[key] = true
  errors.value[key] = ''
  try {
    const args: ApplicationPatch = {}
    const edit = edits.value[key]
    if (decision === 'APPROVE' && edit) {
      if (approval.editableFields?.includes('status') && edit.status) args.status = edit.status
      if (
        approval.editableFields?.includes('note') &&
        (edit.note !== '' || (approval.args as { note?: unknown } | null)?.note !== undefined)
      )
        args.note = edit.note
      for (const field of ['nextAction', 'nextActionAt'] as const) {
        if (
          approval.editableFields?.includes(field) &&
          (edit[field] !== '' || (approval.args as ApplicationPatch | null)?.[field] !== undefined)
        )
          args[field] = edit[field]
      }
    }
    await submitApproval(runId, approval.approvalId, {
      decision,
      ...(decision === 'APPROVE' ? { args } : {}),
    })
    submitted.value[key] = decision === 'APPROVE' ? 'APPROVED' : 'REJECTED'
    if (decision === 'APPROVE') void queryClient.invalidateQueries({ queryKey: ['applications'] })
  } catch (error) {
    errors.value[key] = error instanceof Error ? error.message : '提交失败，请重试'
  } finally {
    busy.value[key] = false
  }
}
</script>

<template>
  <section v-if="approvals.length > 0">
    <h3 class="mb-2 text-xs font-semibold text-muted-foreground">需要确认</h3>
    <ul class="space-y-2">
      <li
        v-for="approval in approvals"
        :key="keyOf(approval)"
        class="rounded-[10px] border p-3 transition-colors"
        :class="
          statusOf(approval) === 'PENDING' ? 'border-primary/40 bg-primary/5' : 'border-border'
        "
      >
        <div class="flex flex-wrap items-center gap-2">
          <span class="break-all text-xs" :class="{ 'font-mono': !memoryArgs(approval) }">{{
            memoryArgs(approval)
              ? memoryArgs(approval)?.suggested
                ? '记忆保存建议'
                : '保存长期记忆'
              : approval.name
          }}</span>
          <span class="ml-auto text-xs text-muted-foreground">{{
            labelOf[statusOf(approval)]
          }}</span>
        </div>
        <p class="mt-1.5 text-xs leading-relaxed text-muted-foreground">{{ approval.reason }}</p>
        <div v-if="memoryArgs(approval)" class="mt-2 space-y-1 text-xs">
          <p class="text-muted-foreground">{{ memoryArgs(approval)?.kind }} · 来自本轮你的原话</p>
          <p class="break-words">{{ memoryArgs(approval)?.fact }}</p>
          <p class="text-muted-foreground">确认后用于新对话；可在设置中编辑、删除或关闭。</p>
          <p v-if="statusOf(approval) === 'APPROVED'" class="text-muted-foreground">
            已提交确认，是否保存成功请查看工具结果。
          </p>
        </div>
        <dl v-else class="mt-2 space-y-1 text-xs">
          <div v-for="(row, index) in toolArguments(approval.args)" :key="index">
            <dt class="text-muted-foreground">{{ row.label }}</dt>
            <dd class="whitespace-pre-wrap break-words">{{ row.value }}</dd>
          </div>
        </dl>
        <fieldset
          v-if="statusOf(approval) === 'PENDING'"
          :disabled="busy[keyOf(approval)] || !canSubmit"
          class="mt-2 min-w-0 space-y-2 text-xs"
        >
          <p aria-live="off">剩余 {{ remaining(approval) }} 秒</p>
          <label v-if="approval.editableFields?.includes('status')" class="block">
            状态
            <select
              v-model="edits[keyOf(approval)]!.status"
              class="mt-1 block w-full rounded border bg-background p-2"
            >
              <option value="">保持原状态</option>
              <option v-for="[status, label] in statuses" :key="status" :value="status">
                {{ label }}
              </option>
            </select>
          </label>
          <label v-if="approval.editableFields?.includes('note')" class="block">
            备注
            <textarea
              v-model="edits[keyOf(approval)]!.note"
              maxlength="2000"
              rows="3"
              class="mt-1 block w-full rounded border bg-background p-2"
            />
          </label>
          <label v-if="approval.editableFields?.includes('nextAction')" class="block">
            下一步行动
            <textarea
              v-model="edits[keyOf(approval)]!.nextAction"
              maxlength="1000"
              rows="2"
              class="mt-1 block w-full rounded border bg-background p-2"
            />
          </label>
          <label v-if="approval.editableFields?.includes('nextActionAt')" class="block">
            行动日期
            <input
              v-model="edits[keyOf(approval)]!.nextActionAt"
              type="date"
              class="mt-1 block w-full rounded border bg-background p-2"
            />
          </label>
          <div class="flex flex-wrap gap-2">
            <button
              type="button"
              class="rounded bg-primary px-3 py-2 text-primary-foreground disabled:opacity-50"
              @click="decide(approval, 'APPROVE')"
            >
              {{ busy[keyOf(approval)] ? '提交中…' : memoryArgs(approval) ? '确认保存' : '批准' }}
            </button>
            <button
              type="button"
              class="rounded border px-3 py-2 disabled:opacity-50"
              @click="decide(approval, 'REJECT')"
            >
              {{ memoryArgs(approval) ? '不保存' : '拒绝' }}
            </button>
          </div>
        </fieldset>
        <p
          v-if="statusOf(approval) === 'PENDING' && !running"
          class="mt-2 text-xs text-muted-foreground"
        >
          运行已结束，无法提交审批
        </p>
        <p v-if="errors[keyOf(approval)]" role="alert" class="mt-2 text-xs text-danger">
          {{ errors[keyOf(approval)] }}
        </p>
      </li>
    </ul>
  </section>
</template>
