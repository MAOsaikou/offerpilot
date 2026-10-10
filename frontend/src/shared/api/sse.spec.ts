import { expect, it } from 'vitest'
import { parseSse } from './sse'
it('跨 chunk 的帧也能正确拼接', async () => {
  const encoder = new TextEncoder()
  const chunks = [
    'id: 42\nevent: text.delta\nda',
    'ta: line1\r\ndata: line2\r\n\r\n\n\n: ping\n\n',
    'id: abc\ndata: x\n\ndata: 尾帧',
  ]
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    },
  })
  const collected: object[] = []
  for await (const frame of parseSse(stream)) collected.push(frame)
  expect(collected).toEqual([
    { id: 42, event: 'text.delta', data: 'line1\nline2' },
    { data: 'x' },
    { data: '尾帧' },
  ])
})
