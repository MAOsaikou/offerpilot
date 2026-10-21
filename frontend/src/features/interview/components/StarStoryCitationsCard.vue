<script setup lang="ts">
import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import { RouterLink } from 'vue-router'
import type { StarStoryCitationsPayload } from '@/shared/types/cards'
defineProps<{ payload: StarStoryCitationsPayload }>()
</script>
<template>
  <section class="panel form-stack min-w-0 break-words" :aria-label="uiText('Confirmed STAR story references')">
    <h3>{{ uiText("Confirmed STAR stories · {0}", [payload.count]) }}</h3>
    <p class="helper-note">{{ uiText("For recall and practice only. These are not the answer to this question or scoring evidence.") }}</p>
    <article v-for="item in payload.items" :key="item.id" class="rounded-lg border p-3">
      <h4>{{ item.title }}</h4>
      <p class="subtle">{{ item.role }} · STAR {{ formatDisplay(item.starCount) }} / 4</p>
      <p class="whitespace-pre-wrap">{{ item.excerpt }}</p>
    </article>
    <RouterLink to="/interviews" class="btn">{{ uiText("View STAR story library") }}</RouterLink>
  </section>
</template>
