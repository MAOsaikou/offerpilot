import { createApp, h, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import InterviewListView from './InterviewListView.vue'
import InterviewRoomView from './InterviewRoomView.vue'
import {
  createInterview,
  getInterview,
  listInterviews,
  saveInterviewAnswer,
  updateInterview,
} from '@/shared/api/interviews'
import { ApiError } from '@/shared/api/client'
import type { InterviewSession } from '@/shared/types/interview'
import { questions } from '@/shared/utils/interviewQuestions'
import { downloadText } from '@/shared/utils/download'
import { useWorkspace } from '@/shared/stores/workspace'
import { installSpeechMocks } from '@/test/speech'

vi.mock('@/shared/api/interviews', () => ({
  createInterview: vi.fn(),
  getInterview: vi.fn(),
  listInterviews: vi.fn(),
  saveInterviewAnswer: vi.fn(),
  updateInterview: vi.fn(),
}))
vi.mock('@/shared/utils/download', () => ({ downloadText: vi.fn() }))
const fixture = (id = 'iv_1'): InterviewSession => ({
  id,
  role: '服务端工程师',
  kind: '项目与经历',
  questionIndex: 0,
  completed: false,
  createdAt: '2026-10-09T00:00:00Z',
  updatedAt: '2026-10-09T00:00:00Z',
  answers: questions.map((questionText, questionIndex) => ({
    questionIndex,
    questionText: questionIndex === 0 ? '服务端题目快照' : questionText,
    answerText: '',
  })),
})
let app: App | undefined, root: HTMLDivElement, cache: QueryClient, router: Router
let server: InterviewSession, pinia: ReturnType<typeof createPinia>
async function mount(path = '/interviews/iv_1') {
  pinia = createPinia()
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/interviews', component: InterviewListView },
      { path: '/interviews/:id', component: InterviewRoomView },
      { path: '/chat', component: { render: () => null } },
      { path: '/career', component: { render: () => null } },
    ],
  })
  await router.push(path)
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(RouterView) })
    .use(router)
    .use(pinia)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
}
function unmount() {
  app?.unmount()
  app = undefined
  cache?.clear()
  root?.remove()
}
function button(text: string) {
  return [...root.querySelectorAll('button')].find((b) => b.textContent?.includes(text))!
}
async function typeAnswer(text: string) {
  const input = root.querySelector('textarea')!
  input.value = text
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}
async function loaded() {
  await vi.waitFor(() => expect(root.querySelector('textarea')).not.toBeNull())
}
function submit() {
  root
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
}
beforeEach(() => {
  localStorage.clear()
  vi.resetAllMocks()
  vi.stubGlobal('SpeechRecognition', undefined)
  vi.stubGlobal('webkitSpeechRecognition', undefined)
  vi.stubGlobal('speechSynthesis', undefined)
  server = fixture()
  vi.mocked(listInterviews).mockImplementation(async () => [structuredClone(server)])
  vi.mocked(createInterview).mockImplementation(async () => structuredClone(server))
  vi.mocked(getInterview).mockImplementation(async () => structuredClone(server))
  vi.mocked(saveInterviewAnswer).mockImplementation(async (_id, index, answerText) => {
    server.answers[index]!.answerText = answerText.trim()
    return structuredClone(server)
  })
  vi.mocked(updateInterview).mockImplementation(async (_id, patch) => {
    server = { ...server, ...patch }
    return structuredClone(server)
  })
})
afterEach(() => {
  unmount()
  vi.unstubAllGlobals()
})

it('mounts without speech APIs and submits through the existing text answer path', async () => {
  await mount()
  await loaded()
  expect(button('开始口述')).toBeUndefined()
  expect(button('朗读题目')).toBeUndefined()
  expect(root.textContent).toContain('Chrome / Edge')
  await typeAnswer('文字回答')
  submit()
  await vi.waitFor(() => expect(server.questionIndex).toBe(1))
  expect(saveInterviewAnswer).toHaveBeenCalledWith('iv_1', 0, '文字回答', expect.any(AbortSignal))
})

it('appends finals to typed answers and saves the last recognition result before advancing', async () => {
  const mock = installSpeechMocks()
  await mount()
  await loaded()
  expect(mock.synthesis.speak).not.toHaveBeenCalled()
  await typeAnswer('手动背景')
  button('开始口述').click()
  mock.latest().result([{ text: '正在说', final: false }])
  await nextTick()
  expect(root.textContent).toContain('正在说')
  expect(root.querySelector('textarea')!.value).toBe('手动背景')
  mock.latest().result([{ text: '语音行动', final: true }])
  await nextTick()
  expect(root.querySelector('textarea')!.value).toBe('手动背景\n语音行动')
  const active = mock.latest()
  active.stop.mockImplementation(() => {})
  submit()
  await nextTick()
  expect(saveInterviewAnswer).not.toHaveBeenCalled()
  active.result(
    [
      { text: '语音行动', final: true },
      { text: '最后的结果', final: true },
    ],
    1,
  )
  active.onend?.()
  await vi.waitFor(() => expect(server.questionIndex).toBe(1))
  expect(server.answers[0]!.answerText).toBe('手动背景\n语音行动\n最后的结果')
  active.result([{ text: '迟到的文本', final: true }])
  await nextTick()
  expect(root.querySelector('textarea')!.value).toBe('')
})

it('keeps text entry and save usable after microphone permission is denied', async () => {
  const mock = installSpeechMocks()
  await mount()
  await loaded()
  button('开始口述').click()
  mock.latest().onerror?.({ error: 'not-allowed' })
  await nextTick()
  expect(root.textContent).toContain('权限被拒绝')
  expect(button('开始口述').disabled).toBe(true)
  expect(root.querySelector('textarea')!.disabled).toBe(false)
  button('朗读题目').click()
  await nextTick()
  expect(root.textContent).toContain('权限被拒绝')
  await typeAnswer('拒绝权限后文字回答')
  button('保存草稿').click()
  await vi.waitFor(() => expect(server.answers[0]!.answerText).toBe('拒绝权限后文字回答'))
})

it('enforces the textarea limit for dictation and releases the microphone on leave', async () => {
  const mock = installSpeechMocks()
  await mount()
  await loaded()
  await typeAnswer('字'.repeat(11997))
  button('开始口述').click()
  mock.latest().result([{ text: '结果很多字', final: true }])
  await nextTick()
  expect(root.querySelector('textarea')!.value).toBe('字'.repeat(11997) + '\n结果')
  expect(root.querySelector('textarea')!.value.length).toBe(12000)
  expect(button('开始口述').disabled).toBe(true)
  await router.push('/interviews')
  expect(server.answers[0]!.answerText.length).toBe(12000)
  expect(mock.synthesis.cancel).toHaveBeenCalled()
})

it('persists opt-in autoplay, reads changed questions and cleans up on unmount', async () => {
  const mock = installSpeechMocks()
  await mount()
  await loaded()
  button('朗读题目').click()
  expect(mock.synthesis.speak.mock.calls[0]![0].text).toBe('服务端题目快照')
  const checkbox = root.querySelector<HTMLInputElement>('input[type="checkbox"]')!
  checkbox.checked = true
  checkbox.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
  expect(localStorage.getItem('offerpilot.voiceAutoPlay')).toBe('true')
  await typeAnswer('第一题')
  submit()
  await vi.waitFor(() => expect(mock.synthesis.speak).toHaveBeenCalledTimes(2))
  expect(mock.synthesis.speak.mock.calls[1]![0].text).toBe(server.answers[1]!.questionText)
  button('停止朗读').click()
  button('开始口述').click()
  const active = mock.latest()
  unmount()
  expect(active.abort).toHaveBeenCalledOnce()
  expect(mock.synthesis.cancel).toHaveBeenCalled()
})

it('creates only server sessions and exports unchanged legacy records', async () => {
  await mount('/interviews')
  const workspace = useWorkspace(pinia)
  workspace.state.interviews.push({
    id: 'legacy',
    role: '旧岗位',
    kind: '表达与结构',
    answers: ['本机回答'],
    question: 0,
    completed: false,
    date: '昨天',
  })
  await vi.waitFor(() => expect(root.textContent).toContain('服务端工程师'))
  expect(root.textContent).toContain('本机旧练习')
  button('导出旧练习').click()
  expect(downloadText).toHaveBeenCalledWith(
    '旧岗位-面试复盘.md',
    expect.stringContaining('本机回答'),
  )
  expect(vi.mocked(downloadText).mock.calls[0]![1]).toContain(questions[0])
  const input = root.querySelector('input')!
  input.value = '新岗位'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  submit()
  await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/interviews/iv_1'))
  expect(createInterview).toHaveBeenCalledWith(
    { role: '新岗位', kind: '项目与经历' },
    expect.any(AbortSignal),
  )
  expect(workspace.state.interviews).toHaveLength(1)
})
it('saves drafts and previous/next progress, reloads and exports stored question snapshots', async () => {
  await mount()
  await loaded()
  expect(root.textContent).toContain('服务端题目快照')
  await typeAnswer('第一题回答')
  button('保存草稿').click()
  await vi.waitFor(() => expect(root.textContent).toContain('已保存到服务端'))
  expect(server.answers[0]!.answerText).toBe('第一题回答')
  submit()
  await vi.waitFor(() => expect(server.questionIndex).toBe(1))
  await vi.waitFor(() => expect(root.querySelector('textarea')!.value).toBe(''))
  await typeAnswer('第二题草稿')
  button('上一题').click()
  await vi.waitFor(() => expect(root.querySelector('textarea')!.value).toBe('第一题回答'))
  expect(server.answers[1]!.answerText).toBe('第二题草稿')
  unmount()
  await mount()
  await loaded()
  expect(root.querySelector('textarea')!.value).toBe('第一题回答')
  button('导出复盘').click()
  await vi.waitFor(() =>
    expect(downloadText).toHaveBeenCalledWith(
      '服务端工程师-面试复盘.md',
      expect.stringContaining('## 服务端题目快照\n\n第一题回答'),
    ),
  )
})
it('keeps drafts and does not advance on failed saves, then permits retry', async () => {
  await mount()
  await loaded()
  await typeAnswer('离线草稿')
  vi.mocked(saveInterviewAnswer).mockRejectedValueOnce(new Error('保存失败'))
  submit()
  await vi.waitFor(() => expect(root.textContent).toContain('保存失败'))
  expect(root.querySelector('textarea')!.value).toBe('离线草稿')
  expect(updateInterview).not.toHaveBeenCalled()
  expect(server.questionIndex).toBe(0)
  submit()
  await vi.waitFor(() => expect(server.questionIndex).toBe(1))
  expect(server.answers[0]!.answerText).toBe('离线草稿')
})
it('retries failed progress while retaining the saved answer', async () => {
  await mount()
  await loaded()
  await typeAnswer('已保存的回答')
  vi.mocked(updateInterview).mockRejectedValueOnce(new Error('进度失败'))
  submit()
  await vi.waitFor(() => expect(root.textContent).toContain('进度失败'))
  expect(root.querySelector('textarea')!.value).toBe('已保存的回答')
  expect(server.questionIndex).toBe(0)
  submit()
  await vi.waitFor(() => expect(server.questionIndex).toBe(1))
  expect(saveInterviewAnswer).toHaveBeenCalledTimes(1)
})
it('completes after saving the final answer and restores the review', async () => {
  server.questionIndex = 4
  await mount()
  await loaded()
  await typeAnswer('学习计划')
  submit()
  await vi.waitFor(() => expect(root.textContent).toContain('已完成全部 5 题'))
  expect(server.completed).toBe(true)
  expect(server.answers[4]!.answerText).toBe('学习计划')
  expect(root.querySelector('textarea')).toBeNull()
  unmount()
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('学习计划'))
  button('导出复盘').click()
  await vi.waitFor(() =>
    expect(downloadText).toHaveBeenCalledWith(
      '服务端工程师-面试复盘.md',
      expect.stringContaining('学习计划'),
    ),
  )
})
it('saves before navigation and blocks leaving when saving fails', async () => {
  await mount()
  await loaded()
  await typeAnswer('离开前草稿')
  vi.mocked(saveInterviewAnswer).mockRejectedValueOnce(new Error('网络断开'))
  await router.push('/interviews')
  expect(router.currentRoute.value.path).toBe('/interviews/iv_1')
  expect(root.querySelector('textarea')!.value).toBe('离开前草稿')
  await router.push('/interviews')
  expect(server.answers[0]!.answerText).toBe('离开前草稿')
  expect(router.currentRoute.value.path).toBe('/interviews')
})
it('ignores late responses from a previous session', async () => {
  let resolve!: (value: InterviewSession) => void
  vi.mocked(getInterview).mockImplementationOnce(
    () =>
      new Promise((done) => {
        resolve = done
      }),
  )
  await mount()
  expect(root.textContent).toContain('正在加载练习')
  server = { ...fixture('iv_2'), role: '第二场' }
  await router.push('/interviews/iv_2')
  await loaded()
  resolve({ ...fixture(), role: '迟到的第一场' })
  await nextTick()
  await nextTick()
  expect(root.textContent).toContain('第二场')
  expect(root.textContent).not.toContain('迟到的第一场')
})
it('distinguishes 404 and retryable loading failures', async () => {
  vi.mocked(getInterview).mockRejectedValueOnce(new Error('加载失败'))
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('练习加载失败'))
  button('重新加载').click()
  await loaded()
  unmount()
  vi.mocked(getInterview).mockRejectedValueOnce(
    new ApiError('不存在', { code: 30701, status: 404 }),
  )
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('未找到这场练习'))
  expect(button('重新加载')).toBeUndefined()
})
it('retries list failures and leaves local data untouched after creation failure', async () => {
  vi.mocked(listInterviews).mockRejectedValueOnce(new Error('记录加载失败'))
  await mount('/interviews')
  await vi.waitFor(() => expect(root.textContent).toContain('记录加载失败'))
  expect(root.textContent).not.toContain('第一场练习，从自我介绍开始')
  button('重新加载').click()
  await vi.waitFor(() => expect(root.textContent).toContain('服务端工程师'))
  const input = root.querySelector('input')!
  input.value = '工程师'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  vi.mocked(createInterview).mockRejectedValueOnce(new Error('创建失败'))
  submit()
  await vi.waitFor(() => expect(root.textContent).toContain('创建失败'))
  expect(useWorkspace(pinia).state.interviews).toHaveLength(0)
  expect(router.currentRoute.value.path).toBe('/interviews')
})
