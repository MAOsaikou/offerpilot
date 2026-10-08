import { apiUrl, authHeaders, fetchWithIdentity, readApiData, toApiError } from './client'
import { getStoredToken } from '@/shared/utils/authSession'
import type { LoginResult, VerifyResult } from '@/shared/types/auth'

async function postAuth(path: string, email: string, password: string): Promise<LoginResult> {
  const response = await fetchWithIdentity(apiUrl(path), {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ email, password }),
  })
  if (!response.ok) throw await toApiError(response)
  return readApiData<LoginResult>(response)
}
export const login = (email: string, password: string) => postAuth('/auth/login', email, password)
export const register = (email: string, password: string) =>
  postAuth('/auth/register', email, password)
export const staffLogin = (email: string, password: string) =>
  postAuth('/auth/staff/login', email, password)

export async function createGuest(): Promise<string> {
  const response = await fetchWithIdentity(apiUrl('/auth/guest'), {
    method: 'POST',
    headers: authHeaders(),
  })
  if (!response.ok) throw await toApiError(response)
  const token = (await readApiData<{ accessToken: string }>(response)).accessToken
  if (!token) throw new Error('无法建立访客身份，请重试')
  return token
}

export async function verifyToken(): Promise<VerifyResult> {
  const token = getStoredToken()
  if (!token) throw new Error('请先登录')
  const response = await fetchWithIdentity(apiUrl('/auth/verify'), {
    headers: authHeaders({ Authorization: `Bearer ${token}` }),
  })
  if (!response.ok) throw await toApiError(response)
  return readApiData<VerifyResult>(response)
}
