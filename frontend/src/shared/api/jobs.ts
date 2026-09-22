import { apiRequest } from './client'
import type {
  EvidenceMatch,
  JobSearchResult,
  JobSite,
  MatchHistory,
  RealJob,
} from '@/shared/types/jobs'
export interface JobSearch {
  query: string
  site: string
  nature: string
  location: string
}
export const listJobSites = (signal?: AbortSignal) =>
  apiRequest<{ sites: JobSite[] }>('/jobs/sites', { signal })
export const searchJobs = (search: JobSearch, signal?: AbortSignal) =>
  apiRequest<JobSearchResult>('/jobs?' + new URLSearchParams({ ...search }), { signal })
export const getJob = (id: string, signal?: AbortSignal) =>
  apiRequest<RealJob>(`/jobs/${encodeURIComponent(id)}`, { signal })
export const refreshJob = (id: string, signal?: AbortSignal) =>
  apiRequest<RealJob>(`/jobs/${encodeURIComponent(id)}/refresh`, { method: 'POST', signal })
export const matchJob = (
  id: string,
  resumeId: string,
  resumeVersionId: string,
  requestId: string,
) =>
  apiRequest<EvidenceMatch>(`/jobs/${encodeURIComponent(id)}/matches`, {
    method: 'POST',
    body: JSON.stringify({ resumeId, resumeVersionId, requestId }),
  })
export const listJobMatches = (id: string, signal?: AbortSignal) =>
  apiRequest<MatchHistory[]>(`/jobs/${encodeURIComponent(id)}/matches`, { signal })
export const getJobMatch = (id: string, signal?: AbortSignal) =>
  apiRequest<EvidenceMatch>(`/jobs/matches/${encodeURIComponent(id)}`, { signal })
