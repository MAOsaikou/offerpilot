import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  clearToken,
  getStoredToken,
  login as loginApi,
  register as registerApi,
  type AuthUser,
} from '@/features/auth/api'

const USER_KEY = 'offerpilot-auth-user'

function readStoredUser(): AuthUser | null {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null') as AuthUser | null
  } catch {
    return null
  }
}

/**
 * 登录认证状态：令牌与用户信息持久化在 localStorage，
 * 业务请求经 shared/api/client 的 authenticatedHeaders 自动携带 JWT。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getStoredToken())
  const user = ref<AuthUser | null>(readStoredUser())
  const isAuthenticated = computed(() => Boolean(token.value))

  async function login(email: string, password: string): Promise<AuthUser> {
    const result = await loginApi(email, password)
    return signIn(result)
  }

  /** 注册成功即登录：后端直接返回令牌。 */
  async function register(email: string, password: string): Promise<AuthUser> {
    const result = await registerApi(email, password)
    return signIn(result)
  }

  function signIn(result: { accessToken: string; user: AuthUser }): AuthUser {
    token.value = result.accessToken
    user.value = result.user
    localStorage.setItem(USER_KEY, JSON.stringify(result.user))
    return result.user
  }

  function logout(): void {
    clearToken()
    localStorage.removeItem(USER_KEY)
    token.value = null
    user.value = null
  }

  return { token, user, isAuthenticated, login, register, logout }
})
