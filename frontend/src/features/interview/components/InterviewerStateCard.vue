<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { asPayload } from '@/shared/utils/payload'
import type { InterviewerStatePayload } from '@/shared/types/cards'
const props = defineProps<{ payload: unknown }>()
const data = computed(() => asPayload<InterviewerStatePayload>(props.payload, 'state'))
</script>
<template>
  <article v-if="data" class="surface space-y-2 rounded-[14px] p-4 text-sm">
    <h3 class="font-semibold">文字模拟面试 · {{ data.questionIndex + 1 }} / {{ data.total }}</h3>
    <p class="break-words">{{ data.question }}</p>
    <p class="text-xs text-muted-foreground">{{ data.note }}</p>
    <RouterLink v-if="data.sessionId" :to="'/interviews/' + data.sessionId" class="btn"
      >打开面试室</RouterLink
    >
    <p class="text-xs text-muted-foreground">
      在聊天中回答；可发送「下一题」「给我面试反馈」或「结束面试」。多场面试时请从对应面试室进入聊天。
    </p>
  </article>
  <p v-else class="text-sm text-muted-foreground">面试问题暂不可用，请重试。</p>
</template>
