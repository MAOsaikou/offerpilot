<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

/**
 * 兜底组件，两种场景共用：
 * - 未知 cardType：显示「暂不支持渲染」+ 原始 JSON
 * - 异步组件加载失败：defineAsyncComponent 会把 error 传进来
 */
const props = defineProps<{
  cardType?: string
  payload?: unknown
  reason?: string
  error?: Error
}>()
</script>

<template>
  <div class="rounded-2xl border border-dashed border-border bg-card/50 p-4">
    <p class="text-xs text-muted-foreground">
      {{ props.reason ?? (props.error ? uiText("Could not load card") : uiText("This card type is not supported yet")) }}
      <span v-if="props.cardType" class="font-mono">{{ props.cardType }}</span>
    </p>
    <p v-if="props.error" class="mt-1 font-mono text-xs text-danger">{{ props.error.message }}</p>

    <details v-if="props.payload !== undefined" class="mt-2">
      <summary class="cursor-pointer text-xs text-muted-foreground select-none"> {{ uiText("View raw data") }} </summary>
      <pre
        class="mt-2 max-h-52 overflow-auto rounded-lg bg-foreground/5 p-2 font-mono text-xs leading-relaxed"
        >{{ JSON.stringify(props.payload, null, 2) }}</pre>
    </details>
  </div>
</template>
