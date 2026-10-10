import {
  ApiError,
  apiRequest,
  apiUrl,
  authenticatedHeaders,
  fetchWithIdentity,
  toApiError,
} from './client'
import { downloadText } from '@/shared/utils/download'
import type { ModelTier } from '@/shared/types/model-tier'
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
/** No automatic POST retry: a disconnected request may still save its report. */
export const matchJob = async (
  id: string,
  resumeId: string,
  resumeVersionId: string,
  requestId: string,
  modelTier?: ModelTier,
) => {
  try {
    return await apiRequest<EvidenceMatch>(`/jobs/${encodeURIComponent(id)}/matches`, {
      method: 'POST',
      body: JSON.stringify({ resumeId, resumeVersionId, requestId, modelTier }),
      signal: AbortSignal.timeout(modelTier === 'FLASH' ? 90_000 : 210_000),
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'TimeoutError')
      throw new ApiError(
        '等待匹配响应超时。分析可能仍在保存，请先刷新匹配记录，再决定是否重新分析。',
        { code: 40001 },
      )
    if (error instanceof ApiError && error.status === 504 && error.code === 504)
      throw new ApiError('网关等待匹配响应超时。请先刷新匹配记录，确认结果后再重新分析。', {
        code: 40001,
        status: 504,
        traceId: error.traceId,
      })
    throw error
  }
}
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
