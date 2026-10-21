import { createApp, h, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import InterviewPlanUpdatePanel from './InterviewPlanUpdatePanel.vue'
import {
  confirmInterviewPlanUpdate,
  previewInterviewPlanUpdates,
  type LearningPlanUpdate,
} from '@/shared/api/learningPlans'
vi.mock('@/shared/api/learningPlans', () => ({
  previewInterviewPlanUpdates: vi.fn(),
  confirmInterviewPlanUpdate: vi.fn(),
}))
const proposal: LearningPlanUpdate = {
  planId: 'p1',
  reportId: 'r2',
  targetRole: '后端',
  fingerprint: 'a'.repeat(64),
  additions: [
    {
      index: 1,
      theme: '报告短板',
      items: [
        {
          id: 't1',
          title: '核对事务边界',
          hours: 2,
          resources: ['报告原文：缺少失败回滚说明'],
          done: false,
          dueAt: null,
        },
      ],
    },
  ],
}
let app: App, root: HTMLDivElement, cache: QueryClient
async function mount() {
  cache = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/interviews/r2')
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(InterviewPlanUpdatePanel, { reportId: 'r2' }) })
    .use(router)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
}
function button(text: string) {
  return Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.includes(text))!
}
afterEach(() => {
  app?.unmount()
  cache?.clear()
  root?.remove()
  vi.resetAllMocks()
  vi.unstubAllGlobals()
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
})
it('previews real weak items, writes only after explicit confirmation, retries and preserves source', async () => {
  vi.mocked(previewInterviewPlanUpdates).mockResolvedValue([proposal])
  vi.mocked(confirmInterviewPlanUpdate)
    .mockRejectedValueOnce(new Error('计划已变化，请重新读取'))
    .mockResolvedValueOnce({
      id: 'p1',
      reportId: 'r1',
      startDate: '2026-10-21',
      roadmap: { weeks: [], milestones: [] },
    })
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('核对事务边界'))
  expect(root.textContent).toContain('缺少失败回滚说明')
  expect(confirmInterviewPlanUpdate).not.toHaveBeenCalled()
  expect(root.querySelector('a')?.getAttribute('href')).toBe('/career?plan=p1')
  button('确认更新此计划').click()
  await vi.waitFor(() => expect(root.textContent).toContain('计划已变化'))
  button('重新读取更新建议').click()
  await vi.waitFor(() => expect(previewInterviewPlanUpdates).toHaveBeenCalledTimes(2))
  button('确认更新此计划').click()
  await vi.waitFor(() => expect(root.textContent).toContain('新增任务待学习'))
  expect(confirmInterviewPlanUpdate).toHaveBeenCalledWith(proposal, expect.any(AbortSignal))
  expect(button('确认更新此计划')).toBeUndefined()
})
it('handles read failure, empty plans and offline without writing', async () => {
  vi.mocked(previewInterviewPlanUpdates)
    .mockRejectedValueOnce(new Error('读取失败'))
    .mockResolvedValue([])
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('读取失败'))
  button('重试更新建议').click()
  await vi.waitFor(() => expect(root.textContent).toContain('暂无可更新'))
  expect(confirmInterviewPlanUpdate).not.toHaveBeenCalled()
})
it('disables offline confirmation and duplicate in-flight clicks', async () => {
  vi.mocked(previewInterviewPlanUpdates).mockResolvedValue([proposal])
  let resolve!: (value: never) => void
  vi.mocked(confirmInterviewPlanUpdate).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  await mount()
  await vi.waitFor(() => expect(button('确认更新此计划')).toBeDefined())
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: false })
  window.dispatchEvent(new Event('offline'))
  await vi.waitFor(() => expect(button('确认更新此计划').disabled).toBe(true))
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
  window.dispatchEvent(new Event('online'))
  await vi.waitFor(() => expect(button('确认更新此计划').disabled).toBe(false))
  button('确认更新此计划').click()
  button('确认更新此计划').click()
  expect(confirmInterviewPlanUpdate).toHaveBeenCalledTimes(1)
  resolve({} as never)
  await vi.waitFor(() => expect(root.textContent).toContain('计划已更新'))
})
