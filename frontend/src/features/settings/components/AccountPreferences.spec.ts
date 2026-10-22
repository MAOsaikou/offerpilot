import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AccountPreferences from './AccountPreferences.vue'
import { setLanguage } from '@/shared/utils/language'
import { getPreferences, savePreferences } from '@/shared/api/preferences'
vi.mock('@/shared/api/preferences', () => ({ getPreferences: vi.fn(), savePreferences: vi.fn() }))
let app: ReturnType<typeof createApp> | undefined
let host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 3; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
beforeEach(() => {
  vi.clearAllMocks()
  setLanguage('zh-CN')
  localStorage.clear()
  document.documentElement.classList.remove('dark')
  host = document.createElement('div')
  document.body.append(host)
})
afterEach(() => {
  app?.unmount()
  host.remove()
})
const selects = () => [...host.querySelectorAll('select')]

it('读取已保存的账号偏好，修改后保存档位与主题并立即切换主题', async () => {
  vi.mocked(getPreferences).mockResolvedValue({
    defaultModelTier: 'PRO',
    theme: 'light',
    language: 'zh-CN',
    piiMasking: true,
    saved: true,
  })
  vi.mocked(savePreferences).mockResolvedValue({
    defaultModelTier: 'FLASH',
    theme: 'dark',
    language: 'zh-CN',
    piiMasking: true,
    saved: true,
  })
  app = createApp(AccountPreferences)
  app.mount(host)
  await flush()
  const [theme, tier] = selects()
  expect(theme!.value).toBe('light')
  expect(tier!.value).toBe('PRO')
  tier!.value = 'FLASH'
  tier!.dispatchEvent(new Event('change'))
  await flush()
  theme!.value = 'dark'
  theme!.dispatchEvent(new Event('change'))
  await flush()
  expect(savePreferences).toHaveBeenLastCalledWith({
    defaultModelTier: 'FLASH',
    theme: 'dark',
    language: 'zh-CN',
    piiMasking: true,
  })
  expect(document.documentElement.classList.contains('dark')).toBe(true)
  expect(host.textContent).toContain('已保存到账号')
})

it('账号从未保存时保留本浏览器的主题', async () => {
  localStorage.setItem('op-theme', 'dark')
  vi.mocked(getPreferences).mockResolvedValue({
    defaultModelTier: 'FLASH',
    theme: 'system',
    language: 'zh-CN',
    piiMasking: true,
    saved: false,
  })
  app = createApp(AccountPreferences)
  app.mount(host)
  await flush()
  expect(selects()[0]!.value).toBe('dark')
})

it('语言与脱敏保存后立即应用，加载/失败期间禁写并允许重试', async () => {
  vi.mocked(getPreferences).mockResolvedValue({
    defaultModelTier: 'FLASH',
    theme: 'light',
    language: 'zh-CN',
    piiMasking: true,
    saved: true,
  })
  vi.mocked(savePreferences).mockResolvedValue({
    defaultModelTier: 'FLASH',
    theme: 'light',
    language: 'en-US',
    piiMasking: true,
    saved: true,
  })
  app = createApp(AccountPreferences)
  app.mount(host)
  expect(host.querySelector('fieldset')?.disabled).toBe(true)
  await flush()
  selects()[2]!.value = 'en-US'
  selects()[2]!.dispatchEvent(new Event('change'))
  await flush()
  expect(document.documentElement.lang).toBe('en-US')
  expect(host.textContent).toContain('Theme')
  expect(savePreferences).toHaveBeenLastCalledWith({
    defaultModelTier: 'FLASH',
    theme: 'light',
    language: 'en-US',
    piiMasking: true,
  })
  vi.mocked(savePreferences).mockResolvedValue({
    defaultModelTier: 'FLASH',
    theme: 'light',
    language: 'en-US',
    piiMasking: false,
    saved: true,
  })
  const checkbox = host.querySelector<HTMLInputElement>('input[type="checkbox"]')!
  checkbox.checked = false
  checkbox.dispatchEvent(new Event('change'))
  await flush()
  expect(savePreferences).toHaveBeenLastCalledWith({
    defaultModelTier: 'FLASH',
    theme: 'light',
    language: 'en-US',
    piiMasking: false,
  })
  expect(host.textContent).toContain('contact details may be sent')
  vi.mocked(savePreferences).mockRejectedValue(new Error('offline'))
  selects()[2]!.value = 'zh-CN'
  selects()[2]!.dispatchEvent(new Event('change'))
  await flush()
  expect(document.documentElement.lang).toBe('en-US')
  expect(host.querySelector('fieldset')?.disabled).toBe(true)
  expect(host.textContent).toContain('Save failed')
  host.querySelector<HTMLButtonElement>('button')!.click()
  await flush()
  expect(getPreferences).toHaveBeenCalledTimes(2)
  expect(document.documentElement.lang).toBe('zh-CN')
})
