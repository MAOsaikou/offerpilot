import { translate as uiText } from '@/shared/utils/language'
import { ref } from 'vue'
import { queryClient } from '@/shared/api/queryClient'
import { cancelAgentRun, resumeAgentRun, streamAgentChat } from '@/shared/api/agent'
import { ApiError } from '@/shared/api/client'
import { useAgentRunStore } from '@/shared/stores/agentRun'
import type { AgentEvent, ChatRequest } from '@/shared/types/agent-events'

/** 断线后按这些间隔重连（毫秒）；服务端在 30 秒宽限期内保留运行 */
export const RECONNECT_DELAYS = [500, 1500, 3000, 5000, 8000]

/**
 * 发起并消费一次 Agent 运行（docs/07 §7）。
 * 拿到的事件全部交给 useAgentRunStore 归约，组件只读 store，不直接碰流。
 * 连接中断（非用户中断）时带 Last-Event-ID 自动续传，事件不重复、不丢失（AG-07）。
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

  const sleep = (ms: number, signal: AbortSignal) =>
    new Promise<void>((resolve, reject) => {
      const timer = setTimeout(resolve, ms)
      signal.addEventListener('abort', () => {
        clearTimeout(timer)
        reject(new DOMException('Aborted', 'AbortError'))
      })
    })

  /** 连接断开但运行仍在进行：带最后收到的事件 id 重连，直到收到结束事件或服务端已不再保留这次运行 */
  async function reconnect(
    failure: unknown,
    version: number,
    signal: AbortSignal,
  ): Promise<string> {
    let lastError = failure
    for (const delay of RECONNECT_DELAYS) {
      const run = currentRunId ? store.getRun(currentRunId) : undefined
      if (!run || run.status !== 'RUNNING' || version !== generation) break
      await sleep(delay, signal)
      try {
        return await resumeAgentRun(run.runId, run.lastEventId, {
          signal,
          onEvent: (event) => {
            if (version === generation) handleEvent(event)
          },
        })
      } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') throw error
        lastError = error
        // 404：运行已结束或服务已重启，交给调用方读取已保存的消息
        if (error instanceof ApiError && error.status === 404) break
      }
    }
    throw lastError
  }

  type Open = (signal: AbortSignal, onEvent: (event: AgentEvent) => void) => Promise<string>

  async function consume(open: Open): Promise<string> {
    controller?.abort()
    currentRunId = null
    controller = new AbortController()
    const version = ++generation
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(6_000_000)])
    streaming.value = true

    try {
      let runId: string
      try {
        runId = await open(signal, (event) => {
          if (version === generation) handleEvent(event)
        })
      } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') throw error
        if (error instanceof ApiError && error.status !== undefined) throw error
        runId = await reconnect(error, version, signal)
      }
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
          message: error instanceof Error ? error.message : uiText("Connection interrupted"),
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

  function start(body: ChatRequest): Promise<string> {
    return consume((signal, onEvent) => streamAgentChat(body, { signal, onEvent }))
  }

  /** 接上一次仍在进行的运行（例如刷新页面后），从头回放已产生的事件 */
  function attach(runId: string): Promise<string> {
    return consume((signal, onEvent) => resumeAgentRun(runId, 0, { signal, onEvent }))
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

  return { start, attach, stop, streaming }
}
