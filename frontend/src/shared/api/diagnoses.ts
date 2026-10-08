import {
  apiRequest,
  apiUrl,
  authenticatedHeaders,
  assertResponseIdentity,
  fetchWithIdentity,
  toApiError,
} from './client'
import { parseSse } from './sse'
import type { DiagnosisTask, ResumeDiagnosisReport } from '@/shared/types/diagnosis'
import type { ResumeVersion } from '@/shared/types/resume'

export const listDiagnoses = (id: string) =>
  apiRequest<DiagnosisTask[]>(`/resumes/${encodeURIComponent(id)}/diagnoses`)
export const getDiagnosis = (id: string) =>
  apiRequest<DiagnosisTask>(`/resumes/diagnoses/${encodeURIComponent(id)}`)
export const cancelDiagnosis = (id: string) =>
  apiRequest<void>(`/resumes/diagnoses/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
export const getDiagnosisVersion = (id: string, version: string) =>
  apiRequest<ResumeVersion>(
    `/resumes/${encodeURIComponent(id)}/versions/${encodeURIComponent(version)}`,
  )

export async function analyzeResume(
  id: string,
  version: string,
  requestId: string,
  signal: AbortSignal,
  started: (id: string) => void,
): Promise<ResumeDiagnosisReport> {
  const response = await fetchWithIdentity(
    apiUrl(`/resumes/${encodeURIComponent(id)}/versions/${encodeURIComponent(version)}/analyze`),
    {
      method: 'POST',
      headers: await authenticatedHeaders({
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      }),
      body: JSON.stringify({ requestId }),
      signal,
    },
  )
  if (!response.ok || !response.body) throw await toApiError(response)
  let report: ResumeDiagnosisReport | undefined
  let complete = false
  for await (const frame of parseSse(response.body)) {
    assertResponseIdentity(response)
    const event = JSON.parse(frame.data) as {
      type: string
      reportId?: string
      cardType?: string
      payload?: ResumeDiagnosisReport
      message?: string
      status?: string
    }
    if (event.type === 'run.started' && event.reportId) started(event.reportId)
    if (event.type === 'ui.card' && event.cardType === 'resume_diagnosis') report = event.payload
    if (event.type === 'error') throw new Error(event.message || '诊断失败，请重试')
    if (event.type === 'run.completed') {
      complete = event.status === 'COMPLETED'
      break
    }
  }
  if (!complete || !report) throw new Error('连接中断，请先刷新报告记录，确认本次结果后再重试。')
  return report
}
