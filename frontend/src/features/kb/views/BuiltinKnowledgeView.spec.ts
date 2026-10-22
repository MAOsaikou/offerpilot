import { setLanguage } from '@/shared/utils/language'
import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin, onlineManager } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import BuiltinKnowledgeView from './BuiltinKnowledgeView.vue'
import { useBuiltinFilters } from '../stores/builtinFilters'
import * as api from '@/shared/api/builtin-knowledge'
vi.mock('@/shared/api/builtin-knowledge', async (original) => ({
  ...(await original<typeof import('@/shared/api/builtin-knowledge')>()),
  getBuiltinFacets: vi.fn(),
  listBuiltinOccupations: vi.fn(),
  getBuiltinOccupation: vi.fn(),
  listBuiltinEntries: vi.fn(),
  searchBuiltinKnowledge: vi.fn(),
}))
const occupation: api.CatalogOccupation = {
  id: 'nurse',
  name: '护士',
  family: '医疗健康',
  description: '核验交接',
  hollandCode: 'SIC',
  riasec: { S: 0.9, I: 0.7, C: 0.6 },
  coreSkills: [{ name: '主动倾听' }],
  salaryRange: null,
  salaryNotice: '暂无数据：尚无可信薪资来源',
  growthPath: null,
  growthPathSource: null,
  skillsSource: 'onet',
  onetCode: '29-1141.00',
  source: 'onet',
  sourceUrl: 'https://www.onetonline.org/link/summary/29-1141.00',
  license: 'CC BY 4.0',
  attribution: 'National Center for O*NET Development',
  notice: '美国资料中文翻译',
}
const entry: api.BuiltinEntry = {
  id: 'q-nurse',
  kind: 'QUESTION',
  title: '护士交接案例',
  content: '交接信息不一致，如何核验？',
  industry: '医疗健康',
  role: '护士',
  questionType: 'CASE',
  difficulty: 'HARD',
  source: 'curated',
  sourceUrl: null,
  license: 'CC BY 4.0',
  notice: '非真实候选人面经',
  status: 'READY',
  payload: { answerGuide: ['核验事实'] },
}
let app: App, root: HTMLDivElement, cache: QueryClient
async function settle() {
  for (let i = 0; i < 5; i++) {
    await nextTick()
    await new Promise((r) => setTimeout(r, 0))
  }
}
async function mount(path = '/kb/builtin') {
  const pinia = createPinia(),
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/kb/builtin/:occupationId?', component: BuiltinKnowledgeView },
        { path: '/kb', component: { template: '<div />' } },
      ],
    })
  await router.push(path)
  await router.isReady()
  root = document.createElement('div')
  document.body.append(root)
  app = createApp(BuiltinKnowledgeView)
    .use(pinia)
    .use(router)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
  await settle()
  return useBuiltinFilters(pinia)
}
async function click(text: string) {
  const btn = [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)
  if (!btn) throw Error(text)
  btn.click()
  await settle()
}
beforeEach(() => {
  vi.resetAllMocks()
  onlineManager.setOnline(true)
  cache = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  vi.mocked(api.getBuiltinFacets).mockResolvedValue({
    families: ['医疗健康'],
    industries: ['医疗健康', '教育'],
    roles: ['护士', '教师'],
    counts: { QUESTION: 576, COMPETENCY: 24, CAMPUS: 6, REPORT: 10 },
    occupations: 402,
    directOnetOccupations: 378,
  })
  vi.mocked(api.listBuiltinOccupations).mockResolvedValue({
    items: [occupation],
    total: 402,
    page: 1,
    size: 20,
  })
  vi.mocked(api.getBuiltinOccupation).mockResolvedValue(occupation)
  vi.mocked(api.listBuiltinEntries).mockResolvedValue({
    items: [entry],
    total: 576,
    page: 1,
    size: 20,
  })
  vi.mocked(api.searchBuiltinKnowledge).mockResolvedValue({
    items: [],
    vectorAvailable: false,
    notice: '本地向量未就绪，当前仅全文检索',
  })
})
afterEach(() => {
  app?.unmount()
  root?.remove()
  cache.clear()
  onlineManager.setOnline(true)
})
it('browses source counts and paginates through server queries', async () => {
  await mount()
  expect(root.textContent).toContain('378 条直接 O*NET')
  expect(root.textContent).toContain('576 道 curated')
  expect(root.querySelector('a[href="/kb/builtin/nurse"]')).not.toBeNull()
  await click('下一页')
  expect(api.listBuiltinOccupations).toHaveBeenLastCalledWith(
    expect.objectContaining({ page: 2 }),
    expect.any(AbortSignal),
  )
  await click('上一页')
  expect(api.listBuiltinOccupations).toHaveBeenLastCalledWith(
    expect.objectContaining({ page: 1 }),
    expect.any(AbortSignal),
  )
})
it('filters nontechnical questions by all four dimensions, resets page and searches references', async () => {
  const state = await mount()
  await click('通用题库')
  expect(root.textContent).toContain('非真实候选人面经')
  state.page = 3
  await settle()
  state.industry = '医疗健康'
  state.role = '护士'
  state.questionType = 'CASE'
  state.difficulty = 'HARD'
  await settle()
  const input = root.querySelector('input')!
  input.value = '交接'
  input.dispatchEvent(new Event('input'))
  await nextTick()
  root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(api.listBuiltinEntries).toHaveBeenLastCalledWith(
    expect.objectContaining({
      kind: 'QUESTION',
      q: '交接',
      industry: '医疗健康',
      role: '护士',
      questionType: 'CASE',
      difficulty: 'HARD',
      page: 1,
    }),
    expect.any(AbortSignal),
  )
  await click('检索参考资料')
  expect(root.textContent).toContain('当前仅全文检索')
  expect(root.textContent).toContain('暂无参考结果')
})
it('loads deep link with RIASEC, provenance and honest missing salary and path', async () => {
  await mount('/kb/builtin/nurse')
  expect(api.getBuiltinOccupation).toHaveBeenCalledWith('nurse', expect.any(AbortSignal))
  expect(api.listBuiltinOccupations).not.toHaveBeenCalled()
  for (const text of [
    'SIC',
    '暂无数据：尚无可信薪资来源',
    '暂无数据：尚无可信路径资料',
    'CC BY 4.0',
    'National Center for O*NET Development',
  ])
    expect(root.textContent).toContain(text)
})
it('shows competency framework and planned report structure, clearing inapplicable role filters', async () => {
  const state = await mount()
  state.industry = '医疗健康'
  state.role = '护士'
  vi.mocked(api.listBuiltinEntries).mockResolvedValue({
    items: [{ ...entry, kind: 'COMPETENCY', title: '护士能力模型', content: '编辑整理能力框架' }],
    total: 1,
    page: 1,
    size: 20,
  })
  await click('岗位能力模型')
  expect(root.textContent).toContain('编辑整理能力框架')
  vi.mocked(api.listBuiltinEntries).mockResolvedValue({
    items: [
      {
        ...entry,
        kind: 'REPORT',
        title: '报告入口（计划）',
        status: 'PLANNED',
        payload: { plannedSections: ['来源与许可', '统计范围'] },
      },
    ],
    total: 1,
    page: 1,
    size: 20,
  })
  await click('行业报告（计划）')
  expect(api.listBuiltinEntries).toHaveBeenLastCalledWith(
    expect.objectContaining({ role: '', questionType: '', difficulty: '' }),
    expect.any(AbortSignal),
  )
  expect(root.textContent).toContain('计划 · 暂无报告数据')
  expect(root.textContent).toContain('来源与许可')
  await click('校招流程')
  expect(api.listBuiltinEntries).toHaveBeenLastCalledWith(
    expect.objectContaining({ role: '', industry: '' }),
    expect.any(AbortSignal),
  )
})
it('renders pending, empty, failed and retry states', async () => {
  let resolve!: (v: api.CatalogPage<api.CatalogOccupation>) => void
  vi.mocked(api.listBuiltinOccupations).mockImplementationOnce(
    () => new Promise((r) => (resolve = r)),
  )
  await mount()
  expect(root.textContent).toContain('正在加载目录')
  resolve({ items: [], total: 0, page: 1, size: 20 })
  await settle()
  expect(root.textContent).toContain('暂无匹配条目')
  vi.mocked(api.listBuiltinOccupations).mockRejectedValueOnce(Error('offline'))
  root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await settle()
  expect(root.textContent).toContain('读取失败')
  await click('重试目录')
  expect(root.textContent).toContain('护士')
})
it('shows explicit offline and detail error recovery', async () => {
  onlineManager.setOnline(false)
  await mount()
  expect(root.textContent).toContain('当前离线')
  onlineManager.setOnline(true)
  await settle()
  app.unmount()
  root.remove()
  cache.clear()
  vi.mocked(api.getBuiltinOccupation).mockRejectedValueOnce(Error('404'))
  await mount('/kb/builtin/nurse')
  expect(root.textContent).toContain('职业详情读取失败')
  await click('重试详情')
  expect(root.textContent).toContain('主动倾听')
})

afterEach(() => setLanguage('zh-CN'))

it('switches knowledge tabs and filters live but preserves catalog entries', async () => {
  await mount()
  expect(root.textContent).toContain('护士')
  setLanguage('en-US')
  await nextTick()
  expect(root.textContent).toContain('General question bank')
  expect(root.textContent).toContain('Career category')
  const family = root.querySelector<HTMLOptionElement>('option[value="医疗健康"]')!
  expect(family.textContent?.trim()).toBe('Healthcare')
  ;(family.parentElement as HTMLSelectElement).value = '医疗健康'
  family.parentElement!.dispatchEvent(new Event('change'))
  await settle()
  expect(api.listBuiltinOccupations).toHaveBeenLastCalledWith(
    expect.objectContaining({ family: '医疗健康' }),
    expect.any(AbortSignal),
  )
  expect(root.textContent).toContain('Industry reports (planned)')
  expect(root.textContent).toContain('护士')
  expect(root.textContent).toContain('核验交接')
  setLanguage('zh-CN')
  await nextTick()
  expect(root.textContent).toContain('通用题库')
})
