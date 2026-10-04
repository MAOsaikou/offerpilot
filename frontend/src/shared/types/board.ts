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
  createdAt: string
  updatedAt: string
  job: RealJob
}
