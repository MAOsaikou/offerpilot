import { afterEach, expect, it, vi } from 'vitest'
import { createApp, h } from 'vue'
import ConversationHistory from './ConversationHistory.vue'
import type { Conversation } from '@/shared/types/conversation'

const at = (daysAgo: number) => new Date(Date.now() - daysAgo * 86_400_000).toISOString()
const conv = (id: string, updatedAt: string, pinned = false): Conversation => ({
  id,
  title: id,
  mode: 'AUTO',
  pinned,
  createdAt: updatedAt,
  updatedAt,
})
let root: HTMLElement
afterEach(() => root?.remove())

it('按置顶、今天、昨天、近 7 天、更早分组', () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-16T12:00:00+08:00'))
  root = document.createElement('div')
  document.body.append(root)
  const ok = () => Promise.resolve(true)
  createApp({
    render: () =>
      h(ConversationHistory, {
        conversations: [
          conv('pinned-old', at(30), true),
          conv('today', at(0)),
          conv('yesterday', at(1)),
          conv('week', at(4)),
          conv('old', at(20)),
        ],
        expanded: true,
        sending: false,
        loading: false,
        error: '',
        hasMore: false,
        mock: false,
        rename: ok,
        togglePin: ok,
        remove: ok,
      }),
  }).mount(root)
  const groups = [...root.querySelectorAll('.conversation-group')].map((group) => [
    group.querySelector('.group-label')!.textContent,
    [...group.querySelectorAll('.conversation-title')].map((t) => t.textContent!.trim()),
  ])
  expect(groups).toEqual([
    ['置顶', ['pinned-old']],
    ['今天', ['today']],
    ['昨天', ['yesterday']],
    ['近 7 天', ['week']],
    ['更早', ['old']],
  ])
  vi.useRealTimers()
})
