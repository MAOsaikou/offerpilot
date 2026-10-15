import {
  apiRequest,
  apiUrl,
  authHeaders,
  fetchWithIdentity,
  readApiData,
  toApiError,
} from './client'
import { getStoredToken } from '@/shared/utils/authSession'
import type { LoginResult, VerifyResult } from '@/shared/types/auth'

async function postAuth(path: string, email: string, password: string): Promise<LoginResult> {
  const response = await fetchWithIdentity(apiUrl(path), {
    method: 'POST',
    headers: authHeaders({ 'Content-Type': 'application/json' }),
    // Lets the browser store the HttpOnly refresh cookie even when the API is on another origin.
    credentials: 'include',
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

/** Revoke this browser's refresh cookie; best effort, the short access token expires anyway. */
export async function logoutSession(): Promise<void> {
  try {
    await fetch(apiUrl('/auth/logout'), {
      method: 'POST',
      credentials: 'include',
      headers: authHeaders(),
    })
  } catch {
    // Offline logout still clears the local session.
  }
}

export async function demoLogin(): Promise<LoginResult> {
  const response = await fetchWithIdentity(apiUrl('/auth/demo-login'), {
    method: 'POST',
    headers: authHeaders(),
  })
  if (!response.ok) throw await toApiError(response)
  return readApiData<LoginResult>(response)
}

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

/* ===== 客服端：账号管理 ===== */

export interface StaffUserRow {
  id: string
  email: string
  nickname: string
  roles: string[]
  status: 'ACTIVE' | 'DISABLED' | 'DELETED'
  createdAt: string
}

export interface StaffUserPage {
  items: StaffUserRow[]
  total: number
  page: number
  size: number
}

export interface StaffUserListParams {
  page?: number
  size?: number
  hideGuests?: boolean
}

/** 分页列出用户端账号（不含客服账号），要求 STAFF 令牌。 */
export async function listStaffUsers(params: StaffUserListParams = {}): Promise<StaffUserPage> {
  const token = getStoredToken()
  if (!token) throw new Error('尚未登录，请先登录客服端')
  const query = new URLSearchParams()
  if (params.page !== undefined) query.set('page', String(params.page))
  if (params.size !== undefined) query.set('size', String(params.size))
  if (params.hideGuests !== undefined) query.set('hideGuests', String(params.hideGuests))
  const response = await fetchWithIdentity(apiUrl(`/staff/users${query.size ? `?${query}` : ''}`), {
    headers: authHeaders({ Authorization: `Bearer ${token}` }),
  })
  if (!response.ok) throw await toApiError(response)
  return readApiData<StaffUserPage>(response)
}

/** 启用/禁用用户端账号，要求 STAFF 令牌。 */
export async function setUserStatus(
  id: string,
  status: 'ACTIVE' | 'DISABLED',
): Promise<StaffUserRow> {
  const token = getStoredToken()
  if (!token) throw new Error('尚未登录，请先登录客服端')
  const response = await fetchWithIdentity(
    apiUrl(`/staff/users/${encodeURIComponent(id)}/status`),
    {
      method: 'PATCH',
      headers: authHeaders({
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      }),
      body: JSON.stringify({ status }),
    },
  )
  if (!response.ok) throw await toApiError(response)
  return readApiData<StaffUserRow>(response)
}

/** Deletes the caller account; no owner id can be sent by the client. */
export function deleteMyData(confirm: string): Promise<null> {
  return apiRequest<null>('/users/me/data', {
    method: 'DELETE',
    body: JSON.stringify({ confirm }),
    signal: AbortSignal.timeout(120_000),
  })
}

export interface DemoSandboxStatus {
  expiresAt: string
  remainingSeconds: number
  dailyTokenLimit: number
  remainingTokens: number
  budgetResetsAt: string
}
export const getDemoSandboxStatus = (signal?: AbortSignal) =>
  apiRequest<DemoSandboxStatus>('/demo/sandbox', { signal })
