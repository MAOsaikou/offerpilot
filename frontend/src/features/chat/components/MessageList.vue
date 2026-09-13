<script setup lang="ts">
import MessageItem from './MessageItem.vue'
import type { ChatMessageView } from '../types'

defineProps<{ messages: ChatMessageView[] }>()
</script>

<template>
  <!-- 消息量大之后换虚拟滚动（docs/07 §12），当前量级不需要 -->
  <div class="space-y-4">
    <MessageItem v-for="message in messages" :key="message.id" :message="message">
      <template #cards="{ cards }"><slot name="cards" :cards="cards" /></template>
    </MessageItem>
  </div>
</template>
