<script setup lang="ts">
import { formatDisplay, localizedText, translate as uiText } from '@/shared/utils/language'

import { computed, ref } from 'vue'
import type { JobEvaluation } from '@/shared/types/jobs'
import { exportJobEvalMarkdown } from '@/shared/api/jobs'

const props = defineProps<{
  payload: JobEvaluation | { evaluation?: JobEvaluation } | unknown
  matchId?: string
}>()
const data = computed(() => {
  const raw = props.payload as {
    evaluation?: JobEvaluation
    overall?: number
    dimensions?: unknown
  }
  if (
    raw &&
    typeof raw === 'object' &&
    Array.isArray((raw as JobEvaluation).dimensions) &&
    'overall' in raw
  ) {
    return raw as JobEvaluation
  }
  if (raw && typeof raw === 'object' && raw.evaluation) return raw.evaluation
  return null
})
const reportId = computed(() => props.matchId || data.value?.matchId)
const exporting = ref(false)
const exportError = localizedText()
async function exportMarkdown() {
  if (!reportId.value || exporting.value) return
  exporting.value = true
  exportError.value = ''
  try {
    const title = data.value?.title?.trim() || uiText("Jobs")
    await exportJobEvalMarkdown(reportId.value, uiText("{0}-job-assessment.md", [title]))
  } catch (error) {
    exportError.value = () => (error instanceof Error ? error.message : uiText("Export failed. Please retry."))
  } finally {
    exporting.value = false
  }
}
</script>
<template>
  <section v-if="data" class="panel form-stack" aria-labelledby="job-eval-heading">
    <div class="section-heading">
      <h2 id="job-eval-heading">{{ uiText("Job assessment · {0}", [data.grade]) }}</h2>
      <span class="tag">{{ formatDisplay(data.overall) }}/100</span>
    </div>
    <p class="subtle">{{ data.summary }}</p>
    <p class="helper-note">{{ data.note }}</p>
    <div v-if="reportId" class="card-footer">
      <button type="button" class="btn" :disabled="exporting" @click="exportMarkdown">
        {{ exporting ? uiText("Exporting…") : uiText("Download assessment.md #2") }}
      </button>
      <p v-if="exportError" class="error-banner" role="alert">{{ exportError }}</p>
    </div>
    <ul class="form-stack" style="list-style: none; padding: 0; margin: 0; gap: 8px">
      <li v-for="dim in data.dimensions" :key="dim.code" class="rounded-lg border p-3">
        <div class="section-heading">
          <strong>{{ dim.code }} {{ dim.name }}</strong>
          <span class="subtle">{{ formatDisplay(dim.score) }}/100</span>
        </div>
        <p class="subtle">{{ dim.reason }}</p>
      </li>
    </ul>
    <div>
      <h3 class="font-semibold">{{ uiText("Suggestions") }}</h3>
      <ul>
        <li v-for="(line, index) in data.advice" :key="index">{{ line }}</li>
      </ul>
    </div>
  </section>
  <p v-else class="helper-note" role="status">{{ uiText("Job assessment data is unavailable.") }}</p>
</template>
