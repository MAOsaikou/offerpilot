import { translate as uiText } from '@/shared/utils/language'
import { apiRequest } from './client'
export const memoryKinds = {
  get GOAL() {
    return uiText('Target job')
  },
  get LOCATION() {
    return uiText('Target city')
  },
  get SKILL() {
    return uiText('Technology and skills')
  },
  get PREFERENCE() {
    return uiText('Preferences')
  },
  get CONSTRAINT() {
    return uiText('Constraints')
  },
  get OTHER() {
    return uiText('Other')
  },
} as const
export type MemoryKind = keyof typeof memoryKinds
export interface MemoryDraft {
  kind: MemoryKind
  fact: string
}
export interface UserMemory extends MemoryDraft {
  id: string
  source: 'EXPLICIT' | 'SUGGESTED_ACCEPTED' | 'MANUAL'
  sourceConversationId: string | null
  sourceMessageId: string | null
  createdAt: string
  updatedAt: string
}
export interface MemoryInventory {
  items: UserMemory[]
  memoryEnabled: boolean
  limit: number
}
export const listMemories = () => apiRequest<MemoryInventory>('/memories')
export const addMemory = (draft: MemoryDraft) =>
  apiRequest<UserMemory>('/memories', { method: 'POST', body: JSON.stringify(draft) })
export const editMemory = (id: string, draft: MemoryDraft) =>
  apiRequest<UserMemory>(`/memories/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(draft),
  })
export const deleteMemory = (id: string) =>
  apiRequest<null>(`/memories/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const clearMemories = () => apiRequest<null>('/memories', { method: 'DELETE' })
export const setMemoryEnabled = (memoryEnabled: boolean) =>
  apiRequest<{ memoryEnabled: boolean }>('/memories/settings', {
    method: 'PUT',
    body: JSON.stringify({ memoryEnabled }),
  })

export interface MemoryCandidate extends MemoryDraft {
  id: string
  confidence: number
  extractor: 'model' | 'rule'
  sourceConversationId: string
  sourceMessageId: string
}
export const listMemoryCandidates = () => apiRequest<MemoryCandidate[]>('/memories/candidates')
export const confirmMemoryCandidate = (id: string) =>
  apiRequest<UserMemory>(`/memories/candidates/${encodeURIComponent(id)}/confirm`, {
    method: 'POST',
  })
export const deleteMemoryCandidate = (id: string) =>
  apiRequest<null>(`/memories/candidates/${encodeURIComponent(id)}`, { method: 'DELETE' })
