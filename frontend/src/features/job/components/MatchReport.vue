<script setup lang="ts">
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
const labels = { MET: '有明确证据', PARTIAL: '部分体现', MISSING: '简历未体现' }

const dimensions = computed(() => {
  const dims = data.value?.dimensions
  if (!dims) return []
  return [
    { label: '技能', value: dims.skills },
    { label: '经验', value: dims.experience },
    { label: '学历', value: dims.education },
    { label: '关键词', value: dims.keywords },
  ]
})

const strengthLabel = { STRONG: '强匹配', MEDIUM: '部分匹配', WEAK: '弱匹配' } as const
const severityLabel = { LOW: '轻微', MEDIUM: '中等', HIGH: '严重' } as const
</script>

<template>
  <article v-if="evidence" class="panel form-stack min-w-0 break-words" aria-label="岗位匹配报告">
    <h3>岗位要求的简历证据覆盖率 · {{ evidence.score }} / 100</h3>
    <p>{{ evidence.resumeTitle }} · 版本 {{ evidence.resumeVersionNo }}</p>
    <p>{{ evidence.summary }}</p>
    <p class="helper-note">
      明确体现计 1，部分体现计 0.5，未体现计 0；未体现不等于不具备，分数不是录用预测。
    </p>
    <ul class="space-y-3">
      <li v-for="(item, index) in evidence.requirements" :key="index" class="rounded-lg border p-3">
        <h4>{{ index + 1 }}. {{ item.requirement }}</h4>
        <span class="tag">{{ labels[item.status] }}</span>
        <p>{{ item.reason }}</p>
        <p class="subtle">岗位依据：{{ item.jdQuote }}</p>
        <p class="subtle">
          {{
            item.resumeQuote ? '简历依据：' + item.resumeQuote : '所选简历中未找到可引用的证据。'
          }}
        </p>
        <p>准备建议：{{ item.suggestion }}</p>
      </li>
    </ul>
    <RouterLink
      :to="{ path: '/jobs/' + evidence.jobId, query: { matchId: evidence.id } }"
      class="btn"
      >查看完整匹配报告</RouterLink
    >
  </article>
  <article v-else-if="data" class="surface overflow-hidden rounded-[14px]">
    <header class="flex items-baseline gap-3 border-b border-border/60 px-4 py-3">
      <div>
        <p class="text-xs text-muted-foreground">简历与岗位匹配度</p>
        <p class="mt-0.5 num text-4xl leading-none">
          {{ data.score }}<span class="ml-0.5 text-sm font-normal text-muted-foreground">/100</span>
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
        <span class="w-8 shrink-0 text-right num text-xs">{{ dim.value }}</span>
      </div>
    </div>

    <section v-if="data.matched.length > 0" class="border-t border-border/60 px-4 py-3">
      <h4 class="mb-1.5 text-xs font-medium text-success">已匹配</h4>
      <ul class="space-y-1">
        <li v-for="item in data.matched" :key="item.requirement" class="text-xs leading-relaxed">
          {{ item.requirement }}
          <span class="ml-1 text-muted-foreground">{{ strengthLabel[item.strength] }}</span>
        </li>
      </ul>
    </section>

    <section v-if="data.gaps.length > 0" class="border-t border-border/60 px-4 py-3">
      <h4 class="mb-1.5 text-xs font-medium text-warning">待补差距</h4>
      <ul class="space-y-1.5">
        <li v-for="gap in data.gaps" :key="gap.requirement" class="text-xs leading-relaxed">
          <span>{{ gap.requirement }}</span>
          <span class="ml-1 text-muted-foreground">（{{ severityLabel[gap.severity] }}）</span>
          <p class="mt-0.5 text-muted-foreground">{{ gap.suggestion }}</p>
        </li>
      </ul>
    </section>

    <section v-if="data.advice.length > 0" class="border-t border-border/60 px-4 py-3">
      <h4 class="mb-1.5 text-xs font-medium text-muted-foreground">建议</h4>
      <ul class="space-y-1 text-xs leading-relaxed text-muted-foreground">
        <li v-for="tip in data.advice" :key="tip">· {{ tip }}</li>
      </ul>
    </section>
  </article>
</template>
