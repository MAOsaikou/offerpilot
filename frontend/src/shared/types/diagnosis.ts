export interface DiagnosisEvidence {
  path: string
  quote: string
}
export interface ResumeDiagnosisReport {
  schemaVersion: 2
  reportId: string
  resumeId: string
  versionId: string
  versionNo: number
  createdAt: string
  summary: string
  strengths: { message: string; evidence: DiagnosisEvidence[] }[]
  findings: {
    findingId: string
    ruleId: string
    kind: 'OBSERVATION' | 'NEEDS_CLARIFICATION'
    priority: 'HIGH' | 'MEDIUM' | 'LOW'
    evidence: DiagnosisEvidence[]
    reason: string
    suggestion: string
    questions: string[]
  }[]
  checks: { path: string; message: string }[]
  limitations: string[]
  model: string
  modelTier: string
  promptVersion: string
  ruleVersion: string
  durationMs: number
  inputTokens: number
  outputTokens: number
}
export interface DiagnosisTask {
  id: string
  resumeId?: string
  versionId: string
  versionNo?: number
  requestId: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  error?: string
  createdAt: string
  report?: ResumeDiagnosisReport
}
