import { ref } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './JobSearchCard.vue'
vi.mock('@/shared/composables/useBoard', () => ({
  useBoard: () => ({
    error: ref(''),
    query: { isError: ref(false), isPending: ref(false) },
    saved: () => false,
    busy: ref(false),
  }),
}))
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it('岗位按真实载荷展示', () => {
  view = mountForTest(Card, {
    payload: {
      schemaVersion: 2,
      returnedCount: 1,
      criteria: { query: 'Java', site: '', nature: '', location: '' },
      fetchedAt: '2026-10-20T00:00:00Z',
      items: [
        {
          id: 'j',
          company: '公司A',
          title: 'Java岗位',
          site: 'site',
          locations: '北京',
          nature: 'CAMPUS',
          updatedAt: '2026-10-20',
        },
      ],
      meta: { cached: false, sites: [], degradedSites: [], skipped: [], truncated: false },
    },
  })
  expect(view.root.textContent).toContain('Java岗位')
  expect(view.root.querySelector('a[href^="javascript:"]')).toBeNull()
})
it('岗位损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})
