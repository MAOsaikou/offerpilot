import { apiRequest } from './client'

export interface ResumeModelOption {
  id: string
  label: string
  provider: string
  model: string
  supportsVision: boolean
  configured: boolean
}
export interface ResumeModelSelection {
  textTier: string
  visionTier: string
}
export interface ResumeModelSettings {
  models: ResumeModelOption[]
  selection: ResumeModelSelection
}
export const getResumeModelSettings = () =>
  apiRequest<ResumeModelSettings>('/resume-model-settings')
export const saveResumeModelSettings = (selection: ResumeModelSelection) =>
  apiRequest<ResumeModelSettings>('/resume-model-settings', {
    method: 'PUT',
    body: JSON.stringify(selection),
  })
