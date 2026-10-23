import type * as Vision from '@mediapipe/tasks-vision'
import { describe, expect, it, vi } from 'vitest'
import { initializeMediaPipe, resolveMediaPipeAssets, sdkVersion } from './mediapipeAssets'

const origin = 'http://localhost:5173'
const cdn = `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${sdkVersion}`
const googleModels = 'https://storage.googleapis.com/mediapipe-models'
function mockSdk() {
  const face = { close: vi.fn() }
  const pose = { close: vi.fn() }
  const wasm = { wasmLoaderPath: 'mock-loader', wasmBinaryPath: 'mock-wasm' }
  const sdk = {
    FilesetResolver: { forVisionTasks: vi.fn().mockResolvedValue(wasm) },
    FaceLandmarker: { createFromOptions: vi.fn().mockResolvedValue(face) },
    PoseLandmarker: { createFromOptions: vi.fn().mockResolvedValue(pose) },
  }
  const loadSdk = vi.fn().mockResolvedValue(sdk as unknown as typeof Vision)
  return { sdk, loadSdk, face, pose, wasm }
}

describe('MediaPipe asset paths', () => {
  it.each(['/mediapipe', '/mediapipe/', ' /mediapipe/// ', 'mediapipe'])(
    'resolves configured paths against the page origin: %s',
    (configured) => {
      const [local, fallback] = resolveMediaPipeAssets(configured, origin)
      expect(local).toEqual({
        base: `${origin}/mediapipe`,
        source: 'Configured assets',
        sdk: `${origin}/mediapipe/vision_bundle.mjs`,
        wasm: `${origin}/mediapipe/wasm`,
        faceModel: `${origin}/mediapipe/face_landmarker.task`,
        poseModel: `${origin}/mediapipe/pose_landmarker_lite.task`,
      })
      expect(fallback?.base).toBe(cdn)
    },
  )

  it('supports an absolute resource root with flat model files', () => {
    const [assets] = resolveMediaPipeAssets('https://assets.example.test/vision/', origin)
    expect(assets?.sdk).toBe('https://assets.example.test/vision/vision_bundle.mjs')
    expect(assets?.faceModel).toBe('https://assets.example.test/vision/face_landmarker.task')
    expect(assets?.poseModel).toBe('https://assets.example.test/vision/pose_landmarker_lite.task')
  })

  it.each([undefined, '', '   ', `${cdn}/`])('uses only CDN when appropriate: %s', (configured) => {
    expect(resolveMediaPipeAssets(configured, origin)).toEqual([
      {
        base: cdn,
        source: 'CDN assets',
        sdk: `${cdn}/vision_bundle.mjs`,
        wasm: `${cdn}/wasm`,
        faceModel: `${googleModels}/face_landmarker/face_landmarker/float16/1/face_landmarker.task`,
        poseModel: `${googleModels}/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task`,
      },
    ])
  })
})

describe('MediaPipe initialization without network or camera', () => {
  it('initializes entirely from configured assets when present', async () => {
    const { sdk, loadSdk, face, pose, wasm } = mockSdk()
    await expect(initializeMediaPipe('/mediapipe', origin, loadSdk)).resolves.toEqual({
      face,
      pose,
    })
    expect(loadSdk).toHaveBeenCalledExactlyOnceWith(`${origin}/mediapipe/vision_bundle.mjs`)
    expect(sdk.FilesetResolver.forVisionTasks).toHaveBeenCalledExactlyOnceWith(
      `${origin}/mediapipe/wasm`,
    )
    expect(sdk.FaceLandmarker.createFromOptions).toHaveBeenCalledWith(wasm, {
      baseOptions: { modelAssetPath: `${origin}/mediapipe/face_landmarker.task`, delegate: 'CPU' },
      runningMode: 'VIDEO',
      numFaces: 1,
      outputFaceBlendshapes: true,
    })
    expect(sdk.PoseLandmarker.createFromOptions).toHaveBeenCalledWith(wasm, {
      baseOptions: {
        modelAssetPath: `${origin}/mediapipe/pose_landmarker_lite.task`,
        delegate: 'CPU',
      },
      runningMode: 'VIDEO',
      numPoses: 1,
    })
  })

  it('falls back to CDN SDK/wasm and Google models when the local bundle is missing', async () => {
    const { sdk, loadSdk, face, pose } = mockSdk()
    loadSdk.mockRejectedValueOnce(new Error('local SDK 404'))
    await expect(initializeMediaPipe('/mediapipe', origin, loadSdk)).resolves.toEqual({
      face,
      pose,
    })
    expect(loadSdk.mock.calls).toEqual([
      [`${origin}/mediapipe/vision_bundle.mjs`],
      [`${cdn}/vision_bundle.mjs`],
    ])
    expect(sdk.FilesetResolver.forVisionTasks).toHaveBeenCalledExactlyOnceWith(`${cdn}/wasm`)
    expect(sdk.FaceLandmarker.createFromOptions.mock.calls[0]?.[1].baseOptions.modelAssetPath).toBe(
      `${googleModels}/face_landmarker/face_landmarker/float16/1/face_landmarker.task`,
    )
    expect(sdk.PoseLandmarker.createFromOptions.mock.calls[0]?.[1].baseOptions.modelAssetPath).toBe(
      `${googleModels}/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task`,
    )
  })

  it('falls back when local wasm is missing', async () => {
    const { sdk, loadSdk } = mockSdk()
    sdk.FilesetResolver.forVisionTasks.mockRejectedValueOnce(new Error('local wasm 404'))
    await initializeMediaPipe('/mediapipe', origin, loadSdk)
    expect(sdk.FilesetResolver.forVisionTasks.mock.calls).toEqual([
      [`${origin}/mediapipe/wasm`],
      [`${cdn}/wasm`],
    ])
  })

  it('closes a partially created local landmarker before retrying after a missing model', async () => {
    const { sdk, loadSdk, face, pose } = mockSdk()
    sdk.PoseLandmarker.createFromOptions.mockRejectedValueOnce(new Error('local pose model 404'))
    await expect(initializeMediaPipe('/mediapipe', origin, loadSdk)).resolves.toEqual({
      face,
      pose,
    })
    expect(face.close).toHaveBeenCalledOnce()
    expect(pose.close).not.toHaveBeenCalled()
    expect(sdk.FaceLandmarker.createFromOptions).toHaveBeenCalledTimes(2)
  })

  it('reports both configured and CDN failures when neither can initialize', async () => {
    const { loadSdk } = mockSdk()
    loadSdk
      .mockRejectedValueOnce(new Error('local SDK 404'))
      .mockRejectedValueOnce(new Error('offline'))
    await expect(initializeMediaPipe('/mediapipe', origin, loadSdk)).rejects.toThrow(
      `MediaPipe assets unavailable. Configured assets (${origin}/mediapipe) missing or failed to load: local SDK 404; CDN assets (${cdn}) failed: offline`,
    )
    expect(loadSdk).toHaveBeenCalledTimes(2)
  })

  it('reports CDN-only failure when no local root is configured', async () => {
    const { loadSdk } = mockSdk()
    loadSdk.mockRejectedValue(new Error('offline'))
    await expect(initializeMediaPipe(undefined, origin, loadSdk)).rejects.toThrow(
      `MediaPipe assets unavailable. CDN assets (${cdn}) failed: offline`,
    )
    expect(loadSdk).toHaveBeenCalledOnce()
  })
})
