<script setup lang="ts">
import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import type { CareerCardDetails } from '@/shared/api/career'
defineProps<{ details?: CareerCardDetails | null; match?: number | null }>()
</script>
<template>
  <div class="form-stack break-words" :aria-label="uiText('Career information')">
    <p> {{ uiText("Interest match: {0} (interest similarity, not hiring probability)", [match == null ? uiText("Not provided #2") : Math.round(match * 100) + '%']) }} </p>
    <p>{{ uiText("Why this fits you (your information):") }}</p>
    <ul v-if="details?.evidence?.length">
      <li v-for="(evidence, index) in details.evidence" :key="index">
        「{{ formatDisplay(evidence.value) }}」 · {{ evidence.source }}/{{ evidence.field }}
      </li>
    </ul>
    <p v-if="(details?.evidence?.length ?? 0) < 2"> {{ uiText("Fewer than two supporting profile facts. Add profile details; missing evidence is not supplied.") }} </p>
    <dl>
      <dt>{{ uiText("Typical roles") }}</dt>
      <dd>{{ details?.typicalTitles?.join('、') || uiText("Not provided #2") }}</dd>
      <dt>{{ uiText("Core skills") }}</dt>
      <dd>{{ details?.coreSkills?.join('、') || uiText("Not provided #2") }}</dd>
      <dt>{{ uiText("Career path") }}</dt>
      <dd>{{ details?.growthPath || uiText("Not provided #2") }}</dd>
      <dt>{{ uiText("Salary range") }}</dt>
      <dd>{{ details?.salaryRange || uiText("Not provided #2") }}</dd>
      <dt>{{ uiText("Representative companies") }}</dt>
      <dd>{{ details?.companies?.join('、') || uiText("Not provided #2") }}</dd>
      <dt>{{ uiText("Potential concerns") }}</dt>
      <dd>{{ details?.concerns || uiText("Not provided #2") }}</dd>
      <dt>{{ uiText("Career catalog source") }}</dt>
      <dd>{{ details?.sourceOccupationId || uiText("Not provided #2") }} · {{ details?.source || uiText("Not provided #2") }}</dd>
    </dl>
  </div>
</template>
<style scoped>
dl {
  display: grid;
  grid-template-columns: minmax(5em, auto) minmax(0, 1fr);
  gap: 6px 12px;
}
dd {
  margin: 0;
  overflow-wrap: anywhere;
}
</style>
