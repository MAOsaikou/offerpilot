import type { RealJob } from './jobs'

export const applicationStages = {
  TODO: '收藏',
  APPLIED: '已投递',
  INTERVIEW: '面试中',
  OFFER: 'Offer',
  CLOSED: '已结束',
} as const
export type ApplicationStatus = keyof typeof applicationStages
export interface ServerApplication {
  id: string
  jobId: string
  status: ApplicationStatus
  note: string
  nextAction: string
  nextActionAt: string | null
  createdAt: string
  updatedAt: string
  job: RealJob
}

export interface ApplicationPatch {
  status?: ApplicationStatus
  note?: string
  nextAction?: string
  /** Empty string clears the date; omitted leaves it unchanged. */
  nextActionAt?: string
}
export interface ApplicationEvent {
  id: string
  kind: 'CREATED' | 'STATUS_CHANGED' | 'NOTE_EDITED' | 'NEXT_ACTION_EDITED' | 'ARTIFACT_LINKED'
  fromStatus: ApplicationStatus | null
  toStatus: ApplicationStatus | null
  detail: string
  artifactType: string | null
  artifactId: string | null
  actor: 'USER' | 'AGENT' | 'SYSTEM'
  createdAt: string
}
export interface ApplicationDetail {
  application: ServerApplication
  latestMatch: {
    id: string
    createdAt: string
    grade: string
    topGaps: string[]
    exportUrl: string
  } | null
  resumeOptimizations: {
    id: string
    resumeId: string
    resumeVersionId: string
    createdAt: string
    status: string
    viewUrl: string
    pdfAvailable: boolean
    exportUrl: string | null
  }[]
  interviews: {
    id: string
    status: string
    overallScore: number | null
    createdAt: string
    recapExportUrl: string | null
  }[]
  starStories: { id: string; title: string; sourceSessionId: string | null }[]
  timeline: ApplicationEvent[]
  linkageNote: string
}

/** dataTransfer type for dragging a board card between stage columns. */
export const APPLICATION_DRAG_TYPE = 'application/x-offerpilot-application'
