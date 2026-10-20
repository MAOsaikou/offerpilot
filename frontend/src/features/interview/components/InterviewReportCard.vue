<script setup lang="ts">
import { computed } from 'vue'
import InterviewFeedback from './InterviewFeedback.vue'
import { RouterLink } from 'vue-router'
import { asPayload } from '@/shared/utils/payload'
import type { InterviewReportPayload } from '@/shared/types/cards'

const props = defineProps<{ payload: unknown; inline?: boolean }>()
const data = computed(() => asPayload<InterviewReportPayload>(props.payload, 'dimensions'))
const dimensions = [
  { key: 'professional', label: '专业', reason: '职责、行动、结果与量化线索' },
  { key: 'expression', label: '表达', reason: '文本长度、分句与结构线索' },
  { key: 'logic', label: '逻辑', reason: 'STAR 四项覆盖' },
  { key: 'jobFit', label: '岗位匹配', reason: '职责、行动、结果与岗位名称关联' },
] as const
const starParts = [
  { key: 'situation', label: 'S 背景' },
  { key: 'task', label: 'T 任务' },
  { key: 'action', label: 'A 行动' },
  { key: 'result', label: 'R 结果' },
] as const
const sections = [
  { key: 'strengths', label: '优势线索' },
  { key: 'weaknesses', label: '短板与待补充' },
  { key: 'advice', label: '改进建议' },
] as const
</script>

<template>
  <article v-if="data" class="surface space-y-5 rounded-[14px] p-4 text-sm">
    <header>
      <h3 class="font-semibold">面试评分与复盘</h3>
      <p class="mt-2 text-2xl font-semibold">
        <span class="text-sm">总分 </span>{{ data.overall ?? '未评分'
        }}<span class="text-sm"> / 100</span>
      </p>
      <p class="mt-1 break-words text-xs text-muted-foreground">
        {{ data.note || '文字规则评分；端侧表现分析未启用。' }}
      </p>
      <RouterLink v-if="data.id && !inline" :to="'/interviews/' + data.id" class="btn mt-2"
        >打开面试复盘</RouterLink
      >
    </header>
    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
      <div v-for="dimension in dimensions" :key="dimension.key">
        <div class="flex justify-between gap-2">
          <span>{{ dimension.label }}</span
          ><span>{{ data.dimensions[dimension.key] ?? '未评分' }} / 100</span>
        </div>
        <progress
          v-if="data.dimensions[dimension.key] !== null"
          class="mt-1 h-2 w-full accent-primary"
          :value="data.dimensions[dimension.key] ?? undefined"
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
    <p v-if="!data.turns?.length" class="text-muted-foreground">
      暂无回答，请完成文字面试后重新评分。
    </p>
    <section
      v-for="(turn, index) in data.turns"
      :key="index"
      class="space-y-2 border-t border-border pt-3"
    >
      <h4 class="font-medium">第 {{ index + 1 }} 题 · {{ turn.score ?? '未评分' }} / 100</h4>
      <p class="break-words">{{ turn.question }}</p>
      <p class="break-words text-muted-foreground">{{ turn.answerSummary || '尚未作答' }}</p>
      <div v-if="!turn.source || turn.source === 'rule'" class="flex flex-wrap gap-2">
        <span
          v-for="part in starParts"
          :key="part.key"
          class="rounded-md bg-muted px-2 py-1 text-xs"
          :aria-label="`${part.label}：${turn.star[part.key] ? '已观察到' : '未观察到'}`"
        >
          {{ turn.star[part.key] ? '✓' : '○' }} {{ part.label }}
        </span>
      </div>
      <p class="break-words">评分理由：{{ turn.feedback }}</p>
      <InterviewFeedback v-if="turn.evaluation" :evaluation="turn.evaluation" />
      <details v-else-if="turn.improvedAnswer" open>
        <summary class="cursor-pointer text-primary">改进答案（原文事实）</summary>
        <p class="prose-content">{{ turn.improvedAnswer }}</p>
      </details>
      <details v-if="turn.betterAnswerOutline.length">
        <summary class="cursor-pointer text-primary">改进答案提纲</summary>
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
      <h4 class="font-medium">建议练习</h4>
      <ul class="mt-1 space-y-2">
        <li v-for="item in data.recommendedLearning" :key="item.topic">
          {{ item.topic }} · 约 {{ item.estimatedHours }} 小时
          <p class="text-xs text-muted-foreground">{{ item.reason }}</p>
        </li>
      </ul>
    </section>
  </article>
  <p v-else class="surface rounded-[14px] p-4 text-sm text-muted-foreground">
    面试评分卡数据不可用，请重新请求评分。
  </p>
</template>

<style scoped>
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
}
progress::-moz-progress-bar {
  background: var(--primary);
}
</style>
