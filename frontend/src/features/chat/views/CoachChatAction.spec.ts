import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import ChatPage from '@/app/views/ChatPage.vue'
import { saveCareerTasks } from '@/shared/api/career'

const busyControl = vi.hoisted(() => ({ set: (_value: boolean) => {} }))
const send = vi.hoisted(() => vi.fn().mockResolvedValue(undefined))
vi.mock('@/shared/api/career', () => ({ saveCareerTasks: vi.fn() }))
vi.mock('../composables/useConversationChat', async () => {
  const { ref } = await import('vue')
  const state = {
    conversations: ref([]),
    current: ref(null),
    loading: ref(false),
    sending: ref(false),
    busy: ref(false),
    listLoading: ref(false),
    error: ref(''),
    listError: ref(''),
    needsReload: ref(false),
    hasMoreConversations: ref(false),
    hasMoreMessages: ref(false),
    activeRun: ref(null),
    remoteRunId: ref(null),
    messages: ref([]),
    viewMessages: ref([
      {
        id: 'message',
        role: 'assistant',
        text: '学习路线',
        status: 'COMPLETED',
        cards: [
          {
            cardId: 'roadmap',
            cardType: 'roadmap',
            payload: {
              weeks: [
                {
                  index: 1,
                  theme: '补齐 SQL',
                  items: [
                    {
                      id: 'task',
                      title: '梳理目标岗位的核心技能',
                      hours: 2,
                      resources: ['岗位职责'],
                      dueAt: null,
                      done: false,
                    },
                  ],
                },
              ],
              milestones: [],
            },
          },
        ],
      },
    ]),
    send,
    open: vi.fn().mockResolvedValue(undefined),
    refreshList: vi.fn().mockResolvedValue(undefined),
    stop: vi.fn(),
    older: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  }
  busyControl.set = (value) => {
    state.busy.value = value
  }
  return { useConversationChat: () => state }
})
let app: App | undefined
beforeEach(() => {
  send.mockClear()
  busyControl.set(false)
})
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
})
async function mount(path = '/chat') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/chat/:conversationId?', component: ChatPage }],
  })
  await router.push(path)
  await router.isReady()
  const root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(ChatPage) }).use(router)
  app.mount(root)
  await vi.waitFor(() => expect(root.querySelector('input[type="checkbox"]')).not.toBeNull())
  return root
}
describe('coach roadmap chat confirmation path', () => {
  it.each(['CHEAP', 'economy', '经济', 'BALANCED', 'unknown'])(
    'offers exactly 快速 / 深度 and normalizes legacy URL tier %s',
    async (tier) => {
      const root = await mount(`/chat?modelTier=${encodeURIComponent(tier)}`)
      const select = root.querySelector<HTMLSelectElement>('select[aria-label="模型档位"]')!
      expect([...select.options].map((option) => [option.value, option.text])).toEqual([
        ['FLASH', '快速'],
        ['PRO', '深度'],
      ])
      expect(select.value).toBe('FLASH')
      root.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
      await nextTick()
      ;[...root.querySelectorAll('button')]
        .find((button) => button.textContent?.trim() === '同步到成长任务')!
        .click()
      await nextTick()
      expect(send).toHaveBeenCalledWith(
        expect.any(String),
        'COACH',
        'FLASH',
        expect.any(Function),
        expect.any(Object),
      )
    },
  )
  it('selects 深度 from the URL and disables model selection while busy', async () => {
    busyControl.set(true)
    const root = await mount('/chat?modelTier=pro')
    const select = root.querySelector<HTMLSelectElement>('select[aria-label="模型档位"]')!
    expect(select.value).toBe('PRO')
    expect(select.disabled).toBe(true)
  })
  it('sends selected completed catalog titles to a COACH approval turn and opens the existing panel', async () => {
    const root = await mount()
    root.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click()
    await nextTick()
    const button = [...root.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === '同步到成长任务',
    )!
    button.click()
    await nextTick()
    expect(send).toHaveBeenCalledExactlyOnceWith(
      '同步成长任务：我确认已完成「梳理目标岗位的核心技能」',
      'COACH',
      'FLASH',
      expect.any(Function),
      {
        coachAction: 'adoptGrowthPlan',
        taskTitles: ['梳理目标岗位的核心技能'],
      },
    )
    expect(root.querySelector('.chat-panel')).not.toBeNull()
    expect(saveCareerTasks).not.toHaveBeenCalled()
  })
  it('disables roadmap selection while the conversation is busy', async () => {
    busyControl.set(true)
    const root = await mount()
    expect(root.querySelector<HTMLInputElement>('input[type="checkbox"]')!.disabled).toBe(true)
    const button = [...root.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === '同步到成长任务',
    )!
    button.click()
    expect(send).not.toHaveBeenCalled()
  })
})
