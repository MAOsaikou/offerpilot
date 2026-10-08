export type ResumeEntry = Record<string, string | string[]>
export interface ResumeStructure {
  basics: { name: string; phone: string; email: string; city: string; links: string[] }
  education: ResumeEntry[]
  experience: ResumeEntry[]
  projects: ResumeEntry[]
  skills: ResumeEntry[]
  certificates: string[]
  summary: string
  confidence?: Record<string, number>
}
export interface ResumeVersion {
  id: string
  versionNo: number
  parentVersionId: string | null
  source: 'UPLOAD' | 'MANUAL_EDIT' | 'OPTIMIZED'
  structured: ResumeStructure
  plainText: string
  parseMeta: Record<string, unknown>
  createdAt: string
}
export interface ServerResume {
  id: string
  title: string
  parseStatus: 'QUEUED' | 'PARSING' | 'READY' | 'FAILED'
  parseProgress: number
  parseError: string | null
  currentVersionId: string | null
  currentVersion: ResumeVersion | null
  fileName: string
  createdAt: string
  updatedAt: string
}
export interface ResumePage {
  items: ServerResume[]
  total: number
  page: number
  size: number
}
export const resumeStatus = {
  QUEUED: '等待解析',
  PARSING: '正在解析',
  READY: '可编辑',
  FAILED: '解析失败',
}
export const resumeVersionSource: Record<ResumeVersion['source'], string> = {
  UPLOAD: '上传解析',
  MANUAL_EDIT: '手动编辑',
  OPTIMIZED: '改写采纳',
}
