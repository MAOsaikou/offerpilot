<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

defineProps<{ source?: string; snapshotDate?: string; cached?: boolean }>()
</script>

<template>
  <span
    v-if="source === 'LOCAL_SNAPSHOT'"
    class="tag"
    :title="
      uiText('Snapshot date: {0}; manually curated sample, not live data', [
        snapshotDate || uiText('Not provided #2'),
      ])
    "
    >{{ uiText('Offline snapshot') }}</span
  >
  <span
    v-else-if="source === 'STORED_SNAPSHOT'"
    class="tag"
    :title="
      uiText(
        'Live details unavailable. Saved company listing shown; it may have changed or closed.',
      )
    "
    >{{ uiText('Saved job · Live update unavailable') }}</span
  >
  <span
    v-else-if="source === 'demo'"
    class="tag"
    :title="uiText('Fictional demo job. No real applications accepted.')"
    >{{ uiText('Demo job') }}</span
  >
  <span
    v-else-if="source === 'USER_IMPORT'"
    class="tag"
    :title="
      uiText('This pasted or URL-imported job is private to you. Refer to the original listing.')
    "
    >{{ uiText('My imported jobs') }}</span
  >
  <span v-else class="tag">{{
    uiText('Company website · {0}', [cached ? uiText('Recent cache') : uiText('Current query')])
  }}</span>
</template>
