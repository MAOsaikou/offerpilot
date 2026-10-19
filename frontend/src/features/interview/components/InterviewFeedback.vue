<script setup lang="ts">
import type { InterviewEvaluation } from '@/shared/types/interview'
defineProps<{ evaluation: InterviewEvaluation }>()
const labels = { professional: '专业', expression: '表达', logic: '逻辑', jobFit: '岗位关联' }
</script>
<template>
  <section class="panel form-stack" aria-label="本题反馈" aria-live="polite">
    <h2>本题反馈 · {{ evaluation.score === null ? '未评分' : evaluation.score + ' / 100' }}</h2>
    <p>{{ evaluation.rationale }}</p>
    <div v-if="evaluation.dimensions" class="feedback-dimensions">
      <article v-for="(dimension, key) in evaluation.dimensions" :key="key">
        <h3>{{ labels[key] }} · {{ dimension.score }}/100</h3>
        <p>{{ dimension.rationale }}</p>
        <blockquote>「{{ dimension.evidence }}」</blockquote>
      </article>
    </div>
    <details open>
      <summary>改进答案（仅使用原文事实）</summary>
      <p class="prose-content">{{ evaluation.improvedAnswer }}</p>
      <p class="helper-note">
        {{ evaluation.provenance || '缺失事实请按占位符补充；当前没有可用的模型评价。' }}
      </p>
    </details>
  </section>
</template>
<style scoped>
.feedback-dimensions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
  gap: 16px;
}
blockquote {
  margin: 8px 0;
  color: var(--muted-foreground);
  overflow-wrap: anywhere;
}
p {
  overflow-wrap: anywhere;
}
</style>
