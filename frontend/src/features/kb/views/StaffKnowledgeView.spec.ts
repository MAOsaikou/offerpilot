import { createApp, nextTick } from 'vue'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import StaffKnowledgeView from './StaffKnowledgeView.vue'
import {
  listStaffDocuments,
  uploadStaffDocument,
  reindexStaffDocument,
  deleteStaffDocument,
  type StaffKnowledgeDocument,
} from '@/shared/api/staff-knowledge'
vi.mock('@/shared/api/staff-knowledge', () => ({
  listStaffDocuments: vi.fn(),
  uploadStaffDocument: vi.fn(),
  reindexStaffDocument: vi.fn(),
  deleteStaffDocument: vi.fn(),
}))
const fixture = (
  indexStatus: StaffKnowledgeDocument['indexStatus'] = 'READY',
): StaffKnowledgeDocument => ({
  id: 'staff-doc',
  title: 'Redis 手册',
  filename: 'ops.md',
  mime: 'text/markdown',
  byteSize: 100,
  indexStatus,
  indexError: indexStatus === 'FAILED' ? '索引暂时失败，请重试' : '仅全文检索',
  chunkCount: 1,
  sourceTruncated: false,
  createdAt: '2026-10-23T00:00:00Z',
  updatedAt: '2026-10-23T00:00:00Z',
})
let app: ReturnType<typeof createApp>, root: HTMLDivElement, cache: QueryClient
async function settle() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve()
    await nextTick()
  }
}
async function mount() {
  root = document.createElement('div')
  document.body.append(root)
  cache = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/staff', component: { template: '<div />' } },
      { path: '/staff/retrieval-debug', component: { template: '<div />' } },
    ],
  })
  app = createApp(StaffKnowledgeView).use(VueQueryPlugin, { queryClient: cache }).use(router)
  app.mount(root)
  await settle()
}
const button = (text: string) =>
  [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === text)!
async function choose(file: File) {
  const input = root.querySelector('input')!
  Object.defineProperty(input, 'files', { value: [file], configurable: true })
  input.dispatchEvent(new Event('change'))
  await settle()
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listStaffDocuments).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
})
afterEach(() => {
  app?.unmount()
  cache?.clear()
  root?.remove()
  vi.useRealTimers()
})
it('shows load/error retry, empty upload, failure and reindex/delete controls', async () => {
  vi.mocked(listStaffDocuments).mockRejectedValueOnce(new Error('offline'))
  await mount()
  expect(root.textContent).toContain('文档加载失败')
  button('重试').click()
  await settle()
  expect(root.textContent).toContain('暂无管理文档')
  await choose(new File(['x'], 'bad.docx'))
  expect(uploadStaffDocument).not.toHaveBeenCalled()
  expect(root.textContent).toContain('仅支持 PDF')
  vi.mocked(uploadStaffDocument).mockResolvedValue(fixture('FAILED'))
  vi.mocked(listStaffDocuments).mockResolvedValue({
    items: [fixture('FAILED')],
    total: 1,
    page: 1,
    size: 20,
  })
  await choose(new File(['Redis'], 'ops.md'))
  expect(root.textContent).toContain('文档已上传')
  expect(root.textContent).toContain('失败')
  vi.mocked(reindexStaffDocument).mockResolvedValue(fixture('QUEUED'))
  button('重新索引').click()
  await settle()
  expect(reindexStaffDocument).toHaveBeenCalledWith('staff-doc')
  button('删除').click()
  await settle()
  expect(deleteStaffDocument).not.toHaveBeenCalled()
  expect(button('确认删除')).toBeDefined()
  button('取消').click()
  await settle()
  expect(button('确认删除')).toBeUndefined()
  expect(deleteStaffDocument).not.toHaveBeenCalled()
  button('删除').click()
  await settle()
  vi.mocked(deleteStaffDocument).mockRejectedValueOnce(new Error('删除失败，请重试'))
  button('确认删除').click()
  await settle()
  expect(root.textContent).toContain('删除失败，请重试')
  expect(button('确认删除').disabled).toBe(false)
  vi.mocked(deleteStaffDocument).mockResolvedValue(null)
  vi.mocked(listStaffDocuments).mockResolvedValue({ items: [], total: 0, page: 1, size: 20 })
  button('确认删除').click()
  await settle()
  expect(deleteStaffDocument).toHaveBeenCalledTimes(2)
  expect(deleteStaffDocument).toHaveBeenLastCalledWith('staff-doc')
  expect(root.textContent).toContain('文档已删除')
})
it('polls queued work, stops at ready, and cancels polling on unmount', async () => {
  vi.useFakeTimers()
  vi.mocked(listStaffDocuments)
    .mockResolvedValueOnce({ items: [fixture('QUEUED')], total: 1, page: 1, size: 20 })
    .mockResolvedValue({ items: [fixture()], total: 1, page: 1, size: 20 })
  await mount()
  expect(root.textContent).toContain('排队')
  await vi.advanceTimersByTimeAsync(3000)
  await settle()
  expect(root.textContent).toContain('完成')
  const calls = vi.mocked(listStaffDocuments).mock.calls.length
  await vi.advanceTimersByTimeAsync(9000)
  expect(listStaffDocuments).toHaveBeenCalledTimes(calls)
  app.unmount()
  await vi.advanceTimersByTimeAsync(9000)
  expect(listStaffDocuments).toHaveBeenCalledTimes(calls)
})
it('does not apply a late upload completion after leaving the page', async () => {
  let resolve!: (value: StaffKnowledgeDocument) => void
  vi.mocked(uploadStaffDocument).mockReturnValue(
    new Promise((r) => {
      resolve = r
    }),
  )
  await mount()
  await choose(new File(['Redis'], 'ops.md'))
  expect(button('处理中…').disabled).toBe(true)
  app.unmount()
  const calls = vi.mocked(listStaffDocuments).mock.calls.length
  resolve(fixture())
  await settle()
  expect(listStaffDocuments).toHaveBeenCalledTimes(calls)
})
