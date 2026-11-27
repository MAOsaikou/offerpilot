import { translate as uiText } from '@/shared/utils/language'

const FAILURE_KEYS: Record<string, string> = {
  timeout: 'Source timed out',
  timed_out: 'Source timed out',
  time_out: 'Source timed out',
  error: 'Source request failed',
  failed: 'Source request failed',
  unavailable: 'Source is temporarily unavailable',
  暂不可用: 'Source is temporarily unavailable',
}

const CITY_ALIASES: Record<string, string[]> = {
  上海: ['shanghai'],
  北京: ['beijing'],
  深圳: ['shenzhen'],
  杭州: ['hangzhou'],
  广州: ['guangzhou'],
  南京: ['nanjing'],
  成都: ['chengdu'],
  武汉: ['wuhan'],
  西安: ['xian', "xi'an"],
  苏州: ['suzhou'],
}

function fold(value: string) {
  return value
    .toLowerCase()
    .replace(/\s+/g, '')
    .replace(/市|省|特别行政区/g, '')
}

/** A city filter is unmet when the listing never mentions that city. */
export function locationMisses(locations: string | null | undefined, city: string): boolean {
  const wanted = city.trim()
  if (!wanted) return false
  const text = (locations ?? '').trim()
  if (!text) return true
  const needle = fold(wanted)
  const folded = fold(text)
  if (!needle) return false
  if (folded.includes(needle)) return false
  return !(CITY_ALIASES[needle] ?? []).some((alias) => folded.includes(alias))
}

export function sourceFailureText(reason: string | undefined): string {
  const raw = (reason ?? '').trim()
  const key = FAILURE_KEYS[raw] ?? FAILURE_KEYS[raw.toLowerCase()]
  if (key) return uiText(key)
  if (raw && raw.length <= 40 && !/https?:|token|secret|exception|stack|password|bearer/i.test(raw))
    return raw
  return uiText('Source is temporarily unavailable')
}

export function formatSkippedSource(siteName: string, reason: string | undefined): string {
  return `${siteName}（${sourceFailureText(reason)}）`
}
