import { expect, it } from 'vitest'
import { parse } from 'vue/compiler-sfc'
import { dictionaries } from './language'

const components = import.meta.glob<string>('/src/{features,widgets,app,shared}/**/*.vue', {
  eager: true,
  query: '?raw',
  import: 'default',
})
// Language autonyms are intentionally the same in both interfaces. No page-wide exemptions.
const allowedAutonyms = ['简体中文', '中文']
function chineseInTemplate(source: string): string[] {
  const { descriptor } = parse(source)
  const content = (descriptor.template?.content ?? '').replace(/<!--[\s\S]*?-->/g, '')
  const remaining = allowedAutonyms.reduce((text, word) => text.replaceAll(word, ''), content)
  return remaining.split('\n').filter((line) => /\p{Script=Han}/u.test(line))
}

it('prevents hardcoded Chinese in all app, feature, widget and shared Vue templates', () => {
  expect(Object.keys(components).length).toBeGreaterThan(100)
  const violations = Object.entries(components).flatMap(([path, source]) =>
    chineseInTemplate(source).map((line) => `${path}: ${line.trim()}`),
  )
  expect(violations).toEqual([])
})

it('detects text, attributes and expression literals while allowing comments and autonyms', () => {
  expect(
    chineseInTemplate(
      '<template><h1>标题</h1><input placeholder="请输入" />{{ busy ? "加载" : "保存" }}</template>',
    ),
  ).toHaveLength(1)
  expect(
    chineseInTemplate('<template><!-- 中文说明 --><option>简体中文</option></template>'),
  ).toEqual([])
})

it('resolves every literal translation key used by Vue components', () => {
  const missing: string[] = []
  for (const [path, source] of Object.entries(components)) {
    for (const match of source.matchAll(
      /\b(?:uiText|sourceText)\(\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')/g,
    )) {
      const key = match[1]!.slice(1, -1).replace(/\\(['"\\])/g, '$1')
      if (!(key in dictionaries['en-US'])) missing.push(`${path}: ${key}`)
    }
  }
  expect(missing).toEqual([])
})
