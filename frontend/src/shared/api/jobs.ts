import { translate as uiText } from '@/shared/utils/language'
import { runGeneration } from './generationTasks'
import {
  apiRequest,
  apiUrl,
  authenticatedHeaders,
  fetchWithIdentity,
  readApiData,
  assertResponseIdentity,
  toApiError,
} from './client'
import { downloadText } from '@/shared/utils/download'
import type { ModelTier } from '@/shared/types/model-tier'
import type {
  DailyJobRecommendations,
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
export const parseJobImport = (source: { text?: string; url?: string; fileIds?: string[] }) =>
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
  filename = uiText("job-assessment.md"),
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

export const getJobRecommendations = (signal?: AbortSignal) =>
  apiRequest<DailyJobRecommendations>('/jobs/recommendations', { signal })

export interface JobScreenshotFile {
  fileId: string
  name: string
  mime: string
  size: number
}
export function validateJobScreenshot(file: File): string | undefined {
  if (!file.size) return uiText("Screenshot is empty. Please select again.")
  if (file.size > 5 * 1024 * 1024) return uiText("Each screenshot must be 5 MB or smaller.")
  if (!/\.(png|jpe?g|webp)$/i.test(file.name)) return uiText("Choose PNG, JPG or WebP screenshots.")
}
export async function uploadJobScreenshot(file: File): Promise<JobScreenshotFile> {
  const error = validateJobScreenshot(file)
  if (error) throw new Error(error)
  const body = new FormData()
  body.append('file', file)
  const response = await fetchWithIdentity(apiUrl('/jobs/import/screenshots'), {
    method: 'POST',
    headers: await authenticatedHeaders(),
    body,
  })
  if (!response.ok) throw await toApiError(response)
  return readApiData<JobScreenshotFile>(response)
}
export async function readJobScreenshot(id: string): Promise<Blob> {
  const response = await fetchWithIdentity(
    apiUrl(`/jobs/import/screenshots/${encodeURIComponent(id)}`),
    {
      headers: await authenticatedHeaders(),
    },
  )
  if (!response.ok) throw await toApiError(response)
  const blob = await response.blob()
  assertResponseIdentity(response)
  return blob
}
