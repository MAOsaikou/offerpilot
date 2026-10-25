<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { computed } from 'vue'
import PresenceGauges from './PresenceGauges.vue'
import InterviewFeedback from './InterviewFeedback.vue'
import InterviewScoreRadar from './InterviewScoreRadar.vue'
import { RouterLink } from 'vue-router'
import CountUp from '@/shared/components/CountUp.vue'
import { asPayload } from '@/shared/utils/payload'
import type { InterviewReportPayload } from '@/shared/types/cards'

const props = defineProps<{ payload: unknown; inline?: boolean }>()
const data = computed(() => asPayload<InterviewReportPayload>(props.payload, 'dimensions'))
const overall = computed(() => {
  const value = data.value?.overall
  return typeof value === 'number' && Number.isFinite(value) ? value : null
})
const dimensions = computed(() =>
  (
    [
      {
        key: 'professional',
        label: uiText('Domain knowledge'),
        reason: uiText('Responsibility, action, results and quantitative evidence'),
      },
      {
        key: 'expression',
        label: uiText('Communication'),
        reason: uiText('Text length, sentence structure and organization'),
      },
      {
        key: 'logic',
        label: uiText('Logic'),
        reason: uiText('Coverage of all four STAR elements'),
      },
      {
        key: 'jobFit',
        label: uiText('Job matching'),
        reason: uiText('Responsibility, action, results and relevance to the role'),
      },
    ] as const
  ).map((dimension) => {
    const value = data.value?.dimensions?.[dimension.key]
    return {
      ...dimension,
      score:
        typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100
          ? value
          : null,
    }
  }),
)
const starParts = computed(
  () =>
    [
      { key: 'situation', label: uiText('S · Situation') },
      { key: 'task', label: uiText('T · Task') },
      { key: 'action', label: uiText('A · Action') },
      { key: 'result', label: uiText('R · Result') },
    ] as const,
)
const sections = computed(
  () =>
    [
      { key: 'strengths', label: uiText('Strengths observed') },
      { key: 'weaknesses', label: uiText('Gaps and missing details') },
      { key: 'advice', label: uiText('Suggestions for improvement') },
    ] as const,
)
</script>

<template>
  <article v-if="data" class="surface space-y-5 rounded-[14px] p-4 text-sm">
    <header>
      <h3 class="font-semibold">{{ uiText('Interview scoring and review') }}</h3>
      <div class="score-hero">
        <div class="score-dial">
          <svg viewBox="0 0 64 64" aria-hidden="true">
            <circle class="ring-track" cx="32" cy="32" r="28" />
            <circle
              v-if="overall !== null"
              class="ring-value"
              cx="32"
              cy="32"
              r="28"
              pathLength="100"
              :style="{ '--score': overall }"
            />
          </svg>
          <p class="score-value">
            <span class="score-label">{{ uiText('Overall score') }} </span
            ><CountUp v-if="overall !== null" class="score-number" :value="overall" :delay="150" />
            <template v-else>{{ uiText('Not scored') }}</template
            ><span class="score-max"> / 100</span>
          </p>
        </div>
        <div class="min-w-0">
          <p class="break-words text-xs text-muted-foreground">
            {{
              data.note ||
              uiText('Rule-based text scoring. On-device performance analysis is disabled.')
            }}
          </p>
          <RouterLink v-if="data.id && !inline" :to="'/interviews/' + data.id" class="btn mt-2">{{
            uiText('Open interview review')
          }}</RouterLink>
        </div>
      </div>
    </header>
    <InterviewScoreRadar :dimensions="data.dimensions" />
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
      <div v-for="dimension in dimensions" :key="dimension.key">
        <div class="flex justify-between gap-2">
          <span>{{ dimension.label }}</span
          ><span>{{ dimension.score ?? uiText('Not scored') }} / 100</span>
        </div>
        <progress
          v-if="dimension.score !== null"
          class="mt-1 h-2 w-full accent-primary"
          :value="dimension.score ?? undefined"
          max="100"
          :aria-label="dimension.label"
        />
        <p
          v-if="
            data.turns.length && data.turns.every((turn) => !turn.source || turn.source === 'rule')
          "
          class="text-xs text-muted-foreground"
        >
          {{ dimension.reason }}
        </p>
      </div>
    </div>
    <PresenceGauges
      v-if="data.presence"
      status="running"
      :values="{
        eyeContact: data.presence.eyeContactRatio,
        smile: data.presence.smileRate,
        headStability: data.presence.headStability,
        postureStability: data.presence.postureStability,
      }"
      :note="data.presence.note"
    />
    <p v-if="!data.turns?.length" class="text-muted-foreground">
      {{ uiText('No answers yet. Complete a text interview and request scoring again.') }}
    </p>
    <section
      v-for="(turn, index) in data.turns"
      :key="index"
      class="space-y-2 border-t border-border pt-3"
    >
      <h4 class="font-medium">
        {{ uiText('Question {0} · {1} / 100', [index + 1, turn.score ?? uiText('Not scored')]) }}
      </h4>
      <p class="break-words">{{ turn.question }}</p>
      <p class="break-words text-muted-foreground">
        {{ turn.answerSummary || uiText('Not answered yet') }}
      </p>
      <div v-if="!turn.source || turn.source === 'rule'" class="flex flex-wrap gap-2">
        <span
          v-for="part in starParts"
          :key="part.key"
          class="rounded-md bg-muted px-2 py-1 text-xs"
          :aria-label="`${part.label}：${turn.star[part.key] ? uiText('Observed') : uiText('Not observed')}`"
        >
          {{ turn.star[part.key] ? '✓' : '○' }} {{ part.label }}
        </span>
      </div>
      <p class="break-words">{{ uiText('Scoring rationale: {0}', [turn.feedback]) }}</p>
      <InterviewFeedback v-if="turn.evaluation" :evaluation="turn.evaluation" />
      <details v-else-if="turn.improvedAnswer" open>
        <summary class="cursor-pointer text-primary">
          {{ uiText('Improved answer (original facts)') }}
        </summary>
        <p class="prose-content">{{ turn.improvedAnswer }}</p>
      </details>
      <details v-if="turn.betterAnswerOutline.length">
        <summary class="cursor-pointer text-primary">
          {{ uiText('Improved answer outline') }}
        </summary>
        <ol class="mt-2 list-decimal space-y-1 pl-5">
          <li v-for="line in turn.betterAnswerOutline" :key="line">{{ line }}</li>
        </ol>
      </details>
    </section>
    <section v-for="section in sections" :key="section.key">
      <h4 class="font-medium">{{ section.label }}</h4>
      <ul class="mt-1 list-disc space-y-1 pl-5">
        <li v-for="item in data[section.key]" :key="item">{{ item }}</li>
      </ul>
    </section>
    <section v-if="data.recommendedLearning?.length">
      <h4 class="font-medium">{{ uiText('Suggested practice') }}</h4>
      <ul class="mt-1 space-y-2">
        <li v-for="item in data.recommendedLearning" :key="item.topic">
          {{ uiText('{0} · About {1} hours', [item.topic, item.estimatedHours]) }}
          <p class="text-xs text-muted-foreground">{{ item.reason }}</p>
        </li>
      </ul>
    </section>
  </article>
  <p v-else class="surface rounded-[14px] p-4 text-sm text-muted-foreground">
    {{ uiText('Interview score data is unavailable. Please request scoring again.') }}
  </p>
</template>

<style scoped>
.score-hero {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 12px;
}
.score-dial {
  position: relative;
  flex-shrink: 0;
  width: 96px;
  height: 96px;
}
.score-dial svg {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}
.score-dial circle {
  fill: none;
  stroke-width: 5;
}
.ring-track {
  stroke: var(--muted);
}
.ring-value {
  stroke: var(--primary);
  stroke-linecap: round;
  stroke-dasharray: 100;
  stroke-dashoffset: calc(100 - var(--score));
  animation: ring-fill 900ms var(--ease-out) 150ms backwards;
}
@keyframes ring-fill {
  from {
    stroke-dashoffset: 100;
  }
}
.score-value {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  line-height: 1.1;
  text-align: center;
}
.score-label,
.score-max {
  color: var(--muted-foreground);
  font-size: 11px;
}
.score-number {
  font-family: var(--app-font-display);
  font-stretch: 88%;
  font-size: 30px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}
progress {
  appearance: none;
  border: 0;
  border-radius: 4px;
  overflow: hidden;
  background: var(--muted);
}
progress::-webkit-progress-bar {
  background: var(--muted);
}
progress::-webkit-progress-value {
  background: var(--primary);
  border-radius: 4px;
  transform-origin: left center;
  animation: bar-grow 800ms var(--ease-out) 250ms backwards;
}
@keyframes bar-grow {
  from {
    transform: scaleX(0);
  }
}
progress::-moz-progress-bar {
  background: var(--primary);
}
</style>
