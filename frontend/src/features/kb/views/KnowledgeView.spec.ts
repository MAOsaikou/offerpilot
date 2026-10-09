import { createApp, h, nextTick, type App } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import KnowledgeView from './KnowledgeView.vue'
import {
  createKnowledgeNote,
  deleteKnowledgeNote,
  getKnowledgeNote,
  importKnowledgeNotes,
  listKnowledgeNotes,
  searchKnowledgeNotes,
  updateKnowledgeNote,
  type KnowledgeNote,
} from '@/shared/api/knowledge'
import { useWorkspace } from '@/shared/stores/workspace'
import { downloadText } from '@/shared/utils/download'
import { sessionRevision } from '@/shared/utils/authSession'

vi.mock('@/shared/api/knowledge', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/shared/api/knowledge')>()),
  createKnowledgeNote: vi.fn(),
  deleteKnowledgeNote: vi.fn(),
  getKnowledgeNote: vi.fn(),
  importKnowledgeNotes: vi.fn(),
  listKnowledgeNotes: vi.fn(),
  searchKnowledgeNotes: vi.fn(),
  updateKnowledgeNote: vi.fn(),
}))
vi.mock('@/shared/utils/download', () => ({ downloadText: vi.fn() }))
const fixture = (id = 'note_1'): KnowledgeNote => ({
  id,
  title: '事务与并发',
  content: '完整的项目经验与并发验证过程',
  category: '项目经历',
  createdAt: '2026-10-10T00:00:00Z',
  updatedAt: '2026-10-10T00:00:00Z',
})
let app: App | undefined, root: HTMLDivElement, cache: QueryClient
let workspace: ReturnType<typeof useWorkspace>
const local = { id: 'legacy_1', title: '本地复盘', content: '原始内容', category: '面试复盘' }
async function settle() {
  for (let i = 0; i < 5; i++) {
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}
async function mount() {
  root = document.createElement('div')
  document.body.append(root)
  app = createApp({ render: () => h(KnowledgeView) })
    .use(pinia)
    .use(VueQueryPlugin, { queryClient: cache })
  app.mount(root)
  await settle()
}
function button(text: string) {
  const found = [...root.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  )
  if (!found) throw new Error('Missing button: ' + text)
  return found
}
async function click(text: string) {
  button(text).click()
  await settle()
}
async function fill(selector: string, value: string) {
  const input = root.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}
let pinia: ReturnType<typeof createPinia>
beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  localStorage.setItem('offerpilot-guest-v1', 'guest-fixture')
  pinia = createPinia()
  setActivePinia(pinia)
  workspace = useWorkspace()
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  vi.mocked(listKnowledgeNotes).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  vi.mocked(getKnowledgeNote).mockResolvedValue(fixture())
  vi.mocked(createKnowledgeNote).mockResolvedValue(fixture())
  vi.mocked(updateKnowledgeNote).mockResolvedValue(fixture())
  vi.mocked(deleteKnowledgeNote).mockResolvedValue(null)
  vi.mocked(importKnowledgeNotes).mockResolvedValue({ imported: 1, alreadyImported: 0 })
})
afterEach(() => {
  app?.unmount()
  app = undefined
  cache.clear()
  root?.remove()
  pinia._s.forEach((store) => store.$dispose())
})
it('shows loading, empty and recoverable error states', async () => {
  let reject!: (error: Error) => void
  vi.mocked(listKnowledgeNotes).mockReturnValueOnce(new Promise((_, fail) => (reject = fail)))
  await mount()
  expect(root.textContent).toContain('正在读取知识笔记')
  reject(new Error('网络不可用'))
  await settle()
  expect(root.querySelector('[role=alert]')?.textContent).toContain('网络不可用')
  await click('重新读取')
  expect(root.textContent).toContain('留住你的第一个知识片段')
})
it('creates and edits through the API, keeps failed drafts, and exports full server content', async () => {
  vi.mocked(listKnowledgeNotes).mockResolvedValue({
    items: [fixture()],
    total: 1,
    page: 1,
    size: 20,
  })
  await mount()
  await fill('form input', '我的笔记')
  await fill('form textarea', '真实经验')
  vi.mocked(createKnowledgeNote).mockRejectedValueOnce(new Error('保存失败'))
  await click('保存笔记')
  expect(root.querySelector<HTMLTextAreaElement>('form textarea')?.value).toBe('真实经验')
  await click('保存笔记')
  expect(createKnowledgeNote).toHaveBeenLastCalledWith({
    title: '我的笔记',
    content: '真实经验',
    category: '面试复盘',
  })
  expect(workspace.state.notes).toEqual([])
  expect(root.textContent).toContain('笔记已保存到当前身份')
  await click('编辑笔记')
  expect(getKnowledgeNote).toHaveBeenCalledWith('note_1')
  await fill('form textarea', '修改后的经历')
  await click('保存笔记')
  expect(updateKnowledgeNote).toHaveBeenCalledWith('note_1', {
    title: '事务与并发',
    content: '修改后的经历',
    category: '项目经历',
  })
  await click('导出')
  expect(downloadText).toHaveBeenCalledWith('事务与并发.md', fixture().content)
})
it('searches on the server and retrieves full content for excerpts', async () => {
  vi.mocked(searchKnowledgeNotes).mockResolvedValue({
    items: [
      {
        id: 'note_1',
        title: '服务端命中',
        category: '技术积累',
        excerpt: '服务器摘录',
        updatedAt: fixture().updatedAt,
        score: 0.03,
        lexicalMatch: true,
        vectorMatch: false,
      },
    ],
    vectorAvailable: false,
  })
  await mount()
  await fill('[aria-label="搜索知识笔记"]', '并发')
  await new Promise((resolve) => setTimeout(resolve, 280))
  await settle()
  expect(searchKnowledgeNotes).toHaveBeenCalledWith('并发', 50, expect.any(AbortSignal))
  expect(root.textContent).toContain('服务端命中')
  expect(root.textContent).toContain('全文检索')
  await click('编辑笔记')
  expect(root.querySelector<HTMLTextAreaElement>('form textarea')?.value).toBe(fixture().content)
})
it('requires explicit import confirmation and preserves data on failure before idempotent retry', async () => {
  workspace.state.notes = [{ ...local }]
  vi.mocked(importKnowledgeNotes).mockRejectedValueOnce(new Error('请求超时'))
  await mount()
  expect(importKnowledgeNotes).not.toHaveBeenCalled()
  await click('导出本地备份')
  expect(downloadText).toHaveBeenCalledWith(
    '本地知识笔记备份.json',
    JSON.stringify([local], null, 2),
  )
  await click('导入本地笔记')
  expect(importKnowledgeNotes).not.toHaveBeenCalled()
  await click('确认上传并导入')
  expect(workspace.state.notes).toEqual([local])
  expect(root.textContent).toContain('请求超时')
  vi.mocked(importKnowledgeNotes).mockResolvedValueOnce({ imported: 0, alreadyImported: 1 })
  await click('确认上传并导入')
  expect(importKnowledgeNotes).toHaveBeenLastCalledWith([
    {
      sourceId: local.id,
      note: { title: local.title, content: local.content, category: local.category },
    },
  ])
  expect(workspace.state.notes).toEqual([])
})
it('clears only acknowledged unchanged copies in successful batches', async () => {
  workspace.state.notes = Array.from({ length: 51 }, (_, id) => ({ ...local, id: String(id) }))
  vi.mocked(importKnowledgeNotes)
    .mockResolvedValueOnce({ imported: 50, alreadyImported: 0 })
    .mockRejectedValueOnce(new Error('第二批失败'))
  await mount()
  await click('导入本地笔记')
  await click('确认上传并导入')
  expect(workspace.state.notes).toEqual([{ ...local, id: '50' }])
  expect(root.textContent).toContain('第二批失败')
  vi.mocked(importKnowledgeNotes).mockImplementationOnce(async () => {
    workspace.state.notes[0]!.content = '本地期间修改'
    return { imported: 1, alreadyImported: 0 }
  })
  await click('确认上传并导入')
  expect(workspace.state.notes[0]!.content).toBe('本地期间修改')
})
it('paginates server notes and requires confirmation before delete', async () => {
  vi.mocked(listKnowledgeNotes).mockResolvedValue({
    items: [fixture()],
    total: 21,
    page: 1,
    size: 20,
  })
  await mount()
  await click('下一页')
  expect(listKnowledgeNotes).toHaveBeenLastCalledWith(2, 20, expect.any(AbortSignal))
  await click('删除笔记')
  expect(deleteKnowledgeNote).not.toHaveBeenCalled()
  await click('确认删除')
  expect(deleteKnowledgeNote).toHaveBeenCalledWith('note_1')
})
it('does not erase the new identity local notes when an old import finishes late', async () => {
  workspace.state.notes = [{ ...local }]
  let resolve!: (result: { imported: number; alreadyImported: number }) => void
  vi.mocked(importKnowledgeNotes).mockReturnValueOnce(new Promise((done) => (resolve = done)))
  await mount()
  await click('导入本地笔记')
  await click('确认上传并导入')
  sessionRevision.value++
  await settle()
  workspace.state.notes = [{ ...local, title: '另一身份的笔记' }]
  resolve({ imported: 1, alreadyImported: 0 })
  await settle()
  expect(workspace.state.notes[0]!.title).toBe('另一身份的笔记')
  expect(root.textContent).not.toContain('已导入服务器确认接收')
})
