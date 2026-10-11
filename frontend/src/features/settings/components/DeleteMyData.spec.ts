import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import DeleteMyData from './DeleteMyData.vue'
import { deleteMyData } from '@/shared/api/auth'
import { saveGuest, saveLogin, getStoredToken, getStoredGuest } from '@/shared/utils/authSession'
import { queryClient } from '@/shared/api/queryClient'
import { createWorkspace } from '@/shared/stores/workspace'

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace }) }))
vi.mock('@/shared/api/auth', async (original) => ({
  ...(await original<typeof import('@/shared/api/auth')>()),
  deleteMyData: vi.fn(),
}))
let app: App | undefined
let host: HTMLDivElement
const workspaceKey = (id: string) => `offerpilot-workspace-v1:user:${id}`
function login(id = 'A', isDemo = false, roles = ['USER'], demoExpiresAt: string | null = null) {
  const payload = btoa(JSON.stringify({ sub: id, exp: Math.floor(Date.now() / 1000) + 3600 }))
  saveLogin({
    accessToken: `header.${payload}.signature`,
    expiresIn: 3600,
    user: { id, email: `${id}@example.test`, nickname: id, roles, isDemo, demoExpiresAt },
  })
}
async function settle() {
  for (let i = 0; i < 8; i++) await nextTick()
}
async function mount() {
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(DeleteMyData)
  app.use(createPinia()).mount(host)
  await settle()
}
function dialog() {
  return host.querySelector<HTMLDialogElement>('dialog')!
}
function trigger() {
  return host.querySelector<HTMLButtonElement>('section > button')!
}
function submit() {
  return dialog().querySelector<HTMLButtonElement>('button:not([type="button"])')!
}
async function type(value: string) {
  const input = dialog().querySelector<HTMLInputElement>('input')!
  input.value = value
  input.dispatchEvent(new Event('input'))
  await settle()
}
async function open() {
  trigger().focus()
  trigger().click()
  await settle()
}
beforeEach(() => {
  localStorage.clear()
  queryClient.clear()
  vi.clearAllMocks()
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.setAttribute('open', '')
    },
  })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.removeAttribute('open')
    },
  })
  vi.mocked(deleteMyData).mockResolvedValue(null)
  login()
})
afterEach(() => {
  app?.unmount()
  host?.remove()
  localStorage.clear()
  queryClient.clear()
})
it('requires exact typed confirmation and traps focus, cancellation restores focus', async () => {
  await mount()
  await open()
  expect(dialog().getAttribute('role')).toBe('alertdialog')
  expect(document.activeElement).toBe(dialog().querySelector('input'))
  expect(submit().disabled).toBe(true)
  await type('delete')
  expect(submit().disabled).toBe(true)
  await type('DELETE')
  expect(submit().disabled).toBe(false)
  submit().focus()
  submit().dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }),
  )
  expect(document.activeElement).toBe(dialog().querySelector('input'))
  dialog()
    .querySelector('input')!
    .dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }),
    )
  expect(document.activeElement).toBe(submit())
  dialog().dispatchEvent(new Event('cancel', { cancelable: true }))
  await settle()
  expect(dialog().hasAttribute('open')).toBe(false)
  expect(document.activeElement).toBe(trigger())
  expect(deleteMyData).not.toHaveBeenCalled()
})
it('deletes current sandbox workspace and its legacy migration only, clears cache and logs out', async () => {
  login('A', true, ['USER'], new Date(Date.now() + 3600000).toISOString())
  localStorage.setItem(workspaceKey('A'), JSON.stringify(createWorkspace()))
  localStorage.setItem(workspaceKey('B'), 'keep B')
  localStorage.setItem('offerpilot-workspace-migration-v1', workspaceKey('A'))
  await mount()
  queryClient.setQueryData(['private'], { text: 'private' })
  await open()
  await type('DELETE')
  submit().click()
  await settle()
  expect(deleteMyData).toHaveBeenCalledExactlyOnceWith('DELETE')
  expect(localStorage.getItem(workspaceKey('A'))).toBeNull()
  expect(localStorage.getItem('offerpilot-workspace-migration-v1')).toBeNull()
  expect(localStorage.getItem(workspaceKey('B'))).toBe('keep B')
  expect(queryClient.getQueryCache().getAll()).toHaveLength(0)
  expect(getStoredToken()).toBeNull()
  expect(replace).toHaveBeenCalledWith({ path: '/login', query: { deleted: '1' } })
  await settle()
  expect(localStorage.getItem(workspaceKey('A'))).toBeNull()
})
it('removes a deleted guest credential and its workspace without allowing guest fallback', async () => {
  saveGuest('guest-credential')
  const key = 'offerpilot-workspace-v1:guest:guest-credential'
  localStorage.setItem(key, JSON.stringify(createWorkspace()))
  await mount()
  await open()
  await type('DELETE')
  submit().click()
  await settle()
  expect(localStorage.getItem(key)).toBeNull()
  expect(getStoredGuest()).toBeNull()
  expect(localStorage.getItem('offerpilot-login-required')).toBe('true')
})
it('preserves local data and identity on failure, prevents duplicate submits while pending', async () => {
  let reject: (reason: Error) => void = () => {}
  vi.mocked(deleteMyData).mockReturnValue(
    new Promise((_, r) => {
      reject = r
    }),
  )
  const raw = JSON.stringify(createWorkspace())
  localStorage.setItem(workspaceKey('A'), raw)
  await mount()
  await open()
  await type('DELETE')
  submit().click()
  await settle()
  expect(submit().disabled).toBe(true)
  dialog()
    .querySelector('form')!
    .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  dialog().dispatchEvent(new Event('cancel', { cancelable: true }))
  await settle()
  expect(dialog().hasAttribute('open')).toBe(true)
  expect(deleteMyData).toHaveBeenCalledTimes(1)
  reject(new Error('存储请求失败'))
  await settle()
  expect(dialog().textContent).toContain('存储请求失败')
  expect(localStorage.getItem(workspaceKey('A'))).toBe(raw)
  expect(getStoredToken()).not.toBeNull()
  expect(submit().disabled).toBe(false)
  expect(replace).not.toHaveBeenCalled()
})
it('does not clear a new identity when deletion response arrives after switching accounts', async () => {
  let resolve: (value: null) => void = () => {}
  vi.mocked(deleteMyData).mockReturnValue(
    new Promise((r) => {
      resolve = r
    }),
  )
  localStorage.setItem(workspaceKey('B'), JSON.stringify(createWorkspace()))
  await mount()
  await open()
  await type('DELETE')
  submit().click()
  await settle()
  login('B')
  resolve(null)
  await settle()
  expect(localStorage.getItem(workspaceKey('B'))).not.toBeNull()
  expect(getStoredToken()).not.toBeNull()
  expect(replace).not.toHaveBeenCalled()
})
it.each([
  { isDemo: true, roles: ['USER'], message: '共享体验账号' },
  { isDemo: false, roles: ['STAFF'], message: '客服账号' },
])('disables deletion for protected accounts: $message', async ({ isDemo, roles, message }) => {
  login('protected', isDemo, roles)
  await mount()
  expect(trigger().disabled).toBe(true)
  expect(host.textContent).toContain(message)
  trigger().click()
  expect(deleteMyData).not.toHaveBeenCalled()
})
