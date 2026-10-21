<script setup lang="ts">
import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import type { CitationSource } from '@/shared/types/agent-events'
import { safeExternalUrl } from '@/shared/utils/safeExternalUrl'
defineProps<{ sources: CitationSource[] }>()
</script>
<template>
  <section
    v-if="sources.length"
    :aria-label="uiText('Sources')"
    class="mt-3 space-y-2 min-w-0 break-words"
  >
    <h3 class="text-sm font-semibold">{{ uiText('Sources') }}</h3>
    <article
      v-for="source in sources"
      :key="`${source.index}-${source.docId}`"
      :data-citation-index="source.index"
      tabindex="-1"
      class="rounded border border-border p-3 text-xs"
    >
      <h4>[{{ formatDisplay(source.index) }}] {{ source.title }}</h4>
      <p>{{ source.snippet || uiText('No excerpt available') }}</p>
      <p>
        {{
          uiText('Retrieval score: {0}', [
            source.score == null ? uiText('Not provided #2') : source.score,
          ])
        }}
      </p>
      <a
        v-if="safeExternalUrl(source.url)"
        :href="safeExternalUrl(source.url)!"
        target="_blank"
        rel="noopener noreferrer"
        class="btn"
        >{{ uiText('Open source') }}</a
      >
    </article>
  </section>
</template>
