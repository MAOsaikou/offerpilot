import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import NotificationsView from './NotificationsView.vue'
import {
  getNotifications,
  readNotification,
  readAllNotifications,
  type NotificationPage,
} from '@/shared/api/notifications'
import { useNotifications } from '@/shared/stores/notifications'
import { saveGuest } from '@/shared/utils/authSession'
vi.mock('@/shared/api/notifications', () => ({
  getNotifications: vi.fn(),
  readNotification: vi.fn(),
  readAllNotifications: vi.fn(),
  getUnreadCount: vi.fn(),
}))
let app: App, host: HTMLDivElement
const result = (): NotificationPage => ({
  items: [
    {
      id: 'n1',
      kind: 'DDL',
      title: '截止还有3天',
      body: '真实来源岗位',
      link: '/board?application=a',
      createdAt: '2026-10-20T00:00:00Z',
      readAt: null,
    },
  ],
  unread: 1,
  total: 1,
  page: 1,
  size: 20,
})
const flush = async () => {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
const button = (text: string) =>
  [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!
async function mount() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: NotificationsView },
      { path: '/board', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  const pinia = createPinia()
  app = createApp(NotificationsView).use(pinia).use(router)
  app.mount(host)
  await flush()
  return useNotifications(pinia)
}
beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  saveGuest('alice')
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(getNotifications).mockResolvedValue(result())
  vi.mocked(readNotification).mockResolvedValue()
  vi.mocked(readAllNotifications).mockResolvedValue()
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true })
})
afterEach(() => {
  app?.unmount()
  host.remove()
  localStorage.clear()
})
it('loads server unread count, marks one and restores persisted read state after refresh', async () => {
  const store = await mount()
  expect(store.unread).toBe(1)
  expect(host.textContent).toContain('真实来源岗位')
  expect(host.querySelector('a')?.getAttribute('href')).toBe('/board?application=a')
  const read = result()
  read.items[0]!.readAt = '2026-10-20T01:00:00Z'
  read.unread = 0
  vi.mocked(getNotifications).mockResolvedValue(read)
  button('标为已读').click()
  await flush()
  expect(readNotification).toHaveBeenCalledWith('n1')
  expect(store.unread).toBe(0)
  expect(host.textContent).toContain('已读')
})
it('supports all-read, empty state, loading failure retry and bounded pagination', async () => {
  const many = result()
  many.total = 21
  vi.mocked(getNotifications).mockResolvedValue(many)
  await mount()
  button('下一页').click()
  await flush()
  expect(getNotifications).toHaveBeenLastCalledWith(2)
  button('全部标为已读').click()
  await flush()
  expect(readAllNotifications).toHaveBeenCalledOnce()
  vi.mocked(getNotifications).mockRejectedValueOnce(new Error('服务暂不可用'))
  button('上一页').click()
  await flush()
  expect(host.querySelector('[role=alert]')?.textContent).toContain('服务暂不可用')
  vi.mocked(getNotifications).mockResolvedValue({ ...result(), items: [], total: 0, unread: 0 })
  button('重新读取').click()
  await flush()
  expect(host.textContent).toContain('暂无通知')
})
it('drops an old identity response and disables writes offline', async () => {
  let finish!: (value: NotificationPage) => void
  vi.mocked(getNotifications).mockReturnValueOnce(
    new Promise((resolve) => {
      finish = resolve
    }),
  )
  const store = await mount()
  vi.mocked(getNotifications).mockResolvedValue({ ...result(), items: [], unread: 0, total: 0 })
  saveGuest('bob')
  await flush()
  finish(result())
  await flush()
  expect(host.textContent).not.toContain('真实来源岗位')
  expect(store.unread).toBe(0)
  Object.defineProperty(navigator, 'onLine', { value: false, configurable: true })
  window.dispatchEvent(new Event('offline'))
  await flush()
  expect(host.textContent).toContain('当前离线')
  expect(button('全部标为已读').disabled).toBe(true)
})
it('keeps unread count and reports a failed read mutation', async () => {
  const store = await mount()
  vi.mocked(readNotification).mockRejectedValue(new Error('标记失败'))
  button('标为已读').click()
  await flush()
  expect(store.unread).toBe(1)
  expect(host.querySelector('[role=alert]')?.textContent).toContain('标记失败')
})
