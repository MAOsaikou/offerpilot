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
            cardId: 'gaps',
            cardType: 'skill_gap',
            payload: {
              id: 'gaps',
              target: { type: 'OCCUPATION', id: 'occ', title: '工程师' },
              summary: '证据差距',
              citations: [],
              skills: [
                {
                  id: 'gap1',
                  name: 'SQL',
                  required: 5,
                  current: 0,
                  gap: 5,
                  evidence: [],
                  evidenceSources: [
                    {
                      type: 'JOB_MATCH',
                      id: 'match1',
                      jobId: 'job1',
                      label: '岗位匹配 · 工程师',
                      excerpt: 'MISSING：SQL <script>untrusted</script>',
                    },
                    {
                      type: 'INTERVIEW',
                      id: 'iv1',
                      label: '面试复盘 · 工程师',
                      excerpt: '逻辑：25/100',
                    },
                  ],
                },
              ],
            },
          },
          {
            cardId: 'roadmap',
            cardType: 'roadmap',
            payload: {
              weeks: [
                {
                  index: 1,
                  theme: '补齐 SQL',
                  gapIds: ['gap1'],
                  evidenceSources: [
                    {
                      type: 'JOB_MATCH',
                      id: 'match1',
                      jobId: 'job1',
                      label: '岗位匹配 · 工程师',
                      excerpt: 'MISSING：SQL',
                    },
                  ],
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
  it.each(['CHEAP'])(
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
  it('sends selected completed catalog titles to a COACH approval turn and opens the existing panel', async () => {
    const root = await mount('/chat?modelTier=pro')
    await vi.waitFor(() =>
      expect(root.querySelector('section[aria-label="技能差距"]')).not.toBeNull(),
    )
    const gap = root.querySelector('section[aria-label="技能差距"]')!
    expect(gap.querySelector('a[href="/jobs/job1?matchId=match1"]')?.textContent).toContain(
      '查看报告',
    )
    expect(gap.querySelector('a[href="/interviews/iv1"]')?.textContent).toContain('查看复盘')
    expect(gap.textContent).toContain('MISSING：SQL <script>untrusted</script>')
    expect(gap.querySelector('script')).toBeNull()
    expect(root.querySelector('section[aria-label="学习路线图"] a')?.getAttribute('href')).toBe(
      '/jobs/job1?matchId=match1',
    )
    expect(root.querySelector<HTMLSelectElement>('select[aria-label="模型档位"]')!.value).toBe(
      'PRO',
    )
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
      'PRO',
      expect.any(Function),
      {
        coachAction: 'adoptGrowthPlan',
        taskTitles: ['梳理目标岗位的核心技能'],
      },
    )
    expect(root.querySelector('.chat-panel')).not.toBeNull()
    expect(saveCareerTasks).not.toHaveBeenCalled()
    busyControl.set(true)
    await nextTick()
    expect(root.querySelector<HTMLInputElement>('input[type="checkbox"]')!.disabled).toBe(true)
    expect(root.querySelector<HTMLSelectElement>('select[aria-label="模型档位"]')!.disabled).toBe(
      true,
    )
    button.click()
    expect(send).toHaveBeenCalledTimes(1)
  })
})
