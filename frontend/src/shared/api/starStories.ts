import { apiRequest } from './client'

export interface StarStoryCandidate {
  questionIndex: number
  questionText: string
  title: string
  body: string
  role: string
  starCount: number
  situation: boolean
  task: boolean
  action: boolean
  result: boolean
  alreadySaved: boolean
}

export interface StarStory {
  id: string
  title: string
  body: string
  role: string
  sourceSessionId: string | null
  sourceQuestionIndex: number | null
  starCount: number
  situation: boolean
  task: boolean
  action: boolean
  result: boolean
  createdAt: string
  updatedAt: string
}

export interface StarStoryInventory {
  items: StarStory[]
  limit: number
}

export const listStarStories = (signal?: AbortSignal) =>
  apiRequest<StarStoryInventory>('/star-stories', { signal })

export const listStarStoryCandidates = (sessionId: string, signal?: AbortSignal) =>
  apiRequest<StarStoryCandidate[]>(
    `/star-stories/candidates?sessionId=${encodeURIComponent(sessionId)}`,
    { signal },
  )

export const saveStarStory = (
  body: { sessionId: string; questionIndex: number },
  signal?: AbortSignal,
) =>
  apiRequest<StarStory>('/star-stories', {
    method: 'POST',
    body: JSON.stringify(body),
    signal,
  })

export const deleteStarStory = (id: string, signal?: AbortSignal) =>
  apiRequest<null>(`/star-stories/${encodeURIComponent(id)}`, { method: 'DELETE', signal })

export const clearStarStories = (signal?: AbortSignal) =>
  apiRequest<null>('/star-stories', { method: 'DELETE', signal })
