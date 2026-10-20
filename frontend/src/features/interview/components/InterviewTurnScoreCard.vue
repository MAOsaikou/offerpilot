<script setup lang="ts">
import { computed } from 'vue'
import type { InterviewTurnScorePayload } from '@/shared/types/cards'
const props = defineProps<{ payload: InterviewTurnScorePayload }>()
const valid = computed(
  () =>
    props.payload &&
    Number.isInteger(props.payload.turnIndex) &&
    typeof props.payload.brief === 'string' &&
    (props.payload.score === null ||
      (Number.isFinite(props.payload.score) &&
        props.payload.score >= 0 &&
        props.payload.score <= 100)),
)
</script>
<template>
  <section class="panel form-stack min-w-0 break-words" aria-label="单题面试评分">
    <h3>单题面试评分</h3>
    <p v-if="!valid" role="alert">评分数据不可用，请重新评分。</p>
    <template v-else
      ><p>第 {{ payload.turnIndex + 1 }} 题 · {{ payload.score ?? '未评分' }} / 100</p>
      <p>{{ payload.brief || '暂无评分说明' }}</p>
    </template>
  </section>
</template>
