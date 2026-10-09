import { apiRequest } from './client'
import type { InterviewKind, InterviewSession, InterviewSummary } from '@/shared/types/interview'

const sessionPath = (id: string) => `/interviews/${encodeURIComponent(id)}`

export const listInterviews = (signal?: AbortSignal) =>
  apiRequest<InterviewSummary[]>('/interviews', { signal })

export const createInterview = (
  body: { role: string; kind: InterviewKind },
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
