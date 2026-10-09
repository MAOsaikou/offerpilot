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
