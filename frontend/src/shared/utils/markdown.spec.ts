import { expect, it } from 'vitest'
import { renderMarkdown } from './markdown'
import { ensureLanguage, highlightRevision } from './codeHighlight'

const java = '```java\npublic class A { String s = "[KB-0123456789ab]"; }\n```'

it('代码块先以纯文本显示，语法就绪后高亮，并带语言标签与复制按钮', async () => {
  const first = renderMarkdown(java)
  expect(first).toContain('class="code-lang">java<')
  expect(first).toContain('data-copy-code')
  expect(first).not.toContain('class="shiki')
  const revision = highlightRevision.value
  await ensureLanguage('java')
  expect(highlightRevision.value).toBe(revision + 1)
  const html = renderMarkdown(java)
  expect(html).toContain('class="shiki')
  expect(html).toContain('--shiki-dark:')
  // 代码里的引用标记保持原文，不渲染成按钮；正文里的照常渲染
  expect(html).not.toContain('md-citation')
  expect(renderMarkdown('见 [KB-0123456789ab]')).toContain('data-kb-reference="KB-0123456789ab"')
})

it('不支持的语言和流式未闭合代码块仍安全转义', () => {
  const html = renderMarkdown('```brainfuck\n<script>alert(1)</script>', { streaming: true })
  expect(html).toContain('&lt;script&gt;')
  expect(html).not.toContain('<script>')
  expect(html).toContain('class="code-lang">brainfuck<')
})
