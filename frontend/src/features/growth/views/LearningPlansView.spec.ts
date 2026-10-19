import { createApp, h, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import LearningPlansView from './LearningPlansView.vue'
import { exportLearningCalendar, listLearningPlans } from '@/shared/api/learningPlans'
vi.mock('@/shared/api/learningPlans', () => ({
  listLearningPlans: vi.fn(),
  exportLearningCalendar: vi.fn(),
}))
let app: App, root: HTMLDivElement, cache: QueryClient
async function mount() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/career', component: LearningPlansView },
      { path: '/interviews/:id', component: { render: () => null } },
    ],
  })
  await router.push('/career?plan=lp1')
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(LearningPlansView) })
    .use(router)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
}
afterEach(() => {
  app?.unmount()
  cache?.clear()
  root?.remove()
  vi.resetAllMocks()
})
it('shows weekly resources, milestone, report link and calendar export with retry', async () => {
  vi.mocked(listLearningPlans).mockResolvedValue([
    {
      id: 'lp1',
      reportId: 'iv1',
      startDate: '2026-10-19',
      roadmap: {
        weeks: [
          {
            index: 1,
            theme: '表达',
            items: [
              {
                id: 't1',
                title: '结果练习',
                hours: 1,
                resources: ['根据真实报告核对'],
                dueAt: null,
                done: false,
              },
            ],
          },
        ],
        milestones: [{ title: '再次练习', at: '第4周末' }],
      },
    },
  ])
  vi.mocked(exportLearningCalendar).mockRejectedValueOnce(new Error('下载失败')).mockResolvedValue()
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('第 1 周'))
  expect(root.textContent).toContain('根据真实报告核对')
  expect(root.textContent).toContain('第4周末 · 再次练习')
  expect(root.querySelector('a[href="/interviews/iv1"]')).not.toBeNull()
  root.querySelector('button')!.click()
  await vi.waitFor(() => expect(root.textContent).toContain('下载失败'))
  root.querySelector('button')!.click()
  await vi.waitFor(() => expect(exportLearningCalendar).toHaveBeenCalledTimes(2))
  expect(exportLearningCalendar).toHaveBeenCalledWith('lp1', expect.any(AbortSignal))
})
it('retries read failure and shows an honest empty state', async () => {
  vi.mocked(listLearningPlans).mockRejectedValueOnce(new Error('读取失败')).mockResolvedValue([])
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('读取失败'))
  root.querySelector('button')!.click()
  await vi.waitFor(() => expect(root.textContent).toContain('还没有面试学习计划'))
})
