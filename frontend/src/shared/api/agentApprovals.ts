import { apiRequest } from './client'

export const boardApprovalActions = ['createApplication', 'updateApplicationStatus'] as const

/**
 * Agent writes whose chat stream ended before the user decided (BD-02 recovery). Board writes carry
 * company/title/currentStatus for a local description; every other tool comes with a server summary.
 */
export interface PendingAgentApproval {
  id: string
  runId: string
  action: string
  args: Record<string, unknown>
  expiresAt: string
  createdAt: string
  company: string | null
  title: string | null
  currentStatus: string | null
  summary: string | null
}
export const listPendingAgentApprovals = (signal?: AbortSignal) =>
  apiRequest<PendingAgentApproval[]>('/agent/approvals/pending', { signal })
export const decidePendingAgentApproval = (id: string, decision: 'APPROVE' | 'REJECT') =>
  apiRequest<{ decision: string; applied: boolean; summary: string | null }>(
    `/agent/approvals/${encodeURIComponent(id)}`,
    { method: 'POST', body: JSON.stringify({ decision }) },
  )
