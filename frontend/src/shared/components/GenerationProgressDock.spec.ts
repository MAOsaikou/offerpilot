import { createApp, nextTick, type App } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import GenerationProgressDock from './GenerationProgressDock.vue'
import { useGenerationTasks } from '@/shared/stores/generationTasks'
vi.mock('@/shared/stores/auth', () => ({
  useAuthStore: () => ({ identityKey: 'fixture', isAuthenticated: false }),
}))
vi.mock('@/shared/api/generationTasks', async (original) => ({
  ...(await original<typeof import('@/shared/api/generationTasks')>()),
  recentGenerationTasks: vi.fn(async () => []),
}))
let app: App, root: HTMLDivElement, pinia: ReturnType<typeof createPinia>
afterEach(() => {
  app.unmount()
  disposePinia(pinia)
  root.remove()
})
it('renders running/finished/failed tasks with result links, real stages and notification', async () => {
  pinia = createPinia()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  await router.push('/')
  root = document.createElement('div')
  document.body.append(root)
  app = createApp(GenerationProgressDock).use(pinia).use(router)
  app.mount(root)
  const store = useGenerationTasks(pinia)
  store.tasks = ['RUNNING', 'SUCCEEDED', 'FAILED'].map((status, index) => ({
    id: 'g' + index,
    kind: 'JOB_IMPORT',
    entityId: 'current',
    status: status as 'RUNNING' | 'SUCCEEDED' | 'FAILED',
    title: '导入' + index,
    href: '/jobs?taskId=g' + index,
    stage: status === 'RUNNING' ? '正在解析' : '结果已保存',
    error: status === 'FAILED' ? '解析失败' : null,
    resultId: null,
    createdAt: '2026-10-20',
    updatedAt: '2026-10-20',
    startedAt: null,
    finishedAt: null,
  }))
  store.notice = '导入1：已完成'
  root.querySelector('header button')!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await nextTick()
  expect(root.textContent).toContain('1 进行中')
  expect(root.textContent).toContain('解析失败')
  expect(root.textContent).toContain('正在解析')
  expect(root.querySelectorAll('a')).toHaveLength(3)
  expect(root.querySelector('a')!.getAttribute('href')).toContain('taskId=g0')
  expect(root.querySelectorAll('li button')).toHaveLength(1)
  expect(root.querySelector('[aria-live]')?.textContent).toContain('已完成')
})
