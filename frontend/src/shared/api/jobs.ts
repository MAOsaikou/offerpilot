import { runGeneration } from './generationTasks'
import { apiRequest, apiUrl, authenticatedHeaders, fetchWithIdentity, toApiError } from './client'
import { downloadText } from '@/shared/utils/download'
import type { ModelTier } from '@/shared/types/model-tier'
import type {
  EvidenceMatch,
  JobImportFields,
  JobImportParse,
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
/** JB-06: parse JD text / a public URL in the background; only a review draft is stored until explicit save. */
export const parseJobImport = (source: { text?: string; url?: string }) =>
  runGeneration<JobImportParse>('JOB_IMPORT', 'current', source)
/** Save the reviewed draft as a job only this user can see. */
export const saveJobImport = (fields: JobImportFields) =>
  apiRequest<RealJob>('/jobs/import', { method: 'POST', body: JSON.stringify(fields) })
export const listImportedJobs = (signal?: AbortSignal) =>
  apiRequest<RealJob[]>('/jobs/imported', { signal })
export const getJob = (id: string, signal?: AbortSignal) =>
  apiRequest<RealJob>(`/jobs/${encodeURIComponent(id)}`, { signal })
export const refreshJob = (id: string, signal?: AbortSignal) =>
  apiRequest<RealJob>(`/jobs/${encodeURIComponent(id)}/refresh`, { method: 'POST', signal })
export const matchJob = (
  id: string,
  resumeId: string,
  versionId: string,
  requestId: string,
  modelTier?: ModelTier,
) => runGeneration<EvidenceMatch>('JOB_MATCH', id, { resumeId, versionId, modelTier }, requestId)
export const listJobMatches = (id: string, signal?: AbortSignal) =>
  apiRequest<MatchHistory[]>(`/jobs/${encodeURIComponent(id)}/matches`, { signal })
export const getJobMatch = (id: string, signal?: AbortSignal) =>
  apiRequest<EvidenceMatch>(`/jobs/matches/${encodeURIComponent(id)}`, { signal })

/** Downloads deterministic A–F evaluation markdown for a COMPLETED match. */
export async function exportJobEvalMarkdown(
  matchId: string,
  filename = '岗位评估.md',
): Promise<void> {
  const response = await fetchWithIdentity(
    apiUrl(`/jobs/matches/${encodeURIComponent(matchId)}/eval.md`),
    {
      headers: await authenticatedHeaders({ Accept: 'text/markdown' }),
    },
  )
  if (!response.ok) throw await toApiError(response)
  downloadText(filename, await response.text())
}
