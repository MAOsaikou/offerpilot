// Keep runtime imports dynamic: Vite dev serves this as a classic Worker for importScripts.
const scope = globalThis as unknown as {
  location: Location
  postMessage(data: unknown): void
  onmessage: (event: MessageEvent) => void
}
let face: import('@mediapipe/tasks-vision').FaceLandmarker | undefined
let pose: import('@mediapipe/tasks-vision').PoseLandmarker | undefined
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
  const { initializeMediaPipe } = await import('./mediapipeAssets')
  const initialized = await initializeMediaPipe(assetsUrl, scope.location.origin)
  face = initialized.face
  pose = initialized.pose
}
function analyze(
  bitmap: ImageBitmap,
  timestamp: number,
): import('../types/presence').PresenceValues | null {
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
  } catch (error) {
    scope.postMessage({
      type: 'error',
      message: error instanceof Error ? error.message : 'MediaPipe analysis failed',
    })
  }
}
