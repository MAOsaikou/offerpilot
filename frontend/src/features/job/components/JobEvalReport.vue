<script setup lang="ts">
import { computed } from 'vue'
import type { JobEvaluation } from '@/shared/types/jobs'

const props = defineProps<{ payload: JobEvaluation | { evaluation?: JobEvaluation } | unknown }>()
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
</script>
<template>
  <section v-if="data" class="panel form-stack" aria-labelledby="job-eval-heading">
    <div class="section-heading">
      <h2 id="job-eval-heading">岗位评估卡 · {{ data.grade }}</h2>
      <span class="tag">{{ data.overall }}/100</span>
    </div>
    <p class="subtle">{{ data.summary }}</p>
    <p class="helper-note">{{ data.note }}</p>
    <ul class="form-stack" style="list-style: none; padding: 0; margin: 0; gap: 8px">
      <li v-for="dim in data.dimensions" :key="dim.code" class="rounded-lg border p-3">
        <div class="section-heading">
          <strong>{{ dim.code }} {{ dim.name }}</strong>
          <span class="subtle">{{ dim.score }}/100</span>
        </div>
        <p class="subtle">{{ dim.reason }}</p>
      </li>
    </ul>
    <div>
      <h3 class="font-semibold">建议</h3>
      <ul>
        <li v-for="(line, index) in data.advice" :key="index">{{ line }}</li>
      </ul>
    </div>
  </section>
  <p v-else class="helper-note" role="status">岗位评估数据不可用。</p>
</template>
