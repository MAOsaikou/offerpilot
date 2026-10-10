import { apiRequest } from './client'

export interface GenerationTask {
  id: string
  kind: 'JOB_MATCH' | 'RESUME_OPTIMIZE' | 'RESUME_DIAGNOSIS' | string
  status: string
  title: string
  href: string
  createdAt: string
  modelTier: string | null
}

export const listGenerationTasks = (signal?: AbortSignal) =>
  apiRequest<GenerationTask[]>('/generation-tasks', { signal })
