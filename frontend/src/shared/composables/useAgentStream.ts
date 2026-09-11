import { ref } from 'vue'
import { cancelAgentRun, resumeAgentRun, streamAgentChat } from '@/shared/api/agent'
import { useAgentRunStore } from '@/shared/stores/agentRun'
import type { AgentEvent, ChatRequest } from '@/shared/types/agent-events'

/**
 * 发起并消费一次 Agent 运行（docs/07 §7）。
 * 拿到的事件全部交给 useAgentRunStore 归约，组件只读 store，不直接碰流。
 */
export function useAgentStream() {
  const store = useAgentRunStore()
  const streaming = ref(false)

  let controller: AbortController | null = null
  let currentRunId: string | null = null

  function handleEvent(event: AgentEvent): void {
    currentRunId ||= event.runId
    store.apply(event)
  }

  async function start(body: ChatRequest): Promise<string> {
    controller?.abort()
    controller = new AbortController()
    streaming.value = true

    try {
      const runId = await streamAgentChat(body, {
        signal: controller.signal,
        onEvent: handleEvent,
      })
      currentRunId = runId || currentRunId
      return runId
    } finally {
      streaming.value = false
      controller = null
    }
  }

  /** 中断当前运行：先断流，本地标记取消，再通知后端 */
  async function stop(): Promise<void> {
    const runId = currentRunId ?? store.activeRunId
    controller?.abort()
    controller = null
    streaming.value = false
    if (!runId) return

    const run = store.getRun(runId)
    if (run && run.status === 'RUNNING') {
      // 后端补发的 run.completed 到达后会覆盖这条本地乐观更新
      const startedAt = run.startedAt ? Date.parse(run.startedAt) : Date.now()
      store.apply({
        id: run.lastEventId + 1,
        runId,
        ts: new Date().toISOString(),
        type: 'run.completed',
        messageId: run.messageId ?? '',
        status: 'CANCELLED',
        durationMs: Math.max(0, Date.now() - startedAt),
      })
    }

    await cancelAgentRun(runId).catch(() => undefined)
  }

  /** 断线续传，事件 id 从 store 里取 */
  async function resume(runId: string, afterId?: number): Promise<void> {
    const after = afterId ?? store.getRun(runId)?.lastEventId ?? 0
    controller = new AbortController()
    await resumeAgentRun(runId, after, {
      signal: controller.signal,
      onEvent: handleEvent,
    })
  }

  return { start, stop, resume, streaming }
}
