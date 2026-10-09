<script setup lang="ts">
import type { CareerRecommendationPayload, CareerTargetSelection } from '@/shared/types/cards'

defineProps<{
  payload: CareerRecommendationPayload
  interactive?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ adoptTarget: [selection: CareerTargetSelection] }>()
</script>

<template>
  <section class="min-w-0 rounded-xl border border-border bg-card p-4" aria-label="职业推荐">
    <h3 class="font-semibold">职业兴趣推荐 · {{ payload.hollandCode }}</h3>
    <p class="mt-1 text-sm text-muted-foreground">
      依据已保存兴趣：{{
        payload.interestsUsed.join('、')
      }}。规则推荐供探索参考，设为目标后仍需批准。
    </p>
    <p v-if="!payload.recommendations.length" class="mt-3 text-sm" role="status">
      暂无匹配职业，请调整探索页兴趣后重试。
    </p>
    <ol v-else class="mt-3 space-y-3">
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
        <ul class="mt-2 space-y-1 text-sm text-muted-foreground">
          <li v-for="reason in item.reasons" :key="reason" class="break-words">{{ reason }}</li>
        </ul>
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
</template>
