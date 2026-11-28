import { createApp, h, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, expect, it, vi } from 'vitest'
import DashboardView from './DashboardView.vue'
import { getCareerProfile, type CareerProfile } from '@/shared/api/career'
import { listInterviews } from '@/shared/api/interviews'
import { listResumes } from '@/shared/api/resumes'
import { listApplications } from '@/shared/api/applications'
import { learningTasks, useWorkspace } from '@/shared/stores/workspace'
import type { InterviewSummary } from '@/shared/types/interview'
import type { ServerApplication } from '@/shared/types/board'

vi.mock('@/shared/api/career', () => ({
  getCareerProfile: vi.fn(),
  saveCareerProfile: vi.fn(),
  saveCareerTasks: vi.fn(),
}))
vi.mock('@/shared/api/interviews', () => ({ listInterviews: vi.fn() }))
vi.mock('@/shared/api/resumes', () => ({ listResumes: vi.fn() }))
vi.mock('@/shared/api/applications', () => ({
  listApplications: vi.fn(),
  favoriteJob: vi.fn(),
  updateApplication: vi.fn(),
}))
vi.mock('@/shared/components/PendingAgentWrites.vue', () => ({ default: { render: () => null } }))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

const profile: CareerProfile = {
  target: '后端开发工程师',
  city: '上海',
  skills: 'Java',
  interests: [],
  major: '',
  courses: '',
  valuesText: '',
  assessment: null,
  completedTasks: learningTasks.slice(0, 3),
  updatedAt: '2026-11-27T00:00:00Z',
}
const interviews = Array.from({ length: 4 }, (_, index) => ({
  id: `iv_${index}`,
  role: 'Java 后端实习生',
  kind: '综合',
  questionIndex: 0,
  completed: index === 0,
  createdAt: '',
  updatedAt: '',
})) as InterviewSummary[]

function application(id: string, status: ServerApplication['status']): ServerApplication {
  return {
    id,
    jobId: `job_${id}`,
    status,
    note: '',
    nextAction: '',
    nextActionAt: null,
    createdAt: '',
    updatedAt: '',
    job: {
      id: `job_${id}`,
      externalId: id,
      site: 'demo',
      company: '星河科技',
      title: 'Java 后端实习生',
      locations: '上海',
      category: '',
      nature: 'intern',
      department: '',
      updatedAt: '',
      url: '',
      source: 'demo',
      description: '',
      requirement: '',
      detailFetchedAt: null,
    },
  }
}

let app: App | undefined
let root: HTMLDivElement | undefined
let cache: QueryClient | undefined

afterEach(() => {
  app?.unmount()
  cache?.clear()
  root?.remove()
  app = undefined
  root = undefined
  localStorage.clear()
})

it('matches career, interview and board sources and does not flash seeded zeros', async () => {
  const career = deferred<CareerProfile>()
  const practice = deferred<InterviewSummary[]>()
  const resumes = deferred<{ items: []; total: number; page: number; size: number }>()
  const board = deferred<ServerApplication[]>()
  vi.mocked(getCareerProfile).mockImplementation(() => career.promise)
  vi.mocked(listInterviews).mockImplementation(() => practice.promise)
  vi.mocked(listResumes).mockImplementation(() => resumes.promise)
  vi.mocked(listApplications).mockImplementation(() => board.promise)

  const pinia = createPinia()
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: DashboardView },
      { path: '/:pathMatch(.*)*', component: { render: () => null } },
    ],
  })
  await router.push('/')
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(RouterView) })
    .use(pinia)
    .use(router)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
  const workspace = useWorkspace(pinia)
  workspace.state.target = '前端开发工程师'
  workspace.state.tasks = []
  workspace.state.interviews = [
    {
      id: 'local',
      role: '前端开发工程师',
      kind: '综合',
      date: '',
      question: 0,
      answers: [],
      completed: false,
    },
  ]
  await nextTick()
  if (!root) throw new Error('dashboard was not mounted')
  const view = root

  expect(view.textContent).not.toContain('前端开发工程师')
  expect(view.textContent).not.toContain('已完成 0 / 6')
  expect(view.textContent).not.toContain('0 场面试练习')
  expect(view.textContent).toContain('正在读取职业档案')
  expect(
    [...view.querySelectorAll('.route-count')].map((node) => node.textContent?.trim()),
  ).toEqual(['—', '—', '—', '—'])

  career.resolve(profile)
  practice.resolve(interviews)
  resumes.resolve({ items: [], total: 3, page: 1, size: 20 })
  board.resolve([
    application('a', 'TODO'),
    application('b', 'TODO'),
    application('c', 'INTERVIEW'),
    application('d', 'INTERVIEW'),
    application('e', 'OFFER'),
    application('f', 'CLOSED'),
  ])
  await vi.waitFor(() => expect(view.textContent).toContain('后端开发工程师'))
  expect(view.textContent).toContain('已完成 3 / 6 项成长行动。你有 3 份简历、4 场面试练习。')
  expect(view.textContent).toContain('打磨简历中的项目成果')
  expect(view.textContent).not.toContain('前端开发工程师')
  expect(workspace.state.target).toBe('前端开发工程师')
  expect(
    [...view.querySelectorAll('.route-count')].map((node) => node.textContent?.trim()),
  ).toEqual(['2', '0', '2', '1'])
  expect(view.textContent).toContain('另有 1 个机会已结束')
})
