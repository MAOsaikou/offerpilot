<script setup lang="ts">
import type { UsageView } from '@/shared/stores/agentRun'

defineProps<{
  usage: UsageView | null
  model: string | null
}>()
</script>

<template>
  <section>
    <h3 class="mb-2 text-xs font-semibold text-muted-foreground">用量</h3>

    <p v-if="!usage && !model" class="text-sm text-muted-foreground">暂无</p>

    <dl v-else class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 num text-xs">
      <dt class="text-muted-foreground">模型</dt>
      <dd class="truncate text-right">{{ usage?.model ?? model ?? '—' }}</dd>

      <template v-if="usage">
        <dt class="text-muted-foreground">输入</dt>
        <dd class="text-right">{{ usage.promptTokens.toLocaleString() }} tok</dd>
        <dt class="text-muted-foreground">输出</dt>
        <dd class="text-right">{{ usage.completionTokens.toLocaleString() }} tok</dd>
        <dt class="text-muted-foreground">成本</dt>
        <dd class="text-right">¥{{ usage.costEstimate.toFixed(4) }}</dd>
        <template v-if="usage.fallbackUsed">
          <dt class="text-muted-foreground">回退</dt>
          <dd class="text-right text-warning">已触发</dd>
        </template>
      </template>
    </dl>
  </section>
</template>
