<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { formatDisplay, translate as uiText } from '@/shared/utils/language'
import type { InterviewTurnScorePayload } from '@/shared/types/cards'

const props = defineProps<{ payload: InterviewTurnScorePayload }>()
const valid = computed(
  () =>
    props.payload &&
    Number.isInteger(props.payload.turnIndex) &&
    typeof props.payload.brief === 'string' &&
    (props.payload.score === null ||
      (Number.isFinite(props.payload.score) &&
        props.payload.score >= 0 &&
        props.payload.score <= 100)),
)
</script>
<template>
  <section class="turn-score-card" :aria-label="uiText('Single-question interview score')">
    <header class="turn-score-header">
      <div class="turn-score-meta">
        <span class="turn-score-eyebrow">
          <AppIcon name="spark" :size="15" />{{ uiText('Single-question interview score') }}
        </span>
        <h3 v-if="valid" class="turn-score-title">
          {{
            uiText('Question {0} · {1} / 100', [
              payload.turnIndex + 1,
              payload.score ?? uiText('Not scored'),
            ])
          }}
        </h3>
      </div>
      <div v-if="valid" class="turn-score-badge" :class="{ 'is-unscored': payload.score === null }">
        <strong>{{
          payload.score === null ? uiText('Not scored') : formatDisplay(payload.score)
        }}</strong>
        <span v-if="payload.score !== null">/ 100</span>
      </div>
    </header>
    <p v-if="!valid" class="turn-score-error" role="alert">
      {{ uiText('Score data is unavailable. Please request scoring again.') }}
    </p>
    <div v-else class="turn-score-body">
      <progress
        v-if="payload.score !== null"
        :value="payload.score"
        max="100"
        :aria-label="uiText('Single-question interview score')"
      />
      <p class="turn-score-brief">
        {{ payload.brief || uiText('No scoring explanation yet') }}
      </p>
    </div>
  </section>
</template>
<style scoped>
.turn-score-card {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
}
.turn-score-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  background: color-mix(in srgb, var(--primary) 5%, var(--card));
  border-bottom: 1px solid var(--border);
}
.turn-score-meta {
  min-width: 0;
}
.turn-score-eyebrow {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--primary);
  font-size: 12px;
  font-weight: 650;
}
.turn-score-title {
  margin: 4px 0 0;
  font-size: 15px;
  font-weight: 600;
  text-wrap: balance;
}
.turn-score-badge {
  display: flex;
  align-items: baseline;
  flex-shrink: 0;
  gap: 3px;
  color: var(--primary);
}
.turn-score-badge strong {
  font-family: var(--app-font-display);
  font-size: 28px;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.turn-score-badge span {
  color: var(--muted-foreground);
  font-size: 12px;
}
.turn-score-badge.is-unscored strong {
  font-family: inherit;
  font-size: 13px;
  color: var(--muted-foreground);
}
.turn-score-error {
  margin: 0;
  padding: 16px 20px;
  color: var(--danger);
  font-size: 14px;
  overflow-wrap: anywhere;
}
.turn-score-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 20px 18px;
  overflow-wrap: anywhere;
}
.turn-score-body progress {
  display: block;
  width: 100%;
  height: 5px;
  appearance: none;
  border: 0;
  border-radius: 5px;
  overflow: hidden;
  background: var(--muted);
  accent-color: var(--primary);
}
.turn-score-body progress::-webkit-progress-bar {
  background: var(--muted);
}
.turn-score-body progress::-webkit-progress-value {
  background: var(--primary);
  border-radius: 5px;
}
.turn-score-body progress::-moz-progress-bar {
  background: var(--primary);
  border-radius: 5px;
}
.turn-score-brief {
  margin: 0;
  color: var(--foreground);
  font-size: 14px;
  line-height: 1.7;
  text-wrap: pretty;
}
</style>
