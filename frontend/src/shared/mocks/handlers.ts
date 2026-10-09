import { HttpResponse, http } from 'msw'
import { buildScenario } from './scenario'
import type { AgentEvent, ChatRequest } from '@/shared/types/agent-events'

const encoder = new TextEncoder()

/** 默认按真实节奏推流；测试里调成 0 可以跳过等待 */
let mockSpeed = 1

export function setMockSpeed(value: number): void {
  mockSpeed = value
}

/** 每类事件的间隔，让流式效果看起来自然 */
function delayFor(event: AgentEvent): number {
  let base: number
  switch (event.type) {
    case 'text.delta':
      base = 60
      break
    case 'reasoning.delta':
      base = 110
      break
    case 'approval.required':
      base = 1200
      break
    case 'tool.call':
    case 'tool.result':
      base = 340
      break
    case 'step.updated':
      base = 280
      break
    default:
      base = 200
      break
  }
  return Math.round(base * mockSpeed)
}

function sseFrame(event: AgentEvent): Uint8Array {
  return encoder.encode(`id: ${event.id}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`)
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve()
      return
    }
    const onAbort = (): void => {
      clearTimeout(timer)
      resolve()
    }
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

export const handlers = [
  // 通配写法：无论 VITE_API_BASE_URL 是绝对地址还是 /api/v1 都能命中
  http.post('*/api/v1/agent/chat', async ({ request }) => {
    const body = (await request.json()) as ChatRequest
    const events = buildScenario(body)

    const stream = new ReadableStream<Uint8Array>({
      async start(controller) {
        for (const event of events) {
          if (request.signal.aborted) break
          await sleep(delayFor(event), request.signal)
          if (request.signal.aborted) break
          controller.enqueue(sseFrame(event))
        }
        try {
          controller.close()
        } catch {
          // 客户端已断开，忽略
        }
      },
    })

    return new HttpResponse(stream, {
      headers: {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache',
      },
    })
  }),

  http.post('*/api/v1/agent/runs/:runId/cancel', () =>
    HttpResponse.json({
      code: 0,
      message: 'ok',
      data: { status: 'CANCELLED' },
      traceId: 'mock',
    }),
  ),
]
