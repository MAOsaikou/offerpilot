import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useConversationChat } from './useConversationChat'
import { conversationsApi } from '@/shared/api/conversations'
import type { Conversation, MessagePage } from '@/shared/types/conversation'

const startMock = vi.hoisted(() => vi.fn())

vi.mock('@/shared/api/conversations', () => ({
  conversationsApi: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    messages: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
  },
}))
vi.mock('@/shared/composables/useAgentStream', () => ({
  useAgentStream: () => ({
    streaming: ref(false),
    start: startMock,
    stop: vi.fn().mockResolvedValue(undefined),
  }),
}))
const conv = (id: string): Conversation => ({
  id,
  title: id,
  mode: 'AUTO',
  pinned: false,
  createdAt: '',
  updatedAt: '',
})
const messagePage = (id: string, hasMore = false): MessagePage => ({
  items: [
    {
      id,
      conversationId: 'conv',
      role: 'ASSISTANT',
      plainText: id,
      status: 'COMPLETED',
      runId: 'run',
      model: 'test',
      createdAt: '',
    },
  ],
  hasMore,
  nextBefore: hasMore ? id : undefined,
})
let scope: ReturnType<typeof effectScope>
beforeEach(() => {
  vi.resetAllMocks()
  setActivePinia(createPinia())
  scope = effectScope()
  vi.mocked(conversationsApi.list).mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
  startMock.mockResolvedValue('run')
})
afterEach(() => scope.stop())
describe('持久会话状态', () => {
  it('发送结果无法同步时阻止再次发送，重新加载后恢复', async () => {
    vi.mocked(conversationsApi.create).mockResolvedValue(conv('created'))
    vi.mocked(conversationsApi.messages).mockRejectedValueOnce(new Error('网络中断'))
    const chat = scope.run(useConversationChat)!
    await chat.send('hello', 'AUTO', 'CHEAP', vi.fn())
    expect(chat.needsReload.value).toBe(true)
    expect(chat.busy.value).toBe(true)
    vi.mocked(conversationsApi.get).mockResolvedValue(conv('created'))
    vi.mocked(conversationsApi.messages).mockResolvedValue(messagePage('saved'))
    await chat.open('created')
    expect(chat.needsReload.value).toBe(false)
    expect(chat.busy.value).toBe(false)
  })
  it('切换时丢弃旧会话的迟到响应', async () => {
    let resolveA!: (value: Conversation) => void
    vi.mocked(conversationsApi.get).mockImplementation((id) =>
      id === 'a'
        ? new Promise((resolve) => {
            resolveA = resolve
          })
        : Promise.resolve(conv(id)),
    )
    vi.mocked(conversationsApi.messages).mockImplementation((id) =>
      Promise.resolve(messagePage(id)),
    )
    const chat = scope.run(useConversationChat)!
    const a = chat.open('a')
    await chat.open('b')
    resolveA(conv('a'))
    await a
    expect(chat.current.value?.id).toBe('b')
    expect(chat.messages.value[0]?.text).toBe('b')
  })
  it('加载失败清空旧记录并提示，防止向错误会话发送', async () => {
    vi.mocked(conversationsApi.get).mockResolvedValue(conv('a'))
    vi.mocked(conversationsApi.messages).mockResolvedValue(messagePage('a'))
    const chat = scope.run(useConversationChat)!
    await chat.open('a')
    vi.mocked(conversationsApi.get).mockRejectedValue(new Error('会话不存在'))
    await chat.open('b')
    expect(chat.current.value).toBeUndefined()
    expect(chat.messages.value).toEqual([])
    expect(chat.error.value).toBe('会话不存在')
  })
  it('更早消息按时间追加到前方，不重复已有消息', async () => {
    vi.mocked(conversationsApi.get).mockResolvedValue(conv('a'))
    vi.mocked(conversationsApi.messages)
      .mockResolvedValueOnce(messagePage('new', true))
      .mockResolvedValueOnce(messagePage('old'))
    const chat = scope.run(useConversationChat)!
    await chat.open('a')
    await chat.older()
    expect(chat.messages.value.map((m) => m.id)).toEqual(['old', 'new'])
    expect(chat.hasMoreMessages.value).toBe(false)
  })
  it('发送后以持久记录替换临时消息，避免刷新或失败时产生重复气泡', async () => {
    vi.mocked(conversationsApi.create).mockResolvedValue(conv('created'))
    vi.mocked(conversationsApi.messages).mockResolvedValue(messagePage('saved'))
    const chat = scope.run(useConversationChat)!
    const created = vi.fn()
    await chat.send('hello', 'AUTO', 'CHEAP', created)
    expect(created).toHaveBeenCalledWith('created')
    expect(chat.messages.value.map((m) => m.id)).toEqual(['saved'])
    expect(chat.sending.value).toBe(false)
  })
  it('发送后服务端改写的标题同步到当前会话，无需刷新页面', async () => {
    vi.mocked(conversationsApi.create).mockResolvedValue({ ...conv('created'), title: '新对话' })
    vi.mocked(conversationsApi.messages).mockResolvedValue(messagePage('saved'))
    vi.mocked(conversationsApi.list).mockResolvedValue({
      items: [{ ...conv('created'), title: '后端校招怎么选' }],
      page: 1,
      size: 20,
      total: 1,
    })
    const chat = scope.run(useConversationChat)!
    await chat.send('后端校招怎么选', 'AUTO', 'CHEAP', vi.fn())
    await vi.waitFor(() => expect(chat.current.value?.title).toBe('后端校招怎么选'))
  })
})
