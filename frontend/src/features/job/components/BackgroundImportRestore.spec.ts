import { createApp, nextTick, type App } from 'vue'
import { createPinia, disposePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import JobImportPanel from './JobImportPanel.vue'
import { recentGenerationTasks, generationResult } from '@/shared/api/generationTasks'
import { useGenerationTasks } from '@/shared/stores/generationTasks'
vi.mock('@/shared/api/jobs', () => ({
  listImportedJobs: vi.fn(async () => []),
  parseJobImport: vi.fn(),
  saveJobImport: vi.fn(),
  readJobScreenshot: vi.fn(async () => new Blob()),
}))
vi.mock('@/shared/api/generationTasks', async (original) => ({
  ...(await original<typeof import('@/shared/api/generationTasks')>()),
  recentGenerationTasks: vi.fn(),
  generationResult: vi.fn(),
}))
let app: App, root: HTMLDivElement, pinia: ReturnType<typeof createPinia>, cache: QueryClient
const task = {
  id: 'gen_restore',
  kind: 'JOB_IMPORT',
  entityId: 'current',
  status: 'RUNNING' as const,
  title: '岗位导入解析',
  href: '/jobs',
  stage: '正在解析岗位原文',
  resultId: null,
  error: null,
  createdAt: '2026-10-20',
  updatedAt: '2026-10-20',
  startedAt: null,
  finishedAt: null,
}
afterEach(() => {
  app.unmount()
  disposePinia(pinia)
  cache.clear()
  root.remove()
})
it('restores live progress after remount and retrieves persisted review draft after completion/reload', async () => {
  vi.stubGlobal(
    'URL',
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:restored'), revokeObjectURL: vi.fn() }),
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/jobs', component: { template: '<div />' } }],
  })
  await router.push('/jobs')
  pinia = createPinia()
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  root = document.createElement('div')
  document.body.append(root)
  function mount() {
    app = createApp(JobImportPanel)
      .use(pinia)
      .use(router)
      .use(VueQueryPlugin, { queryClient: cache })
    app.mount(root)
  }
  vi.mocked(recentGenerationTasks).mockResolvedValue([task])
  mount()
  await vi.waitFor(() => expect(root.textContent).toContain('正在解析岗位原文'))
  app.unmount()
  await nextTick()
  mount()
  await vi.waitFor(() => expect(root.textContent).toContain('正在解析岗位原文'))
  vi.mocked(recentGenerationTasks).mockResolvedValue([
    { ...task, status: 'SUCCEEDED', stage: '结果已保存' },
  ])
  vi.mocked(generationResult).mockResolvedValue({
    draft: {
      title: '已保存草稿',
      company: '测试',
      locations: '上海',
      department: '',
      nature: 'campus',
      description: '岗位职责',
      requirement: '岗位要求',
      skills: ['Java'],
      parser: 'rule',
    },
    url: '',
    sourceKind: 'screenshot',
    fileIds: ['file_saved'],
    securityNotices: [],
  })
  await useGenerationTasks(pinia).refresh()
  await vi.waitFor(() =>
    expect(root.querySelector<HTMLInputElement>('input[maxlength="100"]')?.value).toBe(
      '已保存草稿',
    ),
  )
  app.unmount()
  disposePinia(pinia)
  pinia = createPinia()
  mount()
  await vi.waitFor(() =>
    expect(root.querySelector<HTMLInputElement>('input[maxlength="100"]')?.value).toBe(
      '已保存草稿',
    ),
  )
  expect(generationResult).toHaveBeenCalledWith('gen_restore')
  await vi.waitFor(() => expect(root.querySelector('img')?.alt).toBe('核对截图 1'))
})
