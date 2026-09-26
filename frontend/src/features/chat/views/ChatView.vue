<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
import Composer from '../components/Composer.vue'
import MessageList from '../components/MessageList.vue'
import { useConversationChat } from '../composables/useConversationChat'
import type { AgentMode } from '@/shared/types/agent-events'
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
const tier = ref<'FLASH' | 'CHEAP' | 'PRO'>('FLASH')
const seed = ref('')
const lastAttempt = ref('')
const panelOpen = ref(false)
const historyOpen = ref(false)
const scrollArea = ref<HTMLElement | null>(null)
const editing = ref<string>()
const titleDraft = ref('')
const deleting = ref<string>()
const actionBusy = ref(false)
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
      current.value?.mode || modes.find((m) => m.value === route.query.mode)?.value || 'AUTO'
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
  editing.value = undefined
  deleting.value = undefined
  if (!id && !route.params.conversationId) await chat.open()
  else await router.push(id ? `/chat/${encodeURIComponent(id)}` : '/chat')
}
async function onSend(content: string) {
  if (missingConversation.value) return
  lastAttempt.value = content
  seed.value = ''
  await chat.send(content, mode.value, tier.value, (id) => {
    void router.replace(`/chat/${encodeURIComponent(id)}`)
  })
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
async function saveTitle(id: string) {
  if (!titleDraft.value.trim()) return
  actionBusy.value = true
  if (await chat.update(id, { title: titleDraft.value.trim() })) editing.value = undefined
  actionBusy.value = false
}
function editTitle(id: string, title: string) {
  editing.value = id
  titleDraft.value = title
}
async function remove(id: string) {
  actionBusy.value = true
  if (await chat.remove(id)) {
    deleting.value = undefined
    if (current.value?.id === id) await select()
  }
  actionBusy.value = false
}
</script>
<template>
  <section class="conversation-workspace">
    <aside class="conversation-history" :class="{ expanded: historyOpen }" aria-label="历史会话">
      <div class="history-heading">
        <h2>对话</h2>
        <button class="btn text" :disabled="sending" @click="select()">
          <AppIcon name="plus" :size="16" />新建
        </button>
      </div>
      <div v-if="listError" class="error-banner" role="alert">
        {{ listError }}<button class="btn text" @click="chat.refreshList()">重新加载</button>
      </div>
      <p v-if="listLoading && !conversations.length" role="status" class="history-caption">
        正在加载会话…
      </p>
      <p v-else-if="!conversations.length && !listError" class="history-caption">
        {{ mock ? '脚本演示，不保存历史。' : '第一段对话，从你的目标开始。' }}
      </p>
      <ul class="conversation-list">
        <li
          v-for="(conversation, index) in conversations"
          :key="conversation.id"
          class="stagger-in"
          :style="{ '--i': index }"
          :class="{ selected: current?.id === conversation.id }"
        >
          <form
            v-if="editing === conversation.id"
            class="history-edit"
            @submit.prevent="saveTitle(conversation.id)"
          >
            <input
              v-model="titleDraft"
              class="input"
              maxlength="100"
              aria-label="会话名称"
              autofocus
            />
            <div>
              <button class="btn text" :disabled="actionBusy || !titleDraft.trim()">保存</button
              ><button type="button" class="btn text" @click="editing = undefined">取消</button>
            </div>
          </form>
          <template v-else>
            <button
              class="conversation-link"
              :aria-current="current?.id === conversation.id ? 'page' : undefined"
              :disabled="sending"
              @click="select(conversation.id)"
            >
              <span class="conversation-title"
                ><span v-if="conversation.pinned" class="pin" aria-label="已置顶" />{{
                  conversation.title
                }}</span
              >
              <time :datetime="conversation.updatedAt">{{
                new Date(conversation.updatedAt).toLocaleDateString('zh-CN', {
                  month: 'short',
                  day: 'numeric',
                })
              }}</time>
            </button>
            <div class="conversation-actions">
              <button
                :disabled="actionBusy"
                @click="editTitle(conversation.id, conversation.title)"
              >
                重命名
              </button>
              <button
                :disabled="actionBusy"
                @click="chat.update(conversation.id, { pinned: !conversation.pinned })"
              >
                {{ conversation.pinned ? '取消置顶' : '置顶' }}
              </button>
              <button :disabled="sending || actionBusy" @click="deleting = conversation.id">
                删除
              </button>
            </div>
            <div v-if="deleting === conversation.id" class="delete-confirm" role="alert">
              <p>删除这段对话及全部消息？删除后无法恢复。</p>
              <button
                class="btn text danger"
                :disabled="actionBusy"
                @click="remove(conversation.id)"
              >
                确认删除</button
              ><button class="btn text" @click="deleting = undefined">保留</button>
            </div>
          </template>
        </li>
      </ul>
      <button
        v-if="hasMoreConversations"
        class="btn text"
        :disabled="listLoading"
        @click="chat.refreshList(true)"
      >
        加载更多对话
      </button>
      <p class="history-caption history-foot">
        访客身份保存在这个浏览器，有效期 30 天。清除浏览器数据或更换设备后，无法直接访问原记录。
      </p>
    </aside>
    <div class="chat-layout persistent-chat">
      <div class="chat-main">
        <div class="chat-toolbar">
          <button
            class="icon-button history-toggle"
            :aria-expanded="historyOpen"
            aria-label="历史会话"
            @click="historyOpen = !historyOpen"
          >
            <AppIcon name="history" :size="18" />
          </button>
          <h1 class="chat-title">{{ current?.title || '新对话' }}</h1>
          <span class="chat-status">{{ mock ? 'Mock 演示' : '自动保存' }}</span>
          <button
            class="icon-button"
            :class="{ 'is-on': panelOpen }"
            :aria-expanded="panelOpen"
            aria-label="执行过程"
            title="执行过程"
            @click="panelOpen = !panelOpen"
          >
            <AppIcon name="panel" :size="18" />
          </button>
        </div>
        <div ref="scrollArea" class="chat-scroll" aria-live="polite" aria-relevant="additions text">
          <div class="chat-column">
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
            <div v-else-if="!loading && !missingConversation" class="chat-welcome">
              <h2>下一步，我们一起想。</h2>
              <p class="subtle">描述你眼前的问题，从一个具体的小目标开始。</p>
              <div class="suggestion-grid">
                <button
                  v-for="(text, index) in [
                    '帮我梳理职业兴趣和方向',
                    '如何写好简历中的项目经历？',
                    '帮我准备技术面试的自我介绍',
                    '如何安排一周的求职计划？',
                  ]"
                  :key="text"
                  class="stagger-in"
                  :style="{ '--i': index + 1 }"
                  @click="seed = text"
                >
                  {{ text }}
                </button>
              </div>
              <p class="subtle">支持连续追问。同一对话会参考最近完成的内容，新对话独立开始。</p>
            </div>
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
.conversation-history {
  display: flex;
  flex-direction: column;
  min-width: 0;
  max-height: calc(100dvh - 64px);
  padding: 4px 20px 0 0;
  border-right: 1px solid var(--border);
  overflow-y: auto;
}
.history-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 40px;
}
.history-heading h2 {
  font-size: 15px;
}
.history-caption {
  margin: 8px 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted-foreground);
}
.history-foot {
  margin-top: auto;
  padding: 16px 0;
}
.conversation-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 12px 0;
  padding: 0;
  list-style: none;
}
.conversation-list li {
  position: relative;
  border-radius: var(--radius-control);
  transition: background-color var(--dur-fast);
}
.conversation-list li:hover {
  background: color-mix(in srgb, var(--foreground) 5%, transparent);
}
.conversation-list li.selected {
  background: var(--card);
  box-shadow: 0 0 0 1px var(--border);
}
.conversation-link {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  min-width: 0;
  padding: 10px 12px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
}
.conversation-title {
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 14px;
}
.selected .conversation-title {
  font-weight: 600;
}
.pin {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--muted-foreground);
}
.conversation-link time {
  font-size: 12px;
  color: var(--muted-foreground);
}
.conversation-actions {
  display: flex;
  gap: 2px;
  max-height: 0;
  padding: 0 8px;
  overflow: hidden;
  opacity: 0;
  transition:
    max-height var(--dur-mid) var(--ease-out),
    opacity var(--dur-mid),
    padding var(--dur-mid);
}
.conversation-list li:hover .conversation-actions,
.conversation-list li:focus-within .conversation-actions,
.conversation-list li.selected .conversation-actions {
  max-height: 40px;
  padding-bottom: 6px;
  opacity: 1;
}
.conversation-actions button {
  min-height: 28px;
  padding: 0 6px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted-foreground);
  font: inherit;
  font-size: 12px;
}
.conversation-actions button:hover:not(:disabled) {
  background: var(--muted);
  color: var(--foreground);
}
.history-edit {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px;
}
.delete-confirm {
  padding: 4px 12px 10px;
  font-size: 13px;
  color: var(--danger);
  animation: rise 160ms var(--ease-out) both;
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
.chat-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 17px;
  font-weight: 650;
}
.chat-status {
  font-size: 12px;
  color: var(--muted-foreground);
}
.icon-button.is-on {
  background: var(--card);
  color: var(--primary);
  box-shadow: 0 0 0 1px var(--border);
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
.tool-select {
  min-height: 30px;
  padding: 0 26px 0 10px;
  border: 1px solid var(--border);
  border-radius: 15px;
  background-color: var(--background);
  background-image:
    linear-gradient(45deg, transparent 50%, currentColor 50%),
    linear-gradient(135deg, currentColor 50%, transparent 50%);
  background-position:
    calc(100% - 13px) 50%,
    calc(100% - 9px) 50%;
  background-size: 4px 4px;
  background-repeat: no-repeat;
  color: var(--muted-foreground);
  font: inherit;
  font-size: 13px;
  appearance: none;
  cursor: pointer;
  transition:
    color var(--dur-fast),
    border-color var(--dur-fast);
}
.tool-select:hover:not(:disabled) {
  color: var(--foreground);
  border-color: var(--input);
}
.chat-footnote {
  font-size: 12px;
  color: var(--muted-foreground);
  text-align: center;
}
.history-toggle {
  display: none;
}
@media (hover: none) {
  .conversation-actions {
    max-height: 40px;
    padding-bottom: 6px;
    opacity: 1;
  }
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
  .history-toggle {
    display: inline-flex;
  }
  .conversation-history {
    display: none;
    max-height: 360px;
    padding: 0 0 12px;
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }
  .conversation-history.expanded {
    display: flex;
    animation: rise 200ms var(--ease-out) both;
  }
}
@media (max-width: 600px) {
  .chat-status {
    display: none;
  }
  .chat-footnote {
    line-height: 1.7;
  }
}
</style>
