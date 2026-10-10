import { createApp, nextTick } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import PrivacySettings from './PrivacySettings.vue'
import { getPrivacySettings, savePrivacySettings } from '@/shared/api/privacy-settings'
vi.mock('@/shared/api/privacy-settings', () => ({
  getPrivacySettings: vi.fn(),
  savePrivacySettings: vi.fn(),
}))
let app: ReturnType<typeof createApp> | undefined
let host: HTMLDivElement
const flush = async () => {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}
beforeEach(() => {
  vi.clearAllMocks()
  host = document.createElement('div')
  document.body.append(host)
})
afterEach(() => {
  app?.unmount()
  host.remove()
})
it('loads persisted opt-out and saves opt-in without losing the previous value on failure', async () => {
  vi.mocked(getPrivacySettings).mockResolvedValue({ piiMasking: false })
  vi.mocked(savePrivacySettings)
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce({ piiMasking: true })
  app = createApp(PrivacySettings)
  app.mount(host)
  await flush()
  const input = host.querySelector('input')!
  expect(input.checked).toBe(false)
  expect(host.textContent).toContain('关闭后')
  input.checked = true
  input.dispatchEvent(new Event('change'))
  await flush()
  expect(input.checked).toBe(false)
  expect(host.textContent).toContain('设置未更改')
  input.checked = true
  input.dispatchEvent(new Event('change'))
  await flush()
  expect(savePrivacySettings).toHaveBeenLastCalledWith(true)
  expect(input.checked).toBe(true)
  expect(host.textContent).toContain('已保存')
})
it('keeps the toggle disabled until loading succeeds and supports retry', async () => {
  vi.mocked(getPrivacySettings)
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce({ piiMasking: true })
  app = createApp(PrivacySettings)
  app.mount(host)
  await flush()
  expect(host.querySelector('input')!.disabled).toBe(true)
  host.querySelector('button')!.click()
  await flush()
  expect(host.querySelector('input')!.checked).toBe(true)
  expect(host.querySelector('input')!.disabled).toBe(false)
})
