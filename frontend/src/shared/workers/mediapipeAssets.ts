import type * as Vision from '@mediapipe/tasks-vision'

export const sdkVersion = '0.10.32'
const cdn = `https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${sdkVersion}`
const models = 'https://storage.googleapis.com/mediapipe-models'
type LoadSdk = (url: string) => Promise<typeof Vision>

export function resolveMediaPipeAssets(assetsUrl: string | undefined, origin: string) {
  const configured = assetsUrl?.trim()
  const custom = configured
    ? new URL(configured.replace(/\/+$/, '') + '/', origin).href.replace(/\/$/, '')
    : undefined
  return (custom && custom !== cdn ? [custom, cdn] : [cdn]).map((base) => ({
    base,
    source: base === cdn ? 'CDN assets' : 'Configured assets',
    sdk: `${base}/vision_bundle.mjs`,
    wasm: `${base}/wasm`,
    faceModel:
      base === cdn
        ? `${models}/face_landmarker/face_landmarker/float16/1/face_landmarker.task`
        : `${base}/face_landmarker.task`,
    poseModel:
      base === cdn
        ? `${models}/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task`
        : `${base}/pose_landmarker_lite.task`,
  }))
}

export async function initializeMediaPipe(
  assetsUrl: string | undefined,
  origin: string,
  loadSdk: LoadSdk = (url) => import(/* @vite-ignore */ url),
) {
  const failures: string[] = []
  for (const assets of resolveMediaPipeAssets(assetsUrl, origin)) {
    let face: Vision.FaceLandmarker | undefined
    let pose: Vision.PoseLandmarker | undefined
    try {
      const { FilesetResolver, FaceLandmarker, PoseLandmarker } = await loadSdk(assets.sdk)
      const wasm = await FilesetResolver.forVisionTasks(assets.wasm)
      face = await FaceLandmarker.createFromOptions(wasm, {
        baseOptions: { modelAssetPath: assets.faceModel, delegate: 'CPU' },
        runningMode: 'VIDEO',
        numFaces: 1,
        outputFaceBlendshapes: true,
      })
      pose = await PoseLandmarker.createFromOptions(wasm, {
        baseOptions: { modelAssetPath: assets.poseModel, delegate: 'CPU' },
        runningMode: 'VIDEO',
        numPoses: 1,
      })
      return { face, pose }
    } catch (error) {
      face?.close()
      pose?.close()
      const reason = error instanceof Error ? error.message : String(error)
      const hint = assets.source === 'Configured assets' ? 'missing or failed to load' : 'failed'
      failures.push(`${assets.source} (${assets.base}) ${hint}: ${reason}`)
    }
  }
  throw new Error(`MediaPipe assets unavailable. ${failures.join('; ')}`)
}
