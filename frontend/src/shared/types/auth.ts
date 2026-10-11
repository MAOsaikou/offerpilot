export interface AuthUser {
  id: string
  email: string
  nickname: string
  roles: string[]
  isDemo?: boolean
  demoExpiresAt?: string | null
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
