<script setup lang="ts">
import { computed } from 'vue'
import { asPayload } from '@/shared/utils/payload'
import type { JobMatchPayload } from '@/shared/types/cards'

const props = defineProps<{ payload: unknown }>()

const data = computed(() => asPayload<JobMatchPayload>(props.payload, 'score'))

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
  <article v-if="data" class="glass-card overflow-hidden rounded-2xl">
    <header class="flex items-baseline gap-3 border-b border-border/60 px-4 py-3">
      <div>
        <p class="text-xs text-muted-foreground">简历与岗位匹配度</p>
        <p class="mt-0.5 text-3xl font-semibold tracking-tight">
          {{ data.score }}<span class="ml-0.5 text-sm font-normal text-muted-foreground">/100</span>
        </p>
      </div>
      <span class="ml-auto font-mono text-xs text-muted-foreground">
        jobId={{ data.jobId }}
      </span>
    </header>

    <div class="space-y-2 px-4 py-3">
      <div v-for="dim in dimensions" :key="dim.label" class="flex items-center gap-3">
        <span class="w-12 shrink-0 text-xs text-muted-foreground">{{ dim.label }}</span>
        <div class="h-1.5 min-w-0 flex-1 overflow-hidden rounded-full bg-foreground/10">
          <div
            class="h-full rounded-full bg-primary transition-[width] duration-500"
            :style="{ width: `${Math.max(0, Math.min(100, dim.value))}%` }"
          />
        </div>
        <span class="w-8 shrink-0 text-right font-mono text-xs">{{ dim.value }}</span>
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
