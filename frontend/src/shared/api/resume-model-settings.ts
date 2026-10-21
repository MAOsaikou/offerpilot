import { sourceText } from '@/shared/utils/language'
import { normalizeModelTier } from '../types/model-tier'
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
export const getResumeModelSettings = async () => {
  const settings = await apiRequest<ResumeModelSettings>('/resume-model-settings')
  const legacy = (id: string) =>
    ['CHEAP', 'ECONOMY', sourceText("Economy"), 'BALANCED'].includes(id.trim().toUpperCase())
      ? normalizeModelTier(id)
      : id
  return {
    ...settings,
    models: settings.models.filter((model) => legacy(model.id) === model.id),
    selection: {
      textTier: legacy(settings.selection.textTier),
      visionTier: legacy(settings.selection.visionTier),
    },
  }
}
export const saveResumeModelSettings = (selection: ResumeModelSelection) =>
  apiRequest<ResumeModelSettings>('/resume-model-settings', {
    method: 'PUT',
    body: JSON.stringify(selection),
  })
