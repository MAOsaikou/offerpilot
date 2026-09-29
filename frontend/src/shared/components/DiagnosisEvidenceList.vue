<script setup lang="ts">
import type { DiagnosisEvidence } from '@/shared/types/diagnosis'

defineProps<{ evidence: DiagnosisEvidence[] }>()
const sections: Record<string, string> = {
  summary: '个人简介',
  education: '教育经历',
  experience: '工作 / 实习经历',
  projects: '项目经历',
  skills: '技能',
  certificates: '证书与奖项',
}
function location(path: string) {
  const [, section = '', index] = path.split('/')
  const label = sections[section] || '简历原文'
  return index && /^\d+$/.test(index) ? `${label} · 第 ${Number(index) + 1} 项` : label
}
</script>
<template>
  <ul class="evidence-list" aria-label="报告所引用的简历原文">
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
