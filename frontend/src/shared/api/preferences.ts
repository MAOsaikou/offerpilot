import { apiRequest } from './client'
import type { ThemeMode } from '@/shared/utils/theme'

export type DefaultTier = 'FLASH' | 'PRO'
export interface UserPreferences {
  defaultModelTier: DefaultTier
  theme: ThemeMode
  language: string
  /** false until the account saves once; then the server values win over this browser's. */
  saved: boolean
}

export const getPreferences = () => apiRequest<UserPreferences>('/preferences')
export const savePreferences = (value: { defaultModelTier: DefaultTier; theme: ThemeMode }) =>
  apiRequest<UserPreferences>('/preferences', { method: 'PUT', body: JSON.stringify(value) })
