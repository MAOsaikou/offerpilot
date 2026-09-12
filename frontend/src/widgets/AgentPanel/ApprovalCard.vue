<script setup lang="ts">
import type { ApprovalStatus, ApprovalView } from '@/shared/stores/agentRun'

defineProps<{
  approvals: ApprovalView[]
}>()

const labelOf: Record<ApprovalStatus, string> = {
  PENDING: '等待你确认',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  TIMEOUT: '已超时',
}
</script>

<template>
  <section v-if="approvals.length > 0">
    <h3 class="mb-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
      需要确认
    </h3>

    <ul class="space-y-2">
      <li
        v-for="approval in approvals"
        :key="approval.approvalId"
        class="rounded-xl border border-primary/25 bg-primary/5 p-3"
      >
        <div class="flex items-center gap-2">
          <span class="font-mono text-xs">{{ approval.name }}</span>
          <span class="ml-auto text-xs text-muted-foreground">
            {{ labelOf[approval.status] }}
          </span>
        </div>
        <p class="mt-1.5 text-xs leading-relaxed text-muted-foreground">{{ approval.reason }}</p>
        <pre
          class="mt-2 max-h-32 overflow-auto rounded-lg bg-foreground/5 p-2 font-mono text-xs leading-relaxed"
          >{{ JSON.stringify(approval.args, null, 2) }}</pre
        >
      </li>
    </ul>
  </section>
</template>
