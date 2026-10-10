import { createApp, nextTick, h } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import MemorySettings from './MemorySettings.vue'
import * as api from '@/shared/api/memories'
import { sessionRevision } from '@/shared/utils/authSession'
vi.mock('@/shared/api/memories', async (original) => ({
  ...(await original<typeof api>()),
  listMemories: vi.fn(),
  addMemory: vi.fn(),
  editMemory: vi.fn(),
  deleteMemory: vi.fn(),
  clearMemories: vi.fn(),
  setMemoryEnabled: vi.fn(),
}))
const memory: api.UserMemory = {
  id: 'mem1',
  kind: 'LOCATION',
  fact: '我的目标城市是杭州',
  source: 'EXPLICIT',
  sourceConversationId: 'conv',
  sourceMessageId: 'msg',
  createdAt: '2026-10-10T00:00:00Z',
  updatedAt: '2026-10-10T00:00:00Z',
}
let app: ReturnType<typeof createApp> | undefined
let host: HTMLDivElement
const flush = async () => {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
function button(text: string) {
  const found = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  )
  if (!found) throw new Error(`missing button: ${text}`)
  return found
}
function mount() {
  app = createApp(MemorySettings)
  app.mount(host)
}
beforeEach(() => {
  vi.clearAllMocks()
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(api.listMemories).mockResolvedValue({ items: [memory], memoryEnabled: true, limit: 50 })
})
afterEach(() => {
  app?.unmount()
  host.remove()
})
it('adds a manual fact, enforces Unicode length and deduplicates returned ids', async () => {
  mount()
  await flush()
  button('添加记忆').click()
  await flush()
  const input = host.querySelector<HTMLInputElement>('input[type=text]')!
  input.value = '😀'.repeat(201)
  input.dispatchEvent(new Event('input'))
  await flush()
  expect(button('保存记忆').disabled).toBe(true)
  input.value = '我的目标城市是杭州'
  input.dispatchEvent(new Event('input'))
  await flush()
  vi.mocked(api.addMemory).mockResolvedValue(memory)
  button('保存记忆').click()
  await flush()
  expect(api.addMemory).toHaveBeenCalledWith({ kind: 'OTHER', fact: memory.fact })
  expect(host.querySelectorAll('li')).toHaveLength(1)
  expect(host.textContent).toContain('1 / 50')
})
it('requires confirmation to delete one or all and preserves state on failure', async () => {
  mount()
  await flush()
  button('删除').click()
  await flush()
  expect(api.deleteMemory).not.toHaveBeenCalled()
  button('取消').click()
  await flush()
  button('清空全部记忆').click()
  await flush()
  expect(api.clearMemories).not.toHaveBeenCalled()
  vi.mocked(api.clearMemories).mockRejectedValueOnce(new Error('offline')).mockResolvedValue(null)
  button('确认删除').click()
  await flush()
  expect(host.textContent).toContain('操作失败')
  expect(host.querySelectorAll('li')).toHaveLength(1)
  button('确认删除').click()
  await flush()
  expect(host.textContent).toContain('还没有长期记忆')
  expect(button('清空全部记忆').disabled).toBe(true)
  app!.unmount()
  vi.mocked(api.listMemories).mockResolvedValue({ items: [memory], memoryEnabled: true, limit: 50 })
  vi.mocked(api.deleteMemory).mockResolvedValue(null)
  mount()
  await flush()
  button('删除').click()
  await flush()
  expect(api.deleteMemory).not.toHaveBeenCalled()
  button('确认删除').click()
  await flush()
  expect(api.deleteMemory).toHaveBeenCalledExactlyOnceWith('mem1')
  expect(host.querySelectorAll('li')).toHaveLength(0)
})
it('opt-out retains manual management and failed toggle rolls back', async () => {
  vi.mocked(api.listMemories).mockResolvedValue({
    items: [memory],
    memoryEnabled: false,
    limit: 50,
  })
  vi.mocked(api.setMemoryEnabled)
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValue({ memoryEnabled: true })
  mount()
  await flush()
  const toggle = host.querySelector<HTMLInputElement>('input[type=checkbox]')!
  expect(toggle.checked).toBe(false)
  expect(host.textContent).toContain('新运行不读取记忆')
  expect(button('编辑').disabled).toBe(false)
  toggle.checked = true
  toggle.dispatchEvent(new Event('change'))
  await flush()
  expect(toggle.checked).toBe(false)
  toggle.checked = true
  toggle.dispatchEvent(new Event('change'))
  await flush()
  expect(api.setMemoryEnabled).toHaveBeenLastCalledWith(true)
  expect(toggle.checked).toBe(true)
})
it('load failure prevents writes and offers a retry; cap disables add', async () => {
  vi.mocked(api.listMemories)
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValue({
      items: Array.from({ length: 50 }, (_, i) => ({ ...memory, id: `mem${i}` })),
      memoryEnabled: true,
      limit: 50,
    })
  mount()
  await flush()
  expect(host.querySelector<HTMLInputElement>('input[type=checkbox]')!.disabled).toBe(true)
  expect(host.querySelector('form')).toBeNull()
  button('重新加载').click()
  await flush()
  expect(button('添加记忆').disabled).toBe(true)
  expect(host.textContent).toContain('50 / 50')
})
it('keeps edited draft after save failure and disallows concurrent submits', async () => {
  mount()
  await flush()
  button('编辑').click()
  await flush()
  let fail!: (error: Error) => void
  vi.mocked(api.editMemory).mockReturnValue(
    new Promise((_, reject) => {
      fail = reject
    }),
  )
  const input = host.querySelector<HTMLInputElement>('input[type=text]')!
  input.value = '保留的草稿'
  input.dispatchEvent(new Event('input'))
  await flush()
  button('保存记忆').click()
  await flush()
  expect(host.querySelector('fieldset')!.disabled).toBe(true)
  host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
  await flush()
  expect(api.editMemory).toHaveBeenCalledTimes(1)
  fail(new Error('conflict'))
  await flush()
  expect(input.value).toBe('保留的草稿')
  expect(host.querySelector('fieldset')!.disabled).toBe(false)
})
it('Settings identity key remount prevents old owner responses and drafts from appearing', async () => {
  let complete!: (value: api.MemoryInventory) => void
  vi.mocked(api.listMemories)
    .mockReturnValueOnce(
      new Promise((resolve) => {
        complete = resolve
      }),
    )
    .mockResolvedValueOnce({ items: [], memoryEnabled: false, limit: 50 })
  app = createApp({ render: () => h(MemorySettings, { key: sessionRevision.value }) })
  app.mount(host)
  await flush()
  sessionRevision.value++
  await flush()
  complete({ items: [memory], memoryEnabled: true, limit: 50 })
  await flush()
  expect(host.querySelectorAll('li')).toHaveLength(0)
  expect(host.textContent).toContain('还没有长期记忆')
  expect(host.querySelector<HTMLInputElement>('input[type=checkbox]')!.checked).toBe(false)
})
