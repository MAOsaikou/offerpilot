import {
  apiRequest,
  assertResponseIdentity,
  apiUrl,
  authenticatedHeaders,
  fetchWithIdentity,
  readApiData,
  toApiError,
} from './client'
import { downloadBlob } from '@/shared/utils/download'
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
  const response = await fetchWithIdentity(apiUrl('/resumes'), {
    method: 'POST',
    headers: await authenticatedHeaders(),
    body,
  })
  if (!response.ok) throw await toApiError(response)
  return readApiData<ServerResume>(response)
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
/** RS-07: deletes the resume with all versions, diagnoses, rewrite suggestions, match reports and the uploaded file. */
export const deleteResume = (id: string) =>
  apiRequest<{ deleted: boolean }>(`/resumes/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const listResumeVersions = (id: string, signal?: AbortSignal) =>
  apiRequest<ResumeVersion[]>(`/resumes/${encodeURIComponent(id)}/versions`, { signal })
export const getResumeVersion = (id: string, version: string) =>
  apiRequest<ResumeVersion>(
    `/resumes/${encodeURIComponent(id)}/versions/${encodeURIComponent(version)}`,
  )
export const saveResume = (id: string, version: string, structured: ResumeStructure) =>
  apiRequest<ServerResume>(
    `/resumes/${encodeURIComponent(id)}/versions/${encodeURIComponent(version)}/structured`,
    { method: 'PUT', body: JSON.stringify(structured) },
  )

/** Downloads the server's saved current version, including applied patches. */
export async function exportResumePdf(id: string, template = 'classic'): Promise<void> {
  const response = await fetchWithIdentity(
    apiUrl(
      `/resumes/${encodeURIComponent(id)}/export.pdf?template=${encodeURIComponent(template)}`,
    ),
    { headers: await authenticatedHeaders({ Accept: 'application/pdf' }) },
  )
  if (!response.ok) throw await toApiError(response)
  const blob = await response.blob()
  assertResponseIdentity(response)
  if (!response.headers.get('Content-Type')?.toLowerCase().startsWith('application/pdf'))
    throw new Error('PDF 导出响应格式错误，请重试。')
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const utf8Name = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1]
  let filename = /filename="([^";]+)"/i.exec(disposition)?.[1] ?? '简历.pdf'
  if (utf8Name) {
    try {
      filename = decodeURIComponent(utf8Name)
    } catch {
      // Malformed optional filename must not prevent downloading a valid PDF.
    }
  }
  downloadBlob(filename, blob)
}

/** Rolls back to an earlier version: the server copies it into a new current version on top of `baseVersionId`. */
export const restoreResumeVersion = (id: string, versionId: string, baseVersionId: string) =>
  apiRequest<ServerResume>(
    `/resumes/${encodeURIComponent(id)}/versions/${encodeURIComponent(versionId)}/restore`,
    { method: 'POST', body: JSON.stringify({ baseVersionId }) },
  )
