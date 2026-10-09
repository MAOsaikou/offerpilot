import { apiRequest } from './client'

export const noteCategories = ['面试复盘', '技术积累', '项目经历', '求职心得'] as const
export type NoteCategory = (typeof noteCategories)[number]
export interface KnowledgeDraft {
  title: string
  content: string
  category: NoteCategory
}
export interface KnowledgeNote extends KnowledgeDraft {
  id: string
  createdAt: string
  updatedAt: string
}
export interface KnowledgePage {
  items: KnowledgeNote[]
  total: number
  page: number
  size: number
}
export interface KnowledgeHit {
  id: string
  title: string
  category: NoteCategory
  excerpt: string
  updatedAt: string
  score: number
  lexicalMatch: boolean
  vectorMatch: boolean
}
export interface KnowledgeSearch {
  items: KnowledgeHit[]
  vectorAvailable: boolean
}
export interface KnowledgeImport {
  sourceId: string
  note: KnowledgeDraft
}
export const listKnowledgeNotes = (page = 1, size = 20, signal?: AbortSignal) =>
  apiRequest<KnowledgePage>(`/knowledge/notes?page=${page}&size=${size}`, { signal })
export const searchKnowledgeNotes = (q: string, limit = 50, signal?: AbortSignal) =>
  apiRequest<KnowledgeSearch>(
    `/knowledge/notes/search?${new URLSearchParams({ q, limit: String(limit) })}`,
    { signal },
  )
export const getKnowledgeNote = (id: string) =>
  apiRequest<KnowledgeNote>(`/knowledge/notes/${encodeURIComponent(id)}`)
export const createKnowledgeNote = (note: KnowledgeDraft) =>
  apiRequest<KnowledgeNote>('/knowledge/notes', { method: 'POST', body: JSON.stringify(note) })
export const updateKnowledgeNote = (id: string, note: KnowledgeDraft) =>
  apiRequest<KnowledgeNote>(`/knowledge/notes/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(note),
  })
export const deleteKnowledgeNote = (id: string) =>
  apiRequest<null>(`/knowledge/notes/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const importKnowledgeNotes = (notes: KnowledgeImport[]) =>
  apiRequest<{ imported: number; alreadyImported: number }>('/knowledge/notes/import', {
    method: 'POST',
    body: JSON.stringify({ notes }),
  })
