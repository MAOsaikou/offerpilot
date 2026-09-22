/* eslint-disable vue/one-component-per-file -- Each test mounts an isolated component fixture. */
import { createApp, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { expect, it } from 'vitest'
import AgentPanel from './AgentPanel.vue'
import { useAgentRunStore } from '@/shared/stores/agentRun'
import type { AgentEvent } from '@/shared/types/agent-events'

it('将 SSE 事件归约后展示计划、占位调用结果与零耗时，并在取消后结束运行状态', async () => {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useAgentRunStore()
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp({ render: () => h(AgentPanel, { run: store.activeRun }) })
  app.use(pinia)
  app.mount(root)
  let id = 0
  type Draft<T = AgentEvent> = T extends AgentEvent ? Omit<T, 'id' | 'runId' | 'ts'> : never
  const emit = (event: Draft) =>
    store.apply({ ...event, id: ++id, runId: 'r1', ts: new Date().toISOString() })
  try {
    emit({
      type: 'run.started',
      conversationId: 'c1',
      messageId: 'm1',
      model: 'test',
      mode: 'AUTO',
    })
    emit({
      type: 'plan.created',
      goal: '岗位与简历准备',
      steps: [
        { id: 's1', title: '岗位分析', agent: 'JOB', status: 'PENDING', dependsOn: [] },
        { id: 's2', title: '简历指导', agent: 'RESUME', status: 'PENDING', dependsOn: ['s1'] },
      ],
    })
    emit({ type: 'step.updated', stepId: 's1', status: 'RUNNING' })
    emit({
      type: 'tool.call',
      stepId: 's1',
      toolCallId: 'r1:s1',
      name: 'placeholder.JOB',
      args: {},
      source: 'local',
      access: 'READ',
    })
    await nextTick()
    expect(root.textContent).toContain('岗位与简历准备')
    expect(root.textContent).toContain('placeholder.JOB')
    expect(root.textContent).toContain('占位')
    emit({
      type: 'tool.result',
      stepId: 's1',
      toolCallId: 'r1:s1',
      name: 'placeholder.JOB',
      ok: true,
      resultPreview: '未执行真实搜索',
      durationMs: 0,
      truncated: false,
    })
    emit({ type: 'step.updated', stepId: 's1', status: 'DONE', durationMs: 0, summary: '占位完成' })
    await nextTick()
    expect(root.textContent).toContain('未执行真实搜索')
    expect(root.textContent).toContain('0.0s')
    emit({ type: 'step.updated', stepId: 's2', status: 'RUNNING' })
    emit({
      type: 'tool.call',
      stepId: 's2',
      toolCallId: 'r1:s2',
      name: 'placeholder.RESUME',
      args: {},
      source: 'local',
      access: 'READ',
    })
    emit({ type: 'run.completed', messageId: 'm1', status: 'CANCELLED', durationMs: 15 })
    await nextTick()
    expect(root.textContent).toContain('已取消')
    expect(root.textContent).not.toContain('执行中')
    expect(store.activeRun?.steps.map((s) => s.status)).toEqual(['DONE', 'SKIPPED'])
    expect(store.activeRun?.toolCalls.map((c) => c.status)).toEqual(['OK', 'CANCELLED'])
  } finally {
    app.unmount()
    root.remove()
  }
})

it('纯文字运行结束后不再显示等待规划', async () => {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useAgentRunStore()
  const base = { runId: 'r2', ts: new Date().toISOString() }
  store.apply({
    ...base,
    id: 1,
    type: 'run.started',
    conversationId: 'c2',
    messageId: 'm2',
    model: 'test',
    mode: 'AUTO',
  })
  store.apply({ ...base, id: 2, type: 'text.delta', text: '你好' })
  store.apply({
    ...base,
    id: 3,
    type: 'run.completed',
    status: 'COMPLETED',
    messageId: 'm2',
    durationMs: 1,
  })
  const root = document.createElement('div')
  const app = createApp(AgentPanel, { run: store.activeRun })
  app.mount(root)
  await nextTick()
  expect(root.textContent).toContain('本轮未生成执行计划')
  expect(root.textContent).not.toContain('正在识别')
  app.unmount()
})
