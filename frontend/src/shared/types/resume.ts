import { translate as uiText } from '@/shared/utils/language'
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
  get QUEUED() {
    return uiText('Waiting to parse')
  },
  get PARSING() {
    return uiText('Parsing')
  },
  get READY() {
    return uiText('Ready to edit')
  },
  get FAILED() {
    return uiText('Parsing failed')
  },
}
export const resumeVersionSource: Record<ResumeVersion['source'], string> = {
  get UPLOAD() {
    return uiText('Uploaded and parsed')
  },
  get MANUAL_EDIT() {
    return uiText('Manual edit')
  },
  get OPTIMIZED() {
    return uiText('Accepted rewrite')
  },
}
