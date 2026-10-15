import { apiRequest } from './client'
import type { ApplicationPatch, ApplicationDetail, ServerApplication } from '@/shared/types/board'

export const listApplications = (signal?: AbortSignal) =>
  apiRequest<ServerApplication[]>('/applications', { signal })
export const favoriteJob = (jobId: string) =>
  apiRequest<ServerApplication>('/applications', {
    method: 'POST',
    body: JSON.stringify({ jobId }),
  })
export const updateApplication = (id: string, patch: ApplicationPatch) =>
  apiRequest<ServerApplication>(`/applications/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  })

export const getApplication = (id: string, signal?: AbortSignal) =>
  apiRequest<ApplicationDetail>(`/applications/${encodeURIComponent(id)}`, { signal })

/** Agent board writes whose chat stream ended before the user decided (BD-02 recovery). */
export interface PendingBoardApproval {
  id: string
  runId: string
  action: 'createApplication' | 'updateApplicationStatus'
  args: Partial<
    Record<'jobId' | 'applicationId' | 'status' | 'note' | 'nextAction' | 'nextActionAt', string>
  >
  expiresAt: string
  createdAt: string
  company: string | null
  title: string | null
  currentStatus: string | null
}
export const listPendingBoardApprovals = (signal?: AbortSignal) =>
  apiRequest<PendingBoardApproval[]>('/agent/approvals/pending', { signal })
export const decidePendingBoardApproval = (id: string, decision: 'APPROVE' | 'REJECT') =>
  apiRequest<{ decision: string; applied: boolean }>(`/agent/approvals/${encodeURIComponent(id)}`, {
    method: 'POST',
    body: JSON.stringify({ decision }),
  })
