import { ref } from 'vue'
import { queryClient } from '@/shared/api/queryClient'
import type { AuthUser, LoginResult } from '@/shared/types/auth'

export const ACCESS_TOKEN_KEY = 'offerpilot-access-token'
export const GUEST_TOKEN_KEY = 'offerpilot-guest-v1'
const USER_KEY = 'offerpilot-auth-user'
const LOGIN_REQUIRED_KEY = 'offerpilot-login-required'
const INVALID_GUEST_KEY = 'offerpilot-invalid-guest'
export const sessionRevision = ref(0)
let identityController = new AbortController()
export const identitySignal = () => identityController.signal

export const getStoredToken = () => localStorage.getItem(ACCESS_TOKEN_KEY)
export const getStoredGuest = () => localStorage.getItem(GUEST_TOKEN_KEY)
export const loginRequired = () => localStorage.getItem(LOGIN_REQUIRED_KEY) === 'true'
export const guestAvailable = () =>
  Boolean(getStoredGuest()) && localStorage.getItem(INVALID_GUEST_KEY) !== getStoredGuest()

const storedIdentity = () =>
  getStoredToken() || (!loginRequired() && guestAvailable() ? getStoredGuest() : null)
let observedIdentity = storedIdentity()
function publishSession(): void {
  observedIdentity = storedIdentity()
  sessionRevision.value++
}

export function readStoredUser(): AuthUser | null {
  try {
    const user: AuthUser | null = JSON.parse(localStorage.getItem(USER_KEY) || 'null')
    return user && typeof user.id === 'string' && Array.isArray(user.roles) ? user : null
  } catch {
    return null
  }
}

/** 仅用于浏览器安排重新登录；可信身份和签名仍由服务端校验。 */
export function tokenExpiresAt(token: string): number | null {
  try {
    const part = token.split('.')[1]
    if (!part) return null
    const payload = JSON.parse(atob(part.replace(/-/g, '+').replace(/_/g, '/')))
    return typeof payload.exp === 'number' && Number.isFinite(payload.exp)
      ? payload.exp * 1000
      : null
  } catch {
    return null
  }
}

function resetPrivateCache(): void {
  identityController.abort()
  identityController = new AbortController()
  void queryClient.cancelQueries()
  queryClient.clear()
}

export function saveLogin(result: LoginResult): void {
  resetPrivateCache()
  localStorage.setItem(ACCESS_TOKEN_KEY, result.accessToken)
  localStorage.setItem(USER_KEY, JSON.stringify(result.user))
  localStorage.removeItem(LOGIN_REQUIRED_KEY)
  publishSession()
}

export function saveGuest(credential: string): void {
  clearLogin()
  localStorage.setItem(GUEST_TOKEN_KEY, credential)
  localStorage.removeItem(INVALID_GUEST_KEY)
  localStorage.removeItem(LOGIN_REQUIRED_KEY)
  publishSession()
}

export function clearLogin(): void {
  resetPrivateCache()
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  // 保留访客凭据，但退出/失效后只能由用户明确选择恢复它。
  localStorage.setItem(LOGIN_REQUIRED_KEY, 'true')
  publishSession()
}

export function expireLoginIfNeeded(): void {
  const token = getStoredToken()
  if (!token) return
  const expiry = tokenExpiresAt(token)
  if (expiry === null || expiry <= Date.now()) clearLogin()
}

export function invalidateCredential(credential: string): void {
  if (credential === getStoredToken()) clearLogin()
  else if (credential === getStoredGuest() && !getStoredToken()) {
    resetPrivateCache()
    localStorage.setItem(INVALID_GUEST_KEY, credential)
    localStorage.setItem(LOGIN_REQUIRED_KEY, 'true')
    publishSession()
  }
}

export function resumeGuest(): boolean {
  if (!guestAvailable()) return false
  clearLogin()
  localStorage.removeItem(LOGIN_REQUIRED_KEY)
  publishSession()
  return true
}

// 同一浏览器的其他标签切换身份时也清除旧缓存。
window.addEventListener('storage', (event) => {
  if (
    event.key === null ||
    [ACCESS_TOKEN_KEY, GUEST_TOKEN_KEY, USER_KEY, LOGIN_REQUIRED_KEY, INVALID_GUEST_KEY].includes(
      event.key,
    )
  ) {
    if (storedIdentity() !== observedIdentity) resetPrivateCache()
    publishSession()
  }
})
