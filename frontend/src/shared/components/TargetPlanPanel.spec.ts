import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import TargetPlanPanel from './TargetPlanPanel.vue'
import {
  getTargetPlan,
  confirmTargetPlan,
  retryTargetPlan,
  type TargetPlan,
} from '@/shared/api/careerUpdates'
import { saveGuest } from '@/shared/utils/authSession'
vi.mock('@/shared/api/careerUpdates', () => ({
  getTargetPlan: vi.fn(),
  confirmTargetPlan: vi.fn(),
  retryTargetPlan: vi.fn(),
}))
let app: App, host: HTMLDivElement
const plan = (): TargetPlan => ({
  revision: 'revision-1',
  target: '软件开发工程师',
  city: '上海',
  status: 'SUGGESTED',
  notice: '',
  result: {
    jobs: {
      items: [{ id: 'job1', title: '后端岗位', company: '企业', source: 'company-career-site' }],
    },
    skillGap: { skills: [{ name: 'SQL', level: 'MISSING' }], notice: '材料证据覆盖' },
    learning: {
      summary: '根据真实材料的建议',
      roadmap: {
        weeks: [
          { index: 1, theme: '项目练习', items: [{ id: 'task1', title: '整理证据', hours: 2 }] },
        ],
      },
    },
  },
})
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (text: string) =>
  [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!
async function mount() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:all(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/')
  app = createApp(TargetPlanPanel, { enabled: true, target: '软件开发工程师', city: '上海' })
    .use(createPinia())
    .use(router)
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  saveGuest('alice')
  host = document.createElement('div')
  document.body.append(host)
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true })
  vi.mocked(getTargetPlan).mockResolvedValue(plan())
})
afterEach(() => {
  app?.unmount()
  host.remove()
  localStorage.clear()
  vi.useRealTimers()
})
it('shows searched jobs, evidence gaps and roadmap before explicitly confirming adoption', async () => {
  await mount()
  expect(host.textContent).toContain('后端岗位')
  expect(host.textContent).toContain('SQL：缺少材料')
  expect(host.textContent).toContain('整理证据')
  expect(confirmTargetPlan).not.toHaveBeenCalled()
  vi.mocked(confirmTargetPlan).mockResolvedValue({ ...plan(), status: 'CONFIRMED' })
  button('确认采用这份后续建议').click()
  await flush()
  expect(confirmTargetPlan).toHaveBeenCalledWith('revision-1')
  expect(host.textContent).toContain('已确认采用')
  expect(
    [...host.querySelectorAll('a')].some(
      (a) =>
        a.getAttribute('href') ===
        '/jobs?q=%E8%BD%AF%E4%BB%B6%E5%BC%80%E5%8F%91%E5%B7%A5%E7%A8%8B%E5%B8%88&location=%E4%B8%8A%E6%B5%B7',
    ),
  ).toBe(true)
})
it('polls queued work and keeps explicit retry available for partial failure', async () => {
  vi.useFakeTimers()
  vi.mocked(getTargetPlan).mockResolvedValueOnce({ ...plan(), status: 'PENDING', result: null })
  await mount()
  expect(host.textContent).toContain('正在按已保存目标搜索岗位')
  vi.mocked(getTargetPlan).mockResolvedValue({
    ...plan(),
    status: 'FAILED',
    notice: '岗位搜索未完成',
  })
  await vi.advanceTimersByTimeAsync(10000)
  await flush()
  expect(host.textContent).toContain('岗位搜索未完成')
  vi.mocked(retryTargetPlan).mockResolvedValue({ ...plan(), status: 'PENDING', result: null })
  button('重试生成联动').click()
  await flush()
  expect(retryTargetPlan).toHaveBeenCalledWith('revision-1')
})
it('hides stale target results and reports read errors without losing navigation entries', async () => {
  vi.mocked(getTargetPlan).mockResolvedValue({ ...plan(), target: '旧目标' })
  await mount()
  expect(host.textContent).not.toContain('后端岗位')
  expect(host.textContent).toContain('继续搜索目标岗位')
  vi.mocked(getTargetPlan).mockRejectedValue(new Error('读取失败'))
  saveGuest('bob')
  await flush()
  button('重新读取')?.click()
  await flush()
  expect(host.textContent).toContain('读取失败')
})
it('does not show late previous-owner results and blocks offline confirmation', async () => {
  let finish!: (p: TargetPlan) => void
  vi.mocked(getTargetPlan).mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  await mount()
  vi.mocked(getTargetPlan).mockResolvedValue(null)
  saveGuest('bob')
  await flush()
  finish(plan())
  await flush()
  expect(host.textContent).not.toContain('后端岗位')
  Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
  window.dispatchEvent(new Event('offline'))
  await flush()
  expect(host.textContent).toContain('当前离线')
})
