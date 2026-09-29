import { createApp, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ResumeDiagnosisPanel from './ResumeDiagnosisPanel.vue'
import { analyzeResume, cancelDiagnosis, getDiagnosis, listDiagnoses } from '@/shared/api/diagnoses'
import type { ResumeDiagnosisReport } from '@/shared/types/diagnosis'
vi.mock('@/shared/api/diagnoses', () => ({
  analyzeResume: vi.fn(),
  cancelDiagnosis: vi.fn(),
  getDiagnosis: vi.fn(),
  listDiagnoses: vi.fn(),
  getDiagnosisVersion: vi.fn(),
}))
const report: ResumeDiagnosisReport = {
  schemaVersion: 2,
  reportId: 'd1',
  resumeId: 'r1',
  versionId: 'v1',
  versionNo: 1,
  createdAt: '2026-09-26T00:00:00Z',
  summary: '补充个人行动',
  strengths: [],
  findings: [],
  checks: [],
  limitations: ['未评估版面'],
  model: 'fixture',
  modelTier: 'CHEAP',
  promptVersion: 'v1',
  ruleVersion: 'v1',
  durationMs: 1,
  inputTokens: 1,
  outputTokens: 1,
}
let app: App, root: HTMLDivElement, cache: QueryClient
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(listDiagnoses).mockResolvedValue([])
  vi.mocked(cancelDiagnosis).mockResolvedValue(undefined)
})
afterEach(() => {
  app?.unmount()
  cache?.clear()
  root?.remove()
})
async function mount(
  dirty: boolean,
  saveFirst = vi.fn().mockResolvedValue(false),
  versionId = 'v2',
) {
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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
  app = createApp({
    render: () => h(ResumeDiagnosisPanel, { resumeId: 'r1', versionId, dirty, saveFirst }),
  })
    .use(router)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
  await vi.waitFor(() => expect(root.textContent).toContain('还没有诊断报告'))
}
function button(text: string) {
  return [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!
}
it('保存失败不开始诊断，允许明确诊断已保存版本', async () => {
  const save = vi.fn().mockResolvedValue(false)
  await mount(true, save)
  button('保存新版本后诊断').click()
  await vi.waitFor(() => expect(save).toHaveBeenCalled())
  await nextTick()
  expect(analyzeResume).not.toHaveBeenCalled()
  vi.mocked(analyzeResume).mockResolvedValue(report)
  button('仅诊断已保存版本').click()
  await vi.waitFor(() => expect(analyzeResume).toHaveBeenCalled())
  expect(vi.mocked(analyzeResume).mock.calls[0]?.[1]).toBe('v2')
})
it('取消显式调用接口，失败有可重试反馈', async () => {
  await mount(false)
  vi.mocked(analyzeResume).mockImplementation((_id, _version, _request, signal, started) => {
    started('d1')
    return new Promise((_resolve, reject) =>
      signal.addEventListener('abort', () => reject(new DOMException('stop', 'AbortError'))),
    )
  })
  button('开始 AI 诊断').click()
  await vi.waitFor(() => expect(root.textContent).toContain('取消诊断'))
  button('取消诊断').click()
  await vi.waitFor(() => expect(cancelDiagnosis).toHaveBeenCalledWith('d1'))
  await vi.waitFor(() => expect(root.textContent).toContain('诊断连接已停止'))
})
it('读取历史报告不重新调用模型，显示旧版本并传递讨论上下文', async () => {
  await mount(false)
  vi.mocked(getDiagnosis).mockResolvedValue({
    id: 'd1',
    versionId: 'v1',
    status: 'SUCCEEDED',
    requestId: 'req',
    createdAt: report.createdAt,
    report,
  })
  cache.setQueryData(
    ['diagnoses', 'r1'],
    [{ id: 'd1', versionId: 'v1', versionNo: 1, status: 'SUCCEEDED', createdAt: report.createdAt }],
  )
  await nextTick()
  button('查看报告').click()
  await vi.waitFor(() => expect(root.textContent).toContain('这是旧版本的诊断'))
  expect(document.activeElement).toBe(root.querySelector('.report-result'))
  expect(root.querySelector<HTMLDetailsElement>('.diagnosis-history-group')?.open).toBe(false)
  expect(
    root
      .querySelector('.report-result')!
      .compareDocumentPosition(root.querySelector('.diagnosis-history-group')!),
  ).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  expect(analyzeResume).not.toHaveBeenCalled()
  expect(root.querySelector('a')?.getAttribute('href')).toContain('diagnosisReportId=d1')
  expect(root.querySelector('a')?.getAttribute('href')).toContain('resumeVersionId=v1')
})
