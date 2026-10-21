<script setup lang="ts">
import { formatDisplay, translateLabel, sourceText, translate as uiText } from '@/shared/utils/language'

import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { Stage } from '@/shared/types/workspace'

const props = defineProps<{ stops: { stage: Stage; count: number }[]; loading?: boolean }>()

const route = computed(() => props.stops.filter((s) => s.stage !== sourceText("Closed")))
const ended = computed(() => props.stops.find((s) => s.stage === sourceText("Closed"))?.count ?? 0)
const furthest = computed(() =>
  route.value.reduce((last, stop, index) => (stop.count > 0 ? index : last), -1),
)
const progress = computed(() =>
  furthest.value < 0 ? 0 : furthest.value / Math.max(route.value.length - 1, 1),
)
</script>
<template>
  <div
    class="route"
    :class="{ 'is-loading': loading }"
    :aria-busy="loading"
    :style="{ '--progress': progress, '--stops': route.length }"
  >
    <div class="route-track" aria-hidden="true"><span class="route-fill" /></div>
    <ol :aria-hidden="loading || undefined">
      <li
        v-for="(stop, index) in route"
        :key="stop.stage"
        :class="{ reached: index <= furthest, current: index === furthest }"
        :aria-current="index === furthest ? 'step' : undefined"
      >
        <span class="route-node" aria-hidden="true" />
        <strong class="route-count">{{ formatDisplay(loading ? '—' : stop.count) }}</strong>
        <span class="route-label">{{ translateLabel(stop.stage) }}</span>
      </li>
    </ol>
    <p v-if="loading" class="subtle" role="status">{{ uiText("Loading application board…") }}</p>
    <p v-else-if="furthest < 0" class="subtle"> {{ uiText("Save your first job to start your journey.") }}<RouterLink to="/jobs" class="route-link"
        >{{ uiText("Search jobs") }}</RouterLink
      >
    </p>
    <p v-else-if="ended" class="subtle">{{ uiText("{0} other opportunities are closed.", [ended]) }}</p>
  </div>
</template>
<style scoped>
.route {
  position: relative;
  min-height: 149px;
  --node: 16px;
}
.is-loading .route-count {
  color: transparent;
  background: var(--muted);
  border-radius: var(--radius-tag);
}
.route-track {
  position: absolute;
  top: calc(var(--node) / 2 - 1px);
  left: calc(var(--node) / 2);
  width: calc(100% * (var(--stops) - 1) / var(--stops));
  height: 2px;
  border-radius: 1px;
  background: var(--border);
}
.route-fill {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: var(--primary);
  transform-origin: left center;
  transform: scaleX(var(--progress));
  animation: route-draw 700ms var(--ease-out) 120ms both;
}
@keyframes route-draw {
  from {
    transform: scaleX(0);
  }
}
ol {
  position: relative;
  display: grid;
  grid-template-columns: repeat(var(--stops), minmax(0, 1fr));
  margin: 0;
  padding: 0;
  list-style: none;
}
li {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}
.route-node {
  width: var(--node);
  height: var(--node);
  margin-bottom: 16px;
  border: 2px solid var(--input);
  border-radius: 50%;
  background: var(--card);
}
li.reached .route-node {
  border-color: var(--foreground);
  background: var(--foreground);
}
li.current .route-node {
  border-color: var(--primary);
  background: var(--card);
  box-shadow: inset 0 0 0 2px var(--card);
  background-image: radial-gradient(circle, var(--primary) 0 45%, transparent 50%);
  animation: node-arrive 320ms var(--ease-out) 700ms both;
}
@keyframes node-arrive {
  from {
    transform: scale(0.4);
  }
}
.route-count {
  font-family: var(--app-font-display);
  font-stretch: 88%;
  font-size: 48px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1;
  letter-spacing: -0.02em;
  color: var(--muted-foreground);
}
li.reached .route-count {
  color: var(--foreground);
}
.route-label {
  font-size: 13px;
  color: var(--muted-foreground);
}
li.current .route-label {
  color: var(--primary);
  font-weight: 600;
}
.route > .subtle {
  margin-top: 18px;
}
.route-link {
  margin-left: 6px;
  color: var(--primary);
  text-decoration: underline;
  text-underline-offset: 3px;
}
@media (max-width: 640px) {
  .route {
    min-height: 133px;
  }
  .route-count {
    font-size: 32px;
  }
}
</style>
