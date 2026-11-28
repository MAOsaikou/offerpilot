import { expect, it } from 'vitest'
import { hideInternalIds } from './suggestionFacts'

it('removes resume and snapshot ids from suggestion sentences', () => {
  expect(
    hideInternalIds(
      '简历版本 rv_01m8ersczzzs04g6knmv78wq7c 列出的技能与项目技术；不代表已验证熟练度。',
    ),
  ).toBe('这份简历列出的技能与项目技术；不代表已验证熟练度。')
  expect(hideInternalIds('GitHub 快照 gh_01m8abc，抓取于今天')).toBe('GitHub 快照：抓取于今天')
  expect(hideInternalIds('非fork仓库，语言不证明本人掌握')).toBe('非fork仓库，语言不证明本人掌握')
})
