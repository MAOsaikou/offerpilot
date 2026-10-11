/**
 * API 客户端基础设施。
 * 响应包裹、错误码与追踪约定见 docs/05 §1.2 / §1.3。
 */
import {
  ACCESS_TOKEN_KEY,
  GUEST_TOKEN_KEY,
  expireLoginIfNeeded,
  getStoredToken,
  invalidateCredential,
  loginRequired,
  identitySignal,
  saveGuest,
} from '@/shared/utils/authSession'

export { ACCESS_TOKEN_KEY }

export class ApiError extends Error {
  readonly code: number
  readonly status?: number
  readonly traceId?: string

  constructor(message: string, options: { code: number; status?: number; traceId?: string }) {
    super(message)
    this.name = 'ApiError'
    this.code = options.code
    this.status = options.status
    this.traceId = options.traceId
  }
}

export function isGenerationCancelled(error: unknown): boolean {
  return error instanceof ApiError && error.code === 30205
}

/** 后端基地址；本地开发可直连后端，也可走 vite 代理，见 frontend/.env.example */
export const API_BASE = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/+$/, '')

export function apiUrl(path: string): string {
  return `${API_BASE}${path.startsWith('/') ? path : `/${path}`}`
}

function createTraceId(): string {
  return Math.random().toString(16).slice(2, 10) + Date.now().toString(16).slice(-4)
}

export function authHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return { 'X-Trace-Id': createTraceId(), ...extra }
}

const IDENTITY_KEY = GUEST_TOKEN_KEY
/** 合并首次访客身份创建请求，避免同时创建多份历史。 */
let pendingIdentity: Promise<string> | undefined
const responseCredentials = new WeakMap<Response, string>()
const responseSessions = new WeakMap<Response, AbortSignal>()

function assertCredential(credential: string): void {
  const active = getStoredToken() || (loginRequired() ? null : localStorage.getItem(IDENTITY_KEY))
  if (credential !== active) throw new ApiError('身份已切换，请重新读取数据', { code: 20001 })
}

export function assertResponseIdentity(response: Response): void {
  const credential = responseCredentials.get(response)
  if (responseSessions.get(response)?.aborted)
    throw new ApiError('身份已切换，请重新读取数据', { code: 20001 })
  if (credential) assertCredential(credential)
}

export async function readApiData<T>(response: Response): Promise<T> {
  const body = (await response.json()) as { data: T }
  assertResponseIdentity(response)
  return body.data
}

/** 关联请求使用的身份，迟到的错误不会注销随后登录的新账号。 */
export async function fetchWithIdentity(
  input: RequestInfo | URL,
  init: RequestInit,
): Promise<Response> {
  const authorization = new Headers(init.headers).get('Authorization')
  const credential = authorization?.startsWith('Bearer ') ? authorization.slice(7) : null
  if (credential) assertCredential(credential)
  const session = identitySignal()
  const signal = init.signal ? AbortSignal.any([init.signal, session]) : session
  const response = await fetch(input, { ...init, signal })
  responseSessions.set(response, session)
  if (credential) {
    responseCredentials.set(response, credential)
    if (response.ok) assertResponseIdentity(response)
  }
  return response
}

async function guestToken(): Promise<string> {
  // Never silently replace an expired credential: that would hide the user's history.
  const saved = localStorage.getItem(IDENTITY_KEY)
  if (saved) return saved
  if (!pendingIdentity) {
    pendingIdentity = (async () => {
      const session = identitySignal()
      // Confirm browser storage is available before creating a server-side identity.
      localStorage.setItem('offerpilot-storage-check', '1')
      localStorage.removeItem('offerpilot-storage-check')
      const response = await fetchWithIdentity(apiUrl('/auth/guest'), {
        method: 'POST',
        headers: authHeaders(),
      })
      if (!response.ok) throw await toApiError(response)
      const body = await readApiData<{ accessToken: string }>(response)
      session.throwIfAborted()
      const token = body.accessToken
      if (!token) throw new Error('无法建立访客身份，请重试')
      saveGuest(token)
      return token
    })().finally(() => {
      pendingIdentity = undefined
    })
  }
  return pendingIdentity
}

export async function authenticatedHeaders(
  extra: Record<string, string> = {},
): Promise<Record<string, string>> {
  if (import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCK === 'true') return authHeaders(extra)
  // 已登录（邮箱密码）优先使用 JWT；否则回退到访客身份。
  expireLoginIfNeeded()
  const jwt = getStoredToken()
  if (jwt) return authHeaders({ ...extra, Authorization: `Bearer ${jwt}` })
  if (loginRequired())
    throw new ApiError('身份已失效，请重新登录或明确选择继续原访客', { code: 20002, status: 401 })
  // Web Locks also serialize the first visit across tabs where supported.
  const session = identitySignal()
  const readGuest = async () => {
    session.throwIfAborted()
    // Storage may already have changed before its cross-tab event is delivered.
    if (getStoredToken() || loginRequired())
      throw new ApiError('身份已切换，请重新读取数据', { code: 20001 })
    return guestToken()
  }
  const token =
    typeof navigator !== 'undefined' && navigator.locks
      ? await navigator.locks.request('offerpilot-guest', readGuest)
      : await readGuest()
  assertCredential(token)
  return authHeaders({ ...extra, Authorization: `Bearer ${token}` })
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetchWithIdentity(apiUrl(path), {
    ...init,
    headers: await authenticatedHeaders({ 'Content-Type': 'application/json' }),
  })
  if (!response.ok) throw await toApiError(response)
  return readApiData<T>(response)
}

/** 把非 2xx 响应映射成 ApiError，尽量读出服务端的 code / message / traceId */
export async function toApiError(response: Response): Promise<ApiError> {
  let code = response.status
  let message = response.statusText || '请求失败'
  let traceId: string | undefined

  try {
    const body = (await response.json()) as { code?: number; message?: string; traceId?: string }
    if (typeof body.code === 'number') code = body.code
    if (typeof body.message === 'string' && body.message) message = body.message
    if (typeof body.traceId === 'string') traceId = body.traceId
  } catch {
    // 非 JSON 响应体，保留 HTTP 状态码
  }

  const credential = responseCredentials.get(response)
  if (response.status === 401 && credential) invalidateCredential(credential)

  return new ApiError(message, { code, status: response.status, traceId })
}
