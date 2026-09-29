<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Composer from '../components/Composer.vue'
import MessageList from '../components/MessageList.vue'
import ConversationHistory from '../components/ConversationHistory.vue'
import ChatWelcome from '../components/ChatWelcome.vue'
import ChatToolbar from '../components/ChatToolbar.vue'
import { useConversationChat } from '../composables/useConversationChat'
import type { AgentMode } from '@/shared/types/agent-events'
import type { Conversation } from '@/shared/types/conversation'
const route = useRoute()
const router = useRouter()
const chat = useConversationChat()
const {
  conversations,
  current,
  viewMessages,
  loading,
  sending,
  busy,
  listLoading,
  error,
  listError,
  needsReload,
  hasMoreConversations,
  hasMoreMessages,
  activeRun,
  remoteRunId,
} = chat
const modes: { value: AgentMode; label: string }[] = [
  { value: 'AUTO', label: '智能助手' },
  { value: 'CAREER_DISCOVERY', label: '职业探索' },
  { value: 'RESUME', label: '简历指导' },
  { value: 'JOB', label: '岗位分析' },
  { value: 'INTERVIEWER', label: '面试练习' },
  { value: 'COACH', label: '成长教练' },
]
const mode = ref<AgentMode>('AUTO')
watch(
  () => route.query.diagnosisReportId,
  (id) => {
    if (typeof id === 'string') mode.value = 'RESUME'
  },
  { immediate: true },
)
const tier = ref<'FLASH' | 'CHEAP' | 'PRO'>('FLASH')
const seed = ref('')
const lastAttempt = ref('')
const panelOpen = ref(false)
const historyOpen = ref(false)
const scrollArea = ref<HTMLElement | null>(null)
const missingConversation = computed(
  () => Boolean(route.params.conversationId) && !current.value && !loading.value,
)
const lastQuestion = computed(
  () =>
    lastAttempt.value ||
    [...chat.messages.value].reverse().find((message) => message.role === 'user')?.text,
)
const mock = import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCK === 'true'
watch(
  () => route.params.conversationId,
  async (value) => {
    const id = typeof value === 'string' ? value : undefined
    if (id && id === current.value?.id) return
    lastAttempt.value = ''
    await chat.open(id)
    seed.value = typeof route.query.prompt === 'string' ? route.query.prompt : ''
    mode.value =
      modes.find((m) => m.value === route.query.mode)?.value || current.value?.mode || 'AUTO'
    await nextTick()
    if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
  },
  { immediate: true },
)
watch(
  () => route.query.prompt,
  (value) => {
    seed.value = typeof value === 'string' ? value : ''
  },
  { immediate: true },
)
watch(
  () => activeRun.value?.text,
  async () => {
    const el = scrollArea.value
    if (!el || el.scrollHeight - el.scrollTop - el.clientHeight > 180) return
    await nextTick()
    el.scrollTop = el.scrollHeight
  },
)
void chat.refreshList()
async function select(id?: string) {
  if (sending.value) await chat.stop()
  historyOpen.value = false
  if (!id && !route.params.conversationId) await chat.open()
  else await router.push(id ? `/chat/${encodeURIComponent(id)}` : '/chat')
}
async function onSend(content: string) {
  if (missingConversation.value) return
  lastAttempt.value = content
  seed.value = ''
  const context: Record<string, string> = {}
  for (const key of ['jobId', 'resumeId', 'resumeVersionId', 'diagnosisReportId']) {
    if (typeof route.query[key] === 'string') context[key] = route.query[key]
  }
  await chat.send(
    content,
    mode.value,
    tier.value,
    (id) => {
      const query = Object.keys(context).length ? { ...context, mode: mode.value } : {}
      void router.replace({ path: `/chat/${encodeURIComponent(id)}`, query })
    },
    Object.keys(context).length ? context : undefined,
  )
  await nextTick()
  if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
}
async function editLastQuestion() {
  const content = lastQuestion.value || ''
  seed.value = ''
  await nextTick()
  seed.value = content
}
async function older() {
  const el = scrollArea.value
  const height = el?.scrollHeight || 0
  await chat.older()
  await nextTick()
  if (el) el.scrollTop += el.scrollHeight - height
}
const rename = (id: string, title: string) => chat.update(id, { title })
const togglePin = (conversation: Conversation) =>
  chat.update(conversation.id, { pinned: !conversation.pinned })
async function remove(id: string) {
  const removed = await chat.remove(id)
  if (removed && current.value?.id === id) await select()
  return removed
}
</script>
<template>
  <section class="conversation-workspace">
    <ConversationHistory
      :conversations="conversations"
      :current-id="current?.id"
      :expanded="historyOpen"
      :sending="sending"
      :loading="listLoading"
      :error="listError"
      :has-more="hasMoreConversations"
      :mock="mock"
      :rename="rename"
      :toggle-pin="togglePin"
      :remove="remove"
      @select="select"
      @reload="chat.refreshList()"
      @more="chat.refreshList(true)"
    />
    <div class="chat-layout persistent-chat">
      <div class="chat-main">
        <ChatToolbar
          :title="current?.title || '新对话'"
          :status="mock ? 'Mock 演示' : '自动保存'"
          :history-open="historyOpen"
          :panel-open="panelOpen"
          @toggle-history="historyOpen = !historyOpen"
          @toggle-panel="panelOpen = !panelOpen"
        />
        <div ref="scrollArea" class="chat-scroll" aria-live="polite" aria-relevant="additions text">
          <div class="chat-column">
            <p v-if="typeof route.query.jobId === 'string'" class="helper-note">
              已关联选定岗位和简历版本。发送匹配请求后生成报告；搜索新岗位仍按本轮搜索条件执行。
              <RouterLink :to="'/jobs/' + encodeURIComponent(route.query.jobId)"
                >返回岗位</RouterLink
              >
            </p>
            <p v-if="route.query.diagnosisReportId" class="helper-note">
              已关联选定版本的简历诊断报告。发送消息后，AI 会读取报告继续讨论。
              <RouterLink
                v-if="typeof route.query.resumeId === 'string'"
                :to="'/resumes/' + encodeURIComponent(route.query.resumeId)"
                >返回简历</RouterLink
              >
            </p>
            <button
              v-if="hasMoreMessages"
              class="btn text older"
              :disabled="loading"
              @click="older"
            >
              加载更早消息
            </button>
            <p v-if="loading" class="subtle" role="status">正在加载记录…</p>
            <MessageList v-if="viewMessages.length" :messages="viewMessages"
              ><template #cards="{ cards }"><slot name="cards" :cards="cards" /></template
            ></MessageList>
            <ChatWelcome v-else-if="!loading && !missingConversation" @pick="seed = $event" />
          </div>
        </div>
        <div class="chat-column chat-dock">
          <div v-if="error" class="error-banner" role="alert">
            {{ error
            }}<button
              class="btn text"
              :disabled="sending"
              @click="
                chat.open(
                  typeof route.params.conversationId === 'string'
                    ? route.params.conversationId
                    : undefined,
                )
              "
            >
              重新加载
            </button>
          </div>
          <div v-if="remoteRunId && !sending" class="helper-note">
            这段对话仍在生成，记录会自动更新。<button class="btn text" @click="chat.stop()">
              停止生成
            </button>
          </div>
          <button
            v-if="
              lastQuestion &&
              !busy &&
              (error || ['FAILED', 'CANCELLED'].includes(chat.messages.value.at(-1)?.status || ''))
            "
            class="btn text"
            @click="editLastQuestion"
          >
            重新编辑上条问题
          </button>
          <Composer
            :key="current?.id || 'new'"
            :disabled="
              loading || needsReload || missingConversation || Boolean(remoteRunId && !sending)
            "
            :streaming="sending"
            :initial-draft="seed"
            @send="onSend"
            @stop="chat.stop"
          >
            <template #tools>
              <select v-model="mode" class="tool-select" :disabled="busy" aria-label="对话模式">
                <option v-for="m in modes" :key="m.value" :value="m.value">{{ m.label }}</option>
              </select>
              <select v-model="tier" class="tool-select" :disabled="busy" aria-label="模型档位">
                <option value="FLASH">快速</option>
                <option value="CHEAP">经济</option>
                <option value="PRO">深入</option>
              </select>
            </template>
          </Composer>
          <p class="chat-footnote">
            {{
              mock
                ? 'Mock 脚本演示，内容只在当前页面可见。'
                : '历史保存在服务端，上下文最多参考最近 10 轮完整对话。附件解析尚未接入。'
            }}
          </p>
        </div>
      </div>
      <aside v-if="panelOpen" class="chat-panel visible">
        <slot name="agent" :run="activeRun" />
      </aside>
    </div>
  </section>
</template>
<style scoped>
.conversation-workspace {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 28px;
  margin: -16px 0 -40px;
}
.persistent-chat {
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
}
.persistent-chat:has(.chat-panel) {
  grid-template-columns: minmax(0, 1fr) 300px;
}
.chat-panel {
  animation: rise 200ms var(--ease-out) both;
}
.chat-column {
  width: 100%;
  max-width: 760px;
  margin: 0 auto;
}
.chat-scroll .chat-column {
  padding: 16px 4px 24px;
}
.older {
  display: flex;
  margin: 0 auto 16px;
}
.chat-dock {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: 12px;
}
.chat-footnote {
  font-size: 12px;
  color: var(--muted-foreground);
  text-align: center;
}
@media (max-width: 1300px) {
  .persistent-chat:has(.chat-panel) {
    grid-template-columns: minmax(0, 1fr);
    height: auto;
  }
  .persistent-chat .chat-panel {
    max-height: 360px;
  }
}
@media (max-width: 1000px) {
  .conversation-workspace {
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
    margin: -8px 0 -32px;
  }
}
@media (max-width: 600px) {
  .chat-footnote {
    line-height: 1.7;
  }
}
</style>
