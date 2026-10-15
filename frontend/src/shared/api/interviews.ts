import {
  apiRequest,
  apiUrl,
  authenticatedHeaders,
  fetchWithIdentity,
  toApiError,
  assertResponseIdentity,
} from './client'
import { downloadText } from '@/shared/utils/download'
import type { InterviewKind, InterviewSession, InterviewSummary } from '@/shared/types/interview'

const sessionPath = (id: string) => `/interviews/${encodeURIComponent(id)}`

export const listInterviews = (signal?: AbortSignal) =>
  apiRequest<InterviewSummary[]>('/interviews', { signal })

export const createInterview = (
  body: { role: string; kind: InterviewKind; jobId?: string },
  signal?: AbortSignal,
) =>
  apiRequest<InterviewSession>('/interviews', {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  })

export const getInterview = (id: string, signal?: AbortSignal) =>
  apiRequest<InterviewSession>(sessionPath(id), { signal })

export const updateInterview = (
  id: string,
  body: { questionIndex?: number; completed?: boolean },
  signal?: AbortSignal,
) =>
  apiRequest<InterviewSession>(sessionPath(id), {
    method: 'PATCH',
    body: JSON.stringify(body),
    signal,
  })

export const saveInterviewAnswer = (
  id: string,
  index: number,
  answerText: string,
  signal?: AbortSignal,
) =>
  apiRequest<InterviewSession>(`${sessionPath(id)}/answers/${index}`, {
    method: 'PUT',
    body: JSON.stringify({ answerText }),
    signal,
  })

/** Ask the interviewer's follow-up for a saved answer (idempotent per question). */
export const requestInterviewFollowUp = (id: string, index: number, signal?: AbortSignal) =>
  apiRequest<InterviewSession>(`${sessionPath(id)}/answers/${index}/follow-up`, {
    method: 'POST',
    signal,
  })

export const saveInterviewFollowUpAnswer = (
  id: string,
  index: number,
  answerText: string,
  signal?: AbortSignal,
) =>
  apiRequest<InterviewSession>(`${sessionPath(id)}/answers/${index}/follow-up`, {
    method: 'PUT',
    body: JSON.stringify({ answerText }),
    signal,
  })

export async function exportInterviewRecap(id: string): Promise<void> {
  const response = await fetchWithIdentity(apiUrl(`${sessionPath(id)}/recap.md`), {
    headers: await authenticatedHeaders({ Accept: 'text/markdown' }),
  })
  if (!response.ok) throw await toApiError(response)
  const text = await response.text()
  assertResponseIdentity(response)
  downloadText('面试复盘.md', text, 'text/markdown;charset=utf-8')
}
