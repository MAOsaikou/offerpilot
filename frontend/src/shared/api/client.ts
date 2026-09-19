/**
 * API 客户端基础设施。
 * 响应包裹、错误码与追踪约定见 docs/05 §1.2 / §1.3。
 */

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

const IDENTITY_KEY = 'offerpilot-guest-v1'
let pendingIdentity: Promise<string> | undefined

async function guestToken(): Promise<string> {
  // Never silently replace an expired credential: that would hide the user's history.
  const saved = localStorage.getItem(IDENTITY_KEY)
  if (saved) return saved
  if (!pendingIdentity) {
    pendingIdentity = (async () => {
      // Confirm browser storage is available before creating a server-side identity.
      localStorage.setItem('offerpilot-storage-check', '1')
      localStorage.removeItem('offerpilot-storage-check')
      const response = await fetch(apiUrl('/auth/guest'), {
        method: 'POST',
        headers: authHeaders(),
      })
      if (!response.ok) throw await toApiError(response)
      const body = (await response.json()) as { data: { accessToken: string } }
      const token = body.data.accessToken
      if (!token) throw new Error('无法建立访客身份，请重试')
      localStorage.setItem(IDENTITY_KEY, token)
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
  // Web Locks also serialize the first visit across tabs where supported.
  const token =
    typeof navigator !== 'undefined' && navigator.locks
      ? await navigator.locks.request('offerpilot-guest', guestToken)
      : await guestToken()
  return authHeaders({ ...extra, Authorization: `Bearer ${token}` })
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(apiUrl(path), {
    ...init,
    headers: await authenticatedHeaders({ 'Content-Type': 'application/json' }),
  })
  if (!response.ok) throw await toApiError(response)
  return ((await response.json()) as { data: T }).data
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

  return new ApiError(message, { code, status: response.status, traceId })
}
