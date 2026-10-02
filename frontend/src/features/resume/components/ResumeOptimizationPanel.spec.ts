import { createApp, h, nextTick, reactive, type App } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ResumeOptimizationPanel from './ResumeOptimizationPanel.vue'
import PatchDiffViewer from './PatchDiffViewer.vue'
import {
  optimizeResume,
  listOptimizations,
  getOptimization,
  getPatchBatch,
  applyPatches,
  cancelOptimization,
} from '@/shared/api/optimizations'
import { getResumeVersion } from '@/shared/api/resumes'
import type { OptimizationReport, PatchDecision } from '@/shared/types/optimization'
import type { ResumeVersion } from '@/shared/types/resume'
vi.mock('@/shared/api/optimizations', () => ({
  optimizeResume: vi.fn(),
  listOptimizations: vi.fn(),
  getOptimization: vi.fn(),
  getPatchBatch: vi.fn(),
  applyPatches: vi.fn(),
  cancelOptimization: vi.fn(),
}))
vi.mock('@/shared/api/resumes', () => ({ getResumeVersion: vi.fn() }))
vi.mock('@/shared/api/jobs', () => ({ getJob: vi.fn() }))
const report: OptimizationReport = {
  reportId: 'o1',
  resumeId: 'r1',
  resumeVersionId: 'v1',
  versionNo: 1,
  batchId: 'b1',
  summary: '整理接口开发经历',
  patches: [
    {
      id: 'p1',
      op: 'replace',
      path: '/summary',
      from: '参与开发',
      value: '参与接口开发',
      reason: '明确职责',
      jdEvidence: '接口开发',
    },
  ],
  stats: { added: 0, replaced: 1, removed: 0 },
  model: 'fixture',
  modelTier: 'CHEAP',
  promptVersion: 'v1',
  durationMs: 1,
  inputTokens: 1,
  outputTokens: 1,
  createdAt: '2026-10-03T00:00:00Z',
}
const version: ResumeVersion = {
  id: 'v2',
  versionNo: 2,
  parentVersionId: 'v1',
  source: 'OPTIMIZED',
  structured: {
    basics: { name: '', phone: '', email: '', city: '', links: [] },
    education: [],
    experience: [],
    projects: [],
    skills: [],
    certificates: [],
    summary: '参与接口开发',
  },
  plainText: '参与接口开发',
  parseMeta: {},
  createdAt: report.createdAt,
}
const task = {
  id: 'o1',
  resumeId: 'r1',
  versionId: 'v1',
  versionNo: 1,
  requestId: 'req',
  status: 'SUCCEEDED' as const,
  createdAt: report.createdAt,
  jobDescription: '接口开发',
  report,
}
let app: App, root: HTMLDivElement, cache: QueryClient
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(listOptimizations).mockResolvedValue([])
  vi.mocked(getPatchBatch).mockResolvedValue({ ...report, batchId: 'b1', appliedVersionId: null })
  vi.mocked(cancelOptimization).mockResolvedValue(undefined)
  vi.mocked(optimizeResume).mockResolvedValue(report)
})
afterEach(() => {
  app?.unmount()
  cache?.clear()
  root?.remove()
})
async function mount(
  dirty = false,
  versionId = 'v1',
  saveFirst = vi.fn().mockResolvedValue(false),
  optimizationReportId?: string,
) {
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  await router.push('/')
  const props = reactive({ resumeId: 'r1', versionId, dirty, saveFirst, optimizationReportId })
  const saved = vi.fn()
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({
    render: () =>
      h(
        ResumeOptimizationPanel,
        { ...props, onSaved: saved },
        {
          'optimization-card': (card: {
            report: OptimizationReport
            interactive: boolean
            disabled: boolean
            appliedVersionId: string | null
            apply: (d: PatchDecision) => void
          }) =>
            h(PatchDiffViewer, {
              payload: card.report,
              interactive: card.interactive,
              disabled: card.disabled,
              appliedVersionId: card.appliedVersionId,
              onApply: card.apply,
            }),
        },
      ),
  })
    .use(router)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
  await vi.waitFor(() => expect(root.textContent).toContain('还没有改写记录'))
  const jd = root.querySelector('textarea')!
  jd.value = '接口开发'
  jd.dispatchEvent(new Event('input'))
  await nextTick()
  return { props, saved }
}
const button = (text: string) =>
  [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!
async function generate() {
  button('生成改写建议').click()
  await vi.waitFor(() => expect(root.textContent).toContain(report.summary))
}
async function selectAndSave() {
  root.querySelector<HTMLButtonElement>('[aria-label="采纳建议 1"]')!.click()
  await nextTick()
  button('采纳所选并保存新版本').click()
}
it('保存草稿失败不调用模型；明确选择保存版本时不覆盖草稿', async () => {
  const save = vi.fn().mockResolvedValue(false)
  await mount(true, 'v1', save)
  button('保存新版本后生成').click()
  await vi.waitFor(() => expect(save).toHaveBeenCalled())
  await nextTick()
  expect(optimizeResume).not.toHaveBeenCalled()
  button('仅使用已保存版本生成').click()
  await vi.waitFor(() => expect(root.textContent).toContain(report.summary))
  expect(vi.mocked(optimizeResume).mock.calls[0]?.[1]).toBe('v1')
  expect(root.querySelector('[aria-label="采纳建议 1"]')).toBeNull()
})
it('保存后使用新基准版本；生成本身不写简历', async () => {
  const save = vi.fn()
  const { props } = await mount(true, 'v1', save)
  save.mockImplementation(async () => {
    props.versionId = 'v2'
    props.dirty = false
    return true
  })
  button('保存新版本后生成').click()
  await vi.waitFor(() => expect(optimizeResume).toHaveBeenCalled())
  expect(vi.mocked(optimizeResume).mock.calls[0]?.[1]).toBe('v2')
  expect(applyPatches).not.toHaveBeenCalled()
})
it('只有明确采纳才保存，成功后禁止重复保存', async () => {
  const { saved } = await mount()
  vi.mocked(applyPatches).mockResolvedValue({ batchId: 'b1', version })
  await generate()
  expect(applyPatches).not.toHaveBeenCalled()
  await selectAndSave()
  await vi.waitFor(() => expect(saved).toHaveBeenCalledWith(version))
  expect(applyPatches).toHaveBeenCalledExactlyOnceWith('r1', 'v1', {
    batchId: 'b1',
    accepted: ['p1'],
    edited: {},
    confirmed: [],
  })
  expect(root.textContent).toContain('这批建议已生成新版本')
  expect(root.querySelector('[aria-label="采纳建议 1"]')).toBeNull()
})
it('保存响应丢失从批次恢复已提交版本，不重发采纳请求', async () => {
  const { saved } = await mount()
  vi.mocked(applyPatches).mockRejectedValue(new Error('断网'))
  vi.mocked(getResumeVersion).mockResolvedValue(version)
  await generate()
  vi.mocked(getPatchBatch).mockResolvedValue({ ...report, batchId: 'b1', appliedVersionId: 'v2' })
  await selectAndSave()
  await vi.waitFor(() => expect(saved).toHaveBeenCalledWith(version))
  expect(getResumeVersion).toHaveBeenCalledWith('r1', 'v2')
  expect(applyPatches).toHaveBeenCalledTimes(1)
  expect(root.textContent).not.toContain('断网')
})
it('生成断流查同一请求的持久化结果，历史旧版本只读', async () => {
  await mount(false, 'v2')
  vi.mocked(optimizeResume).mockImplementation(async (_r, _v, requestId) => {
    vi.mocked(listOptimizations).mockResolvedValue([{ ...task, requestId }])
    throw new Error('连接中断')
  })
  vi.mocked(getOptimization).mockResolvedValue(task)
  await generate()
  expect(optimizeResume).toHaveBeenCalledTimes(1)
  expect(root.textContent).not.toContain('连接中断')
  expect(root.querySelector('[aria-label="采纳建议 1"]')).toBeNull()
  expect(root.textContent).toContain('此批建议仅供查看')
})
it('采纳失败保留选择，后续编辑使这批建议只读', async () => {
  const { props } = await mount()
  vi.mocked(applyPatches).mockRejectedValue(new Error('版本冲突，请刷新'))
  await generate()
  await selectAndSave()
  await vi.waitFor(() => expect(root.textContent).toContain('版本冲突'))
  expect(root.querySelector('[aria-label="采纳建议 1"]')?.getAttribute('aria-pressed')).toBe('true')
  expect(button('采纳所选并保存新版本').disabled).toBe(false)
  props.dirty = true
  await nextTick()
  expect(root.querySelector('[aria-label="采纳建议 1"]')).toBeNull()
})
it('历史恢复已采纳状态，无模型调用或重复保存', async () => {
  await mount()
  cache.setQueryData(['optimizations', 'r1'], [task])
  await nextTick()
  vi.mocked(getOptimization).mockResolvedValue(task)
  vi.mocked(getPatchBatch).mockResolvedValue({ ...report, batchId: 'b1', appliedVersionId: 'v2' })
  button('查看建议').click()
  await vi.waitFor(() => expect(root.textContent).toContain('这批建议已生成新版本'))
  expect(root.querySelector('[aria-label="采纳建议 1"]')).toBeNull()
  expect(optimizeResume).not.toHaveBeenCalled()
  expect(applyPatches).not.toHaveBeenCalled()
})
it('聊天卡片深链直接恢复正确报告，既不重新生成也不自动保存', async () => {
  vi.mocked(getOptimization).mockResolvedValue(task)
  await mount(false, 'v1', undefined, 'o1')
  await vi.waitFor(() => expect(root.textContent).toContain(report.summary))
  expect(getOptimization).toHaveBeenCalledWith('o1')
  expect(root.querySelector('textarea')?.value).toBe('接口开发')
  expect(optimizeResume).not.toHaveBeenCalled()
  expect(applyPatches).not.toHaveBeenCalled()
})
it('深链报告属于另一份简历时拒绝显示，也不读取批次或调用模型', async () => {
  vi.mocked(getOptimization).mockResolvedValue({ ...task, resumeId: 'r2' })
  await mount(false, 'v1', undefined, 'o1')
  await vi.waitFor(() => expect(root.textContent).toContain('不属于所选简历版本'))
  expect(root.textContent).not.toContain(report.summary)
  expect(getPatchBatch).not.toHaveBeenCalled()
  expect(optimizeResume).not.toHaveBeenCalled()
})
it('取消调用服务端并停止流；空建议不显示保存入口', async () => {
  await mount()
  vi.mocked(optimizeResume).mockImplementation((_r, _v, _req, _jd, signal, started) => {
    started('o1')
    return new Promise((_resolve, reject) =>
      signal.addEventListener('abort', () => reject(new DOMException('stop', 'AbortError'))),
    )
  })
  button('生成改写建议').click()
  await vi.waitFor(() => expect(root.textContent).toContain('取消生成'))
  button('取消生成').click()
  await vi.waitFor(() => expect(cancelOptimization).toHaveBeenCalledWith('o1'))
  await vi.waitFor(() => expect(root.textContent).toContain('改写连接已停止'))
  vi.mocked(optimizeResume).mockResolvedValue({ ...report, batchId: null, patches: [] })
  await generate()
  expect(root.textContent).toContain('未创建简历新版本')
  expect(root.querySelector('[aria-label="采纳建议 1"]')).toBeNull()
})
