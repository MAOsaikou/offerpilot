import { createApp } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it } from 'vitest'
import JobSearchCard from './JobSearchCard.vue'
import type { ChatJobSearch } from '@/shared/types/jobs'

function payload(): ChatJobSearch {
  return {
    schemaVersion: 2,
    criteria: { query: 'Java & SQL', site: 'didi', nature: 'campus', location: '北京' },
    fetchedAt: '2026-09-28T00:00:00Z',
    returnedCount: 30,
    items: [
      {
        id: 'job_1',
        site: 'didi',
        company: '滴滴',
        title: 'Java工程师',
        locations: '北京',
        nature: 'campus',
        updatedAt: '',
      },
    ],
    meta: {
      cached: false,
      truncated: true,
      sites: ['didi', 'offline'],
      skipped: [{ site: 'offline', reason: '暂不可用' }],
      degradedSites: ['didi'],
    },
  }
}

async function mount(value: ChatJobSearch, check: (root: HTMLElement) => void) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/chat')
  const root = document.createElement('div')
  const app = createApp(JobSearchCard, { payload: value }).use(router)
  app.mount(root)
  try {
    check(root)
  } finally {
    app.unmount()
  }
}

it('restored search snapshots expose counts, partial failures and actionable encoded links', async () => {
  await mount(JSON.parse(JSON.stringify(payload())), (root) => {
    expect(root.textContent).toContain('本次返回 30 条，展示 1 条')
    expect(root.textContent).toContain('部分企业暂不可用：offline')
    expect(root.textContent).toContain('降级过滤')
    expect(root.textContent).toContain('上游结果已截断')
    const links = [...root.querySelectorAll('a')]
    expect(links[0]?.getAttribute('href')).toBe('/jobs/job_1')
    const more = new URL(links[1]!.getAttribute('href')!, 'http://localhost')
    expect(more.searchParams.get('q')).toBe('Java & SQL')
    expect(more.searchParams.get('site')).toBe('didi')
    expect(more.searchParams.get('location')).toBe('北京')
  })
})

it('empty results retain warnings and never imply the company has no openings', async () => {
  const value = payload()
  value.items = []
  value.returnedCount = 0
  await mount(value, (root) => {
    expect(root.textContent).toContain('本次没有找到岗位')
    expect(root.textContent).toContain('空结果不代表企业没有在招岗位')
    expect(root.querySelectorAll('article')).toHaveLength(0)
    expect(root.textContent).toContain('部分企业暂不可用')
  })
})
