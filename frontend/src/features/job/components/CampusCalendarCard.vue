<script setup lang="ts">
import { formatDate, translate as uiText } from '@/shared/utils/language'

import { computed } from 'vue'
import type { CampusCalendarPayload } from '@/shared/types/cards'
import { safeExternalUrl } from '@/shared/utils/safeExternalUrl'
const props = defineProps<{ payload: CampusCalendarPayload }>()
const valid = computed(
  () =>
    Array.isArray(props.payload?.items) &&
    props.payload.items.every(
      (i) =>
        typeof i.company === 'string' &&
        Array.isArray(i.positions) &&
        typeof i.deadline === 'string' &&
        Number.isFinite(Date.parse(i.deadline)),
    ),
)
</script>
<template>
  <section
    class="panel form-stack min-w-0 break-words"
    :aria-label="uiText('Recruitment calendar')"
  >
    <h3>{{ uiText('Recruitment deadlines') }}</h3>
    <p>
      {{
        uiText(
          'Only explicit deadlines from this query are shown. Check the source announcement for confirmation.',
        )
      }}
    </p>
    <p v-if="!valid" role="alert">
      {{ uiText('Calendar data is unavailable. Please search again.') }}
    </p>
    <p v-else-if="!payload.items.length" role="status">
      {{ uiText('No explicit deadlines in these results.') }}
    </p>
    <ol v-else class="space-y-3">
      <li v-for="(item, index) in payload.items" :key="index">
        <strong>{{ item.company }}</strong> · {{ item.batch }}
        <p>{{ item.positions.join('、') }}</p>
        <time :datetime="item.deadline">{{ formatDate(item.deadline) }}</time>
        <a
          v-if="safeExternalUrl(item.url)"
          :href="safeExternalUrl(item.url)!"
          target="_blank"
          rel="noopener noreferrer"
          class="btn"
          >{{ uiText('View source announcement') }}</a
        >
      </li>
    </ol>
  </section>
</template>
