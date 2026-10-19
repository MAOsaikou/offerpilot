import { createApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { expect, it } from 'vitest'
import PlanSteps from './PlanSteps.vue'
import ToolTimeline from './ToolTimeline.vue'
import { useAgentRunStore } from '@/shared/stores/agentRun'
import type { AgentEvent, StepStatus } from '@/shared/types/agent-events'

it('replays all five SSE plan statuses and highlights only the current step', async () => {
  setActivePinia(createPinia())
  const store = useAgentRunStore()
  const base = { runId: 'r', ts: '2026-10-19T00:00:00Z' }
  const statuses: StepStatus[] = ['PENDING', 'RUNNING', 'DONE', 'FAILED', 'SKIPPED']
  const events: AgentEvent[] = [
    {
      ...base,
      id: 1,
      type: 'plan.created',
      goal: '准备求职',
      steps: statuses.map((_, i) => ({
        id: `s${i}`,
        title: `步骤${i}`,
        agent: 'JOB',
        status: 'PENDING',
        dependsOn: [],
      })),
    },
    ...statuses.map((status, i): AgentEvent => ({
      ...base,
      id: i + 2,
      type: 'step.updated',
      stepId: `s${i}`,
      status,
    })),
  ]
  events.forEach(store.apply)
  const root = document.createElement('div')
  const app = createApp(() =>
    h(PlanSteps, { goal: store.activeRun?.goal ?? null, steps: store.activeRun?.steps ?? [] }),
  )
  app.mount(root)
  try {
    for (const label of ['待执行', '执行中', '完成', '失败', '已跳过'])
      expect(root.textContent).toContain(label)
    expect(root.querySelectorAll('[aria-current="step"]')).toHaveLength(1)
    expect(root.querySelector('[aria-current="step"]')?.textContent).toContain('步骤1')
    store.apply({ ...base, id: 8, type: 'step.updated', stepId: 's1', status: 'DONE' })
    // Replayed duplicates must not regress state.
    store.apply(events[2]!)
    await nextTick()
    expect(root.querySelector('[aria-current]')).toBeNull()
    expect(store.activeRun?.steps[1]?.status).toBe('DONE')
  } finally {
    app.unmount()
  }
})

it('expands redacted full args/result JSON with elapsed time, source and result summary', async () => {
  const root = document.createElement('div')
  const app = createApp(() =>
    h(ToolTimeline, {
      toolCalls: [
        {
          id: 'c',
          name: 'search_jobs',
          source: 'MCP',
          serverName: 'jobs-mcp',
          access: 'READ',
          status: 'OK',
          args: { inputs: { keyword: 'Java' }, token: '[REDACTED]' },
          result: { count: 1, email: '[EMAIL_1]' },
          resultPreview: '找到 1 个岗位',
          durationMs: 1234,
          truncated: true,
        },
      ],
    }),
  )
  app.mount(root)
  try {
    expect(root.textContent).toContain('MCP · jobs-mcp')
    expect(root.textContent).toContain('1.2s')
    expect(root.textContent).toContain('找到 1 个岗位')
    expect(root.textContent).toContain('Java')
    expect(root.querySelector('pre')).toBeNull()
    const json = root.querySelectorAll('details')[1]!
    json.open = true
    json.dispatchEvent(new Event('toggle'))
    await nextTick()
    const data = JSON.parse(root.querySelector('pre')!.textContent!)
    expect(data).toEqual({
      args: { inputs: { keyword: 'Java' }, token: '[REDACTED]' },
      result: { count: 1, email: '[EMAIL_1]' },
    })
    expect(root.textContent).toContain('已截断')
  } finally {
    app.unmount()
  }
})
