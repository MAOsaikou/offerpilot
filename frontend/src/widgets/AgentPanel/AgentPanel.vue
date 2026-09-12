<script setup lang="ts">
import { computed, ref } from 'vue'
import ApprovalCard from './ApprovalCard.vue'
import PlanSteps from './PlanSteps.vue'
import ToolTimeline from './ToolTimeline.vue'
import UsageBadge from './UsageBadge.vue'
import type { RunState } from '@/shared/stores/agentRun'

const props = defineProps<{
  run: RunState | null
}>()

const reasoningOpen = ref(false)

const hasRun = computed(() => props.run !== null)
const statusLabel = computed(() => {
  switch (props.run?.status) {
    case 'RUNNING':
      return '执行中'
    case 'COMPLETED':
      return '已完成'
    case 'CANCELLED':
      return '已取消'
    case 'FAILED':
      return '失败'
    default:
      return '空闲'
  }
})
</script>

<template>
  <div class="glass-panel flex h-full flex-col overflow-hidden rounded-2xl">
    <header class="flex h-12 shrink-0 items-center justify-between border-b border-border/60 px-4">
      <span class="text-sm font-medium">Agent</span>
      <span
        class="flex items-center gap-1.5 text-xs"
        :class="run?.status === 'FAILED' ? 'text-danger' : 'text-muted-foreground'"
      >
        <span
          class="size-1.5 rounded-full"
          :class="run?.status === 'RUNNING' ? 'bg-primary pulse-dot' : 'bg-muted-foreground/40'"
        />
        {{ statusLabel }}
      </span>
    </header>

    <div class="min-h-0 flex-1 space-y-5 overflow-y-auto p-4">
      <template v-if="hasRun">
        <PlanSteps :goal="run?.goal ?? null" :steps="run?.steps ?? []" />

        <section v-if="run?.reasoning">
          <button
            type="button"
            class="flex w-full items-center gap-1 text-xs font-medium tracking-wide text-muted-foreground uppercase"
            @click="reasoningOpen = !reasoningOpen"
          >
            思考摘要
            <span class="transition-transform" :class="{ 'rotate-90': reasoningOpen }">›</span>
          </button>
          <p v-if="reasoningOpen" class="mt-2 text-xs leading-relaxed text-muted-foreground">
            {{ run.reasoning }}
          </p>
        </section>

        <ToolTimeline :tool-calls="run?.toolCalls ?? []" />

        <ApprovalCard :approvals="run?.approvals ?? []" />

        <section v-if="run && run.citations.length > 0">
          <h3 class="mb-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
            引用
          </h3>
          <ul class="space-y-1.5">
            <li v-for="source in run.citations" :key="source.docId" class="text-xs">
              <span class="font-mono text-primary">[{{ source.index }}]</span>
              {{ source.title }}
              <span class="ml-1 text-muted-foreground">
                {{ (source.score * 100).toFixed(0) }}%
              </span>
            </li>
          </ul>
        </section>

        <section v-if="run && run.memoryFacts.length > 0">
          <h3 class="mb-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
            用到的记忆
          </h3>
          <ul class="space-y-1 text-xs text-muted-foreground">
            <li v-for="fact in run.memoryFacts" :key="fact.id">{{ fact.fact }}</li>
          </ul>
        </section>

        <UsageBadge :usage="run?.usage ?? null" :model="run?.model ?? null" />
      </template>

      <p v-else class="text-sm text-muted-foreground">发一句话，这里会显示 Agent 的执行过程。</p>
    </div>
  </div>
</template>
