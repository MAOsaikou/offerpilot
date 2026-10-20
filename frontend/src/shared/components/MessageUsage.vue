<script setup lang="ts">
import type { UsageView } from '@/shared/stores/agentRun'
defineProps<{ usage?: UsageView | null; model?: string | null; durationMs?: number | null }>()
</script>
<template>
  <details class="mt-3 min-w-0 break-words text-xs text-muted-foreground" aria-label="消息用量">
    <summary class="cursor-pointer">模型与用量</summary>
    <dl class="mt-2 grid grid-cols-[auto_1fr] gap-2">
      <template v-if="usage?.source === 'legacy_message'">
        <dt>来源</dt>
        <dd>旧版消息记录（无调用日志）</dd>
      </template>
      <dt>模型</dt>
      <dd>{{ usage?.model ?? '未记录' }}</dd>
      <template v-if="!usage?.model && model"
        ><dt>请求模型（无调用日志）</dt>
        <dd>{{ model }}</dd></template
      >
      <dt>输入 / 输出 token</dt>
      <dd>
        {{
          usage && usage.usageReported !== false
            ? `${usage.promptTokens} / ${usage.completionTokens}`
            : '未报告'
        }}
      </dd>
      <dt>模型调用累计耗时</dt>
      <dd>{{ usage?.durationMs != null ? `${usage.durationMs} ms` : '未记录' }}</dd>
      <dt v-if="durationMs != null">本轮耗时</dt>
      <dd v-if="durationMs != null">{{ durationMs }} ms</dd>
      <dt>成本估算</dt>
      <dd>{{ usage?.costEstimate != null ? `¥${usage.costEstimate.toFixed(6)}` : '未配置' }}</dd>
    </dl>
  </details>
</template>
