import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { HttpResponse, http } from 'msw'
import { setupServer } from 'msw/node'
import { createPinia, setActivePinia } from 'pinia'
import { ApiError } from './client'
import { streamAgentChat } from './agent'
import { handlers, setMockSpeed } from '@/shared/mocks/handlers'
import { useAgentRunStore } from '@/shared/stores/agentRun'

/**
 * 端到端验证「脚本流 → SSE 帧 → parseSse → store 归约」这条链路。
 * 后端就绪前，这是唯一能在 CI 里跑通整条对话流的地方。
 */
const server = setupServer(...handlers)

beforeAll(() => {
  setMockSpeed(0)
  server.listen({ onUnhandledRequest: 'error' })
})

afterEach(() => {
  server.resetHandlers()
})

afterAll(() => {
  setMockSpeed(1)
  server.close()
})

describe('streamAgentChat', () => {
  it('将没有终止事件的截断流报告为错误，而非成功回复', async () => {
    server.use(
      http.post(
        '*/api/v1/agent/chat',
        () =>
          new HttpResponse(
            'data: {"type":"text.delta","id":1,"runId":"cut","ts":"2026-09-13","text":"部分回复"}\n\n',
            { headers: { 'Content-Type': 'text/event-stream' } },
          ),
      ),
    )
    await expect(streamAgentChat({ content: '测试中断' })).rejects.toThrow('连接在回复完成前中断')
  })
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('消费脚本流并归约出一次完整运行', async () => {
    const store = useAgentRunStore()

    const runId = await streamAgentChat(
      { content: '帮我找字节和美团的后端校招岗' },
      { onEvent: (event) => store.apply(event) },
    )

    expect(runId).toBe('run_mock_01')

    const run = store.getRun('run_mock_01')
    expect(run?.status).toBe('COMPLETED')
    expect(run?.conversationId).toBe('conv_mock_01')
    expect(run?.goal).toBe('帮我找字节和美团的后端校招岗')
    expect(run?.steps).toHaveLength(4)
    expect(run?.toolCalls).toHaveLength(4)
    expect(run?.cards.map((card) => card.cardType)).toEqual([
      'job_match',
      'resume_diff',
      'board_update',
    ])
    expect(run?.text).toContain('匹配度最高')
    expect(run?.citations[0]?.index).toBe(1)
    expect(run?.usage?.promptTokens).toBe(21480)
    expect(run?.durationMs).toBe(24700)
  })

  it('后端返回错误包裹时抛出带 traceId 的 ApiError', async () => {
    server.use(
      http.post('*/api/v1/agent/chat', () =>
        HttpResponse.json(
          { code: 30204, message: '并发运行超限', data: null, traceId: 'trace_mock' },
          { status: 429 },
        ),
      ),
    )

    const error: unknown = await streamAgentChat({ content: 'x' }).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(30204)
    expect((error as ApiError).status).toBe(429)
    expect((error as ApiError).traceId).toBe('trace_mock')
  })
})
