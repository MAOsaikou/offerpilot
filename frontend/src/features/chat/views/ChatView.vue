<script setup lang="ts">
import { computed, ref } from 'vue'
import { useAgentStream } from '@/shared/composables/useAgentStream'
import { useAgentRunStore } from '@/shared/stores/agentRun'
import AgentPanel from '@/widgets/AgentPanel/AgentPanel.vue'
import Composer from '../components/Composer.vue'
import MessageList from '../components/MessageList.vue'
import type { ChatMessageView } from '../types'

const store = useAgentRunStore()
const { start, stop, streaming } = useAgentStream()

const messages = ref<ChatMessageView[]>([])
let counter = 0

const activeRun = computed(() => store.activeRun)

/**
 * 最后一条助手消息直接映射当前运行状态，所以流式文本、思考摘要都是实时的。
 * 历史消息的持久化要等会话接口 /conversations 接上。
 */
const viewMessages = computed<ChatMessageView[]>(() => {
  const lastIndex = messages.value.length - 1
  const run = activeRun.value

  return messages.value.map((message, index) => {
    if (message.role !== 'assistant' || index !== lastIndex) return message

    const view: ChatMessageView = {
      ...message,
      text: run?.text ?? message.text,
      streaming: streaming.value,
    }
    const reasoning = run?.reasoning
    if (reasoning) view.reasoning = reasoning
    return view
  })
})

async function onSend(content: string): Promise<void> {
  counter += 1
  const assistantId = `local_a_${counter}`

  messages.value.push({ id: `local_u_${counter}`, role: 'user', text: content })
  messages.value.push({ id: assistantId, role: 'assistant', text: '' })

  try {
    await start({ content })
  } catch (error) {
    const target = messages.value.find((m) => m.id === assistantId)
    if (target) {
      target.text =
        error instanceof Error && error.message ? `运行失败：${error.message}` : '运行失败'
    }
  }
}

async function onStop(): Promise<void> {
  await stop()
}
</script>

<template>
  <div class="flex h-[calc(100vh-6.5rem)] gap-4">
    <div class="flex min-w-0 flex-1 flex-col gap-4">
      <div class="min-h-0 flex-1 overflow-y-auto px-1">
        <MessageList v-if="viewMessages.length > 0" :messages="viewMessages" />
        <div v-else class="flex h-full flex-col items-center justify-center gap-2">
          <p class="text-sm text-muted-foreground">开始一段对话</p>
          <p class="max-w-sm text-center text-xs text-muted-foreground">
            当前由本地 Mock 数据驱动，后端就绪后关掉 <code>VITE_ENABLE_MOCK</code> 即可切到真实接口
          </p>
        </div>
      </div>

      <Composer :streaming="streaming" @send="onSend" @stop="onStop" />
    </div>

    <div class="hidden w-80 shrink-0 xl:block">
      <AgentPanel :run="activeRun" />
    </div>
  </div>
</template>
