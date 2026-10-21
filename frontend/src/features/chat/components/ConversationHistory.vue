<script setup lang="ts">
import { language, translate as uiText } from '@/shared/utils/language'

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

// AG-01: group by last activity; pinned stay on top as their own group. Order inside a group is the server's.
const groups = computed(() => {
  const startOfDay = new Date()
  startOfDay.setHours(0, 0, 0, 0)
  const today = startOfDay.getTime()
  const day = 86_400_000
  const buckets: { label: string; items: Conversation[] }[] = [
    { label: uiText('Pinned'), items: [] },
    { label: uiText('Today'), items: [] },
    { label: uiText('Yesterday'), items: [] },
    { label: uiText('Last 7 days'), items: [] },
    { label: uiText('Earlier'), items: [] },
  ]
  for (const conversation of props.conversations) {
    const at = new Date(conversation.updatedAt).getTime()
    const index = conversation.pinned
      ? 0
      : Number.isNaN(at) || at < today - 6 * day
        ? 4
        : at >= today
          ? 1
          : at >= today - day
            ? 2
            : 3
    buckets[index]!.items.push(conversation)
  }
  return buckets.filter((bucket) => bucket.items.length)
})

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
  <aside
    class="conversation-history"
    :class="{ expanded }"
    :aria-label="uiText('Conversation history')"
  >
    <div class="history-heading">
      <h2>{{ uiText('Chat') }}</h2>
      <button class="btn text" :disabled="sending" @click="select()">
        <AppIcon name="plus" :size="16" />{{ uiText('New') }}
      </button>
    </div>
    <div v-if="error" class="error-banner" role="alert">
      {{ error }}<button class="btn text" @click="emit('reload')">{{ uiText('Reload #2') }}</button>
    </div>
    <p v-if="loading && !conversations.length" role="status" class="history-caption">
      {{ uiText('Loading conversations…') }}
    </p>
    <p v-else-if="!conversations.length && !error" class="history-caption">
      {{
        mock
          ? uiText('Scripted demo. History is not saved.')
          : uiText('Start your first conversation with a goal.')
      }}
    </p>
    <section
      v-for="group in groups"
      :key="group.label"
      class="conversation-group"
      :aria-label="group.label"
    >
      <h3 class="group-label">{{ group.label }}</h3>
      <ul class="conversation-list">
        <li
          v-for="(conversation, index) in group.items"
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
              :aria-label="uiText('Conversation name')"
              autofocus
            />
            <div>
              <button class="btn text" :disabled="busy || !titleDraft.trim()">
                {{ uiText('Save') }}</button
              ><button type="button" class="btn text" @click="editing = undefined">
                {{ uiText('Cancel') }}
              </button>
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
                ><span v-if="conversation.pinned" class="pin" :aria-label="uiText('Pinned #2')" />{{
                  conversation.title
                }}</span
              >
              <time :datetime="conversation.updatedAt">{{
                new Date(conversation.updatedAt).toLocaleDateString(language, {
                  month: 'short',
                  day: 'numeric',
                })
              }}</time>
            </button>
            <div class="conversation-actions">
              <button :disabled="busy" @click="editTitle(conversation)">
                {{ uiText('Rename') }}
              </button>
              <button :disabled="busy" @click="togglePin(conversation)">
                {{ conversation.pinned ? uiText('Unpin') : uiText('Pinned') }}
              </button>
              <button :disabled="sending || busy" @click="deleting = conversation.id">
                {{ uiText('Delete') }}
              </button>
            </div>
            <div v-if="deleting === conversation.id" class="delete-confirm" role="alert">
              <p>
                {{
                  uiText('Delete this conversation and all its messages? This cannot be undone.')
                }}
              </p>
              <button
                class="btn text danger"
                :disabled="busy"
                @click="confirmRemove(conversation.id)"
              >
                {{ uiText('Confirm deletion') }}</button
              ><button class="btn text" @click="deleting = undefined">{{ uiText('Keep') }}</button>
            </div>
          </template>
        </li>
      </ul>
    </section>
    <button v-if="hasMore" class="btn text" :disabled="loading" @click="emit('more')">
      {{ uiText('Load more conversations') }}
    </button>
    <p class="history-caption history-foot">
      {{
        signedIn
          ? uiText(
              'Conversations are saved to your account. Sign in with the same account to access them.',
            )
          : uiText(
              'Guest access stays in this browser for 30 days. Clearing browser data or changing devices removes direct access to these records.',
            )
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
.group-label {
  margin: 12px 0 4px;
  color: var(--muted-foreground);
  font-size: 12px;
  font-weight: 500;
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
