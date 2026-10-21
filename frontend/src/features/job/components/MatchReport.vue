<script setup lang="ts">
import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import type { EvidenceMatch } from '@/shared/types/jobs'
import { asPayload } from '@/shared/utils/payload'
import type { JobMatchPayload } from '@/shared/types/cards'

const props = defineProps<{ payload: unknown }>()

const data = computed(() => asPayload<JobMatchPayload>(props.payload, 'score'))

const evidence = computed(() => {
  const report = props.payload as EvidenceMatch | null
  return Array.isArray(report?.requirements) ? report : null
})
const labels = computed(() => ({ MET: uiText("Explicit evidence"), PARTIAL: uiText("Partially demonstrated"), MISSING: uiText("Not shown in resume") }))

const dimensions = computed(() => {
  const dims = data.value?.dimensions
  if (!dims) return []
  return [
    { label: uiText("Skills"), value: dims.skills },
    { label: uiText("Experience"), value: dims.experience },
    { label: uiText("Education"), value: dims.education },
    { label: uiText("Keywords"), value: dims.keywords },
  ]
})

const strengthLabel = computed(() => ({ STRONG: uiText("Strong match"), MEDIUM: uiText("Partial match"), WEAK: uiText("Weak match") }) as const)
const severityLabel = computed(() => ({ LOW: uiText("Minor"), MEDIUM: uiText("Moderate"), HIGH: uiText("Severe") }) as const)
</script>

<template>
  <article v-if="evidence" class="panel form-stack min-w-0 break-words" :aria-label="uiText('Job match report')">
    <h3>{{ uiText("Resume evidence coverage of job requirements · {0} / 100", [evidence.score]) }}</h3>
    <p>{{ uiText("{0} · Version {1}", [evidence.resumeTitle, evidence.resumeVersionNo]) }}</p>
    <p>{{ evidence.summary }}</p>
    <p class="helper-note"> {{ uiText("Explicit evidence counts as 1, partial as 0.5 and missing as 0. Missing evidence does not mean missing ability. This score does not predict hiring.") }} </p>
    <ul class="space-y-3">
      <li v-for="(item, index) in evidence.requirements" :key="index" class="rounded-lg border p-3">
        <h4>{{ formatDisplay(index + 1) }}. {{ item.requirement }}</h4>
        <span class="tag">{{ labels[item.status] }}</span>
        <p>{{ item.reason }}</p>
        <p class="subtle">{{ uiText("Job evidence: {0}", [item.jdQuote]) }}</p>
        <p class="subtle">
          {{
            item.resumeQuote ? uiText("Resume evidence:") + item.resumeQuote : uiText("No citable evidence was found in the selected resume.")
          }}
        </p>
        <p>{{ uiText("Preparation tips: {0}", [item.suggestion]) }}</p>
      </li>
    </ul>
    <section
      v-if="evidence.unverifiedRequirements?.length"
      :aria-label="uiText('Unverified requirements')"
      class="helper-note"
    >
      <h4> {{ uiText("{0} unverified items · Not scored", [evidence.unverifiedCount ?? evidence.unverifiedRequirements.length]) }} </h4>
      <p>{{ uiText("Original evidence for these items failed validation. Coverage cannot be assessed. Check the full job and resume.") }}</p>
      <ul>
        <li v-for="(item, index) in evidence.unverifiedRequirements" :key="index">
          {{ uiText("{0} · Unverified", [item.requirement]) }} </li>
      </ul>
    </section>
    <RouterLink
      :to="{ path: '/jobs/' + evidence.jobId, query: { matchId: evidence.id } }"
      class="btn"
      >{{ uiText("View full match report") }}</RouterLink
    >
  </article>
  <article v-else-if="data" class="surface overflow-hidden rounded-[14px]">
    <header class="flex items-baseline gap-3 border-b border-border/60 px-4 py-3">
      <div>
        <p class="text-xs text-muted-foreground">{{ uiText("Resume and job match") }}</p>
        <p class="mt-0.5 num text-4xl leading-none">
          {{ formatDisplay(data.score) }}<span class="ml-0.5 text-sm font-normal text-muted-foreground">/100</span>
        </p>
      </div>
    </header>

    <div class="space-y-2 px-4 py-3">
      <div v-for="dim in dimensions" :key="dim.label" class="flex items-center gap-3">
        <span class="w-12 shrink-0 text-xs text-muted-foreground">{{ dim.label }}</span>
        <div class="h-1.5 min-w-0 flex-1 overflow-hidden rounded-full bg-foreground/10">
          <div
            class="h-full rounded-full bg-foreground/75 transition-[width] duration-500"
            :style="{ width: `${Math.max(0, Math.min(100, dim.value))}%` }"
          />
        </div>
        <span class="w-8 shrink-0 text-right num text-xs">{{ formatDisplay(dim.value) }}</span>
      </div>
    </div>

    <section v-if="data.matched.length > 0" class="border-t border-border/60 px-4 py-3">
      <h4 class="mb-1.5 text-xs font-medium text-success">{{ uiText("Matched") }}</h4>
      <ul class="space-y-1">
        <li v-for="item in data.matched" :key="item.requirement" class="text-xs leading-relaxed">
          {{ item.requirement }}
          <span class="ml-1 text-muted-foreground">{{ strengthLabel[item.strength] }}</span>
        </li>
      </ul>
    </section>

    <section v-if="data.gaps.length > 0" class="border-t border-border/60 px-4 py-3">
      <h4 class="mb-1.5 text-xs font-medium text-warning">{{ uiText("Gaps to address") }}</h4>
      <ul class="space-y-1.5">
        <li v-for="gap in data.gaps" :key="gap.requirement" class="text-xs leading-relaxed">
          <span>{{ gap.requirement }}</span>
          <span class="ml-1 text-muted-foreground">（{{ severityLabel[gap.severity] }}）</span>
          <p class="mt-0.5 text-muted-foreground">{{ gap.suggestion }}</p>
        </li>
      </ul>
    </section>

    <section v-if="data.advice.length > 0" class="border-t border-border/60 px-4 py-3">
      <h4 class="mb-1.5 text-xs font-medium text-muted-foreground">{{ uiText("Suggestions") }}</h4>
      <ul class="space-y-1 text-xs leading-relaxed text-muted-foreground">
        <li v-for="tip in data.advice" :key="tip">· {{ tip }}</li>
      </ul>
    </section>
  </article>
  <p v-else role="alert">{{ uiText("Match report data is unavailable. Please analyze again.") }}</p>
</template>
