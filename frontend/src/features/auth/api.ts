/**
 * 登录认证 API：用户端 /auth/login、客服端 /auth/staff/login、令牌校验 /auth/verify。
 * 令牌保存在 localStorage，键为 shared/api/client 的 ACCESS_TOKEN_KEY。
 */
import { ACCESS_TOKEN_KEY, apiUrl, toApiError } from '@/shared/api/client'

export interface AuthUser {
  id: string
  email: string
  nickname: string
  roles: string[]
}

export interface LoginResult {
  accessToken: string
  expiresIn: number
  user: AuthUser
}

export interface VerifyResult {
  valid: boolean
  userId: string
  roles: string[]
  nickname: string | null
  issuedAt: string | null
  expiresAt: string | null
}

async function postAuth(path: string, email: string, password: string): Promise<LoginResult> {
  const response = await fetch(apiUrl(path), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!response.ok) throw await toApiError(response)
  const body = (await response.json()) as { data: LoginResult }
  localStorage.setItem(ACCESS_TOKEN_KEY, body.data.accessToken)
  return body.data
}

/** 用户端登录。 */
export function login(email: string, password: string): Promise<LoginResult> {
  return postAuth('/auth/login', email, password)
}

/** 用户端注册：仅需邮箱 + 密码，成功即返回令牌（自动登录）。 */
export function register(email: string, password: string): Promise<LoginResult> {
  return postAuth('/auth/register', email, password)
}

/** 客服端登录（要求 STAFF 角色）。 */
export function staffLogin(email: string, password: string): Promise<LoginResult> {
  return postAuth('/auth/staff/login', email, password)
}

/** 携带已保存令牌请求后端解析与比对校验。 */
export async function verifyToken(): Promise<VerifyResult> {
  const token = localStorage.getItem(ACCESS_TOKEN_KEY)
  if (!token) throw new Error('本地没有已保存的令牌，请先登录')
  const response = await fetch(apiUrl('/auth/verify'), {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!response.ok) throw await toApiError(response)
  const body = (await response.json()) as { data: VerifyResult }
  return body.data
}

export function getStoredToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function clearToken(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
}
