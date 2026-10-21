<script setup lang="ts">
import { computed } from 'vue'

import { formatNumber, translate as uiText } from '@/shared/utils/language'

import { cn } from '@/shared/utils/cn'
import type { PlanStepState } from '@/shared/stores/agentRun'
import type { StepStatus } from '@/shared/types/agent-events'

defineProps<{
  goal: string | null
  steps: PlanStepState[]
  running?: boolean
}>()

const labelOf = computed<Record<StepStatus, string>>(() => ({
  PENDING: uiText("Pending"),
  RUNNING: uiText("Running #2"),
  DONE: uiText("Done"),
  FAILED: uiText("Failed"),
  SKIPPED: uiText("Skipped"),
}))

const dotOf: Record<StepStatus, string> = {
  PENDING: 'bg-muted-foreground/40',
  RUNNING: 'live-dot',
  DONE: 'bg-success',
  FAILED: 'bg-danger',
  SKIPPED: 'bg-muted-foreground/40',
}
</script>

<template>
  <section>
    <h3 class="mb-2 text-xs font-semibold text-muted-foreground">{{ uiText("Plan") }}</h3>

    <p v-if="goal" class="mb-3 text-sm leading-relaxed">{{ goal }}</p>
    <p v-else class="mb-3 text-sm text-muted-foreground">
      {{ running ? uiText("Identifying the task…") : uiText("No execution plan was generated for this run") }}
    </p>

    <ol class="space-y-2">
      <li
        v-for="step in steps"
        :key="step.id"
        class="flex gap-2.5 rounded p-2"
        :aria-current="step.status === 'RUNNING' ? 'step' : undefined"
        :class="step.status === 'RUNNING' ? 'bg-primary/10 ring-1 ring-primary/40' : ''"
      >
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
          <p class="mt-0.5 text-xs text-muted-foreground">
            {{ labelOf[step.status] }}
            <template v-if="step.summary"> · {{ step.summary }}</template>
            <template v-if="step.durationMs !== undefined">
              · {{ formatNumber((step.durationMs / 1000), { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}s
            </template>
          </p>
        </div>
      </li>
    </ol>
  </section>
</template>
