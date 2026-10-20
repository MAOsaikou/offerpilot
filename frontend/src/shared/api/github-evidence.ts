import { apiRequest } from './client'
import type { ServerResume } from '@/shared/types/resume'

export interface GitHubProject {
  name: string
  url: string
  fork: boolean
  stars: number
  languages: Record<string, number>
  commits90d: number
  commitsTruncated: boolean
  highlights: string[]
}
export interface GitHubEvidence {
  username: string
  tokenConfigured: boolean
  status: string
  notice: string
  fetchedAt: string | null
  expiresAt: string | null
  stale: boolean
  snapshot: null | {
    id: string
    since: string
    repositoriesTruncated: boolean
    repositories: GitHubProject[]
    languages: Record<string, number>
    stars: number
    commits90d: number
  }
}
const path = '/resumes/github'
export const getGitHubEvidence = (signal?: AbortSignal) =>
  apiRequest<GitHubEvidence>(path, { signal, cache: 'no-store' })
export const saveGitHubSettings = (
  body: { username: string; token?: string; removeToken: boolean },
  signal?: AbortSignal,
) => apiRequest<GitHubEvidence>(path, { method: 'PUT', body: JSON.stringify(body), signal })
export const refreshGitHubEvidence = (force = false, signal?: AbortSignal) =>
  apiRequest<GitHubEvidence>(path + '/refresh?force=' + force, { method: 'POST', signal })
export const deleteGitHubSettings = (signal?: AbortSignal) =>
  apiRequest<null>(path, { method: 'DELETE', signal })
export const adoptGitHubProject = (
  body: {
    resumeId: string
    baseVersionId: string
    snapshotId: string
    repository: string
    confirmed: boolean
  },
  signal?: AbortSignal,
) =>
  apiRequest<ServerResume>(path + '/adopt', { method: 'POST', body: JSON.stringify(body), signal })
