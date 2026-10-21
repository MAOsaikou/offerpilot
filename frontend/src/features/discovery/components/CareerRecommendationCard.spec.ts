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

it('explains descending interest scores for ordinary recommendations', () => {
  view = mountForTest(Card, {
    payload: {
      hollandCode: 'ICR',
      interestsUsed: ['技术研发'],
      ordering: 'interest',
      recommendations: [
        {
          occupationId: 'a',
          name: '后端',
          family: '研发',
          hollandCode: 'ICR',
          similarity: 0.925,
          reasons: [],
        },
        {
          occupationId: 'b',
          name: '数据库',
          family: '数据',
          hollandCode: 'CIE',
          similarity: 0.79,
          reasons: [],
        },
      ],
    },
  })
  expect(view.root.textContent).toContain('按兴趣匹配度降序')
  expect(view.root.textContent).toContain('Pearson')
  expect(view.root.textContent!.indexOf('后端')).toBeLessThan(
    view.root.textContent!.indexOf('数据库'),
  )
})
it('shows the actual constraint ranking score separately from interest similarity', () => {
  view = mountForTest(Card, {
    payload: {
      hollandCode: '',
      interestsUsed: ['已保存推荐'],
      recommendations: [
        {
          occupationId: 'a',
          name: '后端',
          family: '研发',
          hollandCode: '',
          similarity: 0.8,
          rankingScore: 1.2345,
          rankDelta: 1,
          reasons: [],
        },
      ],
    },
  })
  expect(view.root.textContent).toContain('综合排序分：1.2345')
  expect(view.root.textContent).toContain('初始名次分')
  expect(view.root.textContent).toContain('80%')
  expect(view.root.textContent).not.toContain('展示顺序按兴趣匹配度降序')
})
