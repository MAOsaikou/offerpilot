import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { Script } from 'node:vm'
import { transformWithOxc } from 'vite'
import workerSource from './mediapipe.worker.ts?raw'

const initializeMediaPipe = vi.hoisted(() => vi.fn())
vi.mock('./mediapipeAssets', () => ({ initializeMediaPipe }))

beforeEach(async () => {
  vi.resetModules()
  initializeMediaPipe.mockReset()
  vi.stubGlobal('postMessage', vi.fn())
  vi.stubGlobal('onmessage', null)
  await import('./mediapipe.worker')
})
afterEach(() => vi.unstubAllGlobals())

it('remains a classic script after Vite transforms TypeScript in development', async () => {
  const transformed = await transformWithOxc(workerSource, 'mediapipe.worker.ts')
  // Static imports (even type-only ones) can leave `export {}` and break a classic Worker.
  expect(() => new Script(transformed.code)).not.toThrow()
  expect(transformed.code).toContain('import(')
})

it('announces readiness only after the configured assets have initialized', async () => {
  initializeMediaPipe.mockResolvedValue({ face: {}, pose: {} })
  await window.onmessage!(
    new MessageEvent('message', { data: { type: 'init', assetsUrl: '/mediapipe' } }),
  )
  expect(initializeMediaPipe).toHaveBeenCalledExactlyOnceWith('/mediapipe', location.origin)
  expect(postMessage).toHaveBeenCalledExactlyOnceWith({ type: 'ready' })
})

it('keeps the error event and forwards diagnostics when all asset sources fail', async () => {
  const message =
    'MediaPipe assets unavailable. Configured assets missing; CDN assets failed: offline'
  initializeMediaPipe.mockRejectedValue(new Error(message))
  await window.onmessage!(
    new MessageEvent('message', { data: { type: 'init', assetsUrl: '/mediapipe' } }),
  )
  expect(postMessage).toHaveBeenCalledExactlyOnceWith({ type: 'error', message })
})
