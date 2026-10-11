import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, ref, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useConversationChat } from '@/features/chat/composables/useConversationChat'
import ChatPage from './ChatPage.vue'
import CardRenderer from '@/widgets/CardRenderer/CardRenderer.vue'
import { cardRegistry } from '@/widgets/CardRenderer/registry'
import PageBoundary from '@/app/layouts/PageBoundary.vue'
import { exportJobEvalMarkdown } from '@/shared/api/jobs'
import { saveLogin } from '@/shared/utils/authSession'
vi.mock('@/shared/api/jobs', () => ({
  exportJobEvalMarkdown: vi.fn().mockResolvedValue(undefined),
}))

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
  saveLogin({
    accessToken: 'fixture',
    expiresIn: 43200,
    user: { id: 'user', email: 'user@example.com', nickname: '用户', roles: ['USER'] },
  })
  await nextTick()
  expect(root.textContent).toContain('对话保存在当前账号')
  expect(root.textContent).not.toContain('访客身份保存在这个浏览器')
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

async function mountDiff(payload: Record<string, unknown>, query = '') {
  const send = vi.fn().mockResolvedValue(undefined)
  const busy = ref(false)
  const error = ref('')
  const chat = {
    conversations: ref([]),
    current: ref({ id: 'conv1', title: '简历改写', mode: 'AUTO' }),
    messages: ref([]),
    viewMessages: ref([
      {
        id: 'msg1',
        role: 'assistant',
        text: '改写建议',
        cards: [{ cardId: 'card1', cardType: 'resume_diff', payload }],
      },
    ]),
    loading: ref(false),
    sending: ref(false),
    busy,
    listLoading: ref(false),
    error,
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
  await router.push('/chat/conv1' + query)
  const root = document.createElement('div')
  document.body.append(root)
  app = createApp(ChatPage).use(router)
  app.mount(root)
  await vi.waitFor(() => expect(root.querySelector('[aria-label="采纳建议 1"]')).not.toBeNull())
  return { root, send, busy, error }
}
function diffPayload() {
  return {
    resumeId: 'rs1',
    resumeVersionId: 'rv1',
    reportId: 'report1',
    batchId: 'pb1',
    patches: [
      {
        id: 'p1',
        op: 'replace',
        path: '/summary',
        from: '原文',
        value: '建议一',
        reason: '理由',
        needsUserConfirmation: false,
      },
      {
        id: 'p2',
        op: 'replace',
        path: '/experience/0/bullets/0',
        from: '经历原文',
        value: '建议二',
        reason: '理由',
        needsUserConfirmation: true,
        confirmationHint: '请核对事实',
      },
    ],
    stats: { added: 0, replaced: 2, removed: 0 },
  }
}
it('聊天改写卡片选择、编辑和事实确认通过结构化聊天启动审批，忙碌时不能重发', async () => {
  const { root, send, busy } = await mountDiff(diffPayload())
  root.querySelector<HTMLButtonElement>('[aria-label="采纳建议 1"]')!.click()
  root.querySelector<HTMLButtonElement>('[aria-label="采纳建议 2"]')!.click()
  await nextTick()
  const edit = root.querySelector<HTMLTextAreaElement>('[aria-label="编辑建议 2"]')!
  edit.value = '用户核对的经历'
  edit.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  const apply = [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
    b.textContent?.includes('审批采纳所选建议'),
  )!
  expect(apply.disabled).toBe(true)
  const confirm = root.querySelector<HTMLInputElement>('input[type="checkbox"]')!
  confirm.checked = true
  confirm.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
  expect(apply.disabled).toBe(false)
  apply.click()
  await nextTick()
  expect(send).toHaveBeenCalledExactlyOnceWith(
    '请采纳所选的简历改写建议',
    'RESUME',
    'FLASH',
    expect.any(Function),
    {
      resumeAction: 'applyPatches',
      resumeId: 'rs1',
      resumeVersionId: 'rv1',
      batchId: 'pb1',
      accepted: ['p1'],
      edited: { p2: { value: '用户核对的经历' } },
      confirmed: ['p2'],
    },
  )
  expect(root.querySelector('.chat-panel')).not.toBeNull()
  busy.value = true
  await nextTick()
  expect(apply.disabled).toBe(true)
  apply.click()
  expect(send).toHaveBeenCalledTimes(1)
})
it('旧改写卡片可使用页面关联的简历 id，卡片版本优先于路由', async () => {
  const payload: Record<string, unknown> = diffPayload()
  delete payload.resumeId
  const { root, send } = await mountDiff(payload, '?resumeId=rs1&resumeVersionId=other_version')
  root.querySelector<HTMLButtonElement>('[aria-label="采纳建议 1"]')!.click()
  await nextTick()
  ;[...root.querySelectorAll<HTMLButtonElement>('button')]
    .find((b) => b.textContent?.includes('审批采纳所选建议'))!
    .click()
  await nextTick()
  expect(send).toHaveBeenCalledWith(
    expect.any(String),
    'RESUME',
    'FLASH',
    expect.any(Function),
    expect.objectContaining({
      resumeId: 'rs1',
      resumeVersionId: 'rv1',
      resumeAction: 'applyPatches',
    }),
  )
})
it('没有可信简历关联的卡片采纳失败并显示错误，不发出写请求', async () => {
  const payload: Record<string, unknown> = diffPayload()
  delete payload.resumeId
  const { root, send, error } = await mountDiff(payload)
  root.querySelector<HTMLButtonElement>('[aria-label="采纳建议 1"]')!.click()
  await nextTick()
  ;[...root.querySelectorAll<HTMLButtonElement>('button')]
    .find((b) => b.textContent?.includes('审批采纳所选建议'))!
    .click()
  await nextTick()
  expect(send).not.toHaveBeenCalled()
  expect(error.value).toContain('无法关联')
  expect(root.querySelector('[role="alert"]')?.textContent).toContain('无法关联')
})

it('renders current match and STAR card snapshots without fallback or visible resource ids', async () => {
  const match = {
    id: 'match_internal',
    jobId: 'job_internal',
    resumeTitle: '保存简历',
    resumeVersionNo: 2,
    score: 50,
    summary: '三项要求已核对',
    requirements: ['MET', 'PARTIAL', 'MISSING'].map((status) => ({
      status,
      requirement: '要求 ' + status,
      jdQuote: '岗位原文',
      resumeQuote: status === 'MISSING' ? '' : '真实项目',
      reason: '已核对原文',
      suggestion: '补充真实证据',
    })),
  }
  const evaluation = {
    matchId: 'match_internal',
    overall: 60,
    grade: 'E',
    summary: '规则分',
    note: '不是录用预测',
    dimensions: [{ code: 'A', name: '要求覆盖', score: 50, reason: '明确、部分、未体现' }],
    advice: [],
  }
  const cards = [
    ['job_match', match],
    ['job_eval_report', evaluation],
    [
      'star_story_citations',
      {
        count: 1,
        items: [
          {
            id: 'star_internal',
            title: '缓存项目',
            role: '后端开发',
            starCount: 4,
            excerpt: '<script>原始故事</script>',
          },
        ],
      },
    ],
    [
      'interviewer_state',
      {
        state: 'ASKING',
        sessionId: 'iv_internal',
        questionIndex: 4,
        total: 5,
        question: '如何学习？',
      },
    ],
  ] as const
  const { h } = await import('vue')
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
  })
  await router.push('/chat')
  const root = document.createElement('div')
  document.body.append(root)
  app = createApp({
    render: () =>
      h(
        'div',
        cards.map(([cardType, payload]) => h(CardRenderer, { cardType, payload })),
      ),
  }).use(router)
  app.mount(root)
  await vi.waitFor(() => {
    expect(root.textContent).toContain('缓存项目')
    expect(root.textContent).toContain('部分体现')
    expect(root.textContent).toContain('5 / 5')
  })
  expect(root.textContent).toContain('部分体现')
  expect(root.textContent).toContain('简历未体现')
  expect(root.textContent).toContain('5 / 5')
  expect(root.textContent).not.toMatch(
    /暂不支持|查看原始数据|match_internal|job_internal|star_internal|iv_internal/,
  )
  expect(root.querySelector('script')).toBeNull()
  expect(root.querySelector('a[href="/jobs/job_internal?matchId=match_internal"]')).not.toBeNull()
  ;[...root.querySelectorAll('button')].find((b) => b.textContent?.includes('导出评估.md'))!.click()
  await vi.waitFor(() =>
    expect(exportJobEvalMarkdown).toHaveBeenCalledWith('match_internal', '岗位-岗位评估.md'),
  )
})

it('slow and failed card imports stay inside the card without unmounting the active chat', async () => {
  vi.useFakeTimers()
  let resolve!: (component: ReturnType<typeof defineComponent>) => void
  const { defineComponent, h } = await import('vue')
  const slow = new Promise<ReturnType<typeof defineComponent>>((done) => {
    resolve = done
  })
  let attempts = 0
  cardRegistry.campus_calendar = () => slow
  cardRegistry.career_compare = async () => {
    if (++attempts === 1) throw new Error('network failed')
    return defineComponent(() => () => h('article', '重试后的卡片'))
  }
  const unmounted = vi.fn()
  const { onUnmounted } = await import('vue')
  const chat = defineComponent(() => {
    onUnmounted(unmounted)
    return () =>
      h('div', [
        h('p', '正在接收回复'),
        h(CardRenderer, { cardType: 'campus_calendar', payload: {} }),
        h(CardRenderer, { cardType: 'career_compare', payload: {} }),
      ])
  })
  const root = document.createElement('div')
  document.body.append(root)
  app = createApp(
    defineComponent(() => () => h(PageBoundary, { resetKey: '/chat' }, { default: () => h(chat) })),
  )
  app.mount(root)
  try {
    await vi.advanceTimersByTimeAsync(15000)
    expect(root.textContent).toContain('正在接收回复')
    expect(root.textContent).toContain('这张卡片暂时无法显示')
    expect(root.textContent).not.toContain('页面暂时无法显示')
    expect(unmounted).not.toHaveBeenCalled()
    root.querySelector<HTMLButtonElement>('[aria-label="卡片加载失败"] button')!.click()
    resolve(defineComponent(() => () => h('article', '慢网卡片加载完成')))
    await vi.advanceTimersByTimeAsync(200)
    expect(root.textContent).toContain('慢网卡片加载完成')
    expect(root.textContent).toContain('重试后的卡片')
    expect(root.querySelector('[aria-label="卡片加载失败"]')).toBeNull()
    expect(unmounted).not.toHaveBeenCalled()
  } finally {
    delete cardRegistry.campus_calendar
    delete cardRegistry.career_compare
    vi.useRealTimers()
  }
})
