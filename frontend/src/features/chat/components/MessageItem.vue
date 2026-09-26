<script setup lang="ts">
import MarkdownView from '@/shared/components/MarkdownView.vue'
import type { ChatMessageView } from '../types'

defineProps<{ message: ChatMessageView }>()
</script>

<template>
  <div v-if="message.role === 'user'" class="message-in flex justify-end">
    <div class="user-bubble">{{ message.text }}</div>
  </div>

  <div v-else class="message-in assistant" :class="{ 'is-live': message.streaming }">
    <details v-if="message.reasoning" class="reasoning">
      <summary>思考摘要</summary>
      <p>{{ message.reasoning }}</p>
    </details>

    <MarkdownView
      v-if="message.text"
      :text="message.text"
      :streaming="message.streaming === true"
    />
    <p v-else class="pending">
      <span v-if="!message.status || message.status === 'RUNNING'" class="live-dot pending-dot" />
      {{
        message.status === 'FAILED'
          ? '本轮未收到有效回复'
          : message.status === 'CANCELLED'
            ? '本轮已停止'
            : '正在思考'
      }}
    </p>
    <p v-if="message.status === 'FAILED' || message.status === 'CANCELLED'" class="run-note">
      {{
        message.status === 'FAILED'
          ? '回复未完成，可重新发送问题。'
          : '已停止生成，保留已收到的内容。'
      }}
      此轮不计入后续上下文。
    </p>

    <div v-if="message.cards?.length" class="assistant-cards">
      <slot name="cards" :cards="message.cards ?? []" />
    </div>
  </div>
</template>

<style scoped>
.user-bubble {
  max-width: min(80%, 36em);
  padding: 10px 16px;
  border-radius: 16px 16px 4px 16px;
  background: color-mix(in srgb, var(--foreground) 7%, var(--card));
  font-size: 15px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.assistant {
  min-width: 0;
  padding-left: 16px;
  border-left: 2px solid var(--border);
  transition: border-color 400ms ease;
}
.assistant.is-live {
  border-left-color: var(--primary);
}
.reasoning {
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--muted-foreground);
}
.reasoning summary {
  cursor: pointer;
  user-select: none;
  width: fit-content;
}
.reasoning summary:hover {
  color: var(--foreground);
}
.reasoning p {
  margin-top: 6px;
  line-height: 1.7;
}
.pending {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: var(--muted-foreground);
}
.pending-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.run-note {
  margin-top: 8px;
  font-size: 13px;
  color: var(--muted-foreground);
}
.assistant-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 14px;
}
</style>
