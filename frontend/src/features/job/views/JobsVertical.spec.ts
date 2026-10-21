import { setLanguage } from '@/shared/utils/language'
import { createApp, nextTick } from 'vue'
import type { Component } from 'vue'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { getJobRecommendations, listJobSites, searchJobs } from '@/shared/api/jobs'
import { favoriteJob, listApplications } from '@/shared/api/applications'
import type { DailyJobRecommendations, JobSearchResult, RealJob } from '@/shared/types/jobs'
import type { ServerApplication } from '@/shared/types/board'
import JobSearchView from './JobSearchView.vue'
import DailyJobFeed from '../components/DailyJobFeed.vue'
import JobSearchCard from '../components/JobSearchCard.vue'

vi.mock('@/shared/api/jobs', () => ({
  getJobRecommendations: vi.fn(),
  listJobSites: vi.fn(),
  searchJobs: vi.fn(),
}))
vi.mock('@/shared/api/applications', () => ({
  favoriteJob: vi.fn(),
  listApplications: vi.fn(),
  updateApplication: vi.fn(),
}))
vi.mock('../components/JobImportPanel.vue', () => ({ default: { template: '<div />' } }))
const job: RealJob = {
  id: 'job_real',
  externalId: 'external',
  site: 'fixture',
  title: 'Java开发',
  company: '测试企业',
  locations: '上海',
  nature: 'intern',
  department: '',
  category: '',
  updatedAt: '',
  description: '开发',
  requirement: 'Java',
  url: '',
  source: 'company-career-site',
  detailFetchedAt: null,
  retrievalSource: 'LOCAL_DB',
}
const result: JobSearchResult = {
  items: [job],
  fetchedAt: '2026-10-20T00:00:00Z',
  criteria: {
    query: 'Java',
    company: '测试企业',
    site: 'fixture',
    nature: 'intern',
    location: '上海',
    parser: 'rule',
  },
  meta: {
    sites: ['fixture'],
    cached: false,
    truncated: false,
    skipped: [],
    degradedSites: [],
    partial: true,
    timedOut: true,
  },
}
const feed: DailyJobRecommendations = {
  date: '2026-10-20',
  generatedAt: '',
  cached: true,
  rankingVersion: 'lexical-v1',
  items: [{ jobId: job.id, job, score: 80, reasons: ['目标职业匹配：开发', '保存简历匹配：Java'] }],
}
const mounted: Array<() => void> = []
beforeEach(() => {
  vi.mocked(listApplications).mockReset().mockResolvedValue([])
  vi.mocked(listJobSites).mockReset().mockResolvedValue({ sites: [] })
  vi.mocked(searchJobs).mockReset().mockResolvedValue(result)
  vi.mocked(getJobRecommendations).mockReset().mockResolvedValue(feed)
  vi.mocked(favoriteJob)
    .mockReset()
    .mockImplementation(async () => {
      const saved = {
        id: 'app_one',
        jobId: job.id,
        status: 'TODO',
        job,
        note: '',
        createdAt: '',
        updatedAt: '',
        nextAction: '',
        nextActionAt: null,
      } as ServerApplication
      vi.mocked(listApplications).mockResolvedValue([saved])
      return saved
    })
})
afterEach(() => {
  mounted.splice(0).forEach((cleanup) => cleanup())
})
async function mount(component: Component, path = '/jobs', props?: Record<string, unknown>) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
  })
  await router.push(path)
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const root = document.createElement('div')
  const app = createApp(component, props).use(router).use(VueQueryPlugin, { queryClient })
  document.body.append(root)
  app.mount(root)
  mounted.push(() => {
    app.unmount()
    queryClient.clear()
    root.remove()
  })
  return root
}
it('search renders parsed filters, timeout partial results and persists the joined-board state after repeated clicks', async () => {
  const root = await mount(JobSearchView, '/jobs?q=Java&nature=all')
  await vi.waitFor(() => expect(root.textContent).toContain('Java开发'))
  expect(root.textContent).toContain('查询超时，已返回部分结果')
  expect(root.textContent).toContain('规则解析')
  expect(root.textContent).toContain('本地库')
  const button = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
    (b) => b.textContent?.trim() === '收藏',
  )!
  button.click()
  button.click()
  await vi.waitFor(() => expect(button.textContent?.trim()).toBe('已收藏'))
  expect(favoriteJob).toHaveBeenCalledTimes(1)
  expect(button.disabled).toBe(true)
  expect(searchJobs).toHaveBeenCalledWith(
    { query: 'Java', nature: 'all', site: '', location: '' },
    expect.any(AbortSignal),
  )
})
it('daily feed shows explanations, links, cache date and idempotent board state', async () => {
  const root = await mount(DailyJobFeed)
  await vi.waitFor(() => expect(root.textContent).toContain('Java开发'))
  expect(root.textContent).toContain('今日缓存')
  expect(root.textContent).toContain('保存简历匹配：Java')
  expect(root.querySelector('a')?.getAttribute('href')).toBe('/jobs/job_real')
  const button = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
    (b) => b.textContent?.trim() === '加入看板',
  )!
  button.click()
  button.click()
  await vi.waitFor(() => expect(button.textContent).toContain('已加入看板'))
  expect(favoriteJob).toHaveBeenCalledTimes(1)
})
it('empty recommendations display guidance without sample jobs; failures can retry', async () => {
  vi.mocked(getJobRecommendations)
    .mockRejectedValueOnce(new Error('推荐暂不可用'))
    .mockResolvedValue({ ...feed, items: [] })
  const root = await mount(DailyJobFeed)
  await vi.waitFor(() => expect(root.textContent).toContain('推荐暂不可用'))
  ;[...root.querySelectorAll<HTMLButtonElement>('button')]
    .find((b) => b.textContent?.trim() === '重试')!
    .click()
  await vi.waitFor(() => expect(root.textContent).toContain('暂无可推荐的真实岗位'))
  expect(root.textContent).not.toContain('Java开发')
  await nextTick()
})

it('persisted chat search cards retain partial/source labels and require an explicit board click', async () => {
  const payload = {
    schemaVersion: 2,
    criteria: { query: 'Java', site: '', nature: 'all', location: '' },
    fetchedAt: result.fetchedAt,
    returnedCount: 1,
    items: [job],
    meta: result.meta,
  }
  const root = await mount(JobSearchCard, '/chat', { payload })
  await vi.waitFor(() => expect(root.textContent).toContain('本地库记录'))
  expect(root.textContent).toContain('查询超时，已返回部分结果')
  expect(favoriteJob).not.toHaveBeenCalled()
  const button = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
    (b) => b.textContent?.trim() === '加入看板',
  )!
  await vi.waitFor(() => expect(button.disabled).toBe(false))
  button.click()
  await vi.waitFor(() => expect(button.textContent).toContain('已加入看板'))
  expect(favoriteJob).toHaveBeenCalledTimes(1)
})

afterEach(() => setLanguage('zh-CN'))

it('switches job search labels live without translating listing content', async () => {
  const root = await mount(JobSearchView, '/jobs?q=Java&nature=all')
  await vi.waitFor(() => expect(root.textContent).toContain('Java开发'))
  const calls = vi.mocked(searchJobs).mock.calls.length
  setLanguage('en-US')
  await nextTick()
  expect(root.textContent).toContain('Find your next opportunity')
  expect(root.textContent).toContain('Save job')
  expect(root.textContent).toContain('Java开发')
  expect(root.textContent).toContain('测试企业')
  expect(searchJobs).toHaveBeenCalledTimes(calls)
  setLanguage('zh-CN')
  await nextTick()
  expect(root.textContent).toContain('发现适合你的下一站')
})
