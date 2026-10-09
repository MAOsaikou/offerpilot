import { createApp, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { expect, it, vi } from 'vitest'
import JobDetailView from './JobDetailView.vue'

vi.mock('@/shared/api/jobs', () => ({
  getJob: vi.fn().mockResolvedValue({
    id: 'job_snapshot',
    title: 'Java后端开发工程师',
    company: '字节跳动',
    locations: '北京',
    source: 'LOCAL_SNAPSHOT',
    snapshotDate: '2026-10-10',
    url: '',
    description: '示例职责',
    requirement: '示例要求',
    detailFetchedAt: null,
  }),
  refreshJob: vi.fn(),
  matchJob: vi.fn(),
  listJobMatches: vi.fn().mockResolvedValue([]),
  getJobMatch: vi.fn(),
}))
vi.mock('@/shared/api/resumes', () => ({
  listResumes: vi.fn().mockResolvedValue({ items: [], total: 0 }),
  listResumeVersions: vi.fn(),
}))
vi.mock('@/shared/composables/useBoard', () => ({
  useBoard: () => ({
    saved: () => false,
    busy: ref(false),
    error: ref(''),
    favorite: vi.fn(),
    query: { isPending: ref(false), isError: ref(false) },
  }),
}))

it('snapshot detail keeps the date tooltip and does not offer a live refresh or apply link', async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/jobs/:id', component: JobDetailView }],
  })
  await router.push('/jobs/job_snapshot')
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const root = document.createElement('div')
  const app = createApp(JobDetailView).use(router).use(VueQueryPlugin, { queryClient })
  app.mount(root)
  try {
    await vi.waitFor(() => expect(root.textContent).toContain('离线快照'))
    expect(root.querySelector('[title*="快照日期"]')?.getAttribute('title')).toContain('2026-10-10')
    expect(root.textContent).toContain('非实时数据')
    expect(root.textContent).not.toContain('更新完整详情')
    expect(root.textContent).not.toContain('尚未获取完整详情')
    expect(root.querySelector('a[target="_blank"]')).toBeNull()
  } finally {
    app.unmount()
    queryClient.clear()
  }
})
