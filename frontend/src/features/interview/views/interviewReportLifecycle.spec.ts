import { createApp, h, type App } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import InterviewRoomView from './InterviewRoomView.vue'
import InterviewListView from './InterviewListView.vue'
import { useGenerationTasks } from '@/shared/stores/generationTasks'
import type { BackgroundTask } from '@/shared/api/generationTasks'
import type { InterviewSession } from '@/shared/types/interview'
import type { InterviewReportPayload } from '@/shared/types/cards'

let app: App, root: HTMLDivElement, cache: QueryClient, pinia: ReturnType<typeof createPinia>
let session: InterviewSession, task: BackgroundTask | undefined, stored: boolean, starts: number
let taskReadError = false
const report: InterviewReportPayload = {
  id: 'iv_inline',
  overall: 76,
  dimensions: { professional: 80, expression: 76, logic: 72, jobFit: 76 },
  turns: [
    {
      question: '如何复盘一次活动？',
      answerSummary: '我负责整理反馈',
      score: 76,
      star: { situation: false, task: true, action: true, result: false },
      feedback: '行动明确，缺少效果验证',
      betterAnswerOutline: [],
      source: 'model',
      improvedAnswer: '我负责整理反馈，并【补充真实验证结果】',
    },
  ],
  strengths: ['行动明确'],
  weaknesses: ['效果验证不足'],
  advice: ['补充真实结果'],
  recommendedLearning: [{ topic: '效果验证', reason: '缺少结果', estimatedHours: 1 }],
}
const pendingReport = { ...report, overall: null, turns: [], recommendedLearning: [] }
function response(data: unknown) {
  return new Response(JSON.stringify({ code: 0, data }), {
    headers: { 'Content-Type': 'application/json' },
  })
}
beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('offerpilot-guest-v1', 'inline-fixture')
  stored = false
  starts = 0
  task = undefined
  taskReadError = false
  session = {
    id: 'iv_inline',
    role: '新媒体运营',
    kind: '综合',
    questionIndex: 0,
    completed: false,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    generationSource: 'model',
    options: { difficulty: 'STANDARD', mode: 'TEXT', durationMinutes: 30, feedbackMode: 'END' },
    answers: [
      { questionIndex: 0, questionText: '如何复盘一次活动？', answerText: '我负责整理反馈' },
    ],
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const path = new URL(url, 'http://localhost').pathname.replace('/api/v1', '')
      if (path === '/generation-tasks' && init?.method === 'POST') {
        starts++
        task = {
          id: 'gen_' + starts,
          kind: 'INTERVIEW_REPORT',
          entityId: session.id,
          status: 'RUNNING',
          title: '面试整体报告',
          href: '/interviews/iv_inline',
          stage: '正在评价已保存回答',
          resultId: null,
          error: null,
          createdAt: `2026-10-20T00:00:0${starts}Z`,
          updatedAt: '',
          startedAt: '',
          finishedAt: null,
        }
        return response(task)
      }
      if (path === '/generation-tasks') {
        if (taskReadError)
          return new Response(JSON.stringify({ message: '暂时离线' }), { status: 503 })
        const kind = new URL(url, 'http://localhost').searchParams.get('kind')
        return response(task && (!kind || kind === task.kind) ? [task] : [])
      }
      if (path.endsWith('/result')) return response(report)
      if (path === '/interviews/iv_inline/report') return response(stored ? report : pendingReport)
      if (path === '/interviews/iv_inline') {
        if (init?.method === 'PATCH') Object.assign(session, JSON.parse(init.body as string))
        return response(session)
      }
      if (path === '/interviews') return response([session])
      if (path === '/resumes') return response({ items: [] })
      if (path === '/applications') return response([])
      if (path.startsWith('/star-stories'))
        return response(path.includes('candidates') ? [] : { items: [] })
      if (path.includes('/learning-plans/from-interview/')) return response({ id: 'lp_inline' })
      throw new Error('Unexpected API: ' + path)
    }),
  )
})
afterEach(() => {
  app?.unmount()
  disposePinia(pinia)
  cache.clear()
  root.remove()
  vi.unstubAllGlobals()
})
async function mount() {
  pinia = createPinia()
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/interviews/:id', component: InterviewRoomView },
      { path: '/interviews', component: InterviewListView },
      { path: '/career', component: { template: '<div />' } },
      { path: '/chat', component: { template: '<div />' } },
    ],
  })
  await router.push('/interviews/iv_inline')
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(RouterView) })
    .use(pinia)
    .use(router)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
  return router
}
function button(label: string) {
  return [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!
}
it('ends into progress, retries a failed background task and opens the persisted report from history without another POST', async () => {
  const router = await mount()
  await vi.waitFor(() => expect(button('结束并生成报告')).toBeDefined())
  button('结束并生成报告').click()
  await vi.waitFor(() => expect(root.textContent).toContain('正在生成面试报告'))
  expect(root.textContent).not.toContain('面试评分与复盘')
  expect(root.textContent).not.toContain('未评分 / 100')
  expect(starts).toBe(1)
  task = { ...task!, status: 'FAILED', error: '任务中断，请重试' }
  await useGenerationTasks(pinia).refresh()
  await vi.waitFor(() => expect(root.textContent).toContain('报告生成失败'))
  expect(root.textContent).toContain('任务中断，请重试')
  button('重试生成报告').click()
  await vi.waitFor(() => expect(starts).toBe(2))
  expect(root.textContent).toContain('正在生成面试报告')
  stored = true
  task = { ...task!, status: 'SUCCEEDED', stage: '结果已保存' }
  await useGenerationTasks(pinia).refresh()
  await vi.waitFor(() => expect(root.textContent).toContain('面试评分与复盘'))
  expect(root.textContent).toContain('76 / 100')
  expect(root.textContent).toContain('行动明确，缺少效果验证')
  expect(root.textContent).toContain('【补充真实验证结果】')
  expect(root.textContent).toContain('效果验证不足')
  button('一键生成学习计划').click()
  await vi.waitFor(() =>
    expect(root.querySelector('a[href="/career?plan=lp_inline"]')).not.toBeNull(),
  )
  expect(button('导出复盘')).toBeDefined()
  await router.push('/interviews')
  await vi.waitFor(() => expect(root.textContent).toContain('查看报告'))
  const history = [...root.querySelectorAll('a')].find((a) => a.textContent?.trim() === '查看报告')!
  history.click()
  await vi.waitFor(() => expect(root.textContent).toContain('面试评分与复盘'))
  expect(root.querySelector('textarea')).toBeNull()
  expect(starts).toBe(2)
})
it('restores a running report after a fresh page/store and displays its completion automatically', async () => {
  session.completed = true
  await mount()
  await vi.waitFor(() => expect(button('生成面试报告')).toBeDefined())
  button('生成面试报告').click()
  await vi.waitFor(() => expect(starts).toBe(1))
  app.unmount()
  disposePinia(pinia)
  cache.clear()
  root.remove()
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('正在生成面试报告'))
  stored = true
  task = { ...task!, status: 'SUCCEEDED' }
  await useGenerationTasks(pinia).refresh()
  await vi.waitFor(() => expect(root.textContent).toContain('面试评分与复盘'))
  expect(starts).toBe(1)
})
it('blocks duplicate generation while task restore fails and recovers without a POST', async () => {
  session.completed = true
  stored = true
  taskReadError = true
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('报告任务读取失败'))
  expect(button('生成面试报告')).toBeUndefined()
  taskReadError = false
  const panel = root.querySelector('[aria-label="面试报告与学习计划"]')!
  panel.querySelector<HTMLButtonElement>('button')!.click()
  await vi.waitFor(() => expect(root.textContent).toContain('面试评分与复盘'))
  expect(starts).toBe(0)
})
