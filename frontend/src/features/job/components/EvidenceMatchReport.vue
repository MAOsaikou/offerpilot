<script setup lang="ts">
import { computed, ref, nextTick } from 'vue'
import type { EvidenceMatch, MatchRequirement } from '@/shared/types/jobs'
const props = defineProps<{ payload: unknown }>()
const data = computed(() => props.payload as EvidenceMatch)
const selected = ref<{ title: string; text: string; quote: string }>()
const evidencePanel = ref<HTMLElement>()
const labels = { MET: '有明确证据', PARTIAL: '部分体现', MISSING: '简历未体现' }
function evidence(item: MatchRequirement, side: 'jd' | 'resume') {
  if (side === 'jd')
    selected.value = {
      title: '分析时的岗位原文',
      text: `岗位：${data.value.jobSnapshot.title}\n职责：\n${data.value.jobSnapshot.description}\n要求：\n${data.value.jobSnapshot.requirement}`,
      quote: item.jdQuote,
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
    <article v-for="(item, index) in data.requirements" :key="index" class="requirement-row">
      <div class="section-heading">
        <h3>{{ index + 1 }}. {{ item.requirement }}</h3>
        <span class="tag">{{ labels[item.status] }}</span>
      </div>
      <p>{{ item.reason }}</p>
      <button class="evidence-link" @click="evidence(item, 'jd')">
        岗位依据：{{ item.jdQuote }}
      </button>
      <button v-if="item.resumeQuote" class="evidence-link" @click="evidence(item, 'resume')">
        简历依据：{{ item.resumeQuote }}
      </button>
      <p v-else class="subtle">所选简历中未找到可引用的证据。</p>
      <p><strong>准备建议：</strong>{{ item.suggestion }}</p>
    </article>
    <section
      v-if="selected && excerpt"
      ref="evidencePanel"
      tabindex="-1"
      class="helper-note"
      aria-live="polite"
    >
      <div class="section-heading">
        <h3>{{ selected.title }}</h3>
        <button class="btn" @click="selected = undefined">收起原文</button>
      </div>
      <p class="prose-content">
        {{ excerpt.before }}<mark>{{ excerpt.quote }}</mark
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
.requirement-row {
  display: grid;
  gap: 12px;
  padding: 20px 0;
  border-top: 1px solid var(--color-border, #e2e8f0);
}
.evidence-link {
  text-align: left;
  color: var(--color-primary, #315de8);
  background: transparent;
  border: 0;
  padding: 0;
  cursor: pointer;
  text-decoration: underline;
  overflow-wrap: anywhere;
}
</style>
