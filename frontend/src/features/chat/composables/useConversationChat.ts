import { computed, onScopeDispose, ref } from 'vue'
import { conversationsApi } from '@/shared/api/conversations'
import { cancelAgentRun } from '@/shared/api/agent'
import { useAgentStream } from '@/shared/composables/useAgentStream'
import { useAgentRunStore, type CardView } from '@/shared/stores/agentRun'
import type { Conversation, StoredMessage } from '@/shared/types/conversation'
import type { AgentMode } from '@/shared/types/agent-events'
import type { CardType } from '@/shared/types/cards'
import type { ChatMessageView } from '../types'

interface RunExtras {
  cards?: CardView[]
  reasoning?: string
}

/** 未知 cardType 交给 CardRenderer 的兜底组件处理 */
const storedCards = (m: StoredMessage): CardView[] =>
  (m.parts ?? []).flatMap((part, index) =>
    part.type === 'card' && typeof part.cardType === 'string'
      ? [
          {
            cardId: part.cardId ?? `${m.id}-card-${index}`,
            cardType: part.cardType as CardType,
            payload: part.payload,
          },
        ]
      : [],
  )
const errorText = (error: unknown) => (error instanceof Error ? error.message : '请求失败，请重试')

export function useConversationChat() {
  const mock = import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCK === 'true'
  const store = useAgentRunStore()
  const stream = useAgentStream()
  const conversations = ref<Conversation[]>([])
  const current = ref<Conversation>()
  const messages = ref<ChatMessageView[]>([])
  const loading = ref(false)
  const sending = ref(false)
  const listLoading = ref(false)
  const error = ref('')
  const listError = ref('')
  const needsReload = ref(false)
  const hasMoreConversations = ref(false)
  const hasMoreMessages = ref(false)
  const before = ref<string>()
  const page = ref(1)
  const optimisticId = ref<string>()
  let epoch = 0
  let listEpoch = 0
  let poll: ReturnType<typeof setTimeout> | undefined
  // 服务端记录尚未持久化卡片与思考摘要，本页会话内按 runId 保留已渲染的内容
  const runExtras = new Map<string, RunExtras>()
  function messageView(m: StoredMessage): ChatMessageView {
    const view: ChatMessageView = {
      id: m.id,
      role: m.role === 'USER' ? 'user' : 'assistant',
      text: m.plainText || '',
      status: m.status,
      runId: m.runId,
    }
    if (view.role === 'user') return view
    const extras = m.runId ? runExtras.get(m.runId) : undefined
    const cards = storedCards(m)
    if (cards.length) view.cards = cards
    else if (extras?.cards?.length) view.cards = extras.cards
    if (extras?.reasoning) view.reasoning = extras.reasoning
    return view
  }
  const activeRun = computed(() => store.activeRun)
  const remoteRunId = computed(() => messages.value.find((m) => m.status === 'RUNNING')?.runId)
  const busy = computed(
    () => sending.value || loading.value || needsReload.value || Boolean(remoteRunId.value),
  )
  const viewMessages = computed(() =>
    messages.value.map((m) =>
      m.id === optimisticId.value && activeRun.value
        ? {
            ...m,
            text: activeRun.value.text,
            reasoning: activeRun.value.reasoning,
            cards: activeRun.value.cards,
            streaming: stream.streaming.value,
          }
        : m,
    ),
  )

  async function refreshList(more = false) {
    if (mock) return
    const version = ++listEpoch
    listLoading.value = true
    listError.value = ''
    try {
      const nextPage = more ? page.value + 1 : 1
      const data = await conversationsApi.list(nextPage)
      if (version !== listEpoch) return
      conversations.value = more
        ? [
            ...conversations.value,
            ...data.items.filter((item) => !conversations.value.some((c) => c.id === item.id)),
          ]
        : data.items
      page.value = nextPage
      hasMoreConversations.value = nextPage * data.size < data.total
      // 首条消息发送后服务端会改写标题，当前会话随列表同步
      const fresh = data.items.find((item) => item.id === current.value?.id)
      if (fresh) current.value = fresh
    } catch (e) {
      if (version === listEpoch) listError.value = errorText(e)
    } finally {
      if (version === listEpoch) listLoading.value = false
    }
  }

  function schedulePoll(id: string, version: number) {
    clearTimeout(poll)
    if (!remoteRunId.value || sending.value) return
    poll = setTimeout(async () => {
      try {
        const data = await conversationsApi.messages(id)
        if (version !== epoch) return
        const ids = new Set(data.items.map((m) => m.id))
        messages.value = [
          ...messages.value.filter((m) => !ids.has(m.id)),
          ...data.items.map(messageView),
        ]
        schedulePoll(id, version)
      } catch (e) {
        if (version === epoch) error.value = errorText(e)
      }
    }, 2000)
  }

  async function open(id?: string) {
    const version = ++epoch
    clearTimeout(poll)
    if (stream.streaming.value) await stream.stop().catch(() => undefined)
    if (version !== epoch) return
    store.reset()
    current.value = undefined
    messages.value = []
    optimisticId.value = undefined
    hasMoreMessages.value = false
    error.value = ''
    needsReload.value = false
    sending.value = false
    loading.value = Boolean(id)
    if (!id) return
    if (mock) {
      loading.value = false
      return
    }
    try {
      const [conversation, data] = await Promise.all([
        conversationsApi.get(id),
        conversationsApi.messages(id),
      ])
      if (version !== epoch) return
      current.value = conversation
      messages.value = data.items.map(messageView)
      before.value = data.nextBefore
      hasMoreMessages.value = data.hasMore
      schedulePoll(id, version)
    } catch (e) {
      if (version === epoch) error.value = errorText(e)
    } finally {
      if (version === epoch) loading.value = false
    }
  }

  async function older() {
    if (!current.value || !before.value || loading.value) return
    const version = epoch
    loading.value = true
    try {
      const data = await conversationsApi.messages(current.value.id, before.value)
      if (version !== epoch) return
      const existing = new Set(messages.value.map((m) => m.id))
      messages.value = [
        ...data.items.filter((m) => !existing.has(m.id)).map(messageView),
        ...messages.value,
      ]
      before.value = data.nextBefore
      hasMoreMessages.value = data.hasMore
    } catch (e) {
      if (version === epoch) error.value = errorText(e)
    } finally {
      if (version === epoch) loading.value = false
    }
  }

  async function send(
    content: string,
    mode: AgentMode,
    modelTier: 'FLASH' | 'CHEAP' | 'PRO',
    created: (id: string) => void,
  ) {
    if (busy.value) return
    const version = epoch
    sending.value = true
    error.value = ''
    store.reset()
    let id = current.value?.id
    let runId: string | undefined
    try {
      if (!id) {
        const conversation = mock
          ? {
              id: 'conv_mock_01',
              title: 'Mock 演示',
              mode,
              pinned: false,
              createdAt: '',
              updatedAt: '',
            }
          : await conversationsApi.create(mode)
        if (version !== epoch) return
        current.value = conversation
        id = conversation.id
        if (!mock) created(id)
      }
      optimisticId.value = crypto.randomUUID()
      messages.value.push(
        { id: crypto.randomUUID(), role: 'user', text: content },
        { id: optimisticId.value, role: 'assistant', text: '', streaming: true },
      )
      runId = await stream.start({ conversationId: id, content, mode, options: { modelTier } })
      if (version === epoch && activeRun.value?.error) error.value = activeRun.value.error.message
    } catch (e) {
      if (version === epoch && !(e instanceof DOMException && e.name === 'AbortError'))
        error.value = errorText(e)
    } finally {
      if (version === epoch) {
        const target = messages.value.find((m) => m.id === optimisticId.value)
        if (target) {
          target.text = activeRun.value?.text || ''
          target.cards = activeRun.value?.cards
          target.reasoning = activeRun.value?.reasoning
          target.streaming = false
          runId ||= activeRun.value?.runId
          if (runId && (target.cards?.length || target.reasoning))
            runExtras.set(runId, { cards: target.cards, reasoning: target.reasoning })
          target.status =
            activeRun.value?.status === 'CANCELLED'
              ? 'CANCELLED'
              : error.value
                ? 'FAILED'
                : 'COMPLETED'
        }
        optimisticId.value = undefined
        if (id && !mock) {
          try {
            const data = await conversationsApi.messages(id)
            if (version === epoch) {
              // Reconcile from the server without retaining optimistic duplicates.
              const ids = new Set(data.items.map((m) => m.id))
              messages.value = [
                ...messages.value.filter((m) => m.runId && !ids.has(m.id)),
                ...data.items.map(messageView),
              ]
              before.value = data.nextBefore
              hasMoreMessages.value = data.hasMore
            }
          } catch (e) {
            if (version === epoch) {
              needsReload.value = true
              error.value = `记录同步失败：${errorText(e)}。请重新加载会话后确认结果，避免重复发送。`
            }
          }
        }
        if (version === epoch) {
          sending.value = false
          if (id) schedulePoll(id, version)
          void refreshList()
        }
      }
    }
  }

  async function stop() {
    try {
      if (stream.streaming.value) await stream.stop()
      else if (remoteRunId.value) {
        await cancelAgentRun(remoteRunId.value)
        if (current.value) await open(current.value.id)
      }
    } catch (e) {
      error.value = errorText(e)
    }
  }

  async function update(id: string, value: { title?: string; pinned?: boolean }) {
    try {
      const updated = await conversationsApi.update(id, value)
      if (current.value?.id === id) current.value = updated
      await refreshList()
      return true
    } catch (e) {
      listError.value = errorText(e)
      return false
    }
  }
  async function remove(id: string) {
    try {
      await conversationsApi.delete(id)
      await refreshList()
      return true
    } catch (e) {
      listError.value = errorText(e)
      return false
    }
  }

  onScopeDispose(() => {
    ++epoch
    ++listEpoch
    clearTimeout(poll)
    void stream.stop().catch(() => undefined)
  })
  return {
    conversations,
    current,
    messages,
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
    refreshList,
    open,
    older,
    send,
    stop,
    update,
    remove,
  }
}
