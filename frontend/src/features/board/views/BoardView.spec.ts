import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import BoardView from './BoardView.vue'
import { listApplications, updateApplication, getApplication } from '@/shared/api/applications'
import type { ServerApplication, ApplicationDetail } from '@/shared/types/board'
vi.mock('@/shared/api/applications', () => ({
  listApplications: vi.fn(),
  updateApplication: vi.fn(),
  favoriteJob: vi.fn(),
  getApplication: vi.fn(),
}))
vi.mock('@/shared/api/jobs', () => ({ exportJobEvalMarkdown: vi.fn(), getJobMatch: vi.fn() }))
import { exportJobEvalMarkdown } from '@/shared/api/jobs'

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
      { path: '/jobs/:id?', component: { render: () => null } },
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
    resumeOptimizations: [],
    interviews: [],
    starStories: [],
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
})
