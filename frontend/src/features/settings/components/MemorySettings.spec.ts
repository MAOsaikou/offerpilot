import { createApp, nextTick, h } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import MemorySettings from './MemorySettings.vue'
import * as api from '@/shared/api/memories'
import { setLanguage } from '@/shared/utils/language'
import { sessionRevision } from '@/shared/utils/authSession'
vi.mock('@/shared/api/memories', async (original) => ({
  ...(await original<typeof api>()),
  listMemories: vi.fn(),
  listMemoryCandidates: vi.fn(),
  confirmMemoryCandidate: vi.fn(),
  deleteMemoryCandidate: vi.fn(),
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
  vi.mocked(api.listMemoryCandidates).mockResolvedValue([])
  host = document.createElement('div')
  document.body.append(host)
  vi.mocked(api.listMemories).mockResolvedValue({ items: [memory], memoryEnabled: true, limit: 50 })
})
afterEach(() => {
  app?.unmount()
  host.remove()
  setLanguage('zh-CN')
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
it('shows exact extracted facts with confidence and source and confirms or discards explicitly', async () => {
  const candidate: api.MemoryCandidate = {
    id: 'candidate',
    kind: 'LOCATION',
    fact: '我的目标城市是杭州',
    confidence: 0.85,
    extractor: 'model',
    sourceConversationId: 'conv',
    sourceMessageId: 'msg',
  }
  vi.mocked(api.listMemoryCandidates).mockResolvedValue([candidate])
  vi.mocked(api.confirmMemoryCandidate)
    .mockRejectedValueOnce(new Error('暂时失败'))
    .mockResolvedValue(memory)
  mount()
  await flush()
  expect(host.textContent).toContain('85%')
  expect(host.querySelector('a')?.getAttribute('href')).toContain('/chat/conv?messageId=msg')
  button('确认候选').click()
  await flush()
  expect(host.textContent).toContain('暂时失败')
  expect(host.querySelector('[aria-label="待确认记忆"]')).not.toBeNull()
  button('确认候选').click()
  await flush()
  expect(api.confirmMemoryCandidate).toHaveBeenLastCalledWith('candidate')
  expect(host.querySelector('[aria-label="待确认记忆"]')).toBeNull()
})
it('disabled memory prevents candidate acceptance but still allows candidate deletion', async () => {
  vi.mocked(api.listMemories).mockResolvedValue({ items: [], memoryEnabled: false, limit: 50 })
  vi.mocked(api.listMemoryCandidates).mockResolvedValue([
    {
      id: 'candidate',
      kind: 'GOAL',
      fact: '我想做后端',
      confidence: 0.75,
      extractor: 'rule',
      sourceConversationId: 'c',
      sourceMessageId: 'm',
    },
  ])
  vi.mocked(api.deleteMemoryCandidate).mockResolvedValue(null)
  mount()
  await flush()
  expect(button('确认候选').disabled).toBe(true)
  button('删除候选').click()
  await flush()
  expect(api.deleteMemoryCandidate).toHaveBeenCalledWith('candidate')
  expect(host.querySelector('[aria-label="待确认记忆"]')).toBeNull()
})

it('updates memory kind, editor options and existing save feedback when language changes', async () => {
  vi.mocked(api.editMemory).mockResolvedValue(memory)
  mount()
  await flush()
  button('编辑').click()
  await flush()
  setLanguage('en-US')
  await flush()
  expect(host.querySelector('li')!.textContent).toContain('Target city')
  expect(host.querySelector('select option[value="LOCATION"]')!.textContent).toContain(
    'Target city',
  )
  button('Save memory').click()
  await flush()
  expect(host.querySelector('[role="status"]')!.textContent).toBe('Memory saved.')
  expect(api.editMemory).toHaveBeenCalledWith('mem1', { kind: 'LOCATION', fact: memory.fact })
  expect(host.querySelector('li')!.textContent).toContain(memory.fact)
  setLanguage('zh-CN')
  await flush()
  expect(host.querySelector('[role="status"]')!.textContent).toBe('记忆已保存。')
  expect(host.querySelector('li')!.textContent).toContain('目标城市')
})

it('allows clearing pending candidates even when no confirmed memories exist', async () => {
  vi.mocked(api.listMemories).mockResolvedValue({ items: [], memoryEnabled: false, limit: 50 })
  vi.mocked(api.listMemoryCandidates).mockResolvedValue([
    {
      id: 'candidate',
      kind: 'GOAL',
      fact: '目标原文',
      confidence: 0.8,
      extractor: 'rule',
      sourceConversationId: 'c',
      sourceMessageId: 'm',
    },
  ])
  vi.mocked(api.clearMemories).mockResolvedValue(null)
  mount()
  await flush()
  expect(button('清空全部记忆').disabled).toBe(false)
  button('清空全部记忆').click()
  await flush()
  expect(api.clearMemories).not.toHaveBeenCalled()
  button('确认删除').click()
  await flush()
  expect(api.clearMemories).toHaveBeenCalledTimes(1)
  expect(host.querySelector('[aria-label="待确认记忆"]')).toBeNull()
  expect(button('清空全部记忆').disabled).toBe(true)
})
