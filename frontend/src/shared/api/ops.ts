import { apiRequest } from './client'

export interface OpsStatus {
  state: string
  uptimeSeconds: number
  http: { requests: number; errors: number; errorRate: number }
  usage: {
    calls: number
    failed: number
    promptTokens: number
    completionTokens: number
    unreported: number
  } | null
  alerts: string[]
  moderationHits: Record<string, number>
  externalPushEnabled: boolean
  checkedAt: string
}
export interface CleanupReceipt {
  id: string
  kind: string
  attempts: number
  next_attempt_at: string
  created_at: string
}
export const getOpsStatus = () => apiRequest<OpsStatus>('/admin/ops/status')
export const getCleanupReceipts = () => apiRequest<CleanupReceipt[]>('/admin/ops/cleanup')
