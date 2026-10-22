import { afterEach, expect, it } from 'vitest'
import { nextTick } from 'vue'
import {
  dictionaries,
  formatDate,
  formatNumber,
  language,
  localizedText,
  setLanguage,
  sourceText,
  translate,
  translateLabel,
} from './language'
import { applicationStages, applicationStageLabels } from '@/shared/types/board'
import { noteCategories } from '@/shared/api/knowledge'

afterEach(() => setLanguage('zh-CN'))

it('keeps both dictionaries complete with identical keys and interpolation parameters', () => {
  expect(Object.keys(dictionaries['zh-CN']).sort()).toEqual(
    Object.keys(dictionaries['en-US']).sort(),
  )
  for (const [key, zh] of Object.entries(dictionaries['zh-CN'])) {
    const en = dictionaries['en-US'][key]!
    expect(zh.trim(), key).not.toBe('')
    expect(en.trim(), key).not.toBe('')
    expect([...zh.matchAll(/\{\d+\}/g)].map(([p]) => p).sort(), key).toEqual(
      [...en.matchAll(/\{\d+\}/g)].map(([p]) => p).sort(),
    )
    if (key !== 'Simplified Chinese') expect(en, key).not.toMatch(/\p{Script=Han}/u)
  }
})

it('formats numbers and dates using the active language and preserves unknown text', () => {
  for (const locale of ['zh-CN', 'en-US'] as const) {
    setLanguage(locale)
    expect(document.documentElement.lang).toBe(locale)
    expect(formatDate('2026-10-21T12:30:00Z', { dateStyle: 'full', timeZone: 'UTC' })).toBe(
      new Intl.DateTimeFormat(locale, { dateStyle: 'full', timeZone: 'UTC' }).format(
        new Date('2026-10-21T12:30:00Z'),
      ),
    )
    expect(formatNumber(1234.5, { style: 'currency', currency: 'CNY' })).toBe(
      new Intl.NumberFormat(locale, { style: 'currency', currency: 'CNY' }).format(1234.5),
    )
    expect(translate('server original 正文')).toBe('server original 正文')
    expect(formatDate('old unparseable local date')).toBe('old unparseable local date')
  }
  setLanguage('unsupported')
  expect(language.value).toBe('zh-CN')
})

it('reacts in existing messages and labels without changing persisted values', async () => {
  const message = localizedText()
  message.value = () => translate('Question {0} of {1}', [2, 5])
  const stages = { ...applicationStages }
  const categories = [...noteCategories]
  expect(message.value).toContain('2 / 5 题')
  setLanguage('en-US')
  await nextTick()
  expect(message.value).toBe('Question 2 of 5')
  expect(applicationStageLabels.INTERVIEW).toBe('Interviewing')
  expect(sourceText('Technical')).toBe('技术')
  expect(translateLabel('梳理目标岗位的核心技能')).toBe('Identify core skills for your target job')
  expect(applicationStages).toEqual(stages)
  expect(noteCategories).toEqual(categories)
  message.value = '后端原文'
  setLanguage('zh-CN')
  expect(message.value).toBe('后端原文')
})

it('translates every fixed occupation family without changing catalog values', () => {
  const families = [
    '个人服务',
    '产品与设计',
    '医疗健康',
    '商务与财务',
    '安保与公共安全',
    '建筑与工程',
    '教育与图书',
    '数据',
    '法律',
    '测试与质量',
    '社会服务',
    '算法与人工智能',
    '管理与行政',
    '自然科学与社会科学',
    '艺术设计与传媒',
    '行政与办公支持',
    '计算机与数学',
    '软件研发',
    '运维与安全',
    '运输与物流',
    '销售',
  ]
  setLanguage('en-US')
  for (const family of families) {
    expect(translateLabel(family), family).not.toMatch(/\p{Script=Han}/u)
  }
  expect(translateLabel('未来分类原文')).toBe('未来分类原文')
})
