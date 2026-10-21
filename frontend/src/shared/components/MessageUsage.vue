<script setup lang="ts">
import { formatNumber, translate as uiText } from '@/shared/utils/language'

import type { UsageView } from '@/shared/stores/agentRun'
defineProps<{ usage?: UsageView | null; model?: string | null; durationMs?: number | null }>()
</script>
<template>
  <details class="mt-3 min-w-0 break-words text-xs text-muted-foreground" :aria-label="uiText('Message usage')">
    <summary class="cursor-pointer">{{ uiText("Model and usage") }}</summary>
    <dl class="mt-2 grid grid-cols-[auto_1fr] gap-2">
      <template v-if="usage?.source === 'legacy_message'">
        <dt>{{ uiText("Source") }}</dt>
        <dd>{{ uiText("Legacy message record (no call logs)") }}</dd>
      </template>
      <dt>{{ uiText("Model") }}</dt>
      <dd>{{ usage?.model ?? uiText("Not recorded") }}</dd>
      <template v-if="!usage?.model && model"
        ><dt>{{ uiText("Requested model (no call logs)") }}</dt>
        <dd>{{ model }}</dd></template
      >
      <dt>{{ uiText("Input / Output tokens") }}</dt>
      <dd>
        {{
          usage && usage.usageReported !== false
            ? `${formatNumber(usage.promptTokens)} / ${formatNumber(usage.completionTokens)}`
            : uiText("Not reported")
        }}
      </dd>
      <dt>{{ uiText("Cumulative model call time") }}</dt>
      <dd>{{ usage?.durationMs != null ? `${formatNumber(usage.durationMs)} ms` : uiText("Not recorded") }}</dd>
      <dt v-if="durationMs != null">{{ uiText("Run duration") }}</dt>
      <dd v-if="durationMs != null">{{ formatNumber(durationMs) }} ms</dd>
      <dt>{{ uiText("Estimated cost") }}</dt>
      <dd>{{ usage?.costEstimate != null ? `¥${formatNumber(usage.costEstimate, { minimumFractionDigits: 6, maximumFractionDigits: 6 })}` : uiText("Unconfigured") }}</dd>
    </dl>
  </details>
</template>
