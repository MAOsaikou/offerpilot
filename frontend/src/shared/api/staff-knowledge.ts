import {
  apiRequest,
  apiUrl,
  authenticatedHeaders,
  fetchWithIdentity,
  readApiData,
  toApiError,
} from './client'
import { validateKnowledgeFile, type IndexStatus } from './knowledge'

export interface StaffKnowledgeDocument {
  id: string
  title: string
  filename: string
  mime: string
  byteSize: number
  indexStatus: IndexStatus
  indexError: string | null
  chunkCount: number
  sourceTruncated: boolean
  createdAt: string
  updatedAt: string
}
export interface StaffKnowledgePage {
  items: StaffKnowledgeDocument[]
  total: number
  page: number
  size: number
}
const base = '/staff/knowledge/documents'
export const listStaffDocuments = (page = 1, signal?: AbortSignal) =>
  apiRequest<StaffKnowledgePage>(`${base}?page=${page}&size=20`, { signal })
export const reindexStaffDocument = (id: string) =>
  apiRequest<StaffKnowledgeDocument>(`${base}/${encodeURIComponent(id)}/reindex`, {
    method: 'POST',
  })
export const deleteStaffDocument = (id: string) =>
  apiRequest<null>(`${base}/${encodeURIComponent(id)}`, { method: 'DELETE' })
export async function uploadStaffDocument(file: File): Promise<StaffKnowledgeDocument> {
  validateKnowledgeFile(file)
  const body = new FormData()
  body.append('file', file)
  const response = await fetchWithIdentity(apiUrl(`${base}/upload`), {
    method: 'POST',
    headers: await authenticatedHeaders(),
    body,
  })
  if (!response.ok) throw await toApiError(response)
  return readApiData<StaffKnowledgeDocument>(response)
}
