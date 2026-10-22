import { apiRequest } from './client'
export interface SkillGapReport {
  targetType: 'JOB' | 'OCCUPATION'
  targetId: string
  targetTitle: string
  resumeVersionId: string | null
  notice: string
  skills: Array<{
    name: string
    level: 'POSSESSED' | 'PARTIAL' | 'MISSING'
    sources: Array<{
      type: string
      id: string | null
      label: string
      excerpt: string
      fetchedAt: string | null
    }>
  }>
}
export const getSkillGap = (
  selection: { occupationId?: string; jobId?: string; resumeId?: string; versionId?: string },
  signal?: AbortSignal,
) =>
  apiRequest<SkillGapReport>(
    '/career/skill-gap?' +
      new URLSearchParams(
        Object.entries(selection).filter((entry): entry is [string, string] => Boolean(entry[1])),
      ),
    { signal, cache: 'no-store' },
  )
