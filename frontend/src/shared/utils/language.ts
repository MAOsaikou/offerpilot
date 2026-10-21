import { computed, ref, shallowRef } from 'vue'
import zhCN from '@/shared/locales/zh-CN'
import enUS from '@/shared/locales/en-US'

export type Language = 'zh-CN' | 'en-US'
export const language = ref<Language>('zh-CN')
export const dictionaries: Record<Language, Readonly<Record<string, string>>> = {
  'zh-CN': zhCN,
  'en-US': enUS,
}
export function setLanguage(value: string): void {
  language.value = value === 'en-US' ? 'en-US' : 'zh-CN'
  document.documentElement.lang = language.value
}

/** Translate interface keys only. Never pass source materials or user/server prose here. */
export function translate(key: string, values: readonly unknown[] = []): string {
  const text = dictionaries[language.value][key] ?? key
  return text.replace(/\{(\d+)\}/g, (match, index: string) => {
    const value = values[Number(index)]
    return value === undefined ? match : typeof value === 'number' ? formatNumber(value) : String(value)
  })
}

/** Original values for legacy persisted enums. These must never change with display language. */
export function sourceText<K extends keyof typeof zhCN>(key: K): (typeof zhCN)[K] {
  return zhCN[key]
}

const labelKeys = new Map(Object.entries(zhCN).map(([key, value]) => [value as string, key]))
/** Display adapter for frontend-owned legacy enum labels, never for document or user prose. */
export function translateLabel(value: string): string {
  return translate(labelKeys.get(value) ?? value)
}

export function formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(language.value, options).format(value)
}

export function formatDate(
  value: string | number | Date | null | undefined,
  options?: Intl.DateTimeFormatOptions,
): string {
  if (value === null || value === undefined || value === '') return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return typeof value === 'string' ? value : '—'
  return new Intl.DateTimeFormat(language.value, options).format(date)
}

export function formatDateTime(value: string | number | Date | null | undefined): string {
  return formatDate(value, { dateStyle: 'short', timeStyle: 'medium' })
}

/** Store UI messages as render functions so existing notices follow a language change. */
export function localizedText() {
  const value = shallowRef<string | (() => string)>('')
  return computed({
    get: () => typeof value.value === 'function' ? value.value() : value.value,
    set: (next: string | (() => string)) => { value.value = next },
  })
}

/** Format numeric display values; all source strings and other data pass through untouched. */
export function formatDisplay<T>(value: T): T | string {
  return typeof value === 'number' ? formatNumber(value) : value
}
