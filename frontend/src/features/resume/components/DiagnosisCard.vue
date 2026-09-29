<script setup lang="ts">
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
    <h3>历史简历诊断 · {{ payload.overall }} 分</h3>
    <p class="subtle">旧版评分报告；新版诊断以原文依据和建议为主。</p>
    <div v-for="(issue, index) in payload.issues" :key="index">
      <p>{{ issue.message }}</p>
      <p>{{ issue.suggestion }}</p>
    </div>
  </section>
</template>
