<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { computed, nextTick, ref, watch, type ComponentPublicInstance } from 'vue'
import PresenceGauges from './PresenceGauges.vue'
import InterviewFeedback from './InterviewFeedback.vue'
import InterviewScoreRadar from './InterviewScoreRadar.vue'
import { RouterLink } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
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
const sections = computed(() =>
  (
    [
      {
        key: 'strengths',
        label: uiText('Strengths observed'),
        description: uiText('Keep these clear points in your next answer.'),
        icon: 'check',
      },
      {
        key: 'weaknesses',
        label: uiText('Gaps and missing details'),
        description: uiText('Add missing facts rather than inventing new experience.'),
        icon: 'discover',
      },
      {
        key: 'advice',
        label: uiText('Suggestions for improvement'),
        description: uiText('Turn the feedback into a concrete next practice.'),
        icon: 'arrow',
      },
    ] as const
  ).map((section) => ({
    ...section,
    items: data.value?.[section.key] ?? [],
  })),
)
const hasInsights = computed(() => sections.value.some((section) => section.items.length > 0))
const scoredTurns = computed(
  () => data.value?.turns.filter((turn) => typeof turn.score === 'number').length ?? 0,
)
const nextSteps = computed(() => data.value?.recommendedLearning ?? [])
const openTurn = ref(0)
const turnElements: Array<HTMLElement | undefined> = []
watch(
  () => data.value?.id,
  () => {
    openTurn.value = 0
  },
)
function setTurnRef(element: Element | ComponentPublicInstance | null, index: number) {
  turnElements[index] = element instanceof HTMLElement ? element : undefined
}
function handleToggle(index: number, event: Event) {
  if (event.currentTarget instanceof HTMLDetailsElement && event.currentTarget.open)
    openTurn.value = index
}
async function goToTurn(index: number) {
  openTurn.value = index
  await nextTick()
  const target = turnElements[index]
  target?.scrollIntoView?.({
    behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
    block: 'start',
  })
  target?.focus?.({ preventScroll: true })
}
</script>

<template>
  <article v-if="data" class="interview-review">
    <header class="review-hero">
      <div class="review-heading">
        <span class="review-eyebrow">
          <AppIcon name="check" :size="15" />{{ uiText('Feedback is ready') }}
        </span>
        <h3>{{ uiText('Interview scoring and review') }}</h3>
        <p class="review-tagline">{{ uiText('Turn this practice into your next improvement') }}</p>
        <p class="review-note">
          {{
            data.note ||
            uiText('Rule-based text scoring. On-device performance analysis is disabled.')
          }}
        </p>
      </div>
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
          <span class="score-label">{{ uiText('Overall score') }}</span>
          <CountUp v-if="overall !== null" class="score-number" :value="overall" :delay="150" />
          <strong v-else class="unscored-value">{{ uiText('Not scored') }}</strong>
          <span v-if="overall !== null" class="score-max">/ 100</span>
        </p>
      </div>
      <dl class="review-facts">
        <div>
          <dt>{{ uiText('Questions reviewed') }}</dt>
          <dd>{{ data.turns.length }}</dd>
        </div>
        <div>
          <dt>{{ uiText('Questions scored') }}</dt>
          <dd>
            {{ scoredTurns }}<span> / {{ data.turns.length }}</span>
          </dd>
        </div>
        <div>
          <dt>{{ uiText('Next steps') }}</dt>
          <dd>{{ nextSteps.length }}</dd>
        </div>
      </dl>
      <RouterLink v-if="data.id && !inline" :to="'/interviews/' + data.id" class="btn review-link">
        {{ uiText('Open interview review') }}<AppIcon name="arrow" :size="16" />
      </RouterLink>
    </header>

    <section class="review-section dimension-overview" :aria-label="uiText('Score overview')">
      <div class="review-section-heading">
        <div>
          <span class="section-kicker">01 · {{ uiText('Score overview') }}</span>
          <h4>{{ uiText('Four dimensions at a glance') }}</h4>
        </div>
        <p>{{ uiText('Use the details below to understand each score.') }}</p>
      </div>
      <div class="dimension-layout">
        <InterviewScoreRadar :dimensions="data.dimensions" />
        <div class="dimension-list">
          <article v-for="dimension in dimensions" :key="dimension.key">
            <div class="dimension-score-heading">
              <span>{{ dimension.label }}</span>
              <strong>{{ dimension.score ?? uiText('Not scored') }}<small> / 100</small></strong>
            </div>
            <progress
              v-if="dimension.score !== null"
              :value="dimension.score"
              max="100"
              :aria-label="dimension.label"
            />
            <p
              v-if="
                data.turns.length &&
                data.turns.every((turn) => !turn.source || turn.source === 'rule')
              "
            >
              {{ dimension.reason }}
            </p>
          </article>
        </div>
      </div>
    </section>

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

    <section
      class="review-section question-review"
      :aria-label="uiText('Question-by-question review')"
    >
      <div class="review-section-heading">
        <div>
          <span class="section-kicker">02 · {{ uiText('Question-by-question review') }}</span>
          <h4>{{ uiText('Review the answer behind every score') }}</h4>
        </div>
        <p>{{ uiText('Expand a question for evidence and an improved answer.') }}</p>
      </div>
      <p v-if="!data.turns.length" class="review-empty">
        {{ uiText('No answers yet. Complete a text interview and request scoring again.') }}
      </p>
      <nav
        v-if="data.turns.length > 1"
        class="question-jump"
        :aria-label="uiText('Question navigation')"
      >
        <button
          v-for="(turn, index) in data.turns"
          :key="index"
          type="button"
          :aria-current="openTurn === index ? 'true' : undefined"
          @click="goToTurn(index)"
        >
          <span>{{ String(index + 1).padStart(2, '0') }}</span>
          <small>{{ turn.score ?? uiText('Not scored') }}</small>
        </button>
      </nav>
      <div class="turn-list">
        <details
          v-for="(turn, index) in data.turns"
          :id="`interview-turn-${index + 1}`"
          :key="index"
          :ref="(element) => setTurnRef(element, index)"
          class="turn-review"
          :open="openTurn === index"
          tabindex="-1"
          @toggle="handleToggle(index, $event)"
        >
          <summary>
            <span class="turn-number">{{ String(index + 1).padStart(2, '0') }}</span>
            <span class="turn-summary-copy">
              <strong>{{ turn.question }}</strong>
              <small>{{
                !turn.source || turn.source === 'rule'
                  ? uiText('Rule-based feedback')
                  : turn.source === 'unavailable'
                    ? uiText('Scoring unavailable')
                    : uiText('AI-assisted feedback')
              }}</small>
            </span>
            <span class="turn-score" :class="{ 'is-unscored': turn.score === null }">
              {{ turn.score ?? uiText('Not scored')
              }}<small v-if="turn.score !== null"> / 100</small>
            </span>
          </summary>
          <div class="turn-content">
            <div class="answer-recap">
              <span>{{ uiText('Your answer') }}</span>
              <p>{{ turn.answerSummary || uiText('Not answered yet') }}</p>
            </div>
            <div v-if="!turn.source || turn.source === 'rule'" class="star-evidence">
              <span
                v-for="part in starParts"
                :key="part.key"
                :class="{ observed: turn.star[part.key] }"
                :aria-label="`${part.label}：${turn.star[part.key] ? uiText('Observed') : uiText('Not observed')}`"
              >
                {{ turn.star[part.key] ? '✓' : '○' }} {{ part.label }}
              </span>
            </div>
            <p class="turn-rationale">
              <strong>{{ uiText('Scoring rationale') }}</strong
              >{{ turn.feedback }}
            </p>
            <InterviewFeedback v-if="turn.evaluation" :evaluation="turn.evaluation" />
            <details v-else-if="turn.improvedAnswer" class="answer-details" open>
              <summary>{{ uiText('Improved answer (original facts)') }}</summary>
              <p class="prose-content">{{ turn.improvedAnswer }}</p>
            </details>
            <details v-if="turn.betterAnswerOutline.length" class="answer-details">
              <summary>{{ uiText('Improved answer outline') }}</summary>
              <ol>
                <li v-for="line in turn.betterAnswerOutline" :key="line">{{ line }}</li>
              </ol>
            </details>
          </div>
        </details>
      </div>
    </section>

    <section
      v-if="hasInsights"
      class="review-section review-insights"
      :aria-label="uiText('Actionable feedback')"
    >
      <div class="review-section-heading">
        <div>
          <span class="section-kicker">03 · {{ uiText('Actionable feedback') }}</span>
          <h4>{{ uiText('Keep the strengths. Practice the gaps.') }}</h4>
        </div>
      </div>
      <div class="insight-grid">
        <section v-for="section in sections" :key="section.key" :class="`insight-${section.key}`">
          <div class="insight-heading">
            <AppIcon :name="section.icon" :size="19" />
            <h4>{{ section.label }}</h4>
          </div>
          <p class="insight-description">{{ section.description }}</p>
          <ul>
            <li v-for="item in section.items" :key="item">{{ item }}</li>
          </ul>
        </section>
      </div>
    </section>

    <section v-if="nextSteps.length" class="recommended-learning">
      <div>
        <AppIcon name="career" :size="22" />
        <h4>{{ uiText('Suggested practice') }}</h4>
        <p>{{ uiText('Choose one focused action for your next practice session.') }}</p>
      </div>
      <ul>
        <li v-for="item in nextSteps" :key="item.topic">
          <strong>{{ item.topic }}</strong>
          <span>{{ uiText('About {0} hours', [item.estimatedHours]) }}</span>
          <p>{{ item.reason }}</p>
        </li>
      </ul>
    </section>
  </article>
  <p v-else class="surface rounded-[14px] p-4 text-sm text-muted-foreground">
    {{ uiText('Interview score data is unavailable. Please request scoring again.') }}
  </p>
</template>

<style scoped>
.interview-review {
  min-width: 0;
  container-type: inline-size;
  overflow-wrap: anywhere;
}
.review-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 20px 28px;
  padding: 26px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
}
.review-heading {
  min-width: 0;
  align-self: center;
}
.review-eyebrow {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--success);
  font-size: 12px;
  font-weight: 650;
}
.review-heading h3 {
  max-width: 600px;
  margin: 10px 0 5px;
  font-family: var(--app-font-display);
  font-size: clamp(22px, 3vw, 30px);
  line-height: 1.2;
  letter-spacing: -0.025em;
  text-wrap: balance;
}
.review-tagline {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 550;
}
.review-note {
  max-width: 650px;
  margin: 0;
  color: var(--muted-foreground);
  line-height: 1.65;
}
.score-dial {
  position: relative;
  width: 132px;
  height: 132px;
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
  margin: 0;
  line-height: 1.15;
  text-align: center;
}
.score-label,
.score-max {
  color: var(--muted-foreground);
  font-size: 11px;
}
.score-number {
  font-family: var(--app-font-display);
  font-size: 42px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.04em;
}
.unscored-value {
  font-size: 14px;
}
.review-facts {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}
.review-facts > div {
  padding: 0 20px;
  border-right: 1px solid var(--border);
}
.review-facts > div:first-child {
  padding-left: 0;
}
.review-facts > div:last-child {
  border: 0;
}
.review-facts dt {
  color: var(--muted-foreground);
  font-size: 12px;
}
.review-facts dd {
  margin: 6px 0 0;
  font-family: var(--app-font-display);
  font-size: 24px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.review-facts dd span {
  color: var(--muted-foreground);
  font-size: 13px;
  font-weight: 400;
}
.review-link {
  grid-column: 1 / -1;
  justify-self: start;
}
.review-section {
  margin-top: 18px;
  padding: 24px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
}
.review-section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px 20px;
  margin-bottom: 20px;
}
.review-section-heading h4 {
  margin: 5px 0 0;
  font-size: 18px;
}
.review-section-heading > p {
  max-width: 360px;
  margin: 0;
  color: var(--muted-foreground);
  font-size: 12px;
}
.section-kicker {
  color: var(--primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
}
.dimension-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(220px, 0.85fr);
  align-items: center;
  gap: 24px;
}
.dimension-list {
  display: grid;
  gap: 17px;
}
.dimension-score-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
}
.dimension-score-heading strong {
  font-family: var(--app-font-display);
  font-variant-numeric: tabular-nums;
}
.dimension-score-heading small {
  color: var(--muted-foreground);
  font-weight: 400;
}
.dimension-list progress {
  display: block;
  width: 100%;
  height: 6px;
  margin: 8px 0 0;
  appearance: none;
  border: 0;
  border-radius: 5px;
  overflow: hidden;
  background: var(--muted);
  accent-color: var(--primary);
}
.dimension-list progress::-webkit-progress-bar {
  background: var(--muted);
}
.dimension-list progress::-webkit-progress-value {
  background: var(--primary);
  border-radius: 5px;
  transform-origin: left;
  animation: bar-grow 800ms var(--ease-out) 250ms backwards;
}
.dimension-list progress::-moz-progress-bar {
  background: var(--primary);
}
@keyframes bar-grow {
  from {
    transform: scaleX(0);
  }
}
.dimension-list article > p {
  margin: 6px 0 0;
  color: var(--muted-foreground);
  font-size: 11px;
}
.review-empty {
  margin: 0;
  padding: 24px;
  border-radius: 8px;
  background: var(--muted);
  color: var(--muted-foreground);
  text-align: center;
}
.question-jump {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
  padding-bottom: 4px;
  overflow-x: auto;
}
.question-jump button {
  display: flex;
  align-items: baseline;
  flex: 0 0 auto;
  gap: 6px;
  min-width: 58px;
  min-height: 44px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--background);
  color: var(--muted-foreground);
  cursor: pointer;
}
.question-jump button[aria-current='true'] {
  border-color: var(--primary);
  background: var(--primary-tint);
  color: var(--primary);
}
.question-jump button span {
  font-family: var(--app-font-display);
  font-size: 16px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.question-jump button small {
  font-size: 10px;
}
.turn-list {
  display: grid;
  gap: 10px;
}
.turn-review {
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--background);
  scroll-margin-top: 20px;
}
.turn-review[open] {
  border-color: color-mix(in srgb, var(--primary) 28%, var(--border));
}
.turn-review > summary {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  min-height: 64px;
  padding: 13px 16px;
  cursor: pointer;
}
.turn-number {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--muted);
  color: var(--muted-foreground);
  font-family: var(--app-font-display);
  font-size: 13px;
  font-weight: 650;
}
.turn-review[open] .turn-number {
  background: var(--primary-tint);
  color: var(--primary);
}
.turn-summary-copy {
  min-width: 0;
}
.turn-summary-copy strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}
.turn-summary-copy small {
  display: block;
  margin-top: 3px;
  color: var(--muted-foreground);
  font-size: 11px;
  font-weight: 400;
}
.turn-score {
  color: var(--primary);
  font-family: var(--app-font-display);
  font-size: 19px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.turn-score small {
  color: var(--muted-foreground);
  font-size: 10px;
  font-weight: 400;
}
.turn-score.is-unscored {
  color: var(--muted-foreground);
  font-family: inherit;
  font-size: 12px;
}
.turn-content {
  padding: 0 16px 16px;
  border-top: 1px solid var(--border);
}
.answer-recap {
  margin: 14px 0;
  padding: 13px 14px;
  border-radius: 8px;
  background: var(--muted);
}
.answer-recap > span {
  color: var(--muted-foreground);
  font-size: 11px;
  font-weight: 600;
}
.answer-recap p {
  margin: 5px 0 0;
  line-height: 1.7;
  white-space: pre-wrap;
}
.star-evidence {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-bottom: 14px;
}
.star-evidence > span {
  padding: 5px 8px;
  border-radius: 5px;
  background: var(--muted);
  color: var(--muted-foreground);
  font-size: 11px;
}
.star-evidence > span.observed {
  background: color-mix(in srgb, var(--success) 9%, var(--card));
  color: var(--success);
}
.turn-rationale {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.7;
}
.turn-rationale strong {
  display: block;
  margin-bottom: 3px;
}
.answer-details {
  margin-top: 10px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--muted);
}
.turn-review::details-content,
.answer-details::details-content {
  interpolate-size: allow-keywords;
  overflow: clip;
  contain: layout;
}
.turn-review:not([open])::details-content,
.answer-details:not([open])::details-content {
  block-size: 0;
}
@media (prefers-reduced-motion: no-preference) {
  .turn-review::details-content,
  .answer-details::details-content {
    transition: block-size, content-visibility, display;
    transition-duration: var(--dur-mid);
    transition-timing-function: var(--ease-out);
    transition-behavior: allow-discrete;
  }
}
.answer-details summary {
  color: var(--primary);
  cursor: pointer;
}
.answer-details p,
.answer-details ol {
  line-height: 1.75;
}
.insight-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.insight-grid > section {
  min-width: 0;
  padding: 17px;
  border-radius: 9px;
  background: var(--background);
  border-top: 3px solid var(--border);
}
.insight-grid > .insight-strengths {
  border-color: var(--success);
}
.insight-grid > .insight-weaknesses {
  border-color: var(--warning);
}
.insight-grid > .insight-advice {
  border-color: var(--primary);
}
.insight-heading {
  display: flex;
  align-items: center;
  gap: 8px;
}
.insight-heading h4 {
  margin: 0;
  font-size: 14px;
}
.insight-strengths .insight-heading {
  color: var(--success);
}
.insight-weaknesses .insight-heading {
  color: var(--warning);
}
.insight-advice .insight-heading {
  color: var(--primary);
}
.insight-description {
  min-height: 36px;
  margin: 9px 0;
  color: var(--muted-foreground);
  font-size: 11px;
  line-height: 1.6;
}
.insight-grid ul {
  margin: 0;
  padding-left: 17px;
  font-size: 13px;
  line-height: 1.7;
}
.insight-grid li + li {
  margin-top: 7px;
}
.recommended-learning {
  display: grid;
  grid-template-columns: minmax(190px, 0.7fr) minmax(0, 1.3fr);
  gap: 20px;
  margin-top: 18px;
  padding: 24px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--primary);
  border-radius: var(--radius);
  background: var(--card);
  color: var(--foreground);
}
.recommended-learning > div > svg {
  color: var(--primary);
}
.recommended-learning h4 {
  margin: 10px 0 5px;
  font-size: 18px;
  text-wrap: balance;
}
.recommended-learning > div > p {
  margin: 0;
  color: var(--muted-foreground);
  font-size: 13px;
}
.recommended-learning ul {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.recommended-learning li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 4px 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--border);
}
.recommended-learning li:last-child {
  padding-bottom: 0;
  border: 0;
}
.recommended-learning li span {
  color: var(--muted-foreground);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.recommended-learning li p {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--muted-foreground);
  font-size: 13px;
  text-wrap: pretty;
}
@container (max-width: 720px) {
  .dimension-layout,
  .recommended-learning {
    grid-template-columns: 1fr;
  }
  .insight-grid {
    grid-template-columns: 1fr;
  }
  .insight-description {
    min-height: 0;
  }
}
@container (max-width: 520px) {
  .review-hero,
  .review-section,
  .recommended-learning {
    padding: 18px;
  }
  .review-hero {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 18px 12px;
  }
  .score-dial {
    width: 100px;
    height: 100px;
  }
  .score-number {
    font-size: 34px;
  }
  .review-facts > div {
    padding: 0 10px;
  }
  .review-facts dd {
    font-size: 20px;
  }
  .turn-review > summary {
    grid-template-columns: auto minmax(0, 1fr);
    gap: 10px;
    padding: 12px;
  }
  .turn-score {
    grid-column: 2;
    font-size: 16px;
  }
  .turn-content {
    padding: 0 12px 12px;
  }
}
@supports not (container-type: inline-size) {
  @media (max-width: 720px) {
    .dimension-layout,
    .recommended-learning,
    .insight-grid {
      grid-template-columns: 1fr;
    }
    .insight-description {
      min-height: 0;
    }
  }
  @media (max-width: 520px) {
    .review-hero,
    .review-section,
    .recommended-learning {
      padding: 18px;
    }
    .review-hero {
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 18px 12px;
    }
    .score-dial {
      width: 100px;
      height: 100px;
    }
    .score-number {
      font-size: 34px;
    }
    .review-facts > div {
      padding: 0 10px;
    }
    .review-facts dd {
      font-size: 20px;
    }
    .turn-review > summary {
      grid-template-columns: auto minmax(0, 1fr);
      gap: 10px;
      padding: 12px;
    }
    .turn-score {
      grid-column: 2;
      font-size: 16px;
    }
    .turn-content {
      padding: 0 12px 12px;
    }
  }
}
@media (prefers-reduced-motion: reduce) {
  .ring-value,
  .dimension-list progress::-webkit-progress-value {
    animation: none;
  }
}
</style>
