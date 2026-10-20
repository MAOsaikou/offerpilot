import { runGeneration } from './generationTasks'
import { apiRequest } from './client'

export interface CareerProfileFields {
  target: string
  city: string
  skills: string
  interests: string[]
  major: string
  courses: string
  valuesText: string
}

export interface CareerProfile extends CareerProfileFields {
  evidence?: Array<{ sourceType: string; sourceId: string; facts: string[]; confirmedAt: string }>
  assessment: AssessmentConfirmed | null
  completedTasks: string[]
  updatedAt: string | null
}

// apiRequest composes apiUrl/authenticatedHeaders/fetchWithIdentity/readApiData.
export const getCareerProfile = (signal?: AbortSignal) =>
  apiRequest<CareerProfile>('/career/profile', { signal })

export const saveCareerProfile = (profile: CareerProfileFields) =>
  apiRequest<CareerProfile>('/career/profile', {
    method: 'PUT',
    body: JSON.stringify(profile),
  })

export const saveCareerTasks = (completed: string[]) =>
  apiRequest<CareerProfile>('/career/tasks', {
    method: 'PUT',
    body: JSON.stringify({ completed }),
  })

/** CD-01/CD-02 conversational assessment. */
export type RiasecLetter = 'R' | 'I' | 'A' | 'S' | 'E' | 'C'
export interface AssessmentTrait {
  name: string
  confidence: number
  /** Verbatim quote from the user's answers; empty for traits the user added. */
  evidence: string
}
export interface AssessmentConstraints {
  city: string
  salary: string
  overtime: string
}
export interface AssessmentInference {
  riasec: Record<RiasecLetter, number>
  riasecEvidence: Partial<Record<RiasecLetter, string>>
  traits: AssessmentTrait[]
  constraints: AssessmentConstraints
  summary: string
  source: 'model' | 'rule'
}
export interface AssessmentConfirmed {
  riasec: Record<RiasecLetter, number>
  code: string
  traits: AssessmentTrait[]
  constraints: AssessmentConstraints
}
export interface CareerAssessment {
  questions: string[]
  answers: string[]
  inference: AssessmentInference | null
  confirmed: AssessmentConfirmed | null
  confirmedAt: string | null
}

export const getCareerAssessment = (signal?: AbortSignal) =>
  apiRequest<CareerAssessment>('/career/assessment', { signal })

export const saveAssessmentAnswer = (step: number, answer: string) =>
  apiRequest<CareerAssessment>(`/career/assessment/answers/${step}`, {
    method: 'PUT',
    body: JSON.stringify({ answer }),
  })

export const inferCareerAssessment = () =>
  runGeneration<CareerAssessment>('CAREER_ASSESSMENT', 'current', {})

export const confirmCareerAssessment = (body: {
  riasec: Record<RiasecLetter, number>
  traits: string[]
  city: string
  salary: string
  overtime: string
}) =>
  apiRequest<CareerAssessment>('/career/assessment/confirm', {
    method: 'PUT',
    body: JSON.stringify(body),
  })

export const resetCareerAssessment = () =>
  apiRequest<CareerAssessment>('/career/assessment', { method: 'DELETE' })

export interface CareerConstraints {
  city: string
  minSalaryK: number | null
  avoidOvertime: boolean
  research: boolean
}
export interface CareerOccupation {
  occupationId: string
  name: string
  family: string
  riasec: Record<RiasecLetter, number>
  coreSkills: Array<{ name: string; level?: string; category?: string }>
  salaryRange: { campus?: string; mid?: string; source?: string } | null
  growthPath: string | null
  source: string
  match: number | null
}
export interface CareerRevision {
  id: string
  parentId: string | null
  constraints: CareerConstraints
  items: Array<{
    occupation: CareerOccupation
    baseScore: number
    score: number
    rank: number
    rankDelta: number
    changeReason: string
  }>
  elapsedMs: number
  explanationFallback: boolean
  createdAt: string
}
export interface CareerComparison {
  axes: RiasecLetter[]
  occupations: CareerOccupation[]
}
export const getCareerCatalog = (signal?: AbortSignal) =>
  apiRequest<Array<{ occupationId: string; name: string }>>('/career/occupations', { signal })
export const getCareerRevision = (id?: string, signal?: AbortSignal) =>
  apiRequest<CareerRevision | null>(
    `/career/recommendations${id ? '/' + encodeURIComponent(id) : ''}`,
    { signal },
  )
export const createCareerRecommendations = (signal?: AbortSignal) =>
  runGeneration<CareerRevision>('CAREER_RECOMMENDATION', 'current', {}, undefined, signal)
export const rerankCareers = (id: string, constraints: CareerConstraints, signal?: AbortSignal) =>
  apiRequest<CareerRevision>(`/career/recommendations/${encodeURIComponent(id)}/rerank`, {
    method: 'POST',
    body: JSON.stringify(constraints),
    signal,
  })
export const compareCareers = (ids: string[], signal?: AbortSignal) =>
  apiRequest<CareerComparison>(`/career/compare?ids=${ids.map(encodeURIComponent).join(',')}`, {
    signal,
  })
