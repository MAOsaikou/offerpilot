import { apiRequest } from './client'
import type { CareerProfile } from './career'
export interface TargetPlan {
  revision: string
  target: string
  city: string
  status: 'PENDING' | 'RUNNING' | 'SUGGESTED' | 'CONFIRMED' | 'FAILED'
  notice: string
  result: {
    jobs?: {
      items: Array<{ id: string; title: string; company: string; source?: string }>
      meta?: { partial?: boolean }
    }
    skillGap?: { skills: Array<{ name: string; level: string }>; notice: string }
    learning?: {
      summary: string
      roadmap: {
        weeks: Array<{
          index: number
          theme: string
          items: Array<{ id: string; title: string; hours: number }>
        }>
      } | null
    }
  } | null
}
export interface ProfileSuggestion {
  id: string
  sourceType: 'RESUME' | 'GITHUB' | 'INTERVIEW'
  sourceId: string
  skills: string[]
  facts: string[]
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'STALE'
  createdAt: string
}
export const getTargetPlan = () =>
  apiRequest<TargetPlan | null>('/career/target-plan').then((plan) => plan ?? null)
export const confirmTargetPlan = (revision: string) =>
  apiRequest<TargetPlan>('/career/target-plan/confirm', {
    method: 'POST',
    body: JSON.stringify({ revision }),
  })
export const retryTargetPlan = (revision: string) =>
  apiRequest<TargetPlan>('/career/target-plan/retry', {
    method: 'POST',
    body: JSON.stringify({ revision }),
  })
export const getProfileSuggestions = () =>
  apiRequest<ProfileSuggestion[]>('/career/profile-suggestions')
export const decideProfileSuggestion = (id: string, accept: boolean, skills: string[]) =>
  apiRequest<CareerProfile>(`/career/profile-suggestions/${encodeURIComponent(id)}/decision`, {
    method: 'POST',
    body: JSON.stringify({ accept, skills }),
  })
