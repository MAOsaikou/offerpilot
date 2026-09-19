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
        <strong>我的对话</strong
        ><button class="btn text" :disabled="sending" @click="select()">＋ 新建</button>
      </div>
      <p class="history-caption">
        {{ mock ? '脚本演示，不保存历史' : '记录自动保存，随时接着聊' }}
      </p>
      <div v-if="listError" class="error-banner" role="alert">
        {{ listError }}<button class="btn text" @click="chat.refreshList()">重新加载</button>
      </div>
      <p v-if="listLoading && !conversations.length" role="status" class="subtle">正在加载会话…</p>
      <p v-else-if="!conversations.length && !listError" class="history-empty">
        第一段对话，从你的目标开始。
      </p>
      <ul class="conversation-list">
        <li
          v-for="conversation in conversations"
          :key="conversation.id"
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
            <button class="btn text" :disabled="actionBusy || !titleDraft.trim()">保存</button
            ><button type="button" class="btn text" @click="editing = undefined">取消</button>
          </form>
          <template v-else>
            <button
              class="conversation-link"
              :aria-current="current?.id === conversation.id ? 'page' : undefined"
              :disabled="sending"
              @click="select(conversation.id)"
            >
              <span class="conversation-title"
                >{{ conversation.pinned ? '▴ ' : '' }}{{ conversation.title }}</span
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
              <p>删除这段对话及全部消息？此操作无法撤销。</p>
              <button class="btn text" :disabled="actionBusy" @click="remove(conversation.id)">
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
      <p class="history-caption">
        访客身份保存在此浏览器，有效期 30 天。清除浏览器数据或更换设备后无法直接访问原记录。
      </p>
    </aside>
    <div class="chat-layout persistent-chat">
      <div class="chat-main">
        <div class="chat-toolbar">
          <button
            class="btn text history-toggle"
            :aria-expanded="historyOpen"
            @click="historyOpen = !historyOpen"
          >
            历史会话
          </button>
          <select v-model="mode" class="input" :disabled="busy" aria-label="对话模式">
            <option v-for="m in modes" :key="m.value" :value="m.value">{{ m.label }}</option>
          </select>
          <select v-model="tier" class="input" :disabled="busy" aria-label="模型档位">
            <option value="FLASH">快速 · FLASH</option>
            <option value="CHEAP">经济 · CHEAP</option>
            <option value="PRO">深入 · PRO</option>
          </select>
          <button class="btn text" :disabled="sending" @click="select()">新对话</button>
          <button class="btn text" :aria-expanded="panelOpen" @click="panelOpen = !panelOpen">
            执行过程
          </button>
          <span class="tag">{{ mock ? 'Mock 演示' : '自动保存' }}</span>
        </div>
        <div ref="scrollArea" class="chat-scroll" aria-live="polite" aria-relevant="additions text">
          <button v-if="hasMoreMessages" class="btn text" :disabled="loading" @click="older">
            加载更早消息
          </button>
          <p v-if="loading" class="subtle" role="status">正在加载记录…</p>
          <MessageList v-if="viewMessages.length" :messages="viewMessages"
            ><template #cards="{ cards }"><slot name="cards" :cards="cards" /></template
          ></MessageList>
          <div v-else-if="!loading && !missingConversation" class="chat-welcome">
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
            <p class="subtle">支持连续追问。同一对话会参考最近完成的内容，新的对话独立开始。</p>
          </div>
        </div>
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
        />
        <p class="chat-footnote">
          {{
            mock
              ? 'Mock 脚本演示 · 内容仅在当前页面可见'
              : '历史保存于服务端 · 上下文最多参考最近 10 轮完整对话 · 附件解析尚未接入'
          }}
        </p>
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
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 22px;
}
.conversation-history {
  min-width: 0;
  max-height: calc(100dvh - 160px);
  overflow: auto;
  border-right: 1px solid var(--border);
  padding-right: 16px;
}
.history-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.history-caption,
.history-empty {
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted-foreground);
  margin: 12px 0;
}
.conversation-list {
  list-style: none;
  padding: 0;
  margin: 16px 0;
}
.conversation-list li {
  border-radius: 10px;
  margin-bottom: 9px;
  padding: 6px;
  border: 1px solid transparent;
}
.conversation-list li.selected {
  background: color-mix(in srgb, var(--primary) 8%, transparent);
  border-color: var(--border);
}
.conversation-link {
  display: flex;
  flex-direction: column;
  gap: 7px;
  text-align: left;
  width: 100%;
  padding: 8px;
  min-width: 0;
}
.conversation-title {
  display: block;
  width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 13px;
}
.conversation-link time {
  font-size: 11px;
  color: var(--muted-foreground);
}
.conversation-actions {
  display: flex;
  gap: 10px;
  padding: 0 8px 5px;
  font-size: 11px;
  color: var(--muted-foreground);
}
.conversation-actions button {
  min-height: 28px;
}
.history-edit .input {
  width: 100%;
}
.delete-confirm {
  font-size: 12px;
  padding: 8px;
}
.persistent-chat {
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
}
.persistent-chat:has(.chat-panel) {
  grid-template-columns: minmax(0, 1fr) 240px;
}
.chat-footnote {
  font-size: 11px;
  color: var(--muted-foreground);
  text-align: center;
}
.history-toggle {
  display: none;
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
    gap: 12px;
  }
  .history-toggle {
    display: inline-flex;
  }
  .conversation-history {
    display: none;
    border-right: 0;
    border-bottom: 1px solid var(--border);
    max-height: 340px;
    padding: 0 0 12px;
  }
  .conversation-history.expanded {
    display: block;
  }
}
@media (max-width: 600px) {
  .chat-toolbar {
    gap: 6px;
    flex-wrap: wrap;
  }
  .chat-toolbar .input {
    flex: 1;
    min-width: 120px;
  }
  .chat-footnote {
    line-height: 1.7;
  }
}
</style>
