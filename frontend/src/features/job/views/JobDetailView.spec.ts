import { createApp, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, expect, it, vi } from 'vitest'
import { getJob, refreshJob, matchJob } from '@/shared/api/jobs'
import { listResumes, listResumeVersions } from '@/shared/api/resumes'
import type { RealJob } from '@/shared/types/jobs'
import JobDetailView from './JobDetailView.vue'

vi.mock('@/shared/api/jobs', () => ({
  getJob: vi.fn(),
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
const snapshot: RealJob = {
  id: 'job_snapshot',
  site: 'bytedance',
  externalId: 'snapshot-1',
  nature: 'intern',
  title: 'Java后端开发工程师',
  company: '字节跳动',
  locations: '北京',
  category: '软件',
  department: '',
  updatedAt: '',
  source: 'LOCAL_SNAPSHOT',
  snapshotDate: '2026-10-10',
  url: '',
  description: '示例职责',
  requirement: '示例要求',
  detailFetchedAt: null,
}
beforeEach(() => {
  vi.mocked(getJob).mockReset().mockResolvedValue(snapshot)
  vi.mocked(refreshJob).mockReset()
  vi.mocked(listResumes).mockReset().mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  vi.mocked(listResumeVersions).mockReset()
  vi.mocked(matchJob).mockReset()
})
async function mountDetail() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/jobs/:id', component: JobDetailView }],
  })
  await router.push('/jobs/job_snapshot')
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const root = document.createElement('div')
  const app = createApp(JobDetailView).use(router).use(VueQueryPlugin, { queryClient })
  app.mount(root)
  await vi.waitFor(() => expect(root.textContent).toContain('示例职责'))
  return {
    root,
    router,
    queryClient,
    cleanup: () => {
      app.unmount()
      queryClient.clear()
    },
  }
}
it('snapshot detail keeps the date tooltip and does not offer a live refresh or apply link', async () => {
  const { root, cleanup } = await mountDetail()
  try {
    expect(root.textContent).toContain('离线快照')
    expect(root.querySelector('[title*="快照日期"]')?.getAttribute('title')).toContain('2026-10-10')
    expect(root.textContent).toContain('非实时数据')
    expect(root.textContent).not.toContain('更新完整详情')
    expect(root.textContent).not.toContain('尚未获取完整详情')
    expect(root.querySelector('a[target="_blank"]')).toBeNull()
  } finally {
    cleanup()
  }
})
it('the chat match entry carries the selected job, resume version and structured action', async () => {
  vi.mocked(listResumes).mockResolvedValue({
    items: [
      {
        id: 'resume_selected',
        title: '已保存简历',
        parseStatus: 'READY',
        parseProgress: 100,
        parseError: null,
        currentVersionId: 'version_selected',
        currentVersion: null,
        fileName: 'resume.txt',
        createdAt: '',
        updatedAt: '',
      },
    ],
    total: 1,
    page: 1,
    size: 20,
  })
  vi.mocked(listResumeVersions).mockResolvedValue([
    {
      id: 'version_selected',
      versionNo: 1,
      parentVersionId: null,
      source: 'UPLOAD',
      plainText: '',
      parseMeta: {},
      createdAt: '',
      structured: {
        basics: { name: '', phone: '', email: '', city: '', links: [] },
        education: [],
        experience: [],
        projects: [],
        skills: [],
        certificates: [],
        summary: '',
      },
    },
  ])
  const { root, cleanup } = await mountDetail()
  try {
    await vi.waitFor(() =>
      expect(root.querySelector('option[value="resume_selected"]')).not.toBeNull(),
    )
    const select = root.querySelector<HTMLSelectElement>(
      'select:has(option[value="resume_selected"])',
    )!
    select.value = 'resume_selected'
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await vi.waitFor(() =>
      expect(
        [...root.querySelectorAll('a')].find((a) => a.textContent?.trim() === '在聊天中匹配'),
      ).toBeDefined(),
    )
    const link = [...root.querySelectorAll('a')].find(
      (a) => a.textContent?.trim() === '在聊天中匹配',
    )!
    const url = new URL(link.getAttribute('href')!, 'http://localhost')
    expect(Object.fromEntries(url.searchParams)).toMatchObject({
      mode: 'JOB',
      jobAction: 'match',
      jobId: 'job_snapshot',
      resumeId: 'resume_selected',
      resumeVersionId: 'version_selected',
    })
  } finally {
    cleanup()
  }
})
it('refresh retains the marked stored copy and explains its availability without another GET', async () => {
  const live = { ...snapshot, source: 'company-career-site', snapshotDate: undefined }
  vi.mocked(getJob).mockResolvedValue(live)
  vi.mocked(refreshJob).mockResolvedValue({
    ...live,
    source: 'STORED_SNAPSHOT',
    originalSource: live.source,
    fallbackReason: 'LIVE_DETAIL_UNAVAILABLE',
  })
  const { root, cleanup } = await mountDetail()
  try {
    Array.from(root.querySelectorAll('button'))
      .find((b) => b.textContent?.includes('更新完整详情'))!
      .click()
    await vi.waitFor(() => expect(root.textContent).toContain('已存岗位 · 实时更新不可用'))
    expect(root.textContent).toContain('仍可进行匹配')
    expect(root.textContent).not.toContain('本次查询')
    expect(getJob).toHaveBeenCalledTimes(1)
    expect(refreshJob).toHaveBeenCalledWith('job_snapshot')
  } finally {
    cleanup()
  }
})
it('demo jobs are identified as fictional and never offer a live refresh', async () => {
  vi.mocked(getJob).mockResolvedValue({ ...snapshot, site: 'demo', source: 'demo' })
  const { root, cleanup } = await mountDetail()
  try {
    expect(root.textContent).toContain('演示岗位')
    expect(root.textContent).toContain('不接受实际投递')
    expect(root.textContent).not.toContain('企业官网 ·')
    expect(root.textContent).not.toContain('更新完整详情')
  } finally {
    cleanup()
  }
})
it('a late refresh cannot replace a different selected job', async () => {
  vi.mocked(getJob).mockImplementation(async (id) => ({
    ...snapshot,
    id,
    source: 'company-career-site',
    title: id === 'job_next' ? '下一个岗位' : snapshot.title,
  }))
  let resolve!: (value: RealJob) => void
  vi.mocked(refreshJob).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  const { root, router, queryClient, cleanup } = await mountDetail()
  try {
    Array.from(root.querySelectorAll('button'))
      .find((b) => b.textContent?.includes('更新完整详情'))!
      .click()
    await router.push('/jobs/job_next')
    await vi.waitFor(() => expect(root.textContent).toContain('下一个岗位'))
    resolve({ ...snapshot, source: 'STORED_SNAPSHOT' })
    await Promise.resolve()
    expect(root.textContent).toContain('下一个岗位')
    expect(queryClient.getQueryData<RealJob>(['job', 'job_next'])?.id).toBe('job_next')
  } finally {
    cleanup()
  }
})

function savedResume() {
  vi.mocked(listResumes).mockResolvedValue({
    items: [
      {
        id: 'resume_selected',
        title: '已保存简历',
        parseStatus: 'READY',
        parseProgress: 100,
        parseError: null,
        currentVersionId: 'version_selected',
        currentVersion: null,
        fileName: 'resume.txt',
        createdAt: '',
        updatedAt: '',
      },
    ],
    total: 1,
    page: 1,
    size: 20,
  })
  vi.mocked(listResumeVersions).mockResolvedValue([
    {
      id: 'version_selected',
      versionNo: 1,
      parentVersionId: null,
      source: 'UPLOAD',
      plainText: '',
      parseMeta: {},
      createdAt: '',
      structured: {
        basics: { name: '', phone: '', email: '', city: '', links: [] },
        education: [],
        experience: [],
        projects: [],
        skills: [],
        certificates: [],
        summary: '',
      },
    },
  ])
}
async function selectResume(root: HTMLElement) {
  await vi.waitFor(() =>
    expect(root.querySelector('option[value="resume_selected"]')).not.toBeNull(),
  )
  const select = root.querySelector<HTMLSelectElement>(
    'select:has(option[value="resume_selected"])',
  )!
  select.value = 'resume_selected'
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await vi.waitFor(() =>
    expect(
      [...root.querySelectorAll('button')].find((b) => b.textContent?.includes('生成匹配报告'))
        ?.disabled,
    ).toBe(false),
  )
}
it('deep matching shows progress, preserves the precise failure and allows an explicit retry', async () => {
  savedResume()
  let reject!: (error: Error) => void
  vi.mocked(matchJob).mockImplementationOnce(
    () =>
      new Promise((_resolve, failure) => {
        reject = failure
      }),
  )
  vi.mocked(matchJob).mockRejectedValueOnce(new Error('本次原文证据仍未通过校验'))
  const { root, cleanup } = await mountDetail()
  try {
    await selectResume(root)
    const mode = root.querySelector<HTMLSelectElement>('select:has(option[value="PRO"])')!
    mode.value = 'PRO'
    mode.dispatchEvent(new Event('change', { bubbles: true }))
    await vi.waitFor(() => expect(root.textContent).toContain('深度模式较慢'))
    const button = [...root.querySelectorAll('button')].find((b) =>
      b.textContent?.includes('生成匹配报告'),
    )!
    button.click()
    await vi.waitFor(() => expect(root.textContent).toContain('深度模式正在分析原文证据'))
    expect(button.disabled).toBe(true)
    expect(matchJob).toHaveBeenCalledWith(
      'job_snapshot',
      'resume_selected',
      'version_selected',
      expect.any(String),
      'PRO',
    )
    reject(new Error('深度匹配超过 180 秒，未保存报告，请稍后重试或切换快速模式'))
    await vi.waitFor(() => expect(root.textContent).toContain('深度匹配超过 180 秒'))
    expect(root.textContent).not.toContain('深度模式正在分析原文证据')
    const retry = [...root.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === '重新分析',
    )!
    retry.click()
    await vi.waitFor(() => expect(matchJob).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(root.textContent).toContain('本次原文证据仍未通过校验'))
    expect(vi.mocked(matchJob).mock.calls[0]![3]).not.toBe(vi.mocked(matchJob).mock.calls[1]![3])
  } finally {
    cleanup()
  }
})
it('leaving a pending match ignores its later failure and releases its progress timer', async () => {
  savedResume()
  let reject!: (error: Error) => void
  vi.mocked(matchJob).mockImplementationOnce(
    () =>
      new Promise((_resolve, failure) => {
        reject = failure
      }),
  )
  const { root, router, cleanup } = await mountDetail()
  try {
    await selectResume(root)
    ;[...root.querySelectorAll('button')]
      .find((b) => b.textContent?.includes('生成匹配报告'))!
      .click()
    await vi.waitFor(() => expect(matchJob).toHaveBeenCalledOnce())
    await router.push('/jobs/job_other')
    await vi.waitFor(() => expect(root.textContent).not.toContain('已等待'))
    reject(new Error('old match error'))
    await Promise.resolve()
    expect(root.textContent).not.toContain('old match error')
  } finally {
    cleanup()
  }
})
