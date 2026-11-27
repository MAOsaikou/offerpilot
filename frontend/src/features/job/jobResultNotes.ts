import { translate as uiText } from '@/shared/utils/language'

const FAILURE_KEYS: Record<string, string> = {
  timeout: 'Source timed out',
  timed_out: 'Source timed out',
  time_out: 'Source timed out',
  error: 'Source request failed',
  failed: 'Source request failed',
  unavailable: 'Source is temporarily unavailable',
  暂不可用: 'Source is temporarily unavailable',
  live_unavailable: 'Company sites did not respond. Saved results are still shown.',
  live_timeout: 'The company-site query timed out.',
  live_detail_unavailable: 'The live company page is unavailable. The saved copy is shown.',
  all_sites_failed: 'Every queried company site failed.',
  empty_due_to_error: 'Company sites returned an error and no new jobs.',
  search_timeout: 'The company-site query timed out.',
  search_unavailable: 'Company sites did not respond. Saved results are still shown.',
  search_busy: 'The job query is busy. Partial results are shown.',
  local_unavailable: 'Saved job records are unavailable.',
  upstream_partial: 'Some company sites returned only part of their results.',
}

function looksLikeCode(value: string) {
  return /^[A-Z][A-Z0-9_]*$/.test(value)
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
  if (
    !raw ||
    looksLikeCode(raw) ||
    raw.length > 40 ||
    /https?:|token|secret|exception|stack|password|bearer/i.test(raw)
  )
    return uiText('Source is temporarily unavailable')
  return raw
}

/** A known aggregate code becomes a sentence. Unknown codes stay off the banner. */
export function bannerFailureDetail(reason: string | undefined): string {
  const raw = (reason ?? '').trim()
  if (!raw) return ''
  const key = FAILURE_KEYS[raw] ?? FAILURE_KEYS[raw.toLowerCase()]
  return key ? uiText(key) : ''
}

export function formatSkippedSource(siteName: string, reason: string | undefined): string {
  return `${siteName}（${sourceFailureText(reason)}）`
}
