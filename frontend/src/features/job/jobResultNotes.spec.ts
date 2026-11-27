import { expect, it } from 'vitest'
import {
  bannerFailureDetail,
  formatSkippedSource,
  locationMisses,
  sourceFailureText,
} from './jobResultNotes'

it('treats a city filter as unmet only when the listing never mentions it', () => {
  expect(locationMisses('上海', '上海')).toBe(false)
  expect(locationMisses('上海市 / 远程', '上海')).toBe(false)
  expect(locationMisses('Shanghai, China', '上海')).toBe(false)
  expect(locationMisses('北京', '上海')).toBe(true)
  expect(locationMisses('', '上海')).toBe(true)
  expect(locationMisses('北京', '')).toBe(false)
})

it('explains failed sources without passing through secrets', () => {
  expect(sourceFailureText('timeout')).toBe('超时')
  expect(sourceFailureText('暂不可用')).toBe('暂不可用')
  expect(sourceFailureText('连接失败')).toBe('连接失败')
  expect(sourceFailureText('secret token=abc')).toBe('暂不可用')
  expect(sourceFailureText('LIVE_UNAVAILABLE')).toBe('企业官网这次没有返回，已保留可用结果。')
  expect(sourceFailureText('LIVE_UNAVAILABLE')).not.toContain('LIVE_')
  expect(bannerFailureDetail('NOT_A_REAL_CODE')).toBe('')
  expect(formatSkippedSource('字节', 'timeout')).toBe('字节（超时）')
})
