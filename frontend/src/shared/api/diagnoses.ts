import { translate as uiText } from '@/shared/utils/language'
import { runGeneration, cancelBackgroundTask } from './generationTasks'
import {
  ApiError,
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
  id.startsWith('gen_')
    ? cancelBackgroundTask(id)
    : apiRequest<void>(`/resumes/diagnoses/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
export const getDiagnosisVersion = (id: string, version: string) =>
  apiRequest<ResumeVersion>(
    `/resumes/${encodeURIComponent(id)}/versions/${encodeURIComponent(version)}`,
  )

export async function analyzeResumeStream(
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
      code?: number
      message?: string
      status?: string
    }
    if (event.type === 'run.started' && event.reportId) started(event.reportId)
    if (event.type === 'ui.card' && event.cardType === 'resume_diagnosis') report = event.payload
    if (event.type === 'error')
      throw new ApiError(event.message || uiText('Diagnosis failed. Please retry.'), {
        code: event.code ?? 50000,
      })
    if (event.type === 'run.completed') {
      complete = event.status === 'COMPLETED'
      break
    }
  }
  if (!complete || !report)
    throw new Error(
      uiText(
        "Connection interrupted. Refresh report history and confirm this request's result before retrying.",
      ),
    )
  return report
}

export const analyzeResume = (
  id: string,
  versionId: string,
  requestId: string,
  signal: AbortSignal,
  started: (id: string) => void,
) =>
  runGeneration<ResumeDiagnosisReport>(
    'RESUME_DIAGNOSIS',
    id,
    { versionId },
    requestId,
    signal,
    started,
  )
