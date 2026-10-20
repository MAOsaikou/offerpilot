import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import SkillGapAnalysis from './SkillGapAnalysis.vue'
import { getSkillGap, type SkillGapReport } from '@/shared/api/skill-gap'
import { getCareerCatalog } from '@/shared/api/career'
import { listApplications } from '@/shared/api/applications'
import { listImportedJobs } from '@/shared/api/jobs'
vi.mock('@/shared/api/skill-gap', () => ({ getSkillGap: vi.fn() }))
vi.mock('@/shared/api/career', () => ({ getCareerCatalog: vi.fn() }))
vi.mock('@/shared/api/applications', () => ({ listApplications: vi.fn() }))
vi.mock('@/shared/api/jobs', () => ({ listImportedJobs: vi.fn() }))
let app: App, host: HTMLDivElement
const report: SkillGapReport = {
  targetType: 'OCCUPATION',
  targetId: 'occ',
  targetTitle: '后端开发',
  resumeVersionId: 'v1',
  notice: '材料证据覆盖，不是技能测评',
  skills: [
    {
      name: 'Java',
      level: 'POSSESSED',
      sources: [
        {
          type: 'GITHUB',
          id: 'url',
          label: 'GitHub · fixture/project',
          excerpt: 'Java：100字节；本人提交3',
          fetchedAt: '2026-10-20',
        },
      ],
    },
    {
      name: 'Docker',
      level: 'PARTIAL',
      sources: [
        { type: 'PROFILE', id: null, label: '画像自述', excerpt: 'Docker', fetchedAt: null },
      ],
    },
    {
      name: 'SQL',
      level: 'MISSING',
      sources: [
        {
          type: 'ABSENCE',
          id: null,
          label: '未找到对应技能材料',
          excerpt: '未提及不代表没有能力',
          fetchedAt: null,
        },
      ],
    },
  ],
}
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (label: string) =>
  [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!
async function mount() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: SkillGapAnalysis }],
  })
  await router.push('/')
  app = createApp(SkillGapAnalysis).use(router)
  app.mount(host)
  await flush()
}
beforeEach(() => {
  vi.resetAllMocks()
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(getCareerCatalog).mockResolvedValue([{ occupationId: 'occ', name: '后端开发' }])
  vi.mocked(listApplications).mockResolvedValue([])
  vi.mocked(listImportedJobs).mockResolvedValue([])
  vi.mocked(getSkillGap).mockResolvedValue(report)
})
afterEach(() => {
  app?.unmount()
  host.remove()
})
it('analyzes the saved target and displays three grades with evidence and fetch date', async () => {
  await mount()
  button('分析技能差距').click()
  await flush()
  expect(getSkillGap).toHaveBeenCalledWith({}, expect.any(AbortSignal))
  for (const text of [
    'Java · 已具备',
    'Docker · 部分',
    'SQL · 缺失',
    'GitHub · fixture/project',
    '画像自述',
    '2026-10-20',
    '未提及不代表没有能力',
  ])
    expect(host.textContent).toContain(text)
  const select = host.querySelector('select')!
  select.value = 'occ'
  select.dispatchEvent(new Event('change'))
  await flush()
  expect(host.textContent).not.toContain('Java · 已具备')
  button('分析技能差距').click()
  await flush()
  expect(getSkillGap).toHaveBeenLastCalledWith({ occupationId: 'occ' }, expect.any(AbortSignal))
})
it('supports a saved job, retries failures and reports an honest empty result', async () => {
  vi.mocked(listImportedJobs).mockResolvedValue([{ id: 'job', title: '导入岗位' }] as Awaited<
    ReturnType<typeof listImportedJobs>
  >)
  await mount()
  const select = host.querySelector('select')!
  select.value = 'job:job'
  select.dispatchEvent(new Event('change'))
  await flush()
  vi.mocked(getSkillGap)
    .mockRejectedValueOnce(new Error('离线，请重试'))
    .mockResolvedValueOnce({ ...report, skills: [] })
  button('分析技能差距').click()
  await flush()
  expect(host.textContent).toContain('离线，请重试')
  button('分析技能差距').click()
  await flush()
  expect(getSkillGap).toHaveBeenLastCalledWith({ jobId: 'job' }, expect.any(AbortSignal))
  expect(host.textContent).toContain('暂无可识别的目标技能')
})
it('keeps saved-target analysis usable when optional target lists are unavailable', async () => {
  vi.mocked(getCareerCatalog).mockRejectedValueOnce(new Error('offline'))
  await mount()
  expect(host.textContent).toContain('部分目标未能加载')
  button('分析技能差距').click()
  await flush()
  expect(host.textContent).toContain('后端开发')
})
