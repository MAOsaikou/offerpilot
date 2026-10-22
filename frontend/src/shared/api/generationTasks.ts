import { apiRequest } from './client'

export interface GenerationTask {
  id: string
  kind: 'JOB_MATCH' | 'RESUME_OPTIMIZE' | 'RESUME_DIAGNOSIS' | string
  status: string
  title: string
  href: string
  createdAt: string
  modelTier?: string | null
}

export const listGenerationTasks = (signal?: AbortSignal) =>
  apiRequest<GenerationTask[]>('/generation-tasks', { signal })

export const cancelGenerationTask = (kind: string, id: string, signal?: AbortSignal) =>
  apiRequest<null>(
    `/generation-tasks/${encodeURIComponent(kind)}/${encodeURIComponent(id)}/cancel`,
    {
      method: 'POST',
      signal,
    },
  )

export type GenerationKind =
  | 'RESUME_DIAGNOSIS'
  | 'RESUME_OPTIMIZE'
  | 'JOB_MATCH'
  | 'CAREER_ASSESSMENT'
  | 'CAREER_RECOMMENDATION'
  | 'INTERVIEW_FOLLOW_UP'
  | 'INTERVIEW_QUESTIONS'
  | 'INTERVIEW_SCORE'
  | 'INTERVIEW_REPORT'
  | 'JOB_IMPORT'
export interface BackgroundTask extends GenerationTask {
  entityId: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  stage: string
  resultId: string | null
  error: string | null
  updatedAt: string
  startedAt: string | null
  finishedAt: string | null
}
export const isActiveTask = (task: GenerationTask) => ['QUEUED', 'RUNNING'].includes(task.status)
export const recentGenerationTasks = (kind?: string, entityId?: string) => {
  const query = new URLSearchParams()
  if (kind) query.set('kind', kind)
  if (entityId) query.set('entityId', entityId)
  return apiRequest<BackgroundTask[]>(`/generation-tasks?${query}`)
}
export const startGenerationTask = (
  kind: GenerationKind,
  entityId: string,
  input: object,
  requestId: string,
) =>
  apiRequest<BackgroundTask>('/generation-tasks', {
    method: 'POST',
    body: JSON.stringify({ kind, entityId, input, requestId }),
  })
export const generationResult = <T>(id: string) =>
  apiRequest<T>(`/generation-tasks/${encodeURIComponent(id)}/result`)
export const cancelBackgroundTask = (id: string) =>
  apiRequest<null>(`/generation-tasks/${encodeURIComponent(id)}/cancel`, { method: 'POST' })

/** Only observation uses the page signal. The accepted server job never belongs to that signal. */
export async function runGeneration<T>(
  kind: GenerationKind,
  entityId: string,
  input: object,
  requestId: string = crypto.randomUUID(),
  signal?: AbortSignal,
  started?: (id: string) => void,
): Promise<T> {
  const { useGenerationTasks } = await import('@/shared/stores/generationTasks')
  return useGenerationTasks().run<T>(kind, entityId, input, requestId, signal, started)
}

export const getGenerationTask = (id: string) =>
  apiRequest<BackgroundTask>(`/generation-tasks/${encodeURIComponent(id)}`)
