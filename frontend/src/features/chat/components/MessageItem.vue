<script setup lang="ts">
import MarkdownView from '@/shared/components/MarkdownView.vue'
import type { ChatMessageView } from '../types'

defineProps<{ message: ChatMessageView }>()
</script>

<template>
  <div v-if="message.role === 'user'" class="flex justify-end">
    <div
      class="max-w-[80%] rounded-2xl rounded-br-md bg-primary/15 px-4 py-2.5 text-sm leading-relaxed whitespace-pre-wrap"
    >
      {{ message.text }}
    </div>
  </div>

  <div v-else class="flex justify-start">
    <div class="min-w-0 max-w-[92%] space-y-3">
      <div class="glass-card rounded-2xl rounded-bl-md px-4 py-3">
        <details v-if="message.reasoning" class="mb-2.5">
          <summary class="cursor-pointer text-xs text-muted-foreground select-none">
            思考摘要
          </summary>
          <p class="mt-1.5 text-xs leading-relaxed text-muted-foreground">
            {{ message.reasoning }}
          </p>
        </details>

        <MarkdownView
          v-if="message.text"
          :text="message.text"
          :streaming="message.streaming === true"
        />
        <p v-else class="text-sm text-muted-foreground">
          {{
            message.status === 'FAILED'
              ? '本轮未收到有效回复'
              : message.status === 'CANCELLED'
                ? '本轮已停止'
                : '正在处理…'
          }}
        </p>
        <p
          v-if="message.status === 'FAILED' || message.status === 'CANCELLED'"
          class="mt-2 text-xs text-muted-foreground"
        >
          {{
            message.status === 'FAILED'
              ? '回复未完成，可重新发送问题。'
              : '已停止生成，保留已收到的内容。'
          }}
          此轮不计入后续上下文。
        </p>
      </div>

      <slot name="cards" :cards="message.cards ?? []" />
    </div>
  </div>
</template>
