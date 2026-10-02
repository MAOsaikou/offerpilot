import type { ResumeDiffPayload } from './cards'
import type { ResumeVersion } from './resume'

export interface OptimizationReport extends Omit<ResumeDiffPayload, 'batchId'> {
  batchId: string | null
  reportId: string
  resumeId: string
  versionNo: number
  summary: string
  model: string
  modelTier: string
  promptVersion: string
  durationMs: number
  inputTokens: number
  outputTokens: number
  createdAt: string
}
export interface OptimizationTask {
  id: string
  resumeId?: string
  versionId: string
  versionNo?: number
  requestId: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  error?: string | null
  createdAt: string
  jobDescription?: string
  report?: OptimizationReport
}
export interface PatchDecision {
  batchId: string
  accepted: string[]
  edited: Record<string, { value: string }>
  confirmed: string[]
}
export interface PatchBatch extends ResumeDiffPayload {
  resumeId: string
  appliedVersionId: string | null
  createdAt: string
}
export interface AppliedPatches {
  batchId: string
  version: ResumeVersion
}
