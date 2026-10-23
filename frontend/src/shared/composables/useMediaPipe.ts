import { onBeforeUnmount, ref, shallowRef } from 'vue'
import type { PresenceSample, PresenceStatus, PresenceValues } from '@/shared/types/presence'

export interface PresenceAnalyzer {
  ready(): Promise<void>
  analyze(video: HTMLVideoElement): Promise<PresenceValues | null>
  close(): void
}
interface Options {
  enabled?: boolean
  createAnalyzer?: () => PresenceAnalyzer
  upload: (id: string, samples: PresenceSample[]) => Promise<unknown>
}

/** Frames travel only to our local Worker. Its only output is four numeric estimates. */
export function createWorkerAnalyzer(): PresenceAnalyzer {
  // Classic worker: MediaPipe's wasm loader uses importScripts.
  const worker = new Worker(new URL('../workers/mediapipe.worker.ts', import.meta.url))
  let resolve: ((value: PresenceValues | null) => void) | undefined
  let reject: ((error: Error) => void) | undefined
  let timeout: ReturnType<typeof setTimeout> | undefined
  function fail() {
    reject?.(new Error('Camera analysis unavailable'))
    resolve = reject = undefined
    clearTimeout(timeout)
  }
  worker.onerror = fail
  worker.onmessageerror = fail
  worker.onmessage = ({ data }) => {
    if (data.type === 'error') return fail()
    clearTimeout(timeout)
    resolve?.(data.values ?? null)
    resolve = reject = undefined
  }
  function request(message: object, transfer: Transferable[] = [], delay = 10000) {
    return new Promise<PresenceValues | null>((done, failed) => {
      resolve = done
      reject = failed
      timeout = setTimeout(fail, delay)
      try {
        worker.postMessage(message, transfer)
      } catch {
        fail()
      }
    })
  }
  return {
    async ready() {
      await request(
        { type: 'init', assetsUrl: import.meta.env.VITE_MEDIAPIPE_ASSETS_URL },
        [],
        45000,
      )
    },
    async analyze(video) {
      const bitmap = await createImageBitmap(video, { resizeWidth: 640, resizeHeight: 480 })
      try {
        return await request({ type: 'frame', bitmap, timestamp: performance.now() }, [bitmap])
      } finally {
        bitmap.close()
      }
    },
    close() {
      fail()
      worker.terminate()
    },
  }
}

export function useMediaPipe(options: Options) {
  const status = ref<PresenceStatus>('skipped')
  const starting = ref(false)
  const latest = shallowRef<PresenceValues | null>(null)
  const buffered = ref(0)
  const uploadFailed = ref(false)
  let samples: PresenceSample[] = []
  let sessionId = ''
  let revision = 0
  let stream: MediaStream | undefined
  let video: HTMLVideoElement | undefined
  let analyzer: PresenceAnalyzer | undefined
  let frameTimer: ReturnType<typeof setTimeout> | undefined
  let uploadTimer: ReturnType<typeof setInterval> | undefined
  let uploading: Promise<void> | undefined

  function stop() {
    revision++
    starting.value = false
    clearTimeout(frameTimer)
    clearInterval(uploadTimer)
    analyzer?.close()
    analyzer = undefined
    stream?.getTracks().forEach((track) => track.stop())
    stream = undefined
    if (video) {
      video.pause()
      video.srcObject = null
      video = undefined
    }
    if (status.value === 'running') status.value = 'skipped'
  }
  async function flush(): Promise<void> {
    if (uploading) {
      await uploading
      // Include frames collected while the previous batch was in flight.
      if (!uploadFailed.value && samples.length) return flush()
      return
    }
    if (!samples.length || !sessionId) return
    const id = sessionId
    const batch = samples.splice(0, 240)
    buffered.value = samples.length
    uploading = Promise.resolve().then(async () => {
      try {
        await options.upload(id, batch)
        uploadFailed.value = false
      } catch {
        uploadFailed.value = true
        if (sessionId === id) samples = [...batch, ...samples].slice(-240)
      } finally {
        buffered.value = samples.length
        uploading = undefined
      }
    })
    await uploading
  }
  async function start(id: string) {
    stop()
    status.value = 'skipped'
    const version = revision
    await flush()
    if (version !== revision) return
    sessionId = id
    samples = []
    buffered.value = 0
    latest.value = null
    uploadFailed.value = false
    if (!(options.enabled ?? import.meta.env.VITE_ENABLE_CAMERA_ANALYSIS !== 'false')) return
    if (!navigator.mediaDevices?.getUserMedia) {
      status.value = 'unsupported'
      return
    }
    if (
      !options.createAnalyzer &&
      (typeof Worker === 'undefined' ||
        typeof createImageBitmap === 'undefined' ||
        typeof OffscreenCanvas === 'undefined')
    ) {
      status.value = 'unsupported'
      return
    }
    starting.value = true
    try {
      const acquired = await navigator.mediaDevices.getUserMedia({
        video: { width: 640, height: 480 },
        audio: false,
      })
      if (version !== revision) {
        acquired.getTracks().forEach((track) => track.stop())
        return
      }
      stream = acquired
      video = document.createElement('video')
      video.muted = true
      video.playsInline = true
      video.srcObject = acquired
      // Initialize video and worker together; a failed worker always releases the camera.
      analyzer = (options.createAnalyzer ?? createWorkerAnalyzer)()
      await Promise.all([video.play(), analyzer.ready()])
      if (version !== revision) return
      starting.value = false
      status.value = 'running'
      async function tick() {
        if (version !== revision || !analyzer || !video) return
        try {
          const values = await analyzer.analyze(video)
          if (version !== revision) return
          if (!values) latest.value = null
          const safe = values && {
            eyeContact: values.eyeContact,
            smile: values.smile,
            headStability: values.headStability,
            postureStability: values.postureStability,
          }
          // Whitelist and validate scalars before buffering; no pixels or landmarks cross the API.
          if (safe && Object.values(safe).every((v) => Number.isFinite(v) && v >= 0 && v <= 1)) {
            latest.value = safe
            samples.push({ ts: new Date().toISOString(), ...safe })
            samples = samples.slice(-240)
            buffered.value = samples.length
          }
          frameTimer = setTimeout(() => void tick(), 500)
        } catch {
          if (version !== revision) return
          stop()
          status.value = 'error'
          void flush()
        }
      }
      uploadTimer = setInterval(() => void flush(), 30000)
      void tick()
    } catch {
      if (version !== revision) return
      stop()
      status.value = 'skipped'
    }
  }
  onBeforeUnmount(() => {
    stop()
    void flush()
  })
  return { status, starting, latest, buffered, uploadFailed, start, stop, flush }
}
