import {
  apiRequest,
  apiUrl,
  authenticatedHeaders,
  fetchWithIdentity,
  readApiData,
  assertResponseIdentity,
  toApiError,
} from './client'

export const noteCategories = ['面试复盘', '技术积累', '项目经历', '求职心得'] as const
export type NoteCategory = (typeof noteCategories)[number]
export interface KnowledgeDraft {
  title: string
  content: string
  category: NoteCategory
}
export type IndexStatus = 'QUEUED' | 'INDEXING' | 'READY' | 'FAILED'
export const indexStatusLabels: Record<IndexStatus, string> = {
  QUEUED: '排队',
  INDEXING: '索引中',
  READY: '完成',
  FAILED: '失败',
}
interface DocumentStatus {
  indexStatus?: IndexStatus
  indexError?: string | null
  sourceFilename?: string | null
  sourceTruncated?: boolean
}
export interface KnowledgeNote extends KnowledgeDraft, DocumentStatus {
  sourceFileId?: string | null
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
export interface KnowledgeHit extends DocumentStatus {
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

export function validateKnowledgeFile(file: File): void {
  if (!file.size) throw new Error('文件为空，请选择包含正文的文件')
  if (file.size > 10 * 1024 * 1024) throw new Error('资料文件不能超过 10MB')
  if (!/\.(pdf|md|txt)$/i.test(file.name)) throw new Error('仅支持 PDF、MD、TXT 文件')
}
export async function uploadKnowledgeDocument(
  file: File,
  category?: NoteCategory,
): Promise<KnowledgeNote> {
  validateKnowledgeFile(file)
  const body = new FormData()
  body.append('file', file)
  if (category) body.append('category', category)
  const response = await fetchWithIdentity(apiUrl('/knowledge/notes/upload'), {
    method: 'POST',
    headers: await authenticatedHeaders(),
    body,
  })
  if (!response.ok) throw await toApiError(response)
  return readApiData<KnowledgeNote>(response)
}
export async function getKnowledgeOriginal(id: string): Promise<Blob> {
  const response = await fetchWithIdentity(
    apiUrl(`/knowledge/notes/${encodeURIComponent(id)}/file`),
    {
      headers: await authenticatedHeaders(),
    },
  )
  if (!response.ok) throw await toApiError(response)
  const blob = await response.blob()
  assertResponseIdentity(response)
  return blob
}
