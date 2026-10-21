<script setup lang="ts">
import { computed } from 'vue'

import { translate as uiText } from '@/shared/utils/language'

import type { DiagnosisEvidence } from '@/shared/types/diagnosis'

defineProps<{ evidence: DiagnosisEvidence[] }>()
const sections = computed<Record<string, string>>(() => ({
  summary: uiText('Professional summary'),
  education: uiText('Education #2'),
  experience: uiText('Work / Internship experience'),
  projects: uiText('Projects'),
  skills: uiText('Skills'),
  certificates: uiText('Certificates and awards'),
}))
function location(path: string) {
  const [, section = '', index] = path.split('/')
  const label = sections.value[section] || uiText('Original resume text')
  return index && /^\d+$/.test(index) ? uiText('{0} · Item {1}', [label, Number(index) + 1]) : label
}
</script>
<template>
  <ul class="evidence-list" :aria-label="uiText('Original resume text cited by the report')">
    <li v-for="item in evidence" :key="item.path + item.quote">
      <span class="evidence-location">{{ location(item.path) }}</span>
      <blockquote>{{ item.quote }}</blockquote>
    </li>
  </ul>
</template>
<style scoped>
.evidence-list {
  display: grid;
  gap: 10px;
  list-style: none;
  margin: 8px 0;
  padding: 0;
}
.evidence-list li {
  padding: 12px;
  border-left: 2px solid var(--border);
  background: var(--background);
  border-radius: 0 var(--radius-tag) var(--radius-tag) 0;
}
.evidence-location {
  font-size: 11px;
  color: var(--muted-foreground);
}
blockquote {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
