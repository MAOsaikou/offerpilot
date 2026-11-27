import { createApp, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it, vi } from 'vitest'
import KnowledgeNoteCitationsCard from './KnowledgeNoteCitationsCard.vue'
import { getKnowledgeNote, type KnowledgeNote } from '@/shared/api/knowledge'
import { sessionRevision } from '@/shared/utils/authSession'

vi.mock('@/shared/api/knowledge', () => ({ getKnowledgeNote: vi.fn() }))
function cardApp(payload: {
  count: number
  items: Array<{
    noteId: string
    title: string
    category: string
    excerpt: string
    reference: string
  }>
}) {
  return createApp({
    render: () => h(KnowledgeNoteCitationsCard, { payload }),
  })
}
it('preserves escaped citation snapshots, handles deleted sources and discards late identity responses', async () => {
  const root = document.createElement('div')
  document.body.append(root)
  const snapshot = '<script>历史原文</script> Redis'
  const app = cardApp({
    count: 1,
    items: [
      {
        noteId: 'knt_1',
        title: '缓存复盘',
        category: '项目经历',
        excerpt: snapshot,
        reference: 'KB-123456789abc',
      },
    ],
  })
  app.mount(root)
  try {
    expect(root.textContent).toContain(snapshot)
    expect(root.querySelector('script')).toBeNull()
    expect(root.textContent).toContain('[KB-123456789abc]')
    vi.mocked(getKnowledgeNote).mockRejectedValueOnce(new Error('笔记不存在或已删除'))
    root.querySelector('button')!.click()
    await vi.waitFor(() =>
      expect(root.querySelector('[role=alert]')?.textContent).toContain('已删除'),
    )
    expect(root.textContent).toContain(snapshot)
    vi.mocked(getKnowledgeNote).mockResolvedValueOnce({
      id: 'knt_1',
      title: '修改后的标题',
      content: '当前版本原文',
      category: '项目经历',
      createdAt: '',
      updatedAt: '',
    })
    root.querySelector('button')!.click()
    await vi.waitFor(() => expect(root.textContent).toContain('当前版本原文'))
    expect(root.textContent).toContain(snapshot)
    let resolve!: (note: KnowledgeNote) => void
    vi.mocked(getKnowledgeNote).mockReturnValueOnce(new Promise((done) => (resolve = done)))
    root.querySelector('button')!.click()
    await nextTick()
    sessionRevision.value++
    await nextTick()
    resolve({
      id: 'knt_1',
      title: '迟到的私有笔记',
      content: '旧身份原文',
      category: '项目经历',
      createdAt: '',
      updatedAt: '',
    })
    await nextTick()
    await nextTick()
    expect(root.textContent).not.toContain('旧身份原文')
  } finally {
    app.unmount()
    root.remove()
  }
})
it('links a current-resume citation to the resume instead of opening a note', async () => {
  const root = document.createElement('div')
  document.body.append(root)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: KnowledgeNoteCitationsCard },
      { path: '/resumes/:id', component: KnowledgeNoteCitationsCard },
    ],
  })
  await router.push('/')
  const app = cardApp({
    count: 1,
    items: [
      {
        noteId: 'rs_current',
        title: '当前简历 · 林晓舟',
        category: 'CURRENT_RESUME',
        excerpt: '校园二手交易平台',
        reference: 'KB-aaaaaaaaaaaa',
      },
    ],
  }).use(router)
  app.mount(root)
  try {
    expect(root.textContent).toContain('当前简历')
    expect(root.textContent).not.toContain('CURRENT_RESUME')
    const link = root.querySelector('a')
    expect(link?.getAttribute('href')).toBe('/resumes/rs_current')
    expect(link?.textContent).toContain('打开这份简历')
    expect(getKnowledgeNote).not.toHaveBeenCalled()
  } finally {
    app.unmount()
    root.remove()
  }
})
