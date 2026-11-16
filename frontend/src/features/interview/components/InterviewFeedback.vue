<script setup lang="ts">
import { computed } from 'vue'

import AppIcon from '@/shared/components/AppIcon.vue'
import { formatDisplay, translate as uiText } from '@/shared/utils/language'
import type { InterviewEvaluation } from '@/shared/types/interview'

const props = defineProps<{ evaluation: InterviewEvaluation }>()
const dimensionOrder = ['professional', 'expression', 'logic', 'jobFit'] as const
const labels = computed<Record<(typeof dimensionOrder)[number], string>>(() => ({
  professional: uiText('Domain knowledge'),
  expression: uiText('Communication'),
  logic: uiText('Logic'),
  jobFit: uiText('Job relevance'),
}))
const dimensions = computed(() =>
  dimensionOrder.flatMap((key) => {
    const dimension = props.evaluation.dimensions?.[key]
    return dimension ? [{ ...dimension, key, label: labels.value[key] }] : []
  }),
)
const score = computed(() =>
  typeof props.evaluation.score === 'number' &&
  Number.isFinite(props.evaluation.score) &&
  props.evaluation.score >= 0 &&
  props.evaluation.score <= 100
    ? props.evaluation.score
    : null,
)
</script>

<template>
  <section class="interview-feedback" :aria-label="uiText('Question feedback')" aria-live="polite">
    <header class="feedback-heading">
      <div class="feedback-heading-copy">
        <span class="feedback-eyebrow">
          <AppIcon name="spark" :size="15" />{{
            uiText('Question feedback · {0}', [
              score === null ? uiText('Not scored') : `${formatDisplay(score)} / 100`,
            ])
          }}
        </span>
        <h3>{{ uiText('What worked, and what to strengthen') }}</h3>
      </div>
      <div class="question-score" :class="{ 'is-unscored': score === null }">
        <strong>{{ score === null ? uiText('Not scored') : formatDisplay(score) }}</strong>
        <span v-if="score !== null">/ 100</span>
      </div>
    </header>

    <p class="feedback-summary">{{ evaluation.rationale }}</p>

    <div v-if="dimensions.length" class="feedback-dimensions">
      <article v-for="dimension in dimensions" :key="dimension.key" class="dimension-feedback">
        <div class="dimension-heading">
          <h4>{{ dimension.label }}</h4>
          <strong>{{ formatDisplay(dimension.score) }}<span>/100</span></strong>
        </div>
        <progress
          :value="dimension.score"
          max="100"
          :aria-label="uiText('{0} score', [dimension.label])"
        />
        <p>{{ dimension.rationale }}</p>
        <blockquote>
          <span>{{ uiText('Evidence from your answer') }}</span>
          「{{ dimension.evidence }}」
        </blockquote>
      </article>
    </div>

    <details class="improved-answer" open>
      <summary>
        <span><AppIcon name="resumes" :size="17" />{{ uiText('Improved answer') }}</span>
        <small>{{ uiText('Original facts only') }}</small>
      </summary>
      <div class="improved-answer-body">
        <p class="prose-content">{{ evaluation.improvedAnswer }}</p>
        <p class="helper-note">
          {{
            evaluation.provenance ||
            uiText(
              'Fill in missing facts using the placeholders. No model evaluation is currently available.',
            )
          }}
        </p>
      </div>
    </details>
  </section>
</template>

<style scoped>
.interview-feedback {
  min-width: 0;
  container-type: inline-size;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
}
.feedback-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  background: color-mix(in srgb, var(--primary) 5%, var(--card));
  border-bottom: 1px solid var(--border);
}
.feedback-heading-copy {
  min-width: 0;
}
.feedback-eyebrow {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--primary);
  font-size: 12px;
  font-weight: 650;
}
.feedback-heading h3 {
  margin: 5px 0 0;
  font-size: 16px;
  text-wrap: balance;
}
.question-score {
  display: flex;
  align-items: baseline;
  flex-shrink: 0;
  gap: 3px;
  color: var(--primary);
}
.question-score strong {
  font-family: var(--app-font-display);
  font-size: 30px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.question-score span {
  color: var(--muted-foreground);
  font-size: 12px;
}
.question-score.is-unscored strong {
  font-family: inherit;
  font-size: 14px;
}
.feedback-summary {
  margin: 0;
  padding: 18px 20px 0;
  color: var(--foreground);
  line-height: 1.75;
  text-wrap: pretty;
}
.feedback-dimensions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  padding: 18px 20px 20px;
}
.dimension-feedback {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--border);
  border-radius: calc(var(--radius) - 4px);
  background: var(--background);
}
.dimension-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}
.dimension-heading h4 {
  margin: 0;
  font-size: 13px;
}
.dimension-heading strong {
  font-family: var(--app-font-display);
  font-variant-numeric: tabular-nums;
}
.dimension-heading strong span {
  margin-left: 2px;
  color: var(--muted-foreground);
  font-size: 10px;
  font-weight: 400;
}
.dimension-feedback progress {
  display: block;
  width: 100%;
  height: 5px;
  margin: 10px 0;
  appearance: none;
  border: 0;
  border-radius: 5px;
  overflow: hidden;
  background: var(--muted);
  accent-color: var(--primary);
}
.dimension-feedback progress::-webkit-progress-bar {
  background: var(--muted);
}
.dimension-feedback progress::-webkit-progress-value {
  background: var(--primary);
  border-radius: 5px;
}
.dimension-feedback progress::-moz-progress-bar {
  background: var(--primary);
  border-radius: 5px;
}
.dimension-feedback p {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  text-wrap: pretty;
}
blockquote {
  margin: 12px 0 0;
  padding: 9px 10px;
  border-left: 2px solid var(--primary);
  border-radius: 0 6px 6px 0;
  background: var(--muted);
  color: var(--muted-foreground);
  font-size: 12px;
  line-height: 1.65;
}
blockquote span {
  display: block;
  margin-bottom: 3px;
  color: var(--foreground);
  font-size: 11px;
  font-weight: 600;
}
.improved-answer {
  border-top: 1px solid var(--border);
}
.improved-answer::details-content {
  interpolate-size: allow-keywords;
  overflow: clip;
  contain: layout;
}
.improved-answer:not([open])::details-content {
  block-size: 0;
}
@media (prefers-reduced-motion: no-preference) {
  .improved-answer::details-content {
    transition: block-size, content-visibility, display;
    transition-duration: var(--dur-mid);
    transition-timing-function: var(--ease-out);
    transition-behavior: allow-discrete;
  }
}
.improved-answer summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  padding: 15px 20px;
  color: var(--primary);
  cursor: pointer;
  font-weight: 600;
}
.improved-answer summary > span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}
.improved-answer summary small {
  color: var(--muted-foreground);
  font-size: 11px;
  font-weight: 400;
}
.improved-answer-body {
  padding: 0 20px 18px;
}
.improved-answer-body .prose-content {
  padding: 14px;
  border-radius: 8px;
  background: var(--muted);
  line-height: 1.8;
  text-wrap: pretty;
}
.feedback-summary,
.dimension-feedback,
blockquote,
.improved-answer-body {
  overflow-wrap: anywhere;
}
@container (max-width: 620px) {
  .feedback-dimensions {
    grid-template-columns: 1fr;
  }
}
@supports not (container-type: inline-size) {
  @media (max-width: 620px) {
    .feedback-dimensions {
      grid-template-columns: 1fr;
    }
  }
}
@media (max-width: 520px) {
  .feedback-heading,
  .feedback-dimensions {
    padding-right: 14px;
    padding-left: 14px;
  }
  .feedback-summary {
    padding-right: 14px;
    padding-left: 14px;
  }
  .improved-answer summary,
  .improved-answer-body {
    padding-right: 14px;
    padding-left: 14px;
  }
}
</style>
