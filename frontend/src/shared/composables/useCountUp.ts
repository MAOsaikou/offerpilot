import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

/** 数字从上一个值滚动到目标值；不支持动画或用户要求减少动态时直接显示目标值。 */
export function useCountUp(target: () => number | null | undefined, duration = 700, delay = 0) {
  const shown: Ref<number | null> = ref(null)
  let frame = 0
  let timer: ReturnType<typeof setTimeout> | undefined

  function stop() {
    if (frame) cancelAnimationFrame(frame)
    if (timer) clearTimeout(timer)
    frame = 0
    timer = undefined
  }

  function animatable() {
    return (
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      typeof requestAnimationFrame === 'function' &&
      !window.matchMedia('(prefers-reduced-motion: reduce)').matches
    )
  }

  watch(
    target,
    (value) => {
      stop()
      if (value === null || value === undefined || !Number.isFinite(value) || !animatable()) {
        shown.value = value ?? null
        return
      }
      const from = shown.value ?? 0
      if (from === value) {
        shown.value = value
        return
      }
      shown.value = from
      const run = () => {
        const start = performance.now()
        const tick = (now: number) => {
          const t = Math.min((now - start) / duration, 1)
          const eased = 1 - Math.pow(1 - t, 3)
          shown.value = Math.round(from + (value - from) * eased)
          frame = t < 1 ? requestAnimationFrame(tick) : 0
        }
        frame = requestAnimationFrame(tick)
      }
      if (delay > 0) timer = setTimeout(run, delay)
      else run()
    },
    { immediate: true },
  )

  onBeforeUnmount(stop)
  return shown
}
