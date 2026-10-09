import { createApp, h, nextTick, ref } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ApprovalCard from './ApprovalCard.vue'
import { queryClient } from '@/shared/api/queryClient'
import type { ApprovalView } from '@/shared/stores/agentRun'

let cleanup: () => void
beforeEach(() => {
  localStorage.setItem('offerpilot-guest-v1', 'test-guest-token')
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-09T00:00:00Z'))
})
afterEach(() => {
  cleanup?.()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
function mount(
  expiresAt = '2026-10-09T00:10:00Z',
  args: unknown = { applicationId: 'app1', status: 'APPLIED', note: '原备注' },
) {
  const approval = ref<ApprovalView>({
    approvalId: 'approval1',
    toolCallId: 'run1:s1',
    name: 'updateApplicationStatus',
    args,
    reason: '确认看板修改',
    expiresAt,
    editableFields: ['status', 'note'],
    status: 'PENDING',
  })
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp({
    render: () => h(ApprovalCard, { runId: 'run1', running: true, approvals: [approval.value] }),
  })
  app.mount(root)
  cleanup = () => {
    app.unmount()
    root.remove()
  }
  return { root, approval }
}
async function settle() {
  for (let i = 0; i < 12; i++) await Promise.resolve()
  await nextTick()
}

it('批准编辑后的状态和备注，通过真实 API helper 提交并刷新看板', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValue(
      new Response(JSON.stringify({ code: 0, data: { decision: 'APPROVE' } }), { status: 200 }),
    )
  vi.stubGlobal('fetch', fetch)
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue()
  const { root } = mount()
  const select = root.querySelector('select')!
  expect([...select.options].map((o) => o.value)).not.toContain('WRITTEN_TEST')
  select.value = 'INTERVIEW'
  select.dispatchEvent(new Event('change'))
  const note = root.querySelector('textarea')!
  note.value = '周五面试'
  note.dispatchEvent(new Event('input'))
  await nextTick()
  root.querySelector('button')!.click()
  await nextTick()
  expect(root.querySelector('fieldset')!.disabled).toBe(true)
  await settle()
  expect(fetch).toHaveBeenCalledTimes(1)
  expect(fetch.mock.calls[0]?.[0]).toBe('/api/v1/agent/runs/run1/approvals/approval1')
  expect(JSON.parse(fetch.mock.calls[0]?.[1].body)).toEqual({
    decision: 'APPROVE',
    args: { status: 'INTERVIEW', note: '周五面试' },
  })
  expect(root.textContent).toContain('已批准')
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['applications'] })
})

it('拒绝不发送编辑参数，显示已拒绝且不刷新看板', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }))
  vi.stubGlobal('fetch', fetch)
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue()
  const { root } = mount()
  root.querySelectorAll('button')[1]!.click()
  await settle()
  expect(JSON.parse(fetch.mock.calls[0]?.[1].body)).toEqual({ decision: 'REJECT' })
  expect(root.textContent).toContain('已拒绝')
  expect(invalidate).not.toHaveBeenCalled()
})

it('倒计时到期显示超时并移除提交按钮，无请求', async () => {
  const fetch = vi.fn()
  vi.stubGlobal('fetch', fetch)
  const { root } = mount('2026-10-09T00:00:02Z')
  expect(root.textContent).toContain('剩余 2 秒')
  await vi.advanceTimersByTimeAsync(2000)
  await nextTick()
  expect(root.textContent).toContain('已超时')
  expect(root.querySelector('button')).toBeNull()
  expect(fetch).not.toHaveBeenCalled()
})

it('提交错误可见且可重试，重复点击不重复提交', async () => {
  let resolve!: (response: Response) => void
  const fetch = vi.fn().mockReturnValue(
    new Promise<Response>((r) => {
      resolve = r
    }),
  )
  vi.stubGlobal('fetch', fetch)
  const { root } = mount()
  root.querySelector('button')!.click()
  root.querySelector('button')!.click()
  await settle()
  expect(fetch).toHaveBeenCalledTimes(1)
  resolve(new Response(JSON.stringify({ message: '审批已处理', code: 30203 }), { status: 409 }))
  await settle()
  expect(root.querySelector('[role="alert"]')?.textContent).toContain('审批已处理')
  expect(root.querySelector('fieldset')!.disabled).toBe(false)
})

it('仅改变状态时不清空原本未提供的备注', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }))
  vi.stubGlobal('fetch', fetch)
  const { root } = mount(undefined, { applicationId: 'app1', status: 'INTERVIEW' })
  root.querySelector('button')!.click()
  await settle()
  expect(JSON.parse(fetch.mock.calls[0]?.[1].body)).toEqual({
    decision: 'APPROVE',
    args: { status: 'INTERVIEW' },
  })
})
