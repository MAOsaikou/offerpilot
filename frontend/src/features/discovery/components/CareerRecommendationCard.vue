<script setup lang="ts">
import { computed } from 'vue'
import CareerCardFacts from '@/shared/components/CareerCardFacts.vue'
import { RouterLink } from 'vue-router'
import type { CareerRecommendationPayload, CareerTargetSelection } from '@/shared/types/cards'

const props = defineProps<{
  payload: CareerRecommendationPayload
  interactive?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ adoptTarget: [selection: CareerTargetSelection] }>()
const valid = computed(
  () =>
    Array.isArray(props.payload?.recommendations) && Array.isArray(props.payload?.interestsUsed),
)
</script>

<template>
  <section
    v-if="valid"
    class="min-w-0 rounded-xl border border-border bg-card p-4"
    aria-label="职业推荐"
  >
    <h3 class="font-semibold">职业兴趣推荐 · {{ payload.hollandCode }}</h3>
    <p class="mt-1 text-sm text-muted-foreground">
      {{
        payload.interestsUsed.includes('对话测评')
          ? '依据你确认的对话测评画像（兴趣码 ' + payload.hollandCode + '）'
          : '依据已保存兴趣：' + payload.interestsUsed.join('、')
      }}。推荐供探索参考，设为目标后仍需批准。
    </p>
    <p v-if="!payload.recommendations.length" class="mt-3 text-sm" role="status">
      暂无匹配职业，请调整探索页兴趣后重试。
    </p>
    <RouterLink
      v-if="payload.revisionId"
      :to="{ path: '/career', query: { revision: payload.revisionId } }"
      class="btn"
      >打开本轮推荐与对比</RouterLink
    >
    <p v-if="payload.recommendations.length && payload.ordering === 'interest'" class="mt-2 text-sm text-muted-foreground">
      候选结合职业文本与兴趣召回，展示顺序按兴趣匹配度降序；兴趣匹配度为 (Pearson r + 1) / 2。
    </p>
    <ol v-if="payload.recommendations.length" class="mt-3 space-y-3">
      <li
        v-for="(item, index) in payload.recommendations"
        :key="item.occupationId"
        class="min-w-0 rounded-lg border border-border p-3"
      >
        <div class="flex flex-wrap items-center gap-2">
          <h4 class="break-words font-medium">{{ index + 1 }}. {{ item.name }}</h4>
          <span class="text-xs text-muted-foreground"
            >{{ item.family }} · {{ item.hollandCode }}</span
          >
        </div>
        <p v-if="item.rankingScore != null" class="mt-2 text-sm">
          综合排序分：{{ item.rankingScore.toFixed(4) }}（初始名次分 + 已批准的研究/薪资约束调整，非概率）
        </p>
        <p v-if="item.rankDelta != null" class="mt-2 text-sm">
          {{
            item.rankDelta > 0
              ? '↑ 上升 ' + item.rankDelta
              : item.rankDelta < 0
                ? '↓ 下降 ' + Math.abs(item.rankDelta)
                : '名次未变'
          }}
        </p>
        <ul class="mt-2 space-y-1 text-sm text-muted-foreground">
          <li v-for="reason in item.reasons" :key="reason" class="break-words">{{ reason }}</li>
        </ul>
        <p
          v-for="citation in item.citations ?? []"
          :key="citation.occupationId"
          class="mt-2 break-words text-xs text-muted-foreground"
        >
          引用来源：{{ citation.title }} · {{ citation.excerpt }}
        </p>
        <CareerCardFacts :details="item.details" :match="item.similarity" />
        <button
          v-if="interactive"
          type="button"
          class="mt-3 rounded border border-primary px-3 py-2 text-sm text-primary disabled:opacity-50"
          :disabled="disabled"
          :aria-label="`将${item.name}设为目标`"
          @click="emit('adoptTarget', { occupationId: item.occupationId, name: item.name })"
        >
          设为目标
        </button>
      </li>
    </ol>
  </section>
  <p v-else role="alert">职业推荐数据不可用，请重新查询。</p>
</template>
