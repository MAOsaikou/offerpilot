import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useMediaPipe, type PresenceAnalyzer } from './useMediaPipe'
import { mountForTest } from '@/shared/utils/mountForTest'
import { defineComponent } from 'vue'

let camera: ReturnType<typeof useMediaPipe>
let view: ReturnType<typeof mountForTest> | undefined
let stopTrack: ReturnType<typeof vi.fn>
let getUserMedia: ReturnType<typeof vi.fn>
const values = { eyeContact: 0.8, smile: 0.3, headStability: 0.9, postureStability: 0.7 }
function setup(options: Parameters<typeof useMediaPipe>[0]) {
  view = mountForTest(
    defineComponent({
      setup() {
        camera = useMediaPipe(options)
        return () => null
      },
    }),
  )
  return camera
}
function analyzer(): PresenceAnalyzer {
  return {
    ready: vi.fn().mockResolvedValue(undefined),
    analyze: vi.fn().mockResolvedValue(values),
    close: vi.fn(),
  }
}
beforeEach(() => {
  vi.useFakeTimers()
  stopTrack = vi.fn()
  getUserMedia = vi.fn().mockResolvedValue({ getTracks: () => [{ stop: stopTrack }] })
  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia } })
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
  vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => {})
})
afterEach(() => {
  view?.unmount()
  view = undefined
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  vi.useRealTimers()
})
it('skips when disabled and degrades without camera support without loading wasm', async () => {
  const createAnalyzer = vi.fn()
  vi.stubEnv('VITE_ENABLE_CAMERA_ANALYSIS', 'false')
  setup({ createAnalyzer, upload: vi.fn() })
  await camera.start('one')
  expect(camera.status.value).toBe('skipped')
  expect(getUserMedia).not.toHaveBeenCalled()
  vi.stubEnv('VITE_ENABLE_CAMERA_ANALYSIS', 'true')
  vi.stubGlobal('navigator', {})
  await camera.start('one')
  expect(camera.status.value).toBe('unsupported')
  expect(createAnalyzer).not.toHaveBeenCalled()
})
it('permission rejection and absent camera skip automatically', async () => {
  const createAnalyzer = vi.fn()
  const upload = vi.fn()
  setup({ createAnalyzer, upload })
  for (const reason of ['NotAllowedError', 'NotFoundError']) {
    getUserMedia.mockRejectedValueOnce(new DOMException(reason, reason))
    await camera.start('one')
    expect(camera.status.value).toBe('skipped')
  }
  expect(createAnalyzer).not.toHaveBeenCalled()
  expect(upload).not.toHaveBeenCalled()
})
it('worker load failure releases acquired tracks; late permission cannot restart after unmount', async () => {
  const instance = analyzer()
  vi.mocked(instance.ready).mockRejectedValueOnce(new Error('wasm unavailable'))
  setup({ createAnalyzer: () => instance, upload: vi.fn() })
  await camera.start('one')
  expect(camera.status.value).toBe('skipped')
  expect(stopTrack).toHaveBeenCalledOnce()
  expect(instance.close).toHaveBeenCalledOnce()
  let acquire!: (value: unknown) => void
  getUserMedia.mockReturnValueOnce(
    new Promise((done) => {
      acquire = done
    }),
  )
  const starting = camera.start('one')
  await Promise.resolve()
  view!.unmount()
  view = undefined
  acquire({ getTracks: () => [{ stop: stopTrack }] })
  await starting
  expect(stopTrack).toHaveBeenCalledTimes(2)
  expect(camera.status.value).toBe('skipped')
})
it('buffers only numeric samples, uploads every 30 seconds and flushes remainder on unmount', async () => {
  const instance = analyzer()
  const upload = vi.fn().mockResolvedValue({})
  setup({ createAnalyzer: () => instance, upload })
  await camera.start('one')
  await vi.advanceTimersByTimeAsync(30000)
  expect(upload).toHaveBeenCalledOnce()
  const [id, samples] = upload.mock.calls[0]!
  expect(id).toBe('one')
  expect(samples.length).toBeGreaterThanOrEqual(59)
  expect(samples[0]).toEqual({ ...values, ts: expect.any(String) })
  await vi.advanceTimersByTimeAsync(1500)
  view!.unmount()
  view = undefined
  await Promise.resolve()
  expect(upload).toHaveBeenCalledTimes(2)
  expect(stopTrack).toHaveBeenCalledOnce()
  expect(instance.close).toHaveBeenCalledOnce()
})
it('retries failed numeric windows and keeps old session samples out of a new session', async () => {
  const upload = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue({})
  setup({ createAnalyzer: analyzer, upload })
  await camera.start('one')
  await vi.advanceTimersByTimeAsync(1000)
  camera.stop()
  await camera.flush()
  expect(camera.uploadFailed.value).toBe(true)
  expect(camera.buffered.value).toBeGreaterThan(0)
  await camera.flush()
  expect(camera.buffered.value).toBe(0)
  expect(upload.mock.calls[1]![1]).toEqual(upload.mock.calls[0]![1])
  await camera.start('two')
  await vi.advanceTimersByTimeAsync(500)
  camera.stop()
  await camera.flush()
  expect(upload.mock.calls[2]![0]).toBe('two')
})
it('does not sample concurrently and drops missing detections rather than fabricating scores', async () => {
  const instance = analyzer()
  let complete!: (value: null) => void
  vi.mocked(instance.analyze).mockReturnValueOnce(
    new Promise((done) => {
      complete = done
    }),
  )
  setup({ createAnalyzer: () => instance, upload: vi.fn() })
  await camera.start('one')
  await vi.advanceTimersByTimeAsync(2000)
  expect(instance.analyze).toHaveBeenCalledOnce()
  complete(null)
  await Promise.resolve()
  expect(camera.buffered.value).toBe(0)
  expect(camera.latest.value).toBeNull()
})
