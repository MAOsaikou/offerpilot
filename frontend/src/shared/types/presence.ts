export interface PresenceValues {
  eyeContact: number
  smile: number
  headStability: number
  postureStability: number
}
export interface PresenceSample extends PresenceValues {
  ts: string
}
export type PresenceStatus = 'skipped' | 'running' | 'unsupported' | 'error'
