<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { computed, ref } from 'vue'
import ApprovalCard from './ApprovalCard.vue'
import PlanSteps from './PlanSteps.vue'
import ToolTimeline from './ToolTimeline.vue'
import UsageBadge from './UsageBadge.vue'
import CitationPanel from '@/shared/components/CitationPanel.vue'
import McpTools from './McpTools.vue'
import type { RunState } from '@/shared/stores/agentRun'

const props = defineProps<{
  run: RunState | null
  readonly?: boolean
}>()

const reasoningOpen = ref(false)

const hasRun = computed(() => props.run !== null)
const awaitingMemory = computed(() =>
  props.run?.approvals.some(
    (approval) => approval.name === 'saveUserFact' && approval.status === 'PENDING',
  ),
)
const statusLabel = computed(() => {
  switch (props.run?.status) {
    case 'RUNNING':
      return awaitingMemory.value ? uiText('Waiting for memory confirmation') : uiText('Running #2')
    case 'COMPLETED':
      return uiText('Completed')
    case 'CANCELLED':
      return uiText('Canceled')
    case 'FAILED':
      return uiText('Failed')
    default:
      return uiText('Idle')
  }
})
</script>

<template>
  <div class="surface flex h-full flex-col overflow-hidden rounded-[14px]">
    <header class="flex h-12 shrink-0 items-center justify-between border-b border-border/60 px-4">
      <span class="text-sm font-semibold">{{ uiText('Execution details') }}</span>
      <span
        class="flex items-center gap-1.5 text-xs"
        :class="run?.status === 'FAILED' ? 'text-danger' : 'text-muted-foreground'"
      >
        <span
          class="size-1.5 rounded-full"
          :class="run?.status === 'RUNNING' ? 'live-dot' : 'bg-muted-foreground/40'"
        />
        {{ statusLabel }}
      </span>
    </header>

    <div class="min-h-0 flex-1 space-y-5 overflow-y-auto p-4">
      <McpTools :tool-calls="run?.toolCalls ?? []" />
      <template v-if="hasRun">
        <PlanSteps
          :goal="run?.goal ?? null"
          :steps="run?.steps ?? []"
          :running="run?.status === 'RUNNING' && !awaitingMemory"
        />

        <section v-if="run?.reasoning">
          <button
            type="button"
            class="flex w-full items-center gap-1 text-xs font-semibold text-muted-foreground hover:text-foreground"
            @click="reasoningOpen = !reasoningOpen"
          >
            {{ uiText('Reasoning summary') }}
            <span class="transition-transform" :class="{ 'rotate-90': reasoningOpen }">›</span>
          </button>
          <p v-if="reasoningOpen" class="mt-2 text-xs leading-relaxed text-muted-foreground">
            {{ run.reasoning }}
          </p>
        </section>

        <ToolTimeline :tool-calls="run?.toolCalls ?? []" />

        <ApprovalCard
          :approvals="run?.approvals ?? []"
          :run-id="run?.runId ?? ''"
          :running="!readonly && run?.status === 'RUNNING'"
        />

        <CitationPanel :sources="run?.citations ?? []" />

        <section v-if="run && run.memoryFacts.length > 0">
          <h3 class="mb-2 text-xs font-semibold text-muted-foreground">
            {{ uiText('Memories used') }}
          </h3>
          <ul class="space-y-1 text-xs text-muted-foreground">
            <li v-for="fact in run.memoryFacts" :key="fact.id">{{ fact.fact }}</li>
          </ul>
        </section>

        <UsageBadge :usage="run?.usage ?? null" :model="run?.model ?? null" />
      </template>

      <p v-else class="text-sm text-muted-foreground">
        {{ uiText("Send a message to see the agent's execution here.") }}
      </p>
    </div>
  </div>
</template>
