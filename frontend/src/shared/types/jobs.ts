import type { ModelTier } from './model-tier'

export interface RealJob {
  id: string
  externalId: string
  site: string
  company: string
  title: string
  locations: string
  category: string
  nature: string
  department: string
  updatedAt: string
  url: string
  source: string
  originalSource?: string
  fallbackReason?: string
  snapshotDate?: string
  skills?: string[]
  description: string
  requirement: string
  detailFetchedAt: string | null
}
export interface JobSite {
  id: string
  name: string
  supportedNatures: string[]
}
export interface JobSearchResult {
  items: RealJob[]
  fetchedAt: string
  meta: {
    source?: string
    snapshotDate?: string
    sourceDescription?: string
    fallbackReason?: string
    sites: string[]
    cached: boolean
    truncated: boolean
    skipped: { site: string; reason: string }[]
    degradedSites: string[]
  }
}
export interface ChatJobSearch {
  schemaVersion: 2
  criteria: { query: string; site: string; nature: string; location: string }
  items: Pick<RealJob, 'id' | 'site' | 'title' | 'company' | 'locations' | 'nature' | 'updatedAt'>[]
  returnedCount: number
  fetchedAt: string
  meta: JobSearchResult['meta']
}
export interface MatchRequirement {
  requirement: string
  status: 'MET' | 'PARTIAL' | 'MISSING'
  jdQuote: string
  resumePath: string
  resumeQuote: string
  reason: string
  suggestion: string
}
export interface JobEvalDimension {
  code: string
  name: string
  score: number
  reason: string
}
export interface JobEvaluation {
  cardType: 'job_eval_report'
  matchId: string
  jobId: string
  title: string
  company: string
  dimensions: JobEvalDimension[]
  overall: number
  grade: string
  summary: string
  advice: string[]
  scoringVersion: string
  note: string
}
export interface EvidenceMatch {
  modelTier?: ModelTier
  id: string
  jobId: string
  resumeId: string
  resumeTitle: string
  resumeVersionId: string
  resumeVersionNo: number
  createdAt: string
  score: number
  summary: string
  requirements: MatchRequirement[]
  jobSnapshot: RealJob
  resumeSnapshot: Record<string, unknown>
  model: string
  evaluation?: JobEvaluation
}
export interface MatchHistory {
  id: string
  status: string
  resumeVersionId: string
  resumeTitle: string | null
  score: string | null
  createdAt: string
}
