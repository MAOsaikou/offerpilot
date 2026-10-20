<script setup lang="ts">
import type { CitationSource } from '@/shared/types/agent-events'
import { safeExternalUrl } from '@/shared/utils/safeExternalUrl'
defineProps<{ sources: CitationSource[] }>()
</script>
<template>
  <section v-if="sources.length" aria-label="引用来源" class="mt-3 space-y-2 min-w-0 break-words">
    <h3 class="text-sm font-semibold">引用来源</h3>
    <article
      v-for="source in sources"
      :key="`${source.index}-${source.docId}`"
      :data-citation-index="source.index"
      tabindex="-1"
      class="rounded border border-border p-3 text-xs"
    >
      <h4>[{{ source.index }}] {{ source.title }}</h4>
      <p>{{ source.snippet || '暂无摘录' }}</p>
      <p>检索分数：{{ source.score == null ? '未提供' : source.score }}</p>
      <a
        v-if="safeExternalUrl(source.url)"
        :href="safeExternalUrl(source.url)!"
        target="_blank"
        rel="noopener noreferrer"
        class="btn"
        >打开来源</a
      >
    </article>
  </section>
</template>
