import { ref } from 'vue'

export type Language = 'zh-CN' | 'en-US'
export const language = ref<Language>('zh-CN')
export function setLanguage(value: string): void {
  language.value = value === 'en-US' ? 'en-US' : 'zh-CN'
  document.documentElement.lang = language.value
}
/** Shell/preferences translations; source documents and citations retain their original language. */
export const translate = (zh: string, en: string): string => (language.value === 'en-US' ? en : zh)
