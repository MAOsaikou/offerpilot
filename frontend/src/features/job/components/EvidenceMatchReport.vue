<script setup lang="ts">
import { formatDisplay, formatNumber, language, translate as uiText } from '@/shared/utils/language'

import { computed, ref, nextTick } from 'vue'
import { RouterLink } from 'vue-router'
import { useBoard } from '@/shared/composables/useBoard'
import type { EvidenceMatch, MatchRequirement } from '@/shared/types/jobs'
const props = defineProps<{ payload: unknown }>()
const board = useBoard()
const data = computed(() => props.payload as EvidenceMatch)
const selected = ref<{
  title: string
  text: string
  quote: string
  status: MatchRequirement['status']
}>()
const evidencePanel = ref<HTMLElement>()
const labels = computed(() => ({
  MET: uiText('Explicit evidence'),
  PARTIAL: uiText('Partially demonstrated'),
  MISSING: uiText('Not shown in resume'),
}))
const statuses = computed(
  () =>
    [
      { status: 'MET', label: uiText('Explicit') },
      { status: 'PARTIAL', label: uiText('Partial') },
      { status: 'MISSING', label: uiText('Not shown') },
    ] as const,
)
const coverage = computed(() => {
  const counts = { MET: 0, PARTIAL: 0, MISSING: 0 }
  for (const item of data.value.requirements) counts[item.status]++
  return statuses.value.map((item) => ({ ...item, count: counts[item.status] }))
})
function evidence(item: MatchRequirement, side: 'jd' | 'resume') {
  if (side === 'jd')
    selected.value = {
      title: uiText('Original job text used for analysis'),
      text: uiText('Job: {0} Responsibilities: {1} Requirements: {2}', [
        data.value.jobSnapshot.title,
        data.value.jobSnapshot.description,
        data.value.jobSnapshot.requirement,
      ]),
      quote: item.jdQuote,
      status: item.status,
    }
  else {
    let value: unknown = data.value.resumeSnapshot
    for (const part of item.resumePath.slice(1).split('/')) {
      if (value && typeof value === 'object')
        value = (value as Record<string, unknown>)[part.replace(/~1/g, '/').replace(/~0/g, '~')]
      else value = undefined
    }
    selected.value = {
      title: uiText('Original resume text used for analysis'),
      text: typeof value === 'string' ? value : item.resumeQuote,
      quote: item.resumeQuote,
      status: item.status,
    }
  }
  void nextTick(() => evidencePanel.value?.focus())
}
const excerpt = computed(() => {
  const source = selected.value
  if (!source) return undefined
  const index = source.text.indexOf(source.quote)
  return index < 0
    ? { before: source.text, quote: '', after: '' }
    : {
        before: source.text.slice(0, index),
        quote: source.quote,
        after: source.text.slice(index + source.quote.length),
      }
})
</script>
<template>
  <article class="panel form-stack evidence-report">
    <RouterLink
      class="btn"
      :to="{
        path: '/resumes/' + data.resumeId,
        query: { tab: 'optimize', jobId: data.jobId, resumeVersionId: data.resumeVersionId },
      }"
      >{{ uiText('Rewrite for this job') }}</RouterLink
    >
    <div class="card-footer">
      <button
        class="btn"
        :disabled="
          board.saved(data.jobId) ||
          board.busy.value ||
          board.query.isPending.value ||
          board.query.isError.value
        "
        @click="board.favorite(data.jobId)"
      >
        {{ board.saved(data.jobId) ? uiText('On your board') : uiText('Add to board') }}
      </button>
      <p v-if="board.error.value" role="alert">{{ formatDisplay(board.error.value) }}</p>
      <p v-if="board.query.isError.value" role="alert">
        {{ uiText('Could not load board.')
        }}<button class="btn" @click="board.query.refetch()">{{ uiText('Retry board') }}</button>
      </p>
    </div>
    <div class="section-heading">
      <div>
        <p class="subtle">{{ uiText('Resume evidence coverage of job requirements') }}</p>
        <h2>{{ formatDisplay(data.score) }} / 100</h2>
      </div>
      <span class="tag">{{
        uiText('{0} · Version {1}', [data.resumeTitle, data.resumeVersionNo])
      }}</span>
    </div>
    <p>{{ data.summary }}</p>
    <p class="helper-note">
      {{
        uiText(
          "This score uses extracted requirements: explicit evidence counts as 1, partial as 0.5 and missing as 0. It is not a hiring probability or proof of ability. Review the full requirements on the company's website.",
        )
      }}
    </p>
    <section class="coverage-summary" :aria-label="uiText('Job requirement evidence coverage')">
      <div class="coverage-counts">
        <span v-for="item in coverage" :key="item.status" :data-status="item.status">
          {{ item.label }} <strong>{{ formatDisplay(item.count) }}</strong> {{ uiText('items') }}
        </span>
      </div>
      <div v-if="data.requirements.length" class="coverage-bar" aria-hidden="true">
        <span
          v-for="item in coverage.filter((item) => item.count > 0)"
          :key="item.status"
          :data-status="item.status"
          :style="{ flexGrow: item.count }"
        ></span>
      </div>
      <p v-else class="subtle">
        {{ uiText('No requirement evidence to display in this report.') }}
      </p>
    </section>
    <article
      v-for="(item, index) in data.requirements"
      :key="index"
      class="requirement-row"
      :data-status="item.status"
    >
      <div class="section-heading">
        <h3>{{ formatDisplay(index + 1) }}. {{ item.requirement }}</h3>
        <span class="tag status-chip">{{ labels[item.status] }}</span>
      </div>
      <p>{{ item.reason }}</p>
      <button class="evidence-link" @click="evidence(item, 'jd')">
        {{ uiText('Job evidence:') }}<span class="evidence-quote">{{ item.jdQuote }}</span>
      </button>
      <button v-if="item.resumeQuote" class="evidence-link" @click="evidence(item, 'resume')">
        {{ uiText('Resume evidence:') }}<span class="evidence-quote">{{ item.resumeQuote }}</span>
      </button>
      <p v-else class="subtle">
        {{ uiText('No citable evidence was found in the selected resume.') }}
      </p>
      <p>
        <strong>{{ uiText('Preparation tips:') }}</strong
        >{{ item.suggestion }}
      </p>
    </article>
    <section
      v-if="data.unverifiedRequirements?.length"
      :aria-label="uiText('Unverified requirements')"
      class="helper-note"
    >
      <h4>
        {{
          uiText('{0} unverified items · Not scored', [
            data.unverifiedCount ?? data.unverifiedRequirements.length,
          ])
        }}
      </h4>
      <p>
        {{
          uiText(
            'Original evidence for these items failed validation. Coverage cannot be assessed. Check the full job and resume.',
          )
        }}
      </p>
      <ul>
        <li v-for="(item, index) in data.unverifiedRequirements" :key="index">
          {{ uiText('{0} · Unverified', [item.requirement]) }}
        </li>
      </ul>
    </section>
    <section
      v-if="selected && excerpt"
      ref="evidencePanel"
      tabindex="-1"
      class="helper-note"
      :data-status="selected.status"
      aria-live="polite"
    >
      <div class="section-heading">
        <h3>{{ selected.title }}</h3>
        <button class="btn" @click="selected = undefined">
          {{ uiText('Hide original text') }}
        </button>
      </div>
      <p class="prose-content">
        {{ excerpt.before
        }}<mark v-if="excerpt.quote" class="evidence-mark" :data-status="selected.status">{{
          excerpt.quote
        }}</mark
        >{{ excerpt.after }}
      </p>
    </section>
    <p class="subtle">
      {{
        uiText('Generated {0} · Model {1}', [
          new Date(data.createdAt).toLocaleString(language),
          data.model,
        ])
      }}<template v-if="data.durationMs != null">
        {{
          uiText('· Generation took {0} seconds', [
            formatNumber(data.durationMs / 1000, {
              minimumFractionDigits: 2,
              maximumFractionDigits: 2,
            }),
          ])
        }}</template
      >{{
        uiText(
          '. This report keeps the materials as they were. Later resume edits do not change it.',
        )
      }}
    </p>
  </article>
</template>
<style scoped>
.evidence-report {
  min-width: 0;
  overflow-wrap: anywhere;
}
[data-status='MET'] {
  --evidence-color: var(--success);
}
[data-status='PARTIAL'] {
  --evidence-color: var(--warning);
}
[data-status='MISSING'] {
  --evidence-color: var(--danger);
}
.evidence-report .section-heading {
  flex-wrap: wrap;
}
.coverage-summary {
  display: grid;
  gap: 8px;
}
.coverage-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  font-size: 13px;
}
.coverage-counts > span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.coverage-counts > span::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--evidence-color);
}
.coverage-counts strong {
  font-variant-numeric: tabular-nums;
}
.coverage-bar {
  display: flex;
  gap: 2px;
  height: 8px;
  overflow: hidden;
  border-radius: var(--radius-tag);
}
.coverage-bar > span {
  flex-basis: 0;
  background: var(--evidence-color);
}
.requirement-row {
  display: grid;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--border);
  border-left: 4px solid var(--evidence-color);
  border-radius: var(--radius-control);
  background: color-mix(in srgb, var(--evidence-color) 5%, var(--card));
}
.requirement-row .section-heading {
  margin-bottom: 0;
}
.requirement-row h3 {
  flex: 1 1 16rem;
  min-width: 0;
}
.status-chip {
  color: var(--evidence-color);
  background: var(--card);
  border: 1px solid color-mix(in srgb, var(--evidence-color) 35%, var(--border));
  font-weight: 600;
}
.evidence-link {
  min-height: 44px;
  text-align: left;
  color: var(--foreground);
  background: transparent;
  border: 0;
  padding: 0;
  cursor: pointer;
  text-decoration: underline;
  overflow-wrap: anywhere;
}
.evidence-quote,
.evidence-mark {
  color: var(--foreground);
  background: color-mix(in srgb, var(--evidence-color) 16%, var(--card));
  border-radius: var(--radius-tag);
  padding: 2px 4px;
  box-decoration-break: clone;
  -webkit-box-decoration-break: clone;
}
.evidence-link:hover .evidence-quote {
  background: color-mix(in srgb, var(--evidence-color) 24%, var(--card));
}
</style>
