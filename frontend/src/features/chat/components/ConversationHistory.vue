<script setup lang="ts">
import { computed, ref } from 'vue'
import { getStoredToken, sessionRevision } from '@/shared/utils/authSession'
import AppIcon from '@/shared/components/AppIcon.vue'
import type { Conversation } from '@/shared/types/conversation'

const props = defineProps<{
  conversations: Conversation[]
  currentId?: string
  expanded: boolean
  sending: boolean
  loading: boolean
  error: string
  hasMore: boolean
  mock: boolean
  rename: (id: string, title: string) => Promise<boolean>
  togglePin: (conversation: Conversation) => Promise<boolean>
  remove: (id: string) => Promise<boolean>
}>()
const emit = defineEmits<{ select: [id?: string]; reload: []; more: [] }>()

const signedIn = computed(() => {
  void sessionRevision.value
  return Boolean(getStoredToken())
})
const editing = ref<string>()
const titleDraft = ref('')
const deleting = ref<string>()
const busy = ref(false)

function select(id?: string) {
  editing.value = undefined
  deleting.value = undefined
  emit('select', id)
}
function editTitle(conversation: Conversation) {
  deleting.value = undefined
  editing.value = conversation.id
  titleDraft.value = conversation.title
}
async function run(action: () => Promise<boolean>, done: () => void) {
  busy.value = true
  if (await action()) done()
  busy.value = false
}
function saveTitle(id: string) {
  const title = titleDraft.value.trim()
  if (!title) return
  void run(
    () => props.rename(id, title),
    () => (editing.value = undefined),
  )
}
function confirmRemove(id: string) {
  void run(
    () => props.remove(id),
    () => (deleting.value = undefined),
  )
}
</script>
<template>
  <aside class="conversation-history" :class="{ expanded }" aria-label="历史会话">
    <div class="history-heading">
      <h2>对话</h2>
      <button class="btn text" :disabled="sending" @click="select()">
        <AppIcon name="plus" :size="16" />新建
      </button>
    </div>
    <div v-if="error" class="error-banner" role="alert">
      {{ error }}<button class="btn text" @click="emit('reload')">重新加载</button>
    </div>
    <p v-if="loading && !conversations.length" role="status" class="history-caption">
      正在加载会话…
    </p>
    <p v-else-if="!conversations.length && !error" class="history-caption">
      {{ mock ? '脚本演示，不保存历史。' : '第一段对话，从你的目标开始。' }}
    </p>
    <ul class="conversation-list">
      <li
        v-for="(conversation, index) in conversations"
        :key="conversation.id"
        class="stagger-in"
        :style="{ '--i': index }"
        :class="{ selected: currentId === conversation.id }"
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
            <button class="btn text" :disabled="busy || !titleDraft.trim()">保存</button
            ><button type="button" class="btn text" @click="editing = undefined">取消</button>
          </div>
        </form>
        <template v-else>
          <button
            class="conversation-link"
            :aria-current="currentId === conversation.id ? 'page' : undefined"
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
            <button :disabled="busy" @click="editTitle(conversation)">重命名</button>
            <button :disabled="busy" @click="togglePin(conversation)">
              {{ conversation.pinned ? '取消置顶' : '置顶' }}
            </button>
            <button :disabled="sending || busy" @click="deleting = conversation.id">删除</button>
          </div>
          <div v-if="deleting === conversation.id" class="delete-confirm" role="alert">
            <p>删除这段对话及全部消息？删除后无法恢复。</p>
            <button
              class="btn text danger"
              :disabled="busy"
              @click="confirmRemove(conversation.id)"
            >
              确认删除</button
            ><button class="btn text" @click="deleting = undefined">保留</button>
          </div>
        </template>
      </li>
    </ul>
    <button v-if="hasMore" class="btn text" :disabled="loading" @click="emit('more')">
      加载更多对话
    </button>
    <p class="history-caption history-foot">
      {{
        signedIn
          ? '对话保存在当前账号，使用同一账号登录可继续访问。'
          : '访客身份保存在这个浏览器，有效期 30 天。清除浏览器数据或更换设备后，无法直接访问原记录。'
      }}
    </p>
  </aside>
</template>
<style scoped>
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
@media (hover: none) {
  .conversation-actions {
    max-height: 40px;
    padding-bottom: 6px;
    opacity: 1;
  }
}
@media (max-width: 1000px) {
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
</style>
