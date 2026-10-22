/* eslint-disable vue/one-component-per-file -- 每个测试挂载独立消息实例 */
import { createApp, h, nextTick, type App } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import MessageItem from './MessageItem.vue'

let app: App | undefined
afterEach(() => app?.unmount())

it('a [KB-…] reference in the reply jumps to that note in the turn source card; bare [1] stays text', async () => {
  const root = document.createElement('div')
  document.body.append(root)
  const scroll = vi.fn()
  Element.prototype.scrollIntoView = scroll
  app = createApp({
    render: () =>
      h(
        MessageItem,
        {
          message: {
            id: 'm1',
            role: 'assistant',
            text: '你在笔记里写过分库分表的实践[KB-0123456789ab]，另见第[1]条。',
            status: 'COMPLETED',
            cards: [{ id: 'c1', cardType: 'knowledge_note_citations', payload: {} }],
          },
        } as never,
        {
          cards: () =>
            h('article', { 'data-kb-reference': 'KB-0123456789ab', tabindex: -1 }, '分库分表笔记'),
        },
      ),
  })
  app.mount(root)
  await nextTick()
  const chips = root.querySelectorAll<HTMLButtonElement>('button.md-citation')
  expect([...chips].map((b) => b.textContent)).toEqual(['KB-0123456789ab'])
  expect(root.textContent).toContain('第[1]条')
  expect(root.textContent).not.toContain('正在思考')
  chips[0]!.click()
  await nextTick()
  const note = root.querySelector('article')!
  expect(scroll).toHaveBeenCalled()
  expect(note.classList.contains('cite-flash')).toBe(true)
  expect(document.activeElement).toBe(note)
  root.remove()
})

it('数字引用按当前消息来源定位，代码与无来源编号保持纯文本，恶意链接不进入面板', async () => {
  const root = document.createElement('div')
  document.body.append(root)
  const scroll = vi.fn()
  Element.prototype.scrollIntoView = scroll
  app = createApp({
    render: () =>
      h(MessageItem, {
        message: {
          id: 'm',
          role: 'assistant',
          text: '证据[1] 未知[2] `代码[1]`',
          citations: [
            {
              index: 1,
              docId: 'd',
              title: '真实来源',
              snippet: '原文片段',
              score: null,
              collection: 'notes',
              url: 'javascript:alert(1)',
            },
          ],
        },
      }),
  })
  app.mount(root)
  await nextTick()
  expect(root.querySelectorAll('button.md-citation')).toHaveLength(1)
  expect(root.textContent).toContain('真实来源')
  expect(root.textContent).toContain('原文片段')
  expect(root.querySelector('a')).toBeNull()
  root.querySelector<HTMLButtonElement>('button.md-citation')!.click()
  await nextTick()
  expect(scroll).toHaveBeenCalled()
  expect(document.activeElement?.getAttribute('data-citation-index')).toBe('1')
  root.remove()
})
