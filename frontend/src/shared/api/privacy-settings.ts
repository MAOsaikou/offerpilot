import {
  apiRequest,
  apiUrl,
  authenticatedHeaders,
  fetchWithIdentity,
  readApiData,
  toApiError,
} from './client'
export interface PrivacySettings {
  piiMasking: boolean
}
export interface PrivacyRisk {
  detected: boolean
  warning: string | null
}
export const PII_WARNING =
  'Contact details, an ID number or a home address were detected. Text masking is off, so this information may be sent to the model. Consider enabling masking in settings.'
export const getPrivacySettings = () => apiRequest<PrivacySettings>('/privacy-settings')
export const savePrivacySettings = (piiMasking: boolean) =>
  apiRequest<PrivacySettings>('/privacy-settings', {
    method: 'PUT',
    body: JSON.stringify({ piiMasking }),
  })
export const checkPrivacyText = (text: string) =>
  apiRequest<PrivacyRisk>('/privacy-settings/check', {
    method: 'POST',
    body: JSON.stringify({ text }),
  })
export async function checkPrivacyFile(file: File): Promise<PrivacyRisk> {
  const body = new FormData()
  body.append('file', file)
  const response = await fetchWithIdentity(apiUrl('/resumes/privacy-check'), {
    method: 'POST',
    headers: await authenticatedHeaders(),
    body,
  })
  if (!response.ok) throw await toApiError(response)
  return readApiData<PrivacyRisk>(response)
}
