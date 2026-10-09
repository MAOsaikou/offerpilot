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
