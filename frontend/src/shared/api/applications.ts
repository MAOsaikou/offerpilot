import { apiRequest } from './client'
import type { ApplicationStatus, ServerApplication } from '@/shared/types/board'

export const listApplications = (signal?: AbortSignal) =>
  apiRequest<ServerApplication[]>('/applications', { signal })
export const favoriteJob = (jobId: string) =>
  apiRequest<ServerApplication>('/applications', {
    method: 'POST',
    body: JSON.stringify({ jobId }),
  })
export const updateApplication = (
  id: string,
  patch: { status?: ApplicationStatus; note?: string },
) =>
  apiRequest<ServerApplication>(`/applications/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  })
