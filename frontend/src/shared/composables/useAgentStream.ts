import { ref } from 'vue'
import { queryClient } from '@/shared/api/queryClient'
import { cancelAgentRun, streamAgentChat } from '@/shared/api/agent'
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
  let generation = 0

  function handleEvent(event: AgentEvent): void {
    currentRunId ||= event.runId
    store.apply(event)
    if (event.type === 'ui.card' && event.cardType === 'board_update')
      void queryClient.invalidateQueries({ queryKey: ['applications'] })
  }

  async function start(body: ChatRequest): Promise<string> {
    controller?.abort()
    currentRunId = null
    controller = new AbortController()
    const version = ++generation
    streaming.value = true

    try {
      const runId = await streamAgentChat(body, {
        signal: AbortSignal.any([controller.signal, AbortSignal.timeout(6_000_000)]),
        onEvent: (event) => {
          if (version === generation) handleEvent(event)
        },
      })
      if (version === generation) currentRunId = runId || currentRunId
      return runId
    } catch (error) {
      const run = currentRunId ? store.getRun(currentRunId) : undefined
      if (version === generation && run?.status === 'RUNNING') {
        store.apply({
          id: run.lastEventId + 1,
          runId: run.runId,
          ts: new Date().toISOString(),
          type: 'error',
          code: 0,
          recoverable: false,
          message: error instanceof Error ? error.message : '连接中断',
        })
      }
      throw error
    } finally {
      if (version === generation) {
        streaming.value = false
        controller = null
      }
    }
  }

  /** 中断当前运行：先断流，本地标记取消，再通知后端 */
  async function stop(): Promise<void> {
    const runId = currentRunId ?? store.activeRunId
    const running = runId ? store.getRun(runId)?.status === 'RUNNING' : false
    ++generation
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

    // Always release the browser stream, even when the cancellation request fails.
    if (running) await cancelAgentRun(runId)
  }

  return { start, stop, streaming }
}
