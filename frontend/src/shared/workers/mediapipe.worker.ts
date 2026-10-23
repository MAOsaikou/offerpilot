import type * as Vision from '@mediapipe/tasks-vision'
import type { PresenceValues } from '../types/presence'

// Type-only imports leave a classic script, so the SDK's wasm importScripts loader works.
const scope = globalThis as unknown as {
  location: Location
  postMessage(data: unknown): void
  onmessage: (event: MessageEvent) => void
}
const sdkVersion = '0.10.32'
const cdn = `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${sdkVersion}`
let face: Vision.FaceLandmarker | undefined
let pose: Vision.PoseLandmarker | undefined
let previousHead: number[] | undefined
let previousShoulders: number[] | undefined
const clamp = (n: number) => Math.min(1, Math.max(0, n))
function stability(current: number[], previous: number[] | undefined) {
  if (!previous) return null
  const displacement =
    current.reduce((sum, n, i) => sum + (n - previous[i]!) ** 2, 0) / current.length
  return clamp(1 - Math.sqrt(displacement) * 12)
}
async function initialize(assetsUrl?: string) {
  const custom = assetsUrl
    ? new URL(assetsUrl.replace(/\/$/, '') + '/', scope.location.origin).href.replace(/\/$/, '')
    : undefined
  for (const base of custom ? [custom, cdn] : [cdn]) {
    try {
      const sdk: typeof Vision = await import(/* @vite-ignore */ `${base}/vision_bundle.mjs`)
      const { FilesetResolver, FaceLandmarker, PoseLandmarker } = sdk
      const wasm = await FilesetResolver.forVisionTasks(`${base}/wasm`)
      const models = base === cdn ? 'https://storage.googleapis.com/mediapipe-models' : base
      face = await FaceLandmarker.createFromOptions(wasm, {
        baseOptions: {
          modelAssetPath:
            base === cdn
              ? `${models}/face_landmarker/face_landmarker/float16/1/face_landmarker.task`
              : `${base}/face_landmarker.task`,
          delegate: 'CPU',
        },
        runningMode: 'VIDEO',
        numFaces: 1,
        outputFaceBlendshapes: true,
      })
      pose = await PoseLandmarker.createFromOptions(wasm, {
        baseOptions: {
          modelAssetPath:
            base === cdn
              ? `${models}/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task`
              : `${base}/pose_landmarker_lite.task`,
          delegate: 'CPU',
        },
        runningMode: 'VIDEO',
        numPoses: 1,
      })
      return
    } catch {
      face?.close()
      pose?.close()
      face = pose = undefined
    }
  }
  throw new Error('MediaPipe assets unavailable')
}
function analyze(bitmap: ImageBitmap, timestamp: number): PresenceValues | null {
  const detected = face!.detectForVideo(bitmap, timestamp)
  const body = pose!.detectForVideo(bitmap, timestamp)
  const points = detected.faceLandmarks[0]
  const shoulders = body.landmarks[0]
  if (
    !points ||
    !shoulders ||
    points.length < 478 ||
    (shoulders[11]!.visibility ?? 0) < 0.5 ||
    (shoulders[12]!.visibility ?? 0) < 0.5
  ) {
    previousHead = previousShoulders = undefined
    return null
  }
  // Iris centers relative to eye corners; this is a heuristic, not calibrated gaze tracking.
  const eye = (iris: number, left: number, right: number) => {
    const a = points[left]!,
      b = points[right]!,
      p = points[iris]!
    const dx = b.x - a.x,
      dy = b.y - a.y
    const projection = ((p.x - a.x) * dx + (p.y - a.y) * dy) / Math.max(0.00001, dx * dx + dy * dy)
    return clamp(1 - Math.abs(projection - 0.5) * 4)
  }
  const head = [
    points[1]!.x,
    points[1]!.y,
    points[33]!.x - points[263]!.x,
    points[33]!.y - points[263]!.y,
  ]
  const shoulderValues = [shoulders[11]!.x, shoulders[11]!.y, shoulders[12]!.x, shoulders[12]!.y]
  const headStability = stability(head, previousHead)
  const postureStability = stability(shoulderValues, previousShoulders)
  previousHead = head
  previousShoulders = shoulderValues
  // A baseline alone is not evidence of stability.
  if (headStability === null || postureStability === null) return null
  const shapes = detected.faceBlendshapes[0]?.categories ?? []
  const smile = (name: string) => shapes.find((shape) => shape.categoryName === name)?.score ?? 0
  return {
    eyeContact: (eye(468, 33, 133) + eye(473, 362, 263)) / 2,
    smile: clamp((smile('mouthSmileLeft') + smile('mouthSmileRight')) / 2),
    headStability,
    postureStability,
  }
}
scope.onmessage = async ({ data }) => {
  try {
    if (data.type === 'init') {
      await initialize(data.assetsUrl)
      scope.postMessage({ type: 'ready' })
    } else if (data.type === 'frame') {
      try {
        scope.postMessage({ type: 'result', values: analyze(data.bitmap, data.timestamp) })
      } finally {
        data.bitmap.close()
      }
    }
  } catch {
    scope.postMessage({ type: 'error' })
  }
}
