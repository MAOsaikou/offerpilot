export type ModelTier = 'FLASH' | 'PRO'

/** Old clients/preferences may still carry the removed economy tier. */
export function normalizeModelTier(value: unknown): ModelTier {
  return typeof value === 'string' &&
    ['PRO', 'DEEP', '深度', '深入'].includes(value.trim().toUpperCase())
    ? 'PRO'
    : 'FLASH'
}
