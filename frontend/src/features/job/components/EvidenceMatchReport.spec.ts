import { createApp, nextTick } from 'vue'
import { afterEach, expect, it } from 'vitest'
import type { EvidenceMatch } from '@/shared/types/jobs'
import EvidenceMatchReport from './EvidenceMatchReport.vue'

const report: EvidenceMatch = {
  id: 'match-1',
  jobId: 'job-1',
  resumeId: 'resume-1',
  resumeVersionId: 'version-2',
  score: 63,
  resumeTitle: '已保存的简历',
  resumeVersionNo: 2,
  summary: '已有开发证据，待补数据库与部署经验',
  createdAt: '2026-09-21',
  model: 'test',
  requirements: [
    {
      requirement: 'Java',
      status: 'PARTIAL',
      reason: '待补证据',
      suggestion: '补充真实项目细节',
      jdQuote: '熟悉Java',
      resumePath: '/projects/0/description',
      resumeQuote: 'Java项目',
    },
    {
      requirement: 'SQL',
      status: 'MET',
      reason: '项目中明确使用SQL',
      suggestion: '准备数据库实践案例',
      jdQuote: '掌握SQL',
      resumePath: '/projects/0/description',
      resumeQuote: '使用SQL',
    },
    {
      requirement: '协作',
      status: 'MET',
      reason: '有团队协作经历',
      suggestion: '准备协作案例',
      jdQuote: '团队协作',
      resumePath: '/projects/1/description',
      resumeQuote: '与团队协作交付',
    },
    {
      requirement: 'Docker',
      status: 'MISSING',
      reason: '简历未提供部署证据',
      suggestion: '补充真实部署经历',
      jdQuote: '使用Docker部署',
      resumePath: '',
      resumeQuote: '',
    },
  ],
  jobSnapshot: {
    id: 'job-1',
    externalId: 'external-1',
    site: 'test',
    company: '测试企业',
    title: '工程师',
    locations: '上海',
    category: '研发',
    nature: '校招',
    department: '研发部',
    updatedAt: '2026-09-21',
    url: 'https://example.com/jobs/1',
    source: 'test',
    description: '参与开发，团队协作',
    requirement: '熟悉Java，掌握SQL，使用Docker部署',
    detailFetchedAt: '2026-09-21',
  },
  resumeSnapshot: {
    projects: [
      { description: '曾完成Java项目，使用SQL' },
      { description: '与团队协作交付，按期上线' },
    ],
  },
}

const mounted: { app: ReturnType<typeof createApp>; root: HTMLDivElement }[] = []
function mountReport(payload: EvidenceMatch = report) {
  const root = document.createElement('div')
  const app = createApp(EvidenceMatchReport, { payload })
  document.body.append(root)
  app.mount(root)
  mounted.push({ app, root })
  return root
}
afterEach(() => {
  for (const { app, root } of mounted.splice(0)) {
    app.unmount()
    root.remove()
  }
})

it('exposes row statuses, text labels and inline evidence for mixed coverage', () => {
  const root = mountReport()
  const labels = { MET: '有明确证据', PARTIAL: '部分体现', MISSING: '简历未体现' }
  const rows = root.querySelectorAll('.requirement-row')
  expect(rows).toHaveLength(report.requirements.length)
  report.requirements.forEach((item, index) => {
    const row = rows[index]!
    expect(row.getAttribute('data-status')).toBe(item.status)
    expect(row.querySelector('.status-chip')?.textContent).toBe(labels[item.status])
    expect(row.querySelector('.evidence-quote')?.textContent).toBe(item.jdQuote)
    if (item.resumeQuote)
      expect(row.querySelectorAll('.evidence-quote')[1]?.textContent).toBe(item.resumeQuote)
    else {
      expect(row.querySelectorAll('button')).toHaveLength(1)
      expect(row.textContent).toContain('所选简历中未找到可引用的证据')
    }
  })
})

it('counts each status and proportions the coverage bar by item count', () => {
  const root = mountReport()
  const summary = root.querySelector('.coverage-summary')!
  expect(summary.getAttribute('aria-label')).toBe('岗位要求证据覆盖概览')
  for (const [status, label, count] of [
    ['MET', '明确', 2],
    ['PARTIAL', '部分', 1],
    ['MISSING', '未体现', 1],
  ] as const) {
    const text = summary.querySelector(`.coverage-counts [data-status="${status}"]`)
    expect(text?.textContent?.replace(/\s+/g, ' ').trim()).toBe(`${label} ${count} 项`)
    const segment = summary.querySelector<HTMLElement>(`.coverage-bar [data-status="${status}"]`)
    expect(segment?.style.flexGrow).toBe(String(count))
  }
  expect(summary.querySelector('.coverage-bar')?.getAttribute('aria-hidden')).toBe('true')
})

it.each([
  ['PARTIAL', 'Java项目'],
  ['MET', '使用SQL'],
] as const)(
  '%s evidence opens the immutable original field and highlights the exact quote',
  async (status, quote) => {
    const root = mountReport()
    const row = root.querySelector(`.requirement-row[data-status="${status}"]`)!
    const button = [...row.querySelectorAll('button')].find((b) =>
      b.textContent?.includes('简历依据'),
    )!
    button.click()
    await nextTick()
    expect(root.querySelector('mark')?.textContent).toBe(quote)
    expect(root.querySelector('mark')?.getAttribute('data-status')).toBe(status)
    expect(root.textContent).toContain('曾完成Java项目，使用SQL')
    expect(root.textContent).toContain('不是录用概率')
    expect(document.activeElement).toBe(root.querySelector('section[tabindex="-1"]'))
  },
)

it('switches the original quote and status when navigating to missing JD evidence, then closes', async () => {
  const root = mountReport()
  root.querySelector<HTMLButtonElement>('.requirement-row button')!.click()
  await nextTick()
  expect(root.querySelector('mark')?.getAttribute('data-status')).toBe('PARTIAL')
  root.querySelector<HTMLButtonElement>('.requirement-row[data-status="MISSING"] button')!.click()
  await nextTick()
  expect(root.querySelector('mark')?.textContent).toBe('使用Docker部署')
  expect(root.querySelector('mark')?.getAttribute('data-status')).toBe('MISSING')
  const panel = root.querySelector('section[tabindex="-1"]')!
  expect(panel.textContent).toContain('分析时的岗位原文')
  expect(panel.textContent).toContain('熟悉Java，掌握SQL，使用Docker部署')
  panel.querySelector<HTMLButtonElement>('button')!.click()
  await nextTick()
  expect(root.querySelector('section[tabindex="-1"]')).toBeNull()
})

it('shows zero counts and an empty state without a bar when no requirements are available', () => {
  const root = mountReport({ ...report, requirements: [] })
  expect(root.querySelectorAll('.requirement-row')).toHaveLength(0)
  expect(root.querySelector('.coverage-bar')).toBeNull()
  expect([...root.querySelectorAll('.coverage-counts strong')].map((el) => el.textContent)).toEqual(
    ['0', '0', '0'],
  )
  expect(root.textContent).toContain('本报告暂无可展示的要求证据')
})
