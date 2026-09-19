import { apiRequest, apiUrl, authenticatedHeaders, toApiError } from './client'
import type {
  ResumePage,
  ResumeStructure,
  ResumeVersion,
  ServerResume,
} from '@/shared/types/resume'

export function validateResumeFile(file: File): string | undefined {
  if (!file.size) return '文件为空，请选择包含简历内容的文件。'
  if (file.size > 10 * 1024 * 1024) return '请选择 10MB 以内的简历文件。'
  if (!/\.(pdf|docx|png|jpe?g|txt|md)$/i.test(file.name))
    return '支持 PDF、DOCX、PNG、JPG 和 UTF-8 TXT / Markdown 文件。'
}
export async function uploadResume(file: File): Promise<ServerResume> {
  const error = validateResumeFile(file)
  if (error) throw new Error(error)
  const body = new FormData()
  body.append('file', file)
  const response = await fetch(apiUrl('/resumes'), {
    method: 'POST',
    headers: await authenticatedHeaders(),
    body,
  })
  if (!response.ok) throw await toApiError(response)
  return ((await response.json()) as { data: ServerResume }).data
}
export const listResumes = (page = 1, signal?: AbortSignal) =>
  apiRequest<ResumePage>(`/resumes?page=${page}&size=20`, { signal })
export const getResume = (id: string, signal?: AbortSignal) =>
  apiRequest<ServerResume>(`/resumes/${encodeURIComponent(id)}`, { signal })
export const retryResume = (id: string) =>
  apiRequest<ServerResume>(`/resumes/${encodeURIComponent(id)}/retry`, { method: 'POST' })
export const renameResume = (id: string, title: string) =>
  apiRequest<ServerResume>(`/resumes/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify({ title }),
  })
export const listResumeVersions = (id: string, signal?: AbortSignal) =>
  apiRequest<ResumeVersion[]>(`/resumes/${encodeURIComponent(id)}/versions`, { signal })
export const saveResume = (id: string, version: string, structured: ResumeStructure) =>
  apiRequest<ServerResume>(
    `/resumes/${encodeURIComponent(id)}/versions/${encodeURIComponent(version)}/structured`,
    { method: 'PUT', body: JSON.stringify(structured) },
  )
