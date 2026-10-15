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
  '检测到联系方式、身份证号或住址。当前已关闭文本脱敏，相关信息可能发送给模型；建议在设置中开启。'
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
