import { describe, expect, it } from 'vitest'
import { parseSse, parseFrame, splitFrames } from './sse'

describe('splitFrames', () => {
  it('按空行切分并保留未收完的尾部', () => {
    const { frames, rest } = splitFrames('data: a\n\ndata: b\n\ndata: c')
    expect(frames).toEqual(['data: a', 'data: b'])
    expect(rest).toBe('data: c')
  })

  it('兼容 CRLF', () => {
    const { frames } = splitFrames('data: a\r\n\r\n')
    expect(frames).toEqual(['data: a'])
  })

  it('忽略纯空白的帧', () => {
    const { frames } = splitFrames('data: a\n\n\n\n')
    expect(frames).toEqual(['data: a'])
  })
})

describe('parseFrame', () => {
  it('解析 id / event / data', () => {
    expect(parseFrame('id: 42\nevent: text.delta\ndata: {"a":1}')).toEqual({
      id: 42,
      event: 'text.delta',
      data: '{"a":1}',
    })
  })

  it('多行 data 以换行拼接', () => {
    expect(parseFrame('data: line1\ndata: line2')?.data).toBe('line1\nline2')
  })

  it('心跳注释帧返回 null', () => {
    expect(parseFrame(': ping')).toBeNull()
  })

  it('非法 id 被忽略', () => {
    expect(parseFrame('id: abc\ndata: x')).toEqual({ data: 'x' })
  })
})

describe('parseSse', () => {
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
})
