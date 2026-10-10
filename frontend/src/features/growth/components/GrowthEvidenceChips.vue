<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { GrowthEvidence } from '@/shared/types/cards'
defineProps<{ sources: GrowthEvidence[] }>()
function destination(source: GrowthEvidence) {
  if (!source.id) return undefined
  if (source.type === 'JOB_MATCH' && source.jobId)
    return { path: `/jobs/${encodeURIComponent(source.jobId)}`, query: { matchId: source.id } }
  if (source.type === 'INTERVIEW') return `/interviews/${encodeURIComponent(source.id)}`
  return undefined
}
</script>

<template>
  <ul v-if="sources.length" class="mt-2 flex flex-wrap gap-2 text-xs" aria-label="差距证据来源">
    <li v-for="(source, index) in sources" :key="index" class="min-w-0 max-w-full">
      <RouterLink
        v-if="destination(source)"
        :to="destination(source)!"
        class="inline-flex min-h-11 max-w-full items-center break-words rounded border border-border px-2 py-1 text-primary underline"
        :title="source.excerpt"
        >{{ source.label }} · 查看{{ source.type === 'INTERVIEW' ? '复盘' : '报告' }}</RouterLink
      >
      <span
        v-else
        class="inline-block break-words rounded bg-muted px-2 py-1"
        :title="source.excerpt"
        >{{ source.label }}</span
      >
      <p class="mt-1 break-words text-muted-foreground">{{ source.excerpt }}</p>
    </li>
  </ul>
</template>
