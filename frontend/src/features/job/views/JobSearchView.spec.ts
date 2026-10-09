import { createApp, nextTick, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { expect, it, vi } from 'vitest'
import JobSearchView from './JobSearchView.vue'
import { searchJobs } from '@/shared/api/jobs'
import type { JobSearchResult } from '@/shared/types/jobs'

vi.mock('@/shared/api/jobs', () => ({
  searchJobs: vi.fn(),
  listJobSites: vi.fn().mockResolvedValue({ sites: [] }),
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

for (const source of ['LOCAL_SNAPSHOT', 'company-career-site']) {
  it(`search results show the correct source for ${source}`, async () => {
    const value: JobSearchResult = {
      items: [],
      fetchedAt: '2026-10-10T00:00:00Z',
      meta: {
        source,
        snapshotDate: source === 'LOCAL_SNAPSHOT' ? '2026-10-10' : undefined,
        sites: ['bytedance'],
        cached: false,
        skipped: [],
        degradedSites: [],
        truncated: false,
      },
    }
    vi.mocked(searchJobs).mockResolvedValue(value)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/jobs?q=Java')
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const root = document.createElement('div')
    const app = createApp(JobSearchView).use(router).use(VueQueryPlugin, { queryClient })
    app.mount(root)
    try {
      await vi.waitFor(() => expect(root.textContent).toContain('本次返回 0 个岗位'))
      await nextTick()
      if (source === 'LOCAL_SNAPSHOT') {
        expect(root.textContent).toContain('离线快照')
        expect(root.querySelector('[title*="快照日期"]')?.getAttribute('title')).toContain(
          '2026-10-10',
        )
        expect(root.textContent).toContain('非实时数据')
        expect(root.textContent).not.toContain('企业官网 · 本次查询')
      } else {
        expect(root.textContent).not.toContain('离线快照')
        expect(root.textContent).toContain('企业官网 · 本次查询')
      }
    } finally {
      app.unmount()
      queryClient.clear()
    }
  })
}
