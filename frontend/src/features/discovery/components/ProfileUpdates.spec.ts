import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import ProfileUpdates from './ProfileUpdates.vue'
import {
  getProfileSuggestions,
  decideProfileSuggestion,
  type ProfileSuggestion,
} from '@/shared/api/careerUpdates'
import type { CareerProfile } from '@/shared/api/career'
import { saveGuest } from '@/shared/utils/authSession'
vi.mock('@/shared/api/careerUpdates', () => ({
  getProfileSuggestions: vi.fn(),
  decideProfileSuggestion: vi.fn(),
}))
let app: App, host: HTMLDivElement
const suggestion = (): ProfileSuggestion => ({
  id: 's1',
  sourceType: 'GITHUB',
  sourceId: 'account/snapshot1',
  skills: ['Python'],
  facts: ['非fork仓库，语言不证明本人掌握'],
  status: 'PENDING',
  createdAt: '2026-10-20T00:00:00Z',
})
const profile: CareerProfile = {
  target: '目标',
  city: '上海',
  skills: 'Python',
  interests: [],
  major: '',
  courses: '',
  valuesText: '',
  assessment: null,
  completedTasks: [],
  updatedAt: null,
}
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (text: string) =>
  [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!
async function mount(beforeConfirm = vi.fn().mockResolvedValue(undefined), confirmed = vi.fn()) {
  app = createApp(ProfileUpdates, { beforeConfirm, onConfirmed: confirmed }).use(createPinia())
  app.mount(host)
  await flush()
  return { beforeConfirm, confirmed }
}
beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  saveGuest('alice')
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(getProfileSuggestions).mockResolvedValue([suggestion()])
  vi.mocked(decideProfileSuggestion).mockResolvedValue(profile)
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true })
})
afterEach(() => {
  app?.unmount()
  host.remove()
  localStorage.clear()
})
it('requires selecting proposed skills and flushing edits before explicit confirmation', async () => {
  const { beforeConfirm, confirmed } = await mount()
  const input = host.querySelector('input')!
  expect(input.checked).toBe(false)
  expect(decideProfileSuggestion).not.toHaveBeenCalled()
  expect(host.textContent).toContain('语言不证明本人掌握')
  input.click()
  await flush()
  vi.mocked(getProfileSuggestions).mockResolvedValue([{ ...suggestion(), status: 'CONFIRMED' }])
  button('确认材料依据与所选技能').click()
  await flush()
  expect(beforeConfirm).toHaveBeenCalledOnce()
  expect(decideProfileSuggestion).toHaveBeenCalledWith('s1', true, ['Python'])
  expect(confirmed).toHaveBeenCalledWith(profile)
  expect(host.textContent).toContain('已确认')
})
it('allows rejecting without adding skills and preserves the profile on failed flush', async () => {
  const flushFailure = vi.fn().mockRejectedValue(new Error('草稿保存失败'))
  const { confirmed } = await mount(flushFailure)
  button('确认材料依据与所选技能').click()
  await flush()
  expect(decideProfileSuggestion).not.toHaveBeenCalled()
  expect(confirmed).not.toHaveBeenCalled()
  expect(host.textContent).toContain('草稿保存失败')
  flushFailure.mockResolvedValue(undefined)
  button('忽略这次建议').click()
  await flush()
  expect(decideProfileSuggestion).toHaveBeenCalledWith('s1', false, [])
  expect(confirmed).not.toHaveBeenCalled()
})
it('handles empty, failure retry, stale history and offline controls', async () => {
  vi.mocked(getProfileSuggestions).mockRejectedValueOnce(new Error('暂不可用'))
  await mount()
  expect(host.textContent).toContain('暂不可用')
  vi.mocked(getProfileSuggestions).mockResolvedValue([])
  button('刷新材料建议').click()
  await flush()
  expect(host.textContent).toContain('暂无材料建议')
  vi.mocked(getProfileSuggestions).mockResolvedValue([{ ...suggestion(), status: 'STALE' }])
  button('刷新材料建议').click()
  await flush()
  expect(host.textContent).toContain('来源已变化')
  expect(host.querySelector('input')).toBeNull()
  Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
  window.dispatchEvent(new Event('offline'))
  await flush()
  expect(button('刷新材料建议').disabled).toBe(true)
})
it('discards previous-owner pending responses', async () => {
  let finish!: (s: ProfileSuggestion[]) => void
  vi.mocked(getProfileSuggestions).mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  await mount()
  vi.mocked(getProfileSuggestions).mockResolvedValue([])
  saveGuest('bob')
  await flush()
  finish([suggestion()])
  await flush()
  expect(host.textContent).not.toContain('account/snapshot1')
})
