import { createApp, nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
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
  unverifiedCount: 1,
  unverifiedRequirements: [
    { requirement: '会常用的shell命令', status: 'UNVERIFIED', verificationIssue: 'RESUME_QUOTE' },
  ],
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
  const app = createApp(EvidenceMatchReport, { payload }).use(
    createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/resumes/:id', component: { render: () => null } }],
    }),
  )
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

it('switches the original quote and status when navigating to missing JD evidence, then closes', async () => {
  const root = mountReport()
  expect(root.querySelector('[aria-label="未核实要求"]')?.textContent).toContain(
    '未核实 1 项 · 不计分',
  )
  expect(root.querySelector('[aria-label="未核实要求"]')?.textContent).toContain(
    '会常用的shell命令',
  )
  expect(root.querySelector('a')?.getAttribute('href')).toContain(
    '/resumes/resume-1?tab=optimize&jobId=job-1&resumeVersionId=version-2',
  )
  ;[...root.querySelectorAll<HTMLButtonElement>('.requirement-row button')]
    .find((button) => button.textContent?.includes('简历依据'))!
    .click()
  await nextTick()
  expect(root.querySelector('mark')?.getAttribute('data-status')).toBe('PARTIAL')
  expect(root.querySelector('mark')?.textContent).toBe('Java项目')
  expect(document.activeElement).toBe(root.querySelector('section[tabindex="-1"]'))
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
