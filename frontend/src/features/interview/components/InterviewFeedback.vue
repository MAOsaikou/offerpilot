<script setup lang="ts">
import { computed } from 'vue'

import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import type { InterviewEvaluation } from '@/shared/types/interview'
defineProps<{ evaluation: InterviewEvaluation }>()
const labels = computed(() => ({ professional: uiText("Domain knowledge"), expression: uiText("Communication"), logic: uiText("Logic"), jobFit: uiText("Job relevance") }))
</script>
<template>
  <section class="panel form-stack" :aria-label="uiText('Question feedback')" aria-live="polite">
    <h2>{{ uiText("Question feedback · {0}", [evaluation.score === null ? uiText("Not scored") : evaluation.score + ' / 100']) }}</h2>
    <p>{{ evaluation.rationale }}</p>
    <div v-if="evaluation.dimensions" class="feedback-dimensions">
      <article v-for="(dimension, key) in evaluation.dimensions" :key="key">
        <h3>{{ labels[key] }} · {{ formatDisplay(dimension.score) }}/100</h3>
        <p>{{ dimension.rationale }}</p>
        <blockquote>「{{ dimension.evidence }}」</blockquote>
      </article>
    </div>
    <details open>
      <summary>{{ uiText("Improved answer (original facts only)") }}</summary>
      <p class="prose-content">{{ evaluation.improvedAnswer }}</p>
      <p class="helper-note">
        {{ evaluation.provenance || uiText("Fill in missing facts using the placeholders. No model evaluation is currently available.") }}
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
