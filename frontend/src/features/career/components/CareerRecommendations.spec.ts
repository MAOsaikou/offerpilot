vi.mock('@/shared/components/BackgroundTaskStatus.vue', () => ({
  default: { template: '<div />' },
}))
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import seed from '../../../../../backend/offerpilot-bootstrap/src/main/resources/seed/occupations.json'
import CareerRecommendations from './CareerRecommendations.vue'
import {
  getCareerCatalog,
  getCareerRevision,
  createCareerRecommendations,
  rerankCareers,
  compareCareers,
  type CareerOccupation,
  type CareerRevision,
  type RiasecLetter,
} from '@/shared/api/career'
import { sessionRevision, saveGuest } from '@/shared/utils/authSession'
const chart = vi.hoisted(() => ({ create: vi.fn(), destroy: vi.fn() }))
vi.mock('chart.js', () => ({
  Chart: class {
    static register = vi.fn()
    destroy = chart.destroy
    constructor(...args: unknown[]) {
      chart.create(...args)
    }
  },
  RadarController: {},
  RadialLinearScale: {},
  PointElement: {},
  LineElement: {},
  Filler: {},
  Legend: {},
}))
vi.mock('@/shared/api/career', () => ({
  getCareerCatalog: vi.fn(),
  getCareerRevision: vi.fn(),
  createCareerRecommendations: vi.fn(),
  rerankCareers: vi.fn(),
  compareCareers: vi.fn(),
}))
const occupations: CareerOccupation[] = seed.occupations.slice(0, 4).map((node) => ({
  occupationId: node.id,
  name: node.name,
  family: node.family,
  riasec: node.hollandScores,
  coreSkills: node.coreSkills,
  salaryRange: node.salaryRange,
  growthPath: node.growthPath,
  source: node.source,
  match: 0.8,
}))
const axes: RiasecLetter[] = ['R', 'I', 'A', 'S', 'E', 'C']
const saved = (): CareerRevision => ({
  id: 'original',
  parentId: null,
  constraints: { city: '', minSalaryK: null, avoidOvertime: false, research: false },
  items: occupations.map((occupation, index) => ({
    occupation,
    baseScore: 0.8,
    score: 0.8,
    rank: index + 1,
    rankDelta: 0,
    changeReason: '六维兴趣基础分',
  })),
  elapsedMs: 4,
  explanationFallback: false,
  createdAt: '2026-10-19T00:00:00Z',
})
let app: App
let root: HTMLDivElement
let router: Router
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getCareerRevision).mockResolvedValue(saved())
  vi.mocked(getCareerCatalog).mockResolvedValue(
    occupations.map(({ occupationId, name }) => ({ occupationId, name })),
  )
  vi.mocked(compareCareers).mockImplementation(async (ids) => ({
    axes,
    occupations: occupations.filter((o) => ids.includes(o.occupationId)),
  }))
})
afterEach(() => {
  app?.unmount()
  root?.remove()
})
async function mount(query: Record<string, string> = {}) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/career', component: CareerRecommendations },
      { path: '/chat', component: { template: '<div />' } },
    ],
  })
  await router.push({ path: '/career', query })
  root = document.createElement('div')
  document.body.append(root)
  app = createApp(CareerRecommendations).use(router)
  app.mount(root)
  await vi.waitFor(() => expect(root.textContent).toContain(occupations[0]!.name))
}
function button(text: string) {
  return [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
    b.textContent?.includes(text),
  )!
}
function checkbox(index: number) {
  return root.querySelectorAll<HTMLInputElement>('.recommend-row input')[index]!
}
it('enforces 2–3 selection, renders real radar dimensions and accessible fallback table, and deep-links them', async () => {
  await mount()
  checkbox(0).click()
  await vi.waitFor(() => expect(root.textContent).toContain('请选择 2–3'))
  expect(compareCareers).not.toHaveBeenCalled()
  checkbox(1).click()
  await vi.waitFor(() => expect(chart.create).toHaveBeenCalled())
  expect(root.querySelector('canvas[role="img"]')).not.toBeNull()
  expect(root.querySelector('caption')?.textContent).toContain('目录原始数据')
  expect(root.querySelector('table')?.textContent).toContain('15-30k')
  const config = chart.create.mock.calls.at(-1)![1]
  expect(config.type).toBe('radar')
  expect(config.data.datasets[0].data).toEqual(
    axes.map((axis) => occupations[0]!.riasec[axis] * 100),
  )
  checkbox(2).click()
  await vi.waitFor(() => expect(selectedCount()).toBe(3))
  await vi.waitFor(() => expect(checkbox(3).disabled).toBe(true))
  expect(router.currentRoute.value.query.compare).toBe(
    occupations
      .slice(0, 3)
      .map((o) => o.occupationId)
      .join(','),
  )
  checkbox(1).click()
  await vi.waitFor(() => expect(checkbox(3).disabled).toBe(false))
  function selectedCount() {
    return root.querySelectorAll('.recommend-row input:checked').length
  }
})
it('restores comparison from URL and rejects oversized selections before calling the API', async () => {
  await mount({
    compare: occupations
      .slice(0, 2)
      .map((o) => o.occupationId)
      .join(','),
  })
  await vi.waitFor(() => expect(root.querySelector('table')).not.toBeNull())
  await router.replace({ query: { compare: occupations.map((o) => o.occupationId).join(',') } })
  await vi.waitFor(() => expect(root.textContent).toContain('请选择 2–3'))
  expect(root.querySelector('table')).toBeNull()
  expect(compareCareers).toHaveBeenCalledTimes(1)
})
it('shows rerank deltas, links the previous revision and sends the constraints once', async () => {
  await mount()
  const next = saved()
  next.id = 'reranked'
  next.parentId = 'original'
  next.items[0]!.rankDelta = 2
  next.items[1]!.rankDelta = -1
  next.items[0]!.changeReason = '偏研究：按目录 I 加权'
  vi.mocked(rerankCareers).mockResolvedValue(next)
  const city = root.querySelector<HTMLInputElement>('input[maxlength="100"]')!
  city.value = '上海'
  city.dispatchEvent(new Event('input'))
  root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await vi.waitFor(() => expect(root.textContent).toContain('↑ 上升 2'))
  expect(root.textContent).toContain('↓ 下降 1')
  expect(root.textContent).toContain('偏研究')
  expect(rerankCareers).toHaveBeenCalledTimes(1)
  expect(rerankCareers).toHaveBeenCalledWith(
    'original',
    expect.objectContaining({ city: '上海' }),
    expect.any(AbortSignal),
  )
  expect(router.currentRoute.value.query.revision).toBe('reranked')
  expect(
    [...root.querySelectorAll('a')].some(
      (a) => a.textContent?.includes('上一修订') && a.href.includes('original'),
    ),
  ).toBe(true)
})
it('keeps constraints after save failure and retries; empty state can create a revision', async () => {
  vi.mocked(getCareerRevision).mockResolvedValueOnce(null)
  await mount()
  expect(root.textContent).toContain('还没有推荐修订')
  vi.mocked(createCareerRecommendations).mockResolvedValue(saved())
  button('生成职业推荐').click()
  await vi.waitFor(() => expect(root.querySelector('form')).not.toBeNull())
  const city = root.querySelector<HTMLInputElement>('input[maxlength="100"]')!
  city.value = '杭州'
  city.dispatchEvent(new Event('input'))
  vi.mocked(rerankCareers)
    .mockRejectedValueOnce(new Error('保存暂不可用'))
    .mockResolvedValueOnce(saved())
  root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await vi.waitFor(() => expect(root.textContent).toContain('保存暂不可用'))
  expect(city.value).toBe('杭州')
  root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await vi.waitFor(() => expect(rerankCareers).toHaveBeenCalledTimes(2))
})
it('discards late results when identity changes and destroys the radar on unmount', async () => {
  await mount({
    compare: occupations
      .slice(0, 2)
      .map((o) => o.occupationId)
      .join(','),
  })
  let resolve!: (revision: CareerRevision) => void
  vi.mocked(rerankCareers).mockReturnValueOnce(
    new Promise((r) => {
      resolve = r
    }),
  )
  root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await nextTick()
  vi.mocked(getCareerRevision).mockResolvedValue(null)
  saveGuest('career-other-identity')
  await vi.waitFor(() => expect(root.textContent).toContain('还没有推荐修订'))
  resolve({ ...saved(), id: 'late-private-result' })
  await nextTick()
  expect(root.querySelector('form')).toBeNull()
  app.unmount()
  expect(chart.destroy).toHaveBeenCalled()
})

it('keeps drafts when credentials refresh for the same identity', async () => {
  await mount()
  const city = root.querySelector<HTMLInputElement>('input[maxlength="100"]')!
  city.value = '杭州'
  city.dispatchEvent(new Event('input'))
  sessionRevision.value++
  await nextTick()
  expect(city.value).toBe('杭州')
  expect(getCareerRevision).toHaveBeenCalledTimes(1)
})
