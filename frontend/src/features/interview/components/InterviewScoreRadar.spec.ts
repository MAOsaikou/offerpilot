import { nextTick, reactive } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { setLanguage } from '@/shared/utils/language'
import { mountForTest } from '@/shared/utils/mountForTest'
import Radar from './InterviewScoreRadar.vue'

const chart = vi.hoisted(() => ({ create: vi.fn(), destroy: vi.fn() }))
vi.mock('chart.js', () => ({
  Chart: class {
    static register = vi.fn()
    destroy = chart.destroy
    constructor(...args: unknown[]) {
      chart.create(...args)
    }
  },
  RadarController: {},
  RadialLinearScale: {},
  PointElement: {},
  LineElement: {},
  Filler: {},
}))

const scores = () => ({ professional: 80, expression: 70, logic: 90, jobFit: 60 })
const unscored = () => ({ professional: null, expression: null, logic: null, jobFit: null })
let view: ReturnType<typeof mountForTest> | undefined
beforeEach(() => {
  vi.clearAllMocks()
  setLanguage('zh-CN')
})
afterEach(() => {
  view?.unmount()
  view = undefined
  setLanguage('zh-CN')
})
const config = () => chart.create.mock.calls.at(-1)![1]
const values = () =>
  [...view!.root.querySelectorAll('tbody td')].map((td) => td.textContent?.trim())

it('renders four scores, an accessible canvas and a captioned table with scoped headers', async () => {
  view = mountForTest(Radar, { dimensions: scores() })
  await nextTick()
  expect(chart.create).toHaveBeenCalledTimes(1)
  expect(view.root.querySelector('canvas[role="img"]')?.getAttribute('aria-label')).toBe(
    '面试四维评分雷达，完整数值见下方表格。',
  )
  expect(view.root.querySelector('caption')?.textContent).toContain('评分雷达数值表')
  expect(view.root.querySelectorAll('th[scope="col"]')).toHaveLength(2)
  expect([...view.root.querySelectorAll('th[scope="row"]')].map((th) => th.textContent)).toEqual([
    '专业',
    '表达',
    '逻辑',
    '岗位匹配',
  ])
  expect(values()).toEqual(['80', '70', '90', '60'])
  expect(config().type).toBe('radar')
  expect(config().data.labels).toEqual(['专业', '表达', '逻辑', '岗位匹配'])
  expect(config().data.datasets[0].data).toEqual([80, 70, 90, 60])
  expect(config().options).toMatchObject({
    locale: 'zh-CN',
    animation: false,
    maintainAspectRatio: false,
    scales: { r: { min: 0, max: 100 } },
  })
})

it('keeps null dimensions as gaps and preserves real zero and maximum scores', async () => {
  view = mountForTest(Radar, {
    dimensions: { professional: 0, expression: null, logic: 100, jobFit: null },
  })
  await nextTick()
  expect(config().data.datasets[0]).toMatchObject({ data: [0, null, 100, null], spanGaps: false })
  expect(values()).toEqual(['0', '未评分', '100', '未评分'])
  expect(view.root.querySelector('[role="alert"]')).toBeNull()
})

it('shows an honest empty status and the full table without constructing a chart', async () => {
  view = mountForTest(Radar, { dimensions: unscored() })
  await nextTick()
  expect(view.root.querySelector('[role="status"]')?.textContent).toContain('暂无已评分维度')
  expect(view.root.querySelector('canvas')).toBeNull()
  expect(chart.create).not.toHaveBeenCalled()
  expect(view.root.querySelector('caption')).not.toBeNull()
  expect(values()).toEqual(['未评分', '未评分', '未评分', '未评分'])
})

it.each([
  undefined,
  null,
  {},
  [],
  'broken',
  { professional: -1, expression: Infinity, logic: NaN, jobFit: '90' },
])('handles missing or malformed dimensions honestly: %j', async (dimensions) => {
  view = mountForTest(Radar, { dimensions })
  await nextTick()
  expect(view.root.querySelector('[role="alert"]')?.textContent).toContain('部分维度评分不可用')
  expect(view.root.querySelector('canvas')).toBeNull()
  expect(chart.create).not.toHaveBeenCalled()
  expect(values()).toEqual(['未评分', '未评分', '未评分', '未评分'])
})

it('retains valid evidence when other dimensions are invalid or missing', async () => {
  view = mountForTest(Radar, { dimensions: { professional: 80, expression: 101, logic: false } })
  await nextTick()
  expect(config().data.datasets[0].data).toEqual([80, null, null, null])
  expect(values()).toEqual(['80', '未评分', '未评分', '未评分'])
  expect(view.root.querySelector('[role="alert"]')).not.toBeNull()
})

it('rebuilds on score and language changes and destroys charts for empty state and unmount', async () => {
  const dimensions = reactive<{ [K in keyof ReturnType<typeof scores>]: number | null }>(scores())
  view = mountForTest(Radar, { dimensions })
  await nextTick()
  dimensions.professional = 85
  await nextTick()
  expect(chart.destroy).toHaveBeenCalledTimes(1)
  expect(config().data.datasets[0].data).toEqual([85, 70, 90, 60])

  setLanguage('en-US')
  await nextTick()
  expect(chart.destroy).toHaveBeenCalledTimes(2)
  expect(config().options.locale).toBe('en-US')
  expect(config().data.labels).toEqual([
    'Domain knowledge',
    'Communication',
    'Logic',
    'Job matching',
  ])
  expect(view.root.querySelector('caption')?.textContent).toContain('Score radar values')
  expect(view.root.querySelector('canvas')?.getAttribute('aria-label')).toContain('table below')
  expect(view.root.querySelector('thead')?.textContent).toContain('Score (0–100)')

  Object.assign(dimensions, unscored())
  await nextTick()
  expect(chart.destroy).toHaveBeenCalledTimes(3)
  expect(view.root.querySelector('canvas')).toBeNull()
  expect(values()).toEqual(['Not scored', 'Not scored', 'Not scored', 'Not scored'])
  expect(view.root.querySelector('[role="status"]')?.textContent).toContain(
    'No scored dimensions yet',
  )

  Object.assign(dimensions, scores())
  await nextTick()
  expect(chart.create).toHaveBeenCalledTimes(4)
  view.unmount()
  view = undefined
  expect(chart.destroy).toHaveBeenCalledTimes(4)
})
