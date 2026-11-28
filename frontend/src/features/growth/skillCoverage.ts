import { translate as uiText } from '@/shared/utils/language'

/** Same words as the career skill-gap page. 0 is missing, 1–4 is partial, 5 is demonstrated. */
export function materialCoverage(current: number): 'Missing' | 'Partial' | 'Demonstrated' {
  if (!Number.isFinite(current) || current <= 0) return 'Missing'
  if (current >= 5) return 'Demonstrated'
  return 'Partial'
}

export function coverageLabel(current: number) {
  const level = materialCoverage(current)
  switch (level) {
    case 'Missing':
      return uiText('Missing')
    case 'Partial':
      return uiText('Partial')
    case 'Demonstrated':
      return uiText('Demonstrated')
    default: {
      const unexpected: never = level
      return unexpected
    }
  }
}
