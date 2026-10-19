import { createApp, nextTick, type App } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import InterviewRoomView from './InterviewRoomView.vue'
import { recentGenerationTasks } from '@/shared/api/generationTasks'
import {
  getInterview,
  generateInterviewQuestions,
  scoreInterviewAnswer,
  generateInterviewReport,
} from '@/shared/api/interviews'
import { useGenerationTasks } from '@/shared/stores/generationTasks'
import type { InterviewSession } from '@/shared/types/interview'
vi.mock('@/shared/api/interviews', () => ({
  getInterview: vi.fn(),
  getInterviewReport: vi.fn(),
  generateInterviewQuestions: vi.fn(),
  scoreInterviewAnswer: vi.fn(),
  generateInterviewReport: vi.fn(),
  saveInterviewAnswer: vi.fn(),
  saveInterviewFollowUpAnswer: vi.fn(),
  updateInterview: vi.fn(),
  requestInterviewFollowUp: vi.fn(),
}))
vi.mock('@/shared/api/generationTasks', async (original) => ({
  ...(await original<typeof import('@/shared/api/generationTasks')>()),
  recentGenerationTasks: vi.fn(),
}))
vi.mock('@/shared/api/starStories', () => ({
  listStarStoryCandidates: vi.fn(async () => []),
  saveStarStory: vi.fn(),
}))
let app: App, root: HTMLDivElement, pinia: ReturnType<typeof createPinia>, cache: QueryClient
const task = {
  id: 'gen_score',
  kind: 'INTERVIEW_SCORE',
  entityId: 'iv_restore',
  status: 'RUNNING' as const,
  title: '面试逐题评价',
  href: '/interviews/iv_restore?taskId=gen_score',
  stage: '正在评价已保存回答',
  resultId: null,
  error: null,
  createdAt: '2026-10-20',
  updatedAt: '2026-10-20',
  startedAt: null,
  finishedAt: null,
}
afterEach(() => {
  app?.unmount()
  disposePinia(pinia)
  cache.clear()
  root.remove()
})
it('restores a running score after remount and loads persisted feedback after completion and a fresh store', async () => {
  const session: InterviewSession = {
    id: 'iv_restore',
    role: '新媒体运营',
    kind: '综合',
    questionIndex: 0,
    completed: false,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    generationSource: 'model',
    options: { difficulty: 'STANDARD', mode: 'TEXT', durationMinutes: 30, questionCount: 3 },
    answers: [0, 1, 2].map((questionIndex) => ({
      questionIndex,
      questionText: '如何安排活动复盘？',
      answerText: questionIndex === 0 ? '我负责复盘' : '',
      questionType: 'CASE',
      difficulty: 'STANDARD',
      questionSource: 'model',
    })),
  }
  vi.mocked(getInterview).mockImplementation(async () => structuredClone(session))
  vi.mocked(recentGenerationTasks).mockImplementation(async (kind) =>
    !kind || kind === task.kind ? [task] : [],
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/interviews/:id', component: { template: '<div />' } }],
  })
  await router.push('/interviews/iv_restore?taskId=gen_score')
  pinia = createPinia()
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  root = document.createElement('div')
  document.body.append(root)
  function mount() {
    app = createApp(InterviewRoomView)
      .use(pinia)
      .use(router)
      .use(VueQueryPlugin, { queryClient: cache })
    app.mount(root)
  }
  mount()
  await vi.waitFor(() => expect(root.textContent).toContain('正在评价已保存回答'))
  app.unmount()
  await nextTick()
  mount()
  await vi.waitFor(() => expect(root.textContent).toContain('正在评价已保存回答'))
  const draft = root.querySelector('textarea')!
  draft.value = '我负责复盘，补充中的草稿'
  draft.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  session.answers[0]!.evaluation = {
    source: 'model',
    score: 76,
    rationale: '已保存的复盘反馈',
    improvedAnswer: '行动：我负责复盘\n结果：【补充真实结果】',
  }
  const done = { ...task, status: 'SUCCEEDED' as const, stage: '结果已保存' }
  vi.mocked(recentGenerationTasks).mockImplementation(async (kind) =>
    !kind || kind === task.kind ? [done] : [],
  )
  await useGenerationTasks(pinia).refresh()
  await vi.waitFor(() => expect(root.textContent).toContain('已保存的复盘反馈'))
  expect(root.querySelector('textarea')?.value).toBe('我负责复盘，补充中的草稿')
  app.unmount()
  disposePinia(pinia)
  pinia = createPinia()
  mount()
  await vi.waitFor(() => expect(root.textContent).toContain('本题反馈 · 76 / 100'))
  expect(root.textContent).toContain('【补充真实结果】')
  expect(generateInterviewQuestions).not.toHaveBeenCalled()
  expect(scoreInterviewAnswer).not.toHaveBeenCalled()
  expect(generateInterviewReport).not.toHaveBeenCalled()
})
