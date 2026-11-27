import { expect, it } from 'vitest'
import { chatHeadingTitle } from './chatHeadingTitle'

const full = '请结合我已保存的 Java 后端实习目标，用几句话指出我目前最该补的一项证据，不要编造经历'

it('restores a first question that was stored as a 40-character crop', () => {
  const stored = [...full].slice(0, 40).join('')
  expect(stored.endsWith('不')).toBe(true)
  expect(chatHeadingTitle(stored, full)).toBe(full)
  expect(chatHeadingTitle(`${stored}…`, `  ${full}  `)).toBe(full)
})

it('keeps a shorter title the user renamed', () => {
  expect(chatHeadingTitle('面试准备', full)).toBe('面试准备')
  expect(chatHeadingTitle('', '')).toBe('')
})
