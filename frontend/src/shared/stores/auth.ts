import { computed, onScopeDispose, watch } from 'vue'
import { defineStore } from 'pinia'
import {
  login as loginApi,
  register as registerApi,
  staffLogin as staffLoginApi,
  demoLogin as demoLoginApi,
  createGuest,
  logoutSession,
} from '@/shared/api/auth'
import { ensureFreshLogin } from '@/shared/api/client'
import {
  clearLogin,
  expireLoginIfNeeded,
  getStoredToken,
  guestAvailable,
  loginRequired,
  readStoredUser,
  resumeGuest,
  saveLogin,
  saveGuest,
  getStoredGuest,
  sessionRevision,
  tokenExpiresAt,
  identitySignal,
} from '@/shared/utils/authSession'
import type { AuthUser, LoginResult } from '@/shared/types/auth'

/**
 * 登录认证状态：令牌与用户信息持久化在 localStorage，
 * 业务请求经 shared/api/client 的 authenticatedHeaders 自动携带 JWT。
 */
export const useAuthStore = defineStore('auth', () => {
  // An expired access token may still be renewable through the refresh cookie (AC-01).
  async function refreshSession(): Promise<void> {
    const refreshing = ensureFreshLogin()
    if (refreshing) await refreshing
    expireLoginIfNeeded()
  }
  void refreshSession()
  const token = computed(() => {
    void sessionRevision.value
    return getStoredToken()
  })
  const user = computed(() => {
    void sessionRevision.value
    return readStoredUser()
  })
  const requiresLogin = computed(() => {
    void sessionRevision.value
    return loginRequired()
  })
  const hasGuest = computed(() => {
    void sessionRevision.value
    return guestAvailable()
  })
  const hasStoredGuest = computed(() => {
    void sessionRevision.value
    return Boolean(getStoredGuest())
  })
  const isLoggedIn = computed(() => Boolean(token.value))
  const isAuthenticated = computed(
    () => isLoggedIn.value || (hasGuest.value && !requiresLogin.value),
  )
  const isStaff = computed(() => isLoggedIn.value && Boolean(user.value?.roles.includes('STAFF')))
  // Keyed by account, not token string: a refreshed token must not remount views or reset runs.
  const identityKey = computed(
    () =>
      (token.value && user.value ? `user:${user.value.id}` : token.value) ||
      (isAuthenticated.value ? getStoredGuest() : null) ||
      'anonymous',
  )
  let expiryTimer: ReturnType<typeof setTimeout> | undefined
  let renewTimer: ReturnType<typeof setTimeout> | undefined
  watch(
    token,
    (value) => {
      clearTimeout(expiryTimer)
      clearTimeout(renewTimer)
      const expiry = value ? tokenExpiresAt(value) : null
      if (expiry === null) return
      // Renew shortly before expiry (a refused renewal ends the session); if renewal cannot
      // happen (offline), the token still expires on time.
      renewTimer = setTimeout(
        () => void refreshSession(),
        Math.max(0, expiry - Date.now() - 60_000),
      )
      expiryTimer = setTimeout(expireLoginIfNeeded, Math.max(0, expiry - Date.now()))
    },
    { immediate: true },
  )
  onScopeDispose(() => {
    clearTimeout(expiryTimer)
    clearTimeout(renewTimer)
  })

  async function login(email: string, password: string): Promise<AuthUser> {
    return signIn(loginApi(email, password))
  }

  /** 注册成功即登录：后端直接返回令牌。 */
  async function register(email: string, password: string): Promise<AuthUser> {
    return signIn(registerApi(email, password))
  }

  async function staffLogin(email: string, password: string): Promise<AuthUser> {
    return signIn(staffLoginApi(email, password))
  }

  async function demoLogin(): Promise<AuthUser> {
    return signIn(demoLoginApi())
  }

  async function signIn(pending: Promise<LoginResult>): Promise<AuthUser> {
    const session = identitySignal()
    const result = await pending
    session.throwIfAborted()
    saveLogin(result)
    return result.user
  }

  function logout(): void {
    // Only a signed-in session has a refresh cookie to revoke; guests stay offline-only.
    if (getStoredToken()) void logoutSession()
    clearLogin()
  }

  async function enterGuest(): Promise<boolean> {
    const session = identitySignal()
    const initialToken = getStoredToken()
    const initialGuest = getStoredGuest()
    const initialLoginRequired = loginRequired()
    const enter = async () => {
      session.throwIfAborted()
      if (
        getStoredToken() !== initialToken ||
        getStoredGuest() !== initialGuest ||
        loginRequired() !== initialLoginRequired
      )
        throw new Error('身份已切换，请重新选择访客体验')
      if (getStoredGuest()) return resumeGuest()
      localStorage.setItem('offerpilot-storage-check', '1')
      localStorage.removeItem('offerpilot-storage-check')
      const credential = await createGuest()
      session.throwIfAborted()
      saveGuest(credential)
      return true
    }
    return navigator.locks ? navigator.locks.request('offerpilot-guest', enter) : enter()
  }

  return {
    token,
    user,
    isAuthenticated,
    isLoggedIn,
    isStaff,
    identityKey,
    requiresLogin,
    hasGuest,
    hasStoredGuest,
    login,
    register,
    staffLogin,
    demoLogin,
    logout,
    resumeGuest,
    enterGuest,
    refreshSession,
  }
})
