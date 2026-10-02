import { apiRequest, apiUrl, authenticatedHeaders, toApiError } from './client'
import { parseSse } from './sse'
import type {
  AppliedPatches,
  OptimizationReport,
  OptimizationTask,
  PatchBatch,
  PatchDecision,
} from '@/shared/types/optimization'

const versionPath = (id: string, version: string) =>
  `/resumes/${encodeURIComponent(id)}/versions/${encodeURIComponent(version)}`
export const listOptimizations = (id: string) =>
  apiRequest<OptimizationTask[]>(`/resumes/${encodeURIComponent(id)}/optimizations`)
export const getOptimization = (id: string) =>
  apiRequest<OptimizationTask>(`/resumes/optimizations/${encodeURIComponent(id)}`)
export const cancelOptimization = (id: string) =>
  apiRequest<void>(`/resumes/optimizations/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
export const getPatchBatch = (id: string, version: string, batchId: string) =>
  apiRequest<PatchBatch>(`${versionPath(id, version)}/patches/${encodeURIComponent(batchId)}`)
export const applyPatches = (id: string, version: string, decision: PatchDecision) =>
  apiRequest<AppliedPatches>(`${versionPath(id, version)}/apply-patches`, {
    method: 'POST',
    body: JSON.stringify(decision),
  })

export async function optimizeResume(
  id: string,
  version: string,
  requestId: string,
  jobDescription: string,
  signal: AbortSignal,
  started: (id: string) => void,
): Promise<OptimizationReport> {
  const response = await fetch(apiUrl(`${versionPath(id, version)}/optimize`), {
    method: 'POST',
    headers: await authenticatedHeaders({
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    }),
    body: JSON.stringify({ requestId, jobDescription }),
    signal,
  })
  if (!response.ok || !response.body) throw await toApiError(response)
  let report: OptimizationReport | undefined
  let complete = false
  for await (const frame of parseSse(response.body)) {
    const event = JSON.parse(frame.data) as {
      type: string
      reportId?: string
      cardType?: string
      payload?: OptimizationReport
      message?: string
      status?: string
    }
    if (event.type === 'run.started' && event.reportId) started(event.reportId)
    if (event.type === 'ui.card' && event.cardType === 'resume_diff') report = event.payload
    if (event.type === 'error') throw new Error(event.message || '改写失败，请重试')
    if (event.type === 'run.completed') {
      complete = event.status === 'COMPLETED'
      break
    }
  }
  if (!complete || !report) throw new Error('连接中断，请先刷新改写记录，确认本次结果后再重试。')
  return report
}
