/**
 * SSE 帧解析（docs/05 §1.6）。
 * 使用 fetch + ReadableStream 而非 EventSource：需要 POST 与自定义请求头。
 */

export interface SseFrame {
  /** 事件 id，用于断线续传的 Last-Event-ID */
  id?: number
  /** 事件名，如 text.delta */
  event?: string
  /** 未解析的 data 原文（通常是一行 JSON） */
  data: string
}

/** 把缓冲区按空行切分，返回完整帧与未收完的剩余内容 */
export function splitFrames(buffer: string): { frames: string[]; rest: string } {
  const normalized = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const parts = normalized.split('\n\n')
  const rest = parts.pop() ?? ''
  return { frames: parts.filter((part) => part.trim() !== ''), rest }
}

/** 解析单帧；心跳等注释帧（以 `:` 开头）返回 null */
export function parseFrame(raw: string): SseFrame | null {
  const frame: SseFrame = { data: '' }
  const dataLines: string[] = []
  let hasField = false

  for (const line of raw.split('\n')) {
    if (line === '' || line.startsWith(':')) continue

    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    switch (field) {
      case 'id': {
        const parsed = Number.parseInt(value, 10)
        if (!Number.isNaN(parsed)) frame.id = parsed
        hasField = true
        break
      }
      case 'event':
        frame.event = value
        hasField = true
        break
      case 'data':
        dataLines.push(value)
        hasField = true
        break
      default:
        // 未知字段按 SSE 规范忽略
        break
    }
  }

  if (!hasField) return null
  frame.data = dataLines.join('\n')
  return frame
}

/** 逐帧消费 SSE 响应体 */
export async function* parseSse(stream: ReadableStream<Uint8Array>): AsyncGenerator<SseFrame> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const { frames, rest } = splitFrames(buffer)
      buffer = rest

      for (const raw of frames) {
        const frame = parseFrame(raw)
        if (frame) yield frame
      }
    }

    // 冲刷尾部未以空行结束的最后一帧
    buffer += decoder.decode()
    const { frames } = splitFrames(`${buffer}\n\n`)
    for (const raw of frames) {
      const frame = parseFrame(raw)
      if (frame) yield frame
    }
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
