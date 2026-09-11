/**
 * 卡片 payload 来自 SSE，运行时是 unknown。
 * 这里做一层最轻的守卫：只检查顶层必需字段存在，避免脏数据把组件直接打崩。
 * 字段级的完整校验交给后端（结构化输出校验失败会返回 40003）。
 */
export function asPayload<T extends object>(payload: unknown, requiredKey: keyof T & string): T | null {
  if (typeof payload !== 'object' || payload === null) return null
  if (!(requiredKey in payload)) return null
  return payload as T
}
