import { readonly, ref } from 'vue'

/**
 * 路由切换时的「进场柔雾」。
 *
 * 时序：柔雾淡入把画面虚化 → 切换路由 → 柔雾淡出，露出新页面。
 * 路由守卫会 await 淡入完成再放行导航，所以内容交换一定发生在画面被虚化的时候。
 *
 * 注意：下面的时长常量必须与 index.css 中
 * `--curtain-cover-duration` / `--curtain-reveal-duration` 保持一致。
 */

export type CurtainPhase = 'idle' | 'covering' | 'uncovering'

const COVER_MS = 400
const REVEAL_MS = 440

/** 实际等待时间，留一点余量避免抢在动画结束前放行 */
export const CURTAIN_COVER_MS = COVER_MS + 20
export const CURTAIN_REVEAL_MS = REVEAL_MS + 20

/** 兜底：万一导航被中断导致没收到 afterEach，也不至于让柔雾一直糊在屏幕上 */
const WATCHDOG_MS = 8000

const phase = ref<CurtainPhase>('idle')

let timers: number[] = []
let resolveCover: (() => void) | null = null

function clearTimers(): void {
  for (const id of timers) window.clearTimeout(id)
  timers = []
}

function later(fn: () => void, ms: number): void {
  timers.push(window.setTimeout(fn, ms))
}

function flushCover(): void {
  const resolve = resolveCover
  resolveCover = null
  resolve?.()
}

/** 起雾；返回的 Promise 在画面被完全虚化后 resolve */
export function coverCurtain(): Promise<void> {
  clearTimers()
  phase.value = 'covering'

  const covered = new Promise<void>((resolve) => {
    resolveCover = resolve
  })

  later(flushCover, CURTAIN_COVER_MS)
  later(() => {
    // 兜底路径：一直没等到散雾就自己收场
    if (phase.value === 'covering') {
      flushCover()
      startReveal()
    }
  }, WATCHDOG_MS)

  return covered
}

function startReveal(): void {
  phase.value = 'uncovering'
  later(() => {
    phase.value = 'idle'
  }, CURTAIN_REVEAL_MS)
}

/** 散雾；只有在起雾之后调用才生效 */
export function revealCurtain(): void {
  if (phase.value !== 'covering') return
  clearTimers()
  flushCover()
  startReveal()
}

export function usePageCurtain() {
  return { phase: readonly(phase) }
}
