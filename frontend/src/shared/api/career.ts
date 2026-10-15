import { apiRequest } from './client'

export interface CareerProfileFields {
  target: string
  city: string
  skills: string
  interests: string[]
}

export interface CareerProfile extends CareerProfileFields {
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
  apiRequest<CareerAssessment>('/career/assessment/infer', { method: 'POST' })

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
