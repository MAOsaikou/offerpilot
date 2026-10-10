import { createApp, h, nextTick, ref, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import ResumeDiagnosisReport from './ResumeDiagnosisReport.vue'
import type { ResumeDiagnosisReport as Report } from '@/shared/types/diagnosis'
import { getDiagnosisVersion } from '@/shared/api/diagnoses'

vi.mock('@/shared/api/diagnoses', () => ({ getDiagnosisVersion: vi.fn() }))

const example: Report = {
  schemaVersion: 2,
  reportId: 'report-1',
  resumeId: 'resume-1',
  versionId: 'version-1',
  versionNo: 1,
  createdAt: '2026-09-29T10:00:00Z',
  summary: '项目细节清楚，可进一步补充验证方式。',
  strengths: [
    {
      message: '技术实现具体',
      evidence: [{ path: '/projects/0/description', quote: '实现登录接口' }],
    },
  ],
  findings: [
    {
      findingId: 'f-low',
      priority: 'LOW',
      ruleId: 'CLARITY',
      kind: 'OBSERVATION',
      reason: '表达可以精简',
      suggestion: '合并重复描述',
      questions: [],
      evidence: [{ path: '/skills/0/name', quote: 'Java 基础' }],
    },
    {
      findingId: 'f-high',
      priority: 'HIGH',
      ruleId: 'RESULT',
      kind: 'NEEDS_CLARIFICATION',
      reason: '原文没有说明如何验证接口',
      suggestion: '补充你实际执行过的接口验证步骤',
      questions: ['你验证过哪些异常场景？'],
      evidence: [{ path: '/projects/0/description', quote: '实现登录接口' }],
    },
  ],
  checks: [],
  limitations: ['不评估原文件排版'],
  model: 'fixture-model',
  modelTier: 'CHEAP',
  promptVersion: 'v1',
  ruleVersion: 'v1',
  durationMs: 100,
  inputTokens: 10,
  outputTokens: 10,
}
let app: App, root: HTMLDivElement
afterEach(() => {
  app?.unmount()
  root?.remove()
  vi.clearAllMocks()
})
async function mount() {
  const report = ref<Report>(structuredClone(example))
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/chat', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(ResumeDiagnosisReport, { report: report.value }) }).use(router)
  app.mount(root)
  return report
}
function filter(label: string) {
  return [...root.querySelectorAll<HTMLButtonElement>('.priority-filters button')].find((button) =>
    button.textContent?.includes(label),
  )!
}
it('优先展示行动建议，原文在同一卡片内展开且不再请求版本接口', async () => {
  await mount()
  const cards = root.querySelectorAll('.finding-card')
  expect(cards[0]?.textContent).toContain('补充你实际执行过的接口验证步骤')
  expect(cards[1]?.textContent).toContain('合并重复描述')
  const evidence = cards[0]!.querySelector<HTMLDetailsElement>('details')!
  expect(evidence.open).toBe(false)
  evidence.querySelector('summary')!.click()
  await nextTick()
  expect(evidence.open).toBe(true)
  expect(evidence.querySelector('blockquote')?.textContent).toBe('实现登录接口')
  expect(evidence.textContent).toContain('项目经历 · 第 1 项')
  expect(evidence.textContent).toContain('原文没有说明如何验证接口')
  expect(getDiagnosisVersion).not.toHaveBeenCalled()
  expect(root.textContent).not.toContain('查看所在段落')
  evidence.querySelector('summary')!.click()
  expect(evidence.open).toBe(false)
})
it('筛选有空状态且不改变原顺序编号，切换报告重置筛选与展开状态', async () => {
  const report = await mount()
  filter('可选优化').click()
  await nextTick()
  expect(root.querySelectorAll('.finding-card')).toHaveLength(1)
  expect(root.querySelector('.finding-number')?.textContent).toBe('02')
  expect(filter('可选优化').getAttribute('aria-pressed')).toBe('true')
  filter('建议改进').click()
  await nextTick()
  expect(root.querySelectorAll('.finding-card')).toHaveLength(0)
  expect(root.querySelector('[role="status"]')?.textContent).toContain('显示 0 项建议')
  root.querySelector<HTMLButtonElement>('.empty-findings button')!.click()
  await nextTick()
  root.querySelector<HTMLElement>('.report-summary summary')!.click()
  filter('优先处理').click()
  await nextTick()
  report.value = { ...structuredClone(example), reportId: 'report-2' }
  await nextTick()
  expect(root.querySelectorAll('.finding-card')).toHaveLength(2)
  expect(filter('全部').getAttribute('aria-pressed')).toBe('true')
  expect(root.querySelector<HTMLDetailsElement>('.report-summary')?.open).toBe(false)
})
it('单条讨论链接绑定原报告和简历版本，并明确当前建议', async () => {
  await mount()
  const link = root.querySelector<HTMLAnchorElement>('.finding-action')!
  const url = new URL(link.getAttribute('href')!, 'http://localhost')
  expect(url.pathname).toBe('/chat')
  expect(url.searchParams.get('diagnosisReportId')).toBe('report-1')
  expect(url.searchParams.get('resumeVersionId')).toBe('version-1')
  expect(url.searchParams.get('resumeId')).toBe('resume-1')
  expect(url.searchParams.get('prompt')).toContain('f-high')
  expect(url.searchParams.get('prompt')).toContain('补充你实际执行过的接口验证步骤')
})
