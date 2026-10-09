import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, ref, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useConversationChat } from '@/features/chat/composables/useConversationChat'
import ChatPage from './ChatPage.vue'

vi.mock('@/features/chat/composables/useConversationChat', () => ({ useConversationChat: vi.fn() }))
let app: App | undefined
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

it('推荐卡片选择发送结构化职业聊天请求并打开现有审批面板', async () => {
  const send = vi.fn().mockResolvedValue(undefined)
  const busy = ref(false)
  const chat = {
    conversations: ref([]),
    current: ref({ id: 'conv1', title: '职业探索', mode: 'AUTO' }),
    messages: ref([]),
    viewMessages: ref([
      {
        id: 'msg1',
        role: 'assistant',
        text: '推荐结果',
        cards: [
          {
            cardId: 'card1',
            cardType: 'career_recommendation',
            payload: {
              hollandCode: 'IRC',
              interestsUsed: ['技术研发'],
              recommendations: [
                {
                  occupationId: 'occ1',
                  name: '软件工程师',
                  family: '技术',
                  hollandCode: 'IRC',
                  similarity: 1,
                  reasons: ['共同兴趣 IRC'],
                },
              ],
            },
          },
        ],
      },
    ]),
    loading: ref(false),
    sending: ref(false),
    busy,
    listLoading: ref(false),
    error: ref(''),
    listError: ref(''),
    needsReload: ref(false),
    hasMoreConversations: ref(false),
    hasMoreMessages: ref(false),
    activeRun: ref(null),
    remoteRunId: ref(null),
    refreshList: vi.fn(),
    open: vi.fn(),
    older: vi.fn(),
    send,
    stop: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  }
  vi.mocked(useConversationChat).mockReturnValue(
    chat as unknown as ReturnType<typeof useConversationChat>,
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/chat/:conversationId?', component: ChatPage }],
  })
  await router.push('/chat/conv1')
  const root = document.createElement('div')
  document.body.append(root)
  app = createApp(ChatPage).use(router)
  app.mount(root)
  await vi.waitFor(() =>
    expect(root.querySelector('[aria-label="将软件工程师设为目标"]')).not.toBeNull(),
  )
  const adopt = root.querySelector<HTMLButtonElement>('[aria-label="将软件工程师设为目标"]')!
  adopt.click()
  await nextTick()
  expect(send).toHaveBeenCalledExactlyOnceWith(
    '请将「软件工程师」设为目标职业',
    'CAREER_DISCOVERY',
    'FLASH',
    expect.any(Function),
    { careerAction: 'setCareerTarget', occupationId: 'occ1', name: '软件工程师' },
  )
  expect(root.querySelector('.chat-panel')).not.toBeNull()
  busy.value = true
  await nextTick()
  expect(adopt.disabled).toBe(true)
  adopt.click()
  expect(send).toHaveBeenCalledTimes(1)
})
