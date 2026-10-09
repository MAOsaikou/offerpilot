import { expect, it } from 'vitest'
import { normalizeModelTier } from './model-tier'

it('normalizes legacy or unknown tiers to FLASH and preserves PRO', () => {
  for (const value of [
    'CHEAP',
    'cheap',
    'economy',
    '经济',
    'balanced',
    'unknown',
    null,
    undefined,
  ]) {
    expect(normalizeModelTier(value)).toBe('FLASH')
  }
  expect(normalizeModelTier(' pro ')).toBe('PRO')
})
