import { afterEach, expect, it, vi } from 'vitest'
import { createApp, nextTick, ref, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useConversationChat } from '../composables/useConversationChat'
import ChatPage from '@/app/views/ChatPage.vue'

vi.mock('../composables/useConversationChat', () => ({ useConversationChat: vi.fn() }))
let app: App | undefined
afterEach(() => {
  app?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
})
async function mountMatch() {
  const send = vi.fn().mockResolvedValue(undefined)
  const current = ref<{ id: string; mode: string } | null>(null)
  const chat = {
    conversations: ref([]),
    current,
    messages: ref([]),
    viewMessages: ref([]),
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
    refreshList: vi.fn(),
    open: vi.fn(async () => {
      current.value = { id: 'conv1', mode: 'JOB' }
    }),
    send,
    stop: vi.fn(),
  }
  vi.mocked(useConversationChat).mockReturnValue(
    chat as unknown as ReturnType<typeof useConversationChat>,
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/chat/:conversationId?', component: ChatPage }],
  })
  await router.push(
    '/chat/conv1?mode=JOB&jobAction=match&jobId=job1&resumeId=resume1&resumeVersionId=version1&prompt=请匹配',
  )
  const root = document.createElement('div')
  document.body.append(root)
  app = createApp(ChatPage).use(router)
  app.mount(root)
  await vi.waitFor(() =>
    expect(root.querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('请匹配'),
  )
  return { root, router, send }
}
async function submit(root: HTMLElement, content: string) {
  const field = root.querySelector<HTMLTextAreaElement>('textarea')!
  field.value = content
  field.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  root.querySelector<HTMLButtonElement>('[aria-label="发送"]')!.click()
  await nextTick()
}
it('selected matching sends an explicit action once and leaves follow-up resources without an action', async () => {
  const { root, router, send } = await mountMatch()
  expect(root.textContent).toContain('本次发送将匹配所选岗位与简历')
  await submit(root, '请匹配这个岗位与我的简历')
  await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(1))
  expect(send.mock.calls[0]).toEqual([
    '请匹配这个岗位与我的简历',
    'JOB',
    'FLASH',
    expect.any(Function),
    {
      jobAction: 'match',
      jobId: 'job1',
      resumeId: 'resume1',
      resumeVersionId: 'version1',
    },
  ])
  expect(router.currentRoute.value.query.jobAction).toBeUndefined()
  expect(root.textContent).not.toContain('本次发送将匹配')
  await submit(root, '再搜索北京的岗位')
  await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(2))
  expect(send.mock.calls[1]![4]).toEqual({
    jobId: 'job1',
    resumeId: 'resume1',
    resumeVersionId: 'version1',
  })
})
it('canceling the selected action preserves a normal question without matching', async () => {
  const { root, send } = await mountMatch()
  ;[...root.querySelectorAll('button')]
    .find((b) => b.textContent?.trim() === '改为普通提问')!
    .click()
  await vi.waitFor(() => expect(root.textContent).not.toContain('本次发送将匹配'))
  await submit(root, '这个岗位怎么样')
  await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(1))
  expect(send.mock.calls[0]![4]).not.toHaveProperty('jobAction')
})
it('waiting for action consumption cannot submit a second request', async () => {
  const { root, router, send } = await mountMatch()
  let release!: () => void
  const navigation = new Promise<void>((resolve) => {
    release = resolve
  })
  router.beforeEach(async (to) => {
    if (!to.query.jobAction) await navigation
  })
  await submit(root, '请匹配')
  await submit(root, '第二次发送')
  expect(send).not.toHaveBeenCalled()
  release()
  await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(1))
  expect(send.mock.calls[0]![0]).toBe('请匹配')
  expect(send.mock.calls[0]![4]).toHaveProperty('jobAction', 'match')
})
it('switching experts never sends the pending matching action', async () => {
  const { root, router, send } = await mountMatch()
  const mode = root.querySelector<HTMLSelectElement>('[aria-label="对话模式"]')!
  mode.value = 'RESUME'
  mode.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
  await submit(root, '请改写简历')
  await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(1))
  expect(send.mock.calls[0]![1]).toBe('RESUME')
  expect(send.mock.calls[0]![4]).not.toHaveProperty('jobAction')
  expect(router.currentRoute.value.query.jobAction).toBeUndefined()
})
