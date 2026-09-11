import { readonly, ref } from 'vue'

/**
 * 路由切换时的「舞台幕布」。
 *
 * 时序：落幕（条带自上而下遮住画面）→ 切换路由 → 拉幕（条带继续向下抽走，露出新页面）。
 * 路由守卫会 await 落幕完成再放行导航，所以内容交换一定发生在画面被遮住的时候。
 *
 * 注意：下面的时长常量必须与 index.css 中
 * `--curtain-cover-duration` / `--curtain-reveal-duration` / `--curtain-stagger` 保持一致。
 */

export type CurtainPhase = 'idle' | 'covering' | 'uncovering'

/** 幕布条带数量，与 StageCurtain.vue 中的 PANEL_COUNT 对应 */
const PANEL_COUNT = 8
const STAGGER_MS = 18
const COVER_ANIM_MS = 300
const REVEAL_ANIM_MS = 340

/** 全部条带落到位所需时间 */
export const CURTAIN_COVER_MS = COVER_ANIM_MS + STAGGER_MS * (PANEL_COUNT - 1) + 20
/** 全部条带抽走所需时间 */
export const CURTAIN_REVEAL_MS = REVEAL_ANIM_MS + STAGGER_MS * (PANEL_COUNT - 1) + 20

/** 兜底：万一导航被中断导致没收到 afterEach，也不至于让幕布一直糊在屏幕上 */
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

/** 落幕；返回的 Promise 在画面被完全遮住后 resolve */
export function coverCurtain(): Promise<void> {
  clearTimers()
  phase.value = 'covering'

  const covered = new Promise<void>((resolve) => {
    resolveCover = resolve
  })

  later(flushCover, CURTAIN_COVER_MS)
  later(() => {
    // 兜底路径：一直没等到拉幕就自己收场
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

/** 拉幕；只有在落幕之后调用才生效 */
export function revealCurtain(): void {
  if (phase.value !== 'covering') return
  clearTimers()
  flushCover()
  startReveal()
}

export function usePageCurtain() {
  return { phase: readonly(phase) }
}
