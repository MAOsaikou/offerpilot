<script setup lang="ts">
import { computed, ref, nextTick } from 'vue'
import type { EvidenceMatch, MatchRequirement } from '@/shared/types/jobs'
const props = defineProps<{ payload: unknown }>()
const data = computed(() => props.payload as EvidenceMatch)
const selected = ref<{
  title: string
  text: string
  quote: string
  status: MatchRequirement['status']
}>()
const evidencePanel = ref<HTMLElement>()
const labels = { MET: '有明确证据', PARTIAL: '部分体现', MISSING: '简历未体现' }
const statuses = [
  { status: 'MET', label: '明确' },
  { status: 'PARTIAL', label: '部分' },
  { status: 'MISSING', label: '未体现' },
] as const
const coverage = computed(() => {
  const counts = { MET: 0, PARTIAL: 0, MISSING: 0 }
  for (const item of data.value.requirements) counts[item.status]++
  return statuses.map((item) => ({ ...item, count: counts[item.status] }))
})
function evidence(item: MatchRequirement, side: 'jd' | 'resume') {
  if (side === 'jd')
    selected.value = {
      title: '分析时的岗位原文',
      text: `岗位：${data.value.jobSnapshot.title}\n职责：\n${data.value.jobSnapshot.description}\n要求：\n${data.value.jobSnapshot.requirement}`,
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
      title: '分析时的简历原文',
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
    <div class="section-heading">
      <div>
        <p class="subtle">岗位要求的简历证据覆盖率</p>
        <h2>{{ data.score }} / 100</h2>
      </div>
      <span class="tag">{{ data.resumeTitle }} · 版本 {{ data.resumeVersionNo }}</span>
    </div>
    <p>{{ data.summary }}</p>
    <p class="helper-note">
      此分数按本报告提取的要求计算：明确体现计 1，部分体现计 0.5，未体现计
      0。它不是录用概率，也不能证明实际能力；请结合官网完整要求判断。
    </p>
    <section class="coverage-summary" aria-label="岗位要求证据覆盖概览">
      <div class="coverage-counts">
        <span v-for="item in coverage" :key="item.status" :data-status="item.status">
          {{ item.label }} <strong>{{ item.count }}</strong> 项
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
      <p v-else class="subtle">本报告暂无可展示的要求证据。</p>
    </section>
    <article
      v-for="(item, index) in data.requirements"
      :key="index"
      class="requirement-row"
      :data-status="item.status"
    >
      <div class="section-heading">
        <h3>{{ index + 1 }}. {{ item.requirement }}</h3>
        <span class="tag status-chip">{{ labels[item.status] }}</span>
      </div>
      <p>{{ item.reason }}</p>
      <button class="evidence-link" @click="evidence(item, 'jd')">
        岗位依据：<span class="evidence-quote">{{ item.jdQuote }}</span>
      </button>
      <button v-if="item.resumeQuote" class="evidence-link" @click="evidence(item, 'resume')">
        简历依据：<span class="evidence-quote">{{ item.resumeQuote }}</span>
      </button>
      <p v-else class="subtle">所选简历中未找到可引用的证据。</p>
      <p><strong>准备建议：</strong>{{ item.suggestion }}</p>
    </article>
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
        <button class="btn" @click="selected = undefined">收起原文</button>
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
      生成于 {{ new Date(data.createdAt).toLocaleString('zh-CN') }} · 模型
      {{ data.model }}。报告保留当时资料，后续编辑简历不会改写此报告。
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
