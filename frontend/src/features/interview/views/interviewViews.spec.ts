import { setLanguage } from '@/shared/utils/language'
vi.mock('@/shared/components/BackgroundTaskStatus.vue', () => ({
  default: { template: '<div />' },
}))
import { createApp, h, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import InterviewListView from './InterviewListView.vue'
import InterviewRoomView from './InterviewRoomView.vue'
import {
  createInterview,
  generateInterviewQuestions,
  scoreInterviewAnswer,
  generateInterviewReport,
  getInterview,
  getInterviewReport,
  listInterviews,
  requestInterviewFollowUp,
  saveInterviewAnswer,
  saveInterviewFollowUpAnswer,
  updateInterview,
} from '@/shared/api/interviews'
import { listResumes, listResumeVersions } from '@/shared/api/resumes'
import { createReportLearningPlan } from '@/shared/api/learningPlans'
import { getJob } from '@/shared/api/jobs'
import { listApplications } from '@/shared/api/applications'
import { ApiError } from '@/shared/api/client'
import { listStarStoryCandidates, listStarStories, saveStarStory } from '@/shared/api/starStories'
import type { InterviewSession } from '@/shared/types/interview'
import { questions } from '@/shared/utils/interviewQuestions'
import { downloadText } from '@/shared/utils/download'
import { useWorkspace } from '@/shared/stores/workspace'
import { installSpeechMocks } from '@/test/speech'

vi.mock('@/shared/api/generationTasks', async (original) => ({
  ...(await original<typeof import('@/shared/api/generationTasks')>()),
  recentGenerationTasks: vi.fn(async () => []),
}))

vi.mock('@/shared/api/jobs', () => ({ getJob: vi.fn() }))
vi.mock('@/shared/api/applications', () => ({ listApplications: vi.fn() }))
vi.mock('@/shared/api/interviews', () => ({
  createInterview: vi.fn(),
  generateInterviewQuestions: vi.fn(),
  scoreInterviewAnswer: vi.fn(),
  generateInterviewReport: vi.fn(),
  getInterview: vi.fn(),
  getInterviewReport: vi.fn(),
  listInterviews: vi.fn(),
  requestInterviewFollowUp: vi.fn(),
  saveInterviewAnswer: vi.fn(),
  saveInterviewFollowUpAnswer: vi.fn(),
  updateInterview: vi.fn(),
}))
vi.mock('@/shared/api/resumes', () => ({ listResumes: vi.fn(), listResumeVersions: vi.fn() }))
vi.mock('@/shared/api/learningPlans', () => ({
  createReportLearningPlan: vi.fn(),
  previewInterviewPlanUpdates: vi.fn(async () => []),
  confirmInterviewPlanUpdate: vi.fn(),
}))
vi.mock('@/shared/api/starStories', () => ({
  listStarStoryCandidates: vi.fn(),
  listStarStories: vi.fn(),
  saveStarStory: vi.fn(),
  deleteStarStory: vi.fn(),
}))
vi.mock('@/shared/utils/download', () => ({ downloadText: vi.fn() }))
vi.mock('@/shared/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/shared/api/client')>()),
  fetchWithIdentity: vi.fn(
    async () =>
      new Response('# 面试复盘包\n\n服务端题目快照\n第一题回答\n学习计划\nSTAR', {
        headers: { 'Content-Type': 'text/markdown' },
      }),
  ),
}))
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
    // Most cases exercise saving/progress after the interviewer has already followed up.
    followUpQuestion: `追问 ${questionIndex + 1}？`,
    followUpSource: 'model' as const,
    followUpAnswer: null,
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
function withoutFollowUps() {
  server.answers.forEach((item) => {
    item.followUpQuestion = null
    item.followUpSource = null
  })
}
function submit() {
  root
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
}
beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('offerpilot-guest-v1', 'fixture')
  vi.clearAllMocks()
  vi.stubGlobal('SpeechRecognition', undefined)
  vi.stubGlobal('webkitSpeechRecognition', undefined)
  vi.stubGlobal('speechSynthesis', undefined)
  vi.mocked(listStarStoryCandidates).mockResolvedValue([])
  vi.mocked(listStarStories).mockResolvedValue({ items: [], limit: 30 })
  vi.mocked(listApplications).mockResolvedValue([])
  vi.mocked(listResumes).mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
  vi.mocked(listResumeVersions).mockResolvedValue([])
  vi.mocked(getInterviewReport).mockResolvedValue({
    id: 'iv_1',
    overall: 20,
    dimensions: { professional: 20, expression: 20, logic: 20, jobFit: 20 },
    turns: [],
    strengths: [],
    weaknesses: ['R 结果不足'],
    advice: [],
    recommendedLearning: [{ topic: '结果表达', reason: '缺少验证', estimatedHours: 1 }],
  })
  server = fixture()
  vi.mocked(listInterviews).mockImplementation(async () => [structuredClone(server)])
  vi.mocked(createInterview).mockImplementation(async () => structuredClone(server))
  vi.mocked(getInterview).mockImplementation(async () => structuredClone(server))
  vi.mocked(saveInterviewAnswer).mockImplementation(async (_id, index, answerText) => {
    server.answers[index]!.answerText = answerText.trim()
    return structuredClone(server)
  })
  vi.mocked(requestInterviewFollowUp).mockImplementation(async (_id, index) => {
    server.answers[index]!.followUpQuestion = '你提到缓存，命中率是怎么验证的？'
    server.answers[index]!.followUpSource = 'model'
    return structuredClone(server)
  })
  vi.mocked(saveInterviewFollowUpAnswer).mockImplementation(async (_id, index, answerText) => {
    server.answers[index]!.followUpAnswer = answerText.trim()
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

it('asks one follow-up from the saved answer, saves its answer, then advances', async () => {
  withoutFollowUps()
  await mount()
  await loaded()
  expect(root.querySelectorAll('textarea')).toHaveLength(1)
  await typeAnswer('我负责缓存设计')
  submit()
  await vi.waitFor(() => expect(root.textContent).toContain('你提到缓存，命中率是怎么验证的？'))
  expect(saveInterviewAnswer).toHaveBeenCalledWith('iv_1', 0, '我负责缓存设计', expect.anything())
  expect(requestInterviewFollowUp).toHaveBeenCalledWith('iv_1', 0)
  expect(server.questionIndex).toBe(0)
  const followUp = root.querySelectorAll('textarea')[1]!
  followUp.value = '用压测和监控验证'
  followUp.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  submit()
  await vi.waitFor(() => expect(server.questionIndex).toBe(1))
  expect(server.answers[0]!.followUpAnswer).toBe('用压测和监控验证')
  expect(root.querySelectorAll('textarea')).toHaveLength(1)
})

it('lets the candidate skip the follow-up when the interviewer cannot ask', async () => {
  withoutFollowUps()
  vi.mocked(requestInterviewFollowUp).mockRejectedValueOnce(new Error('网络错误'))
  await mount()
  await loaded()
  await typeAnswer('我负责缓存设计')
  submit()
  await vi.waitFor(() => expect(root.textContent).toContain('面试官暂时无法追问'))
  button('跳过追问').click()
  await vi.waitFor(() => expect(server.questionIndex).toBe(1))
})

it('appends finals to typed answers and saves the last recognition result before the follow-up', async () => {
  const mock = installSpeechMocks()
  withoutFollowUps()
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
  expect(root.textContent).toContain('正在结束口述')
  expect(root.textContent).toContain('麦克风已停止')
  active.result(
    [
      { text: '语音行动', final: true },
      { text: '最后的结果', final: true },
    ],
    1,
  )
  active.onend?.()
  await vi.waitFor(() => expect(root.querySelectorAll('textarea')).toHaveLength(2))
  expect(server.answers[0]!.answerText).toBe('手动背景\n语音行动\n最后的结果')
  active.result([{ text: '迟到的文本', final: true }])
  await nextTick()
  expect(root.querySelectorAll('textarea')[1]!.value).toBe('')
  // After the follow-up appears, dictation answers it rather than the main question.
  button('开始口述').click()
  mock.latest().result([{ text: '压测验证', final: true }])
  await nextTick()
  expect(root.querySelectorAll('textarea')[1]!.value).toBe('压测验证')
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
  withoutFollowUps()
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

it('flushes the final speech result when leaving before any text has arrived', async () => {
  const mock = installSpeechMocks()
  withoutFollowUps()
  await mount()
  await loaded()
  button('开始口述').click()
  const active = mock.latest()
  active.stop.mockImplementation(() => {
    active.result([{ text: '离开时才返回的最终回答', final: true }])
    active.onend?.()
  })
  expect(root.querySelector('textarea')!.value).toBe('')
  await router.push('/interviews')
  expect(server.answers[0]!.answerText).toBe('离开时才返回的最终回答')
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
  checkbox.checked = false
  checkbox.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
  expect(localStorage.getItem('offerpilot.voiceAutoPlay')).toBe('false')
  expect(button('朗读题目')).toBeDefined()
  expect(mock.synthesis.cancel).toHaveBeenCalled()
  checkbox.checked = true
  checkbox.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
  button('开始口述').click()
  const active = mock.latest()
  unmount()
  expect(active.abort).toHaveBeenCalledOnce()
  expect(mock.synthesis.cancel).toHaveBeenCalled()
  await mount()
  await loaded()
  expect(root.querySelector<HTMLInputElement>('input[type="checkbox"]')!.checked).toBe(true)
  expect(mock.synthesis.speak.mock.calls.at(-1)![0].text).toBe(server.answers[1]!.questionText)
})

it.each(['recognition', 'synthesis'])(
  'keeps the available voice feature without %s',
  async (missing) => {
    const mock = installSpeechMocks()
    withoutFollowUps()
    if (missing === 'recognition') vi.stubGlobal('SpeechRecognition', undefined)
    else vi.stubGlobal('speechSynthesis', undefined)
    await mount()
    await loaded()
    if (missing === 'recognition') {
      expect(button('开始口述')).toBeUndefined()
      button('朗读题目').click()
      expect(mock.synthesis.speak).toHaveBeenCalledOnce()
      expect(root.textContent).toContain('不支持语音输入')
    } else {
      expect(button('朗读题目')).toBeUndefined()
      button('开始口述').click()
      mock.latest().result([{ text: '独立听写', final: true }])
      await nextTick()
      expect(root.querySelector('textarea')!.value).toBe('独立听写')
      expect(root.textContent).toContain('不支持题目朗读')
      button('停止口述').click()
    }
    await typeAnswer('编辑确认后的回答')
    button('保存草稿').click()
    await vi.waitFor(() => expect(server.answers[0]!.answerText).toBe('编辑确认后的回答'))
  },
)

it('creates only server sessions and exports unchanged legacy records', async () => {
  server.jobId = 'job_interview'
  vi.mocked(getJob).mockResolvedValue({ id: server.jobId, title: 'Java 后端实习生' } as Awaited<
    ReturnType<typeof getJob>
  >)
  vi.mocked(listApplications).mockResolvedValue([
    { id: 'app_interview', jobId: server.jobId },
  ] as Awaited<ReturnType<typeof listApplications>>)
  await mount('/interviews')
  await vi.waitFor(() => expect(root.textContent).toContain('针对：Java 后端实习生'))
  expect(
    root.querySelector<HTMLAnchorElement>('a[href="/board?application=app_interview"]'),
  ).not.toBeNull()
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
    {
      role: '新岗位',
      kind: '综合',
      difficulty: 'STANDARD',
      mode: 'TEXT',
      durationMinutes: 30,
      questionCount: 5,
      typeMix: {},
      industry: '',
      feedbackMode: 'IMMEDIATE',
    },
    expect.any(AbortSignal),
  )
  await vi.waitFor(() => expect(root.textContent).toContain('针对：Java 后端实习生'))
  expect(
    root.querySelector<HTMLAnchorElement>('a[href="/board?application=app_interview"]'),
  ).not.toBeNull()
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
      '服务端工程师-面试复盘包.md',
      expect.stringContaining('第一题回答'),
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
  const candidate = {
    questionIndex: 4,
    questionText: 'q',
    title: '学习故事',
    body: '当时负责，我通过实现，最终完成',
    role: '后端',
    starCount: 4,
    situation: true,
    task: true,
    action: true,
    result: true,
    alreadySaved: false,
  }
  vi.mocked(listStarStoryCandidates).mockRejectedValueOnce(new Error('候选加载失败'))
  submit()
  await vi.waitFor(() => expect(root.textContent).toContain('已结束本场 5 题'))
  expect(server.completed).toBe(true)
  expect(server.answers[4]!.answerText).toBe('学习计划')
  expect(root.querySelector('textarea')).toBeNull()
  await vi.waitFor(() => expect(root.textContent).toContain('候选加载失败'))
  expect(root.textContent).not.toContain('暂无满足')
  vi.mocked(listStarStoryCandidates).mockResolvedValue([candidate])
  button('重新加载候选').click()
  await vi.waitFor(() => expect(root.textContent).toContain('学习故事'))
  button('保存到故事库').click()
  await vi.waitFor(() => expect(root.textContent).toContain('已入库'))
  expect(saveStarStory).toHaveBeenCalledWith(
    { sessionId: 'iv_1', questionIndex: 4 },
    expect.any(AbortSignal),
  )
  unmount()
  await mount()
  await vi.waitFor(() => expect(button('导出复盘')).toBeDefined())
  button('导出复盘').click()
  await vi.waitFor(() =>
    expect(downloadText).toHaveBeenCalledWith(
      '服务端工程师-面试复盘包.md',
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

it('submits saved resume version, type, difficulty, voice mode and duration with optional job', async () => {
  installSpeechMocks()
  vi.mocked(listResumes).mockResolvedValue({
    items: [{ id: 'r1', title: '已保存简历', parseStatus: 'READY' }] as Awaited<
      ReturnType<typeof listResumes>
    >['items'],
    total: 1,
    page: 1,
    size: 20,
  })
  vi.mocked(listResumeVersions).mockResolvedValue([
    { id: 'v1', versionNo: 1, createdAt: '2026-10-19T00:00:00Z' },
  ] as Awaited<ReturnType<typeof listResumeVersions>>)
  await mount('/interviews')
  await vi.waitFor(() => expect(root.textContent).toContain('已保存简历'))
  async function choose(label: string, value: string) {
    const select = [...root.querySelectorAll('label')]
      .find((el) => el.textContent?.startsWith(label))!
      .querySelector('select')!
    select.value = value
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick()
  }
  await choose('简历', 'r1')
  await vi.waitFor(() => expect(root.textContent).toContain('第 1 版'))
  expect(button('开始练习').disabled).toBe(true)
  await choose('简历版本', 'v1')
  await choose('面试类型', '技术')
  await choose('难度', 'HARD')
  await choose('模式', 'VOICE')
  await choose('时长', '60')
  const input = root.querySelector('input')!
  input.value = '后端'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  submit()
  await vi.waitFor(() =>
    expect(createInterview).toHaveBeenCalledWith(
      {
        role: '后端',
        kind: '技术',
        difficulty: 'HARD',
        mode: 'VOICE',
        durationMinutes: 60,
        questionCount: 5,
        typeMix: {},
        industry: '',
        feedbackMode: 'IMMEDIATE',
        resumeId: 'r1',
        versionId: 'v1',
      },
      expect.any(AbortSignal),
    ),
  )
})
it('disables unavailable voice and preserves options after create failure', async () => {
  await mount('/interviews')
  const option = [...root.querySelectorAll('option')].find((el) => el.value === 'VOICE')!
  expect(option.disabled).toBe(true)
  expect(option.textContent).toContain('暂不支持')
})
it('creates a report learning plan with retry and links to the saved plan', async () => {
  server.completed = true
  vi.mocked(createReportLearningPlan)
    .mockRejectedValueOnce(new Error('保存失败'))
    .mockResolvedValue({
      id: 'lp_1',
      reportId: 'iv_1',
      startDate: '2026-10-19',
      roadmap: { weeks: [], milestones: [] },
    })
  await mount()
  await vi.waitFor(() => expect(button('一键生成学习计划')).toBeDefined())
  button('一键生成学习计划').click()
  await vi.waitFor(() => expect(root.textContent).toContain('保存失败'))
  button('一键生成学习计划').click()
  await vi.waitFor(() => expect(root.querySelector('a[href="/career?plan=lp_1"]')).not.toBeNull())
  expect(createReportLearningPlan).toHaveBeenCalledWith('iv_1', expect.any(AbortSignal))
})
it('restores duration timer from persisted creation time and ends without dropping the draft', async () => {
  server.options = { difficulty: 'STANDARD', mode: 'TEXT', durationMinutes: 15 }
  server.createdAt = new Date(Date.now() - 16 * 60000).toISOString()
  await mount()
  await loaded()
  expect(root.querySelector('[role="timer"]')?.textContent).toContain('时间已到')
  await typeAnswer('需要保留的回答')
  button('保存并结束面试').click()
  await vi.waitFor(() => expect(server.completed).toBe(true))
  expect(server.answers[0]!.answerText).toBe('需要保留的回答')
})

it('creates non-tech practice with count, difficulty, explicit type counts and deferred feedback', async () => {
  await mount('/interviews')
  await vi.waitFor(() => expect(button('开始练习')).toBeDefined())
  async function set(label: string, value: string) {
    const field = [...root.querySelectorAll('label')].find((el) =>
      el.textContent?.startsWith(label),
    )!
    const input = field.querySelector('input,select') as HTMLInputElement | HTMLSelectElement
    input.value = value
    input.dispatchEvent(
      new Event(input.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true }),
    )
    await nextTick()
  }
  await set('目标岗位', '新媒体运营')
  await set('题数', '3')
  await set('难度', 'HARD')
  await set('行业', '消费品')
  await set('反馈时机', 'END')
  const checkbox = [...root.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')][0]!
  checkbox.click()
  await nextTick()
  expect(button('开始练习').disabled).toBe(true)
  await set('行为', '1')
  await set('案例分析', '0')
  expect(button('开始练习').disabled).toBe(false)
  submit()
  await vi.waitFor(() =>
    expect(createInterview).toHaveBeenCalledWith(
      expect.objectContaining({
        role: '新媒体运营',
        questionCount: 3,
        difficulty: 'HARD',
        industry: '消费品',
        feedbackMode: 'END',
        typeMix: { BEHAVIORAL: 1, SITUATIONAL: 1, KNOWLEDGE: 1, CASE: 0 },
      }),
      expect.any(AbortSignal),
    ),
  )
})
function modern() {
  server.generationSource = 'model'
  server.options = {
    difficulty: 'HARD',
    mode: 'TEXT',
    durationMinutes: 30,
    questionCount: 5,
    feedbackMode: 'IMMEDIATE',
  }
  for (const answer of server.answers) {
    answer.followUpQuestion = null
    answer.questionType = 'CASE'
    answer.difficulty = 'HARD'
    answer.questionSource = 'model'
  }
}
it('generates a pending question set then reveals evidence feedback, supports retry, skip and final report', async () => {
  modern()
  server.generationSource = 'pending'
  vi.mocked(generateInterviewQuestions).mockImplementation(async () => {
    server.generationSource = 'model'
    return structuredClone(server)
  })
  vi.mocked(scoreInterviewAnswer).mockImplementation(async (_id, index) => {
    server.answers[index]!.evaluation = {
      source: 'model',
      score: 72,
      rationale: '行动具体，补充验证方式',
      improvedAnswer: '行动：我负责活动复盘\n结果：【补充你的具体数据】',
      dimensions: {
        professional: { score: 72, rationale: '职责明确', evidence: '我负责活动复盘' },
        expression: { score: 72, rationale: '表达清楚', evidence: '我负责活动复盘' },
        logic: { score: 72, rationale: '行动明确', evidence: '我负责活动复盘' },
        jobFit: { score: 72, rationale: '岗位相关', evidence: '我负责活动复盘' },
      },
    }
    return structuredClone(server)
  })
  vi.mocked(generateInterviewReport).mockResolvedValue(await getInterviewReport('iv_1'))
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('准备你的面试题目'))
  button('生成面试题目').click()
  await vi.waitFor(() => expect(root.textContent).toContain('第 1 / 5 题'))
  expect(root.textContent).toContain('案例分析')
  expect(root.textContent).toContain('高级')
  expect(root.textContent).toContain('本题 0 分')
  await typeAnswer('我负责活动复盘')
  button('提交并查看反馈').click()
  await vi.waitFor(() => expect(root.textContent).toContain('本题反馈 · 72 / 100'))
  expect(root.textContent).toContain('【补充你的具体数据】')
  await typeAnswer('我负责活动复盘并收集反馈')
  button('重新评价').click()
  await vi.waitFor(() => expect(scoreInterviewAnswer).toHaveBeenCalledTimes(2))
  await vi.waitFor(() => expect(button('下一题').disabled).toBe(false))
  button('下一题').click()
  await vi.waitFor(() => expect(root.textContent).toContain('第 2 / 5 题'))
  button('跳过本题').click()
  await vi.waitFor(() => expect(root.textContent).toContain('第 3 / 5 题'))
  button('结束并生成报告').click()
  await vi.waitFor(() =>
    expect(generateInterviewReport).toHaveBeenCalledWith('iv_1', expect.any(AbortSignal)),
  )
  expect(server.completed).toBe(true)
})
it('defers feedback until finish and renders unscored report honestly', async () => {
  modern()
  server.options!.feedbackMode = 'END'
  server.answers[0]!.evaluation = {
    source: 'model',
    score: 70,
    rationale: '隐藏的反馈',
    improvedAnswer: '隐藏的答案',
  }
  await mount()
  await loaded()
  expect(root.textContent).not.toContain('隐藏的反馈')
  await typeAnswer('我负责复盘')
  submit()
  await vi.waitFor(() => expect(server.questionIndex).toBe(1))
  expect(scoreInterviewAnswer).not.toHaveBeenCalled()
  unmount()
  server.completed = true
  vi.mocked(getInterviewReport).mockResolvedValue({
    id: 'iv_1',
    overall: null,
    dimensions: { professional: null, expression: null, logic: null, jobFit: null },
    turns: [
      {
        question: '如何复盘？',
        answerSummary: '我负责复盘',
        score: null,
        star: { situation: false, task: true, action: false, result: false },
        feedback: '模型不可用，本题未评分',
        betterAnswerOutline: [],
        source: 'unavailable',
      },
    ],
    strengths: [],
    weaknesses: [],
    advice: [],
    recommendedLearning: [],
    note: '模型不可用，未评分',
  })
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('模型不可用，未评分'))
  expect(root.textContent).toContain('未评分')
  expect(root.textContent).not.toContain('0 / 100')
})

it('renders saved per-question dimensions, quoted evidence and improved answers in the final report', async () => {
  modern()
  server.completed = true
  const dimension = { score: 68, rationale: '行动清楚，验证待补充', evidence: '我负责活动复盘' }
  vi.mocked(getInterviewReport).mockResolvedValue({
    id: 'iv_1',
    overall: 68,
    dimensions: { professional: 68, expression: 68, logic: 68, jobFit: 68 },
    turns: [
      {
        question: '如何安排活动复盘？',
        answerSummary: '我负责活动复盘',
        score: 68,
        star: { situation: false, task: true, action: true, result: false },
        feedback: '请补充验证',
        betterAnswerOutline: [],
        source: 'model',
        evaluation: {
          source: 'model',
          score: 68,
          rationale: '请补充验证',
          improvedAnswer: '行动：我负责活动复盘\n结果：【补充真实结果】',
          dimensions: {
            professional: dimension,
            expression: dimension,
            logic: dimension,
            jobFit: dimension,
          },
        },
      },
    ],
    strengths: ['行动明确'],
    weaknesses: ['效果验证不足'],
    advice: ['重答并说明真实结果'],
    recommendedLearning: [{ topic: '效果验证', reason: '第1题缺少验证', estimatedHours: 1 }],
    note: '已评分1/5题',
  })
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('本题反馈 · 68 / 100'))
  expect(root.textContent).toContain('「我负责活动复盘」')
  expect(root.textContent).toContain('结果：【补充真实结果】')
  expect(root.textContent).toContain('重答并说明真实结果')
  expect(root.textContent).toContain('已评分1/5题')
})

afterEach(() => setLanguage('zh-CN'))

it('switches interview form labels live and keeps the transport kind unchanged', async () => {
  await mount('/interviews')
  await vi.waitFor(() => expect(root.textContent).toContain('服务端工程师'))
  setLanguage('en-US')
  await nextTick()
  expect(root.textContent).toContain('Create a mock interview')
  expect(root.querySelector('.page-stack')?.classList.contains('overflow-safe')).toBe(true)
  expect(root.textContent).toContain('Behavioral')
  expect(root.textContent).toContain('Question mix')
  expect(root.textContent).toContain('服务端工程师')
  const general = [...root.querySelectorAll('option')].find(
    (option) => option.textContent === 'General',
  )!
  expect(general.value).toBe('综合')
  expect((general.parentElement as HTMLSelectElement).value).toBe('综合')
  setLanguage('zh-CN')
  await nextTick()
  expect(root.textContent).toContain('创建模拟面试')
  expect(createInterview).not.toHaveBeenCalled()
})
