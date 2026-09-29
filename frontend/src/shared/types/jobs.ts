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
export interface EvidenceMatch {
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
}
export interface MatchHistory {
  id: string
  status: string
  resumeVersionId: string
  resumeTitle: string | null
  score: string | null
  createdAt: string
}
