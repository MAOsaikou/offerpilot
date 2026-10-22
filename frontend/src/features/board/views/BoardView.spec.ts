import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import BoardView from './BoardView.vue'
import { listApplications, updateApplication, getApplication } from '@/shared/api/applications'
import { listPendingAgentApprovals, decidePendingAgentApproval } from '@/shared/api/agentApprovals'
import type { ServerApplication, ApplicationDetail } from '@/shared/types/board'
vi.mock('@/shared/api/applications', () => ({
  listApplications: vi.fn(),
  updateApplication: vi.fn(),
  favoriteJob: vi.fn(),
  getApplication: vi.fn(),
}))
vi.mock('@/shared/api/agentApprovals', async (load) => ({
  ...(await load<typeof import('@/shared/api/agentApprovals')>()),
  listPendingAgentApprovals: vi.fn(),
  decidePendingAgentApproval: vi.fn(),
}))
vi.mock('@/shared/api/jobs', () => ({ exportJobEvalMarkdown: vi.fn(), getJobMatch: vi.fn() }))
import { exportJobEvalMarkdown } from '@/shared/api/jobs'
import { exportInterviewRecap } from '@/shared/api/interviews'
vi.mock('@/shared/api/interviews', () => ({ exportInterviewRecap: vi.fn() }))

const fixture = (): ServerApplication => ({
  id: 'app_1',
  jobId: 'job_1',
  status: 'TODO',
  note: '原备注',
  nextAction: '',
  nextActionAt: null,
  createdAt: '2026-10-04',
  updatedAt: '2026-10-04',
  job: {
    id: 'job_1',
    title: '工程师',
    company: '企业',
    locations: '上海',
    externalId: '1',
    site: 'fixture',
    category: '',
    nature: 'campus',
    department: '',
    updatedAt: '',
    url: '',
    source: '',
    description: '',
    requirement: '',
    detailFetchedAt: null,
  },
})
let app: App, root: HTMLDivElement, cache: QueryClient
let server: ServerApplication[]
async function mount() {
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/board', component: BoardView },
      { path: '/chat', component: { render: () => null } },
      { path: '/jobs/:id?', component: { render: () => null } },
      { path: '/resumes/:id?', component: { render: () => null } },
      { path: '/interviews/:id?', component: { render: () => null } },
    ],
  })
  await router.push('/board')
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(BoardView) })
    .use(router)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
}
function unmount() {
  app.unmount()
  cache.clear()
  root.remove()
}
beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value() {
      this.setAttribute('open', '')
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value() {
      this.removeAttribute('open')
    },
  })
  vi.resetAllMocks()
  server = [fixture()]
  vi.mocked(listApplications).mockImplementation(async () => structuredClone(server))
  vi.mocked(listPendingAgentApprovals).mockResolvedValue([])
  vi.mocked(updateApplication).mockImplementation(async (_id, patch) => {
    server[0] = { ...server[0]!, ...patch }
    return structuredClone(server[0])
  })
})
afterEach(unmount)
it('loads server state, saves notes and stages, and restores with a fresh cache', async () => {
  await mount()
  await vi.waitFor(() => expect(root.querySelector('textarea')?.value).toBe('原备注'))
  const input = root.querySelector('textarea')!
  input.value = '准备项目'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  expect(root.querySelector('select')!.disabled).toBe(true)
  root.querySelector('article button')!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await vi.waitFor(() =>
    expect(updateApplication).toHaveBeenCalledWith('app_1', { note: '准备项目' }),
  )
  await vi.waitFor(() => expect(root.querySelector('select')!.disabled).toBe(false))
  const select = root.querySelector('select')!
  select.value = 'INTERVIEW'
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await vi.waitFor(() => expect(root.querySelector('select')!.value).toBe('INTERVIEW'))
  unmount()
  await mount()
  await vi.waitFor(() => expect(root.querySelector('textarea')?.value).toBe('准备项目'))
  expect(root.querySelector('select')!.value).toBe('INTERVIEW')
})
it('keeps unsaved note and original stage on failure and permits retry', async () => {
  await mount()
  await vi.waitFor(() => expect(root.querySelector('textarea')).not.toBeNull())
  vi.mocked(updateApplication).mockRejectedValueOnce(new Error('离线'))
  const select = root.querySelector('select')!
  select.value = 'OFFER'
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await vi.waitFor(() => expect(root.textContent).toContain('离线'))
  expect(select.value).toBe('TODO')
  const input = root.querySelector('textarea')!
  input.value = '草稿'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  vi.mocked(updateApplication).mockRejectedValueOnce(new Error('保存失败'))
  root.querySelector('article button')!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await vi.waitFor(() => expect(root.textContent).toContain('保存失败'))
  expect(input.value).toBe('草稿')
  expect(server[0]!.note).toBe('原备注')
  root.querySelector('article button')!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await vi.waitFor(() => expect(server[0]!.note).toBe('草稿'))
})
it('shows load failures with retry instead of an empty board', async () => {
  vi.mocked(listApplications).mockRejectedValueOnce(new Error('加载失败'))
  await mount()
  await vi.waitFor(() => expect(root.textContent).toContain('加载失败'))
  expect(root.textContent).not.toContain('把第一个机会放进看板')
  root.querySelector('button')!.click()
  await vi.waitFor(() => expect(root.querySelector('textarea')).not.toBeNull())
})

it('opens the selected workspace, retries detail, preserves failed edits and exports the linked report', async () => {
  const detail = (): ApplicationDetail => ({
    application: structuredClone(server[0]!),
    latestMatch: {
      id: 'match_1',
      grade: 'C',
      createdAt: '2026-10-11',
      topGaps: ['补齐项目证据'],
      exportUrl: '/api/v1/jobs/matches/match_1/eval.md',
    },
    resumeOptimizations: [
      {
        id: 'opt_1',
        resumeId: 'res_1',
        resumeVersionId: 'ver_1',
        createdAt: '2026-10-11',
        status: 'SUCCEEDED',
        viewUrl: '/resumes/res_1',
        pdfAvailable: true,
        exportUrl: '/api/v1/resumes/res_1/export.pdf',
      },
    ],
    interviews: [
      {
        id: 'iv_1',
        status: 'IN_PROGRESS',
        overallScore: 42,
        createdAt: '2026-10-11',
        recapExportUrl: '/api/v1/interviews/iv_1/recap.md',
      },
    ],
    starStories: [{ id: 'star_1', title: '项目复盘', sourceSessionId: 'iv_1' }],
    linkageNote: '仅按岗位 ID 关联',
    timeline: [
      {
        id: 'evt_1',
        kind: 'CREATED',
        fromStatus: null,
        toStatus: 'TODO',
        detail: '加入求职看板',
        actor: 'USER',
        artifactType: null,
        artifactId: null,
        createdAt: '2026-10-11T00:00:00Z',
      },
    ],
  })
  vi.mocked(getApplication)
    .mockRejectedValueOnce(new Error('详情读取失败'))
    .mockImplementation(async () => detail())
  await mount()
  await vi.waitFor(() => expect(root.querySelector('article a')).not.toBeNull())
  root.querySelector<HTMLAnchorElement>('article a')!.click()
  await vi.waitFor(() =>
    expect(document.querySelector('dialog')?.textContent).toContain('详情读取失败'),
  )
  document.querySelector<HTMLButtonElement>('dialog .error-banner button')!.click()
  await vi.waitFor(() =>
    expect(document.querySelector('dialog')?.textContent).toContain('补齐项目证据'),
  )
  const drawer = document.querySelector('dialog')!
  expect(drawer.textContent).toContain('加入求职看板')
  const links = [...drawer.querySelectorAll('a')].map((a) => a.getAttribute('href'))
  expect(links).toContain(
    '/resumes/res_1?tab=optimize&optimizationReportId=opt_1&resumeVersionId=ver_1&jobId=job_1',
  )
  expect(links).toContain('/interviews/iv_1')
  expect(links).toContain('/interviews?jobId=job_1&role=%E5%B7%A5%E7%A8%8B%E5%B8%88')
  ;[...drawer.querySelectorAll('button')]
    .find((button) => button.textContent?.includes('下载复盘.md'))!
    .click()
  await vi.waitFor(() => expect(exportInterviewRecap).toHaveBeenCalledWith('iv_1'))
  await vi.waitFor(() =>
    expect(
      [...drawer.querySelectorAll('button')].find((button) =>
        button.textContent?.includes('下载评估.md'),
      )!.disabled,
    ).toBe(false),
  )
  ;[...drawer.querySelectorAll('button')]
    .find((button) => button.textContent?.includes('下载评估.md'))!
    .click()
  await vi.waitFor(() => expect(exportJobEvalMarkdown).toHaveBeenCalledWith('match_1'))
  const action = drawer.querySelector<HTMLTextAreaElement>('form textarea')!
  action.value = '准备 STAR 复盘'
  action.dispatchEvent(new Event('input', { bubbles: true }))
  const date = drawer.querySelector<HTMLInputElement>('input[type=date]')!
  date.value = '2026-10-12'
  date.dispatchEvent(new Event('input', { bubbles: true }))
  vi.mocked(updateApplication).mockRejectedValueOnce(new Error('保存失败'))
  drawer
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await vi.waitFor(() => expect(drawer.textContent).toContain('保存失败'))
  expect(action.value).toBe('准备 STAR 复盘')
  drawer
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await vi.waitFor(() => expect(server[0]!.nextAction).toBe('准备 STAR 复盘'))
  await vi.waitFor(() =>
    expect(drawer.querySelector<HTMLButtonElement>('form button')!.disabled).toBe(true),
  )
  drawer.dispatchEvent(new Event('cancel', { cancelable: true }))
  await vi.waitFor(() => expect(document.querySelector('dialog')).toBeNull())
  // Newly created artifacts must appear even while the previous detail cache is fresh.
  cache.setQueryDefaults(['applications', 'detail'], { staleTime: 60_000 })
  vi.mocked(getApplication).mockImplementation(async () => ({
    ...detail(),
    starStories: [{ id: 'star_2', title: '刚保存的岗位故事', sourceSessionId: 'iv_1' }],
  }))
  root.querySelector<HTMLAnchorElement>('article a')!.click()
  await vi.waitFor(() =>
    expect(document.querySelector('dialog')?.textContent).toContain('刚保存的岗位故事'),
  )
})
function dragEvent(type: string, data: Map<string, string>) {
  const event = new Event(type, { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'dataTransfer', {
    value: {
      types: [...data.keys()],
      setData: (k: string, v: string) => data.set(k, v),
      getData: (k: string) => data.get(k) ?? '',
      effectAllowed: '',
      dropEffect: '',
    },
  })
  return event
}
const column = (stage: string) => root.querySelector(`[data-stage="${stage}"]`)!
it('dragging a card to another column moves it at once and rolls back if the save fails', async () => {
  await mount()
  await vi.waitFor(() => expect(column('TODO').querySelector('article')).not.toBeNull())
  let release!: () => void
  vi.mocked(updateApplication).mockImplementationOnce(async (_id, patch) => {
    await new Promise<void>((resolve) => (release = resolve))
    server[0] = { ...server[0]!, ...patch }
    return structuredClone(server[0])
  })
  const data = new Map<string, string>()
  column('TODO').querySelector('article')!.dispatchEvent(dragEvent('dragstart', data))
  const over = dragEvent('dragover', data)
  column('APPLIED').dispatchEvent(over)
  expect(over.defaultPrevented).toBe(true)
  column('APPLIED').dispatchEvent(dragEvent('drop', data))
  // Optimistic: the card is in the new column before the server answers.
  await vi.waitFor(() => expect(column('APPLIED').querySelector('article')).not.toBeNull())
  expect(column('TODO').querySelector('article')).toBeNull()
  expect(updateApplication).toHaveBeenCalledWith('app_1', { status: 'APPLIED' })
  release()
  await vi.waitFor(() => expect(server[0]!.status).toBe('APPLIED'))

  vi.mocked(updateApplication).mockRejectedValueOnce(new Error('离线'))
  column('APPLIED').querySelector('article')!.dispatchEvent(dragEvent('dragstart', data))
  column('OFFER').dispatchEvent(dragEvent('drop', data))
  await vi.waitFor(() => expect(root.textContent).toContain('卡片已退回原来的列'))
  expect(column('APPLIED').querySelector('article')).not.toBeNull()
  expect(column('OFFER').querySelector('article')).toBeNull()
})
it('a card with an unsaved note cannot be dragged', async () => {
  await mount()
  await vi.waitFor(() => expect(root.querySelector('textarea')).not.toBeNull())
  expect(root.querySelector('article')!.getAttribute('draggable')).toBe('true')
  const input = root.querySelector('textarea')!
  input.value = '未保存'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
  expect(root.querySelector('article')!.getAttribute('draggable')).toBe('false')
})
it('an agent write left pending by a closed chat is applied only after approval on the board', async () => {
  let pending = [
    {
      id: 'ap_1',
      runId: 'run_1',
      action: 'updateApplicationStatus' as const,
      args: { applicationId: 'app_1', status: 'INTERVIEW' },
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
      createdAt: new Date().toISOString(),
      company: '企业',
      title: '工程师',
      currentStatus: 'TODO',
      summary: null,
    },
    {
      id: 'ap_2',
      runId: 'run_2',
      action: 'adoptTargetCareer',
      args: { occupationId: 'occ_1', name: '数据分析师' },
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
      createdAt: new Date().toISOString(),
      company: null,
      title: null,
      currentStatus: null,
      summary: '把职业目标设为「数据分析师」',
    },
  ]
  vi.mocked(listPendingAgentApprovals).mockImplementation(async () => structuredClone(pending))
  vi.mocked(decidePendingAgentApproval).mockImplementation(async () => {
    server[0] = { ...server[0]!, status: 'INTERVIEW' }
    pending = []
    return { decision: 'APPROVE', applied: true, summary: null }
  })
  await mount()
  await vi.waitFor(() =>
    expect(root.textContent).toContain('「企业 · 工程师」：阶段从「收藏」改为「面试中」'),
  )
  expect(root.textContent).not.toContain('数据分析师')
  expect(column('TODO').querySelector('article')).not.toBeNull()
  const approve = [...root.querySelectorAll('button')].find(
    (b) => b.textContent?.trim() === '批准',
  )!
  approve.click()
  await vi.waitFor(() => expect(column('INTERVIEW').querySelector('article')).not.toBeNull())
  expect(decidePendingAgentApproval).toHaveBeenCalledWith('ap_1', 'APPROVE')
  await vi.waitFor(() => expect(root.textContent).not.toContain('待你确认的 Agent 看板操作'))
})
