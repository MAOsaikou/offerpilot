import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it, vi } from 'vitest'
import RetrievalDebugView from './RetrievalDebugView.vue'
import { debugRetrieval } from '@/shared/api/retrieval-debug'
vi.mock('@/shared/api/retrieval-debug', () => ({ debugRetrieval: vi.fn() }))
let app: ReturnType<typeof createApp>
let host: HTMLDivElement
const flush = async () => {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}
afterEach(() => {
  app?.unmount()
  host?.remove()
  vi.clearAllMocks()
})
it('displays recall and rerank scores independently, explicit vector degradation, empty and denied states', async () => {
  vi.mocked(debugRetrieval)
    .mockResolvedValueOnce({
      vectorAvailable: false,
      embedding: '本地 bge-m3',
      reranker: 'query-term-coverage-v1',
      notice: '仅全文召回',
      items: [
        {
          id: 'job',
          name: '开发',
          excerpt: 'Java项目',
          lexicalScore: 0.4,
          vectorScore: null,
          lexicalRank: 1,
          vectorRank: null,
          recallScore: 1 / 61,
          rerankScore: 0.5,
          recallRank: 1,
          rank: 1,
        },
      ],
    })
    .mockResolvedValueOnce({
      vectorAvailable: false,
      embedding: '本地 bge-m3',
      reranker: 'coverage',
      notice: '仅全文召回',
      items: [],
    })
    .mockRejectedValueOnce(new Error('forbidden'))
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/staff', component: { template: '<div />' } }],
  })
  host = document.createElement('div')
  document.body.append(host)
  app = createApp(RetrievalDebugView).use(router)
  app.mount(host)
  const input = host.querySelector('input')!
  input.value = 'Java'
  input.dispatchEvent(new Event('input'))
  await nextTick()
  const submit = async () => {
    host.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await flush()
  }
  await submit()
  expect(debugRetrieval).toHaveBeenCalledWith('Java', 10)
  expect(host.textContent).toContain('0.016393')
  expect(host.textContent).toContain('0.500000')
  expect(host.textContent).toContain('未命中')
  expect(host.textContent).toContain('向量不可用')
  expect(host.textContent).toContain('不是模型置信度')
  await submit()
  expect(host.textContent).toContain('没有召回结果')
  await submit()
  expect(host.textContent).toContain('确认管理员权限')
  expect(host.textContent).not.toContain('Java项目')
})
