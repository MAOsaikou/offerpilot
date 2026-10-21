<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import ResumeDiagnosisReport from '@/shared/components/ResumeDiagnosisReport.vue'
import type { ResumeDiagnosisPayload } from '@/shared/types/cards'
defineProps<{ payload: ResumeDiagnosisPayload }>()
</script>
<template>
  <ResumeDiagnosisReport
    v-if="'schemaVersion' in payload && payload.schemaVersion === 2"
    :report="payload"
  />
  <section v-else-if="'overall' in payload" class="form-stack">
    <h3>{{ uiText("Historical resume diagnosis · Score {0}", [payload.overall]) }}</h3>
    <p class="subtle">{{ uiText("Legacy score report. New diagnoses focus on source evidence and suggestions.") }}</p>
    <div v-for="(issue, index) in payload.issues" :key="index">
      <p>{{ issue.message }}</p>
      <p>{{ issue.suggestion }}</p>
    </div>
  </section>
</template>
