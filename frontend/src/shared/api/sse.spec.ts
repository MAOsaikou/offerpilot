import { expect, it } from 'vitest'
import { parseSse, parseFrame, splitFrames } from './sse'

it('切分 LF/CRLF 帧，保留尾部并丢弃空白帧', () => {
  expect(splitFrames('data: a\r\n\r\n\n\ndata: b\n\ndata: tail')).toEqual({
    frames: ['data: a', 'data: b'],
    rest: 'data: tail',
  })
})
it('解析多行数据和元信息，丢弃心跳及非法 ID', () => {
  expect(parseFrame('id: 42\nevent: text.delta\ndata: line1\ndata: line2')).toEqual({
    id: 42,
    event: 'text.delta',
    data: 'line1\nline2',
  })
  expect(parseFrame(': ping')).toBeNull()
  expect(parseFrame('id: abc\ndata: x')).toEqual({ data: 'x' })
})
it('跨 chunk 的帧也能正确拼接', async () => {
  const encoder = new TextEncoder()
  const chunks = ['id: 1\nevent: text.delta\nda', 'ta: {"text":"你"}\n\n: ping\n\n', 'data: 尾帧']
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    },
  })
  const collected: string[] = []
  for await (const frame of parseSse(stream)) collected.push(frame.data)
  expect(collected).toEqual(['{"text":"你"}', '尾帧'])
})
