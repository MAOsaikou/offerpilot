<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAgentStream } from '@/shared/composables/useAgentStream'
import { useAgentRunStore } from '@/shared/stores/agentRun'
import AppIcon from '@/shared/components/AppIcon.vue'
import Composer from '../components/Composer.vue'
import MessageList from '../components/MessageList.vue'
import type { ChatMessageView } from '../types'
import type { AgentMode } from '@/shared/types/agent-events'
const route = useRoute()
const store = useAgentRunStore()
const { start, stop, streaming } = useAgentStream()
const modes: { value: AgentMode; label: string }[] = [
  { value: 'AUTO', label: '智能助手' },
  { value: 'CAREER_DISCOVERY', label: '职业探索' },
  { value: 'RESUME', label: '简历指导' },
  { value: 'JOB', label: '岗位分析' },
  { value: 'INTERVIEWER', label: '面试练习' },
  { value: 'COACH', label: '成长教练' },
]
const mode = ref<AgentMode>(modes.find((m) => m.value === route.query.mode)?.value || 'AUTO')
const tier = ref<'FLASH' | 'CHEAP' | 'PRO'>('FLASH')
const seed = ref(typeof route.query.prompt === 'string' ? route.query.prompt : '')
const messages = ref<ChatMessageView[]>([])
const conversationId = ref<string | undefined>()
const error = ref('')
const lastContent = ref('')
const panelOpen = ref(false)
const scrollArea = ref<HTMLElement | null>(null)
const currentAssistant = ref<string | null>(null)
const activeRun = computed(() => store.activeRun)
const mock = import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCK === 'true'
const viewMessages = computed(() =>
  messages.value.map((message) => {
    if (message.id !== currentAssistant.value || !activeRun.value) return message
    return {
      ...message,
      text: activeRun.value.text,
      reasoning: activeRun.value.reasoning || undefined,
      cards: activeRun.value.cards,
      streaming: streaming.value,
    }
  }),
)
watch(
  () => activeRun.value?.text,
  async () => {
    const el = scrollArea.value
    if (!el || el.scrollHeight - el.scrollTop - el.clientHeight > 160) return
    await nextTick()
    el.scrollTop = el.scrollHeight
  },
)
watch(
  () => route.query.prompt,
  (value) => {
    if (typeof value === 'string') seed.value = value
  },
)
async function onSend(content: string) {
  if (streaming.value) return
  lastContent.value = content
  error.value = ''
  store.reset()
  const id = crypto.randomUUID()
  currentAssistant.value = id
  messages.value.push(
    { id: crypto.randomUUID(), role: 'user', text: content },
    { id, role: 'assistant', text: '' },
  )
  await nextTick()
  if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
  try {
    await start({
      content,
      mode: mode.value,
      options: { modelTier: tier.value },
      ...(conversationId.value ? { conversationId: conversationId.value } : {}),
    })
    conversationId.value = activeRun.value?.conversationId || conversationId.value
    if (activeRun.value?.error) error.value = activeRun.value.error.message
  } catch (e) {
    if (!(e instanceof DOMException && e.name === 'AbortError'))
      error.value = e instanceof Error ? e.message : '连接失败，请检查后端'
  } finally {
    const target = messages.value.find((m) => m.id === id)
    if (target) {
      target.text =
        activeRun.value?.text ||
        (error.value ? '本轮未收到有效回复，请检查连接后重试。' : '已停止接收。')
      target.cards = activeRun.value?.cards
      target.reasoning = activeRun.value?.reasoning || undefined
      target.streaming = false
    }
    currentAssistant.value = null
  }
}
function newChat() {
  if (streaming.value) return
  messages.value = []
  conversationId.value = undefined
  store.reset()
  error.value = ''
  seed.value = ''
}
onBeforeUnmount(() => {
  void stop()
})
</script>
<template>
  <section class="chat-layout">
    <div class="chat-main">
      <div class="chat-toolbar">
        <select v-model="mode" class="input" :disabled="streaming" aria-label="对话模式">
          <option v-for="m in modes" :key="m.value" :value="m.value">{{ m.label }}</option></select
        ><select v-model="tier" class="input" :disabled="streaming" aria-label="模型档位">
          <option value="FLASH">快速 · FLASH</option>
          <option value="CHEAP">经济 · CHEAP</option>
          <option value="PRO">深入 · PRO</option></select
        ><button class="btn text" :disabled="streaming" @click="newChat">新对话</button
        ><button
          class="btn mobile-panel-toggle"
          :aria-expanded="panelOpen"
          @click="panelOpen = !panelOpen"
        >
          执行过程</button
        ><span class="tag">{{ mock ? 'Mock 演示' : '真实后端' }}</span>
      </div>
      <div ref="scrollArea" class="chat-scroll" aria-live="polite" aria-relevant="additions text">
        <MessageList v-if="viewMessages.length" :messages="viewMessages">
          <template #cards="{ cards }"><slot name="cards" :cards="cards" /></template>
        </MessageList>
        <div v-else class="chat-welcome">
          <AppIcon :size="45" />
          <p class="eyebrow">YOUR CAREER COPILOT</p>
          <h2>下一步，我们一起想。</h2>
          <p class="subtle">描述你眼前的问题，从一个具体的小目标开始。</p>
          <div class="suggestion-grid">
            <button
              v-for="text in [
                '帮我梳理职业兴趣和方向',
                '如何写好简历中的项目经历？',
                '帮我准备技术面试的自我介绍',
                '如何安排一周的求职计划？',
              ]"
              :key="text"
              @click="seed = text"
            >
              {{ text }} ↗
            </button>
          </div>
          <p class="subtle">当前后端为联调回显；发送内容可验证实时事件链路。</p>
        </div>
      </div>
      <div v-if="error" class="error-banner" role="alert">
        {{ error }}
        <button class="btn text" :disabled="streaming" @click="onSend(lastContent)">重试</button>
      </div>
      <Composer :streaming="streaming" :initial-draft="seed" @send="onSend" @stop="stop" />
      <p class="subtle" style="font-size: 11px; text-align: center">
        对话发送到后端 · 内容仅保留在当前页面 · 离开页面会停止接收
      </p>
    </div>
    <aside class="chat-panel" :class="{ visible: panelOpen }">
      <slot name="agent" :run="activeRun" />
    </aside>
  </section>
</template>
