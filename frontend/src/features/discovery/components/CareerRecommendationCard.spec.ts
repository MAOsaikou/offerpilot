import { afterEach, expect, it } from 'vitest'
import { mountForTest } from '@/shared/utils/mountForTest'
import Card from './CareerRecommendationCard.vue'
let view: ReturnType<typeof mountForTest>
afterEach(() => view?.unmount())
it('职业推荐按真实载荷展示', () => {
  view = mountForTest(Card, {
    payload: {
      hollandCode: 'IA',
      interestsUsed: ['设计'],
      recommendations: [
        {
          occupationId: 'o',
          name: '产品设计师',
          family: '设计',
          hollandCode: 'IA',
          similarity: 0.5,
          reasons: ['兴趣契合'],
        },
      ],
    },
  })
  expect(view.root.textContent).toContain('产品设计师')
  expect(view.root.querySelector('a[href^="javascript:"]')).toBeNull()
})
it('职业推荐空态可见', () => {
  view = mountForTest(Card, {
    payload: { hollandCode: '', interestsUsed: [], recommendations: [] },
  })
  expect(view.root.textContent).toContain('暂无匹配职业')
})
it('职业推荐损坏载荷安全显示错误', () => {
  view = mountForTest(Card, { payload: null })
  expect(view.root.textContent).toMatch(/不可用|无效|无法|缺少|错误/)
})
it('shows all catalog fields, two user facts and explicit missing values for old cards', () => {
  view = mountForTest(Card, {
    payload: {
      hollandCode: 'IA',
      interestsUsed: ['设计'],
      recommendations: [
        {
          occupationId: 'o',
          name: '设计师',
          family: '设计',
          hollandCode: 'IA',
          similarity: 0.5,
          reasons: [],
          details: {
            evidence: [
              { field: 'major', value: '设计专业', source: 'career/profile' },
              { field: 'interests', value: '创意设计', source: 'career/profile' },
            ],
            typicalTitles: ['视觉设计'],
            coreSkills: ['排版'],
            growthPath: '初级到资深',
            salaryRange: '未提供',
            companies: ['未提供'],
            concerns: '未提供',
            sourceOccupationId: 'o',
            source: '职业库条目',
          },
        },
      ],
    },
  })
  for (const text of [
    '50%',
    '设计专业',
    '创意设计',
    '典型岗位',
    '核心技能',
    '成长路径',
    '薪资区间',
    '代表企业',
    '可能的顾虑',
    '职业库来源',
    '未提供',
    '职业库条目',
  ])
    expect(view.root.textContent).toContain(text)
})
