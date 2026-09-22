<script setup lang="ts">
import { cn } from '@/shared/utils/cn'
import type { PlanStepState } from '@/shared/stores/agentRun'
import type { StepStatus } from '@/shared/types/agent-events'

defineProps<{
  goal: string | null
  steps: PlanStepState[]
  running?: boolean
}>()

const labelOf: Record<StepStatus, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  DONE: '完成',
  FAILED: '失败',
  SKIPPED: '已跳过',
}

const dotOf: Record<StepStatus, string> = {
  PENDING: 'bg-muted-foreground/40',
  RUNNING: 'bg-primary pulse-dot',
  DONE: 'bg-success',
  FAILED: 'bg-danger',
  SKIPPED: 'bg-muted-foreground/40',
}
</script>

<template>
  <section>
    <h3 class="mb-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">计划</h3>

    <p v-if="goal" class="mb-3 text-sm leading-relaxed">{{ goal }}</p>
    <p v-else class="mb-3 text-sm text-muted-foreground">
      {{ running ? '正在识别任务…' : '本轮未生成执行计划' }}
    </p>

    <ol class="space-y-2">
      <li v-for="step in steps" :key="step.id" class="flex gap-2.5">
        <span class="mt-1.5 flex size-2 shrink-0">
          <span class="size-2 rounded-full" :class="dotOf[step.status]" />
        </span>
        <div class="min-w-0 flex-1">
          <p
            class="text-sm leading-snug"
            :class="cn(step.status === 'PENDING' && 'text-muted-foreground')"
          >
            {{ step.title }}
          </p>
          <p class="mt-0.5 font-mono text-xs text-muted-foreground">
            {{ labelOf[step.status] }}
            <template v-if="step.summary"> · {{ step.summary }}</template>
            <template v-if="step.durationMs !== undefined">
              · {{ (step.durationMs / 1000).toFixed(1) }}s
            </template>
          </p>
        </div>
      </li>
    </ol>
  </section>
</template>
