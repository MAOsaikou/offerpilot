import { translate as uiText } from '@/shared/utils/language'
import { apiRequest } from './client'
export type BuiltinKind = 'QUESTION' | 'COMPETENCY' | 'CAMPUS' | 'REPORT'
export interface CatalogPage<T> {
  items: T[]
  total: number
  page: number
  size: number
}
export interface CatalogOccupation {
  id: string
  name: string
  family: string
  description: string
  hollandCode: string | null
  riasec: Record<string, number> | null
  coreSkills: { name: string; level?: string; category?: string }[] | null
  salaryRange: Record<string, unknown> | null
  salaryNotice: string
  growthPath: string | null
  growthPathSource: string | null
  skillsSource: string | null
  onetCode: string | null
  source: string
  sourceUrl: string | null
  license: string | null
  attribution: string | null
  notice: string | null
}
export interface BuiltinEntry {
  id: string
  kind: BuiltinKind
  title: string
  content: string
  industry: string
  role: string
  questionType: string | null
  difficulty: string | null
  source: string
  sourceUrl: string | null
  license: string
  notice: string
  status: 'READY' | 'PLANNED'
  payload: {
    answerGuide?: string[]
    plannedSections?: string[]
    competencies?: { name: string; evidence: string }[]
  }
}
export interface BuiltinFacets {
  families: string[]
  industries: string[]
  roles: string[]
  counts: Partial<Record<BuiltinKind, number>>
  occupations: number
  directOnetOccupations: number
}
export const questionTypes: Record<string, string> = {
  get BEHAVIORAL() { return uiText("Behavioral") },
  get SITUATIONAL() { return uiText("Situational") },
  get KNOWLEDGE() { return uiText("Major") },
  get CASE() { return uiText("Case study") },
}
export const difficulties: Record<string, string> = { get EASY() { return uiText("Basic") }, get STANDARD() { return uiText("Standard") }, get HARD() { return uiText("Advanced #2") } }
export const builtinKinds: Record<BuiltinKind, string> = {
  get QUESTION() { return uiText("General question bank") },
  get COMPETENCY() { return uiText("Job competency models") },
  get CAMPUS() { return uiText("Graduate recruitment process") },
  get REPORT() { return uiText("Industry reports (planned)") },
}
export type CatalogFilters = Record<string, string | number>
const params = (filters: CatalogFilters) =>
  new URLSearchParams(Object.entries(filters).map(([k, v]) => [k, String(v)])).toString()
export const getBuiltinFacets = (signal?: AbortSignal) =>
  apiRequest<BuiltinFacets>('/knowledge/builtin/facets', { signal })
export const listBuiltinOccupations = (filters: CatalogFilters, signal?: AbortSignal) =>
  apiRequest<CatalogPage<CatalogOccupation>>(`/knowledge/builtin/occupations?${params(filters)}`, {
    signal,
  })
export const getBuiltinOccupation = (id: string, signal?: AbortSignal) =>
  apiRequest<CatalogOccupation>(`/knowledge/builtin/occupations/${encodeURIComponent(id)}`, {
    signal,
  })
export const listBuiltinEntries = (filters: CatalogFilters, signal?: AbortSignal) =>
  apiRequest<CatalogPage<BuiltinEntry>>(`/knowledge/builtin/entries?${params(filters)}`, { signal })
export interface BuiltinSearchHit {
  id: string
  kind: BuiltinKind
  title: string
  excerpt: string
  source: string
  notice: string
  score: number
  lexicalMatch: boolean
  vectorMatch: boolean
}
export const searchBuiltinKnowledge = (q: string, kind: string, signal?: AbortSignal) =>
  apiRequest<{ items: BuiltinSearchHit[]; vectorAvailable: boolean; notice: string }>(
    `/knowledge/builtin/search?${params({ q, kind, limit: 20 })}`,
    { signal },
  )
