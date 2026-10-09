import { computed, getCurrentScope, onScopeDispose, ref } from 'vue'

// Recognition is not in lib.dom; keep the vendor API boundary narrow.
export interface SpeechResultEvent {
  resultIndex: number
  results: ArrayLike<{ isFinal: boolean; [index: number]: { transcript: string } }>
}
export interface BrowserSpeechRecognition {
  lang: string
  continuous: boolean
  interimResults: boolean
  onstart: (() => void) | null
  onend: (() => void) | null
  onspeechstart: (() => void) | null
  onspeechend: (() => void) | null
  onresult: ((event: SpeechResultEvent) => void) | null
  onerror: ((event: { error: string }) => void) | null
  start(): void
  stop(): void
  abort(): void
}
type SpeechWindow = Window & {
  SpeechRecognition?: new () => BrowserSpeechRecognition
  webkitSpeechRecognition?: new () => BrowserSpeechRecognition
  SpeechSynthesisUtterance?: typeof SpeechSynthesisUtterance
}

export function useSpeech(
  options: {
    lang?: string
    continuous?: boolean
    interimResults?: boolean
    silenceMs?: number
    onFinal?: (text: string) => void
  } = {},
) {
  const browser = typeof window === 'undefined' ? undefined : (window as SpeechWindow)
  const Recognition = browser?.SpeechRecognition ?? browser?.webkitSpeechRecognition
  const synthesis = browser?.speechSynthesis
  const Utterance = browser?.SpeechSynthesisUtterance
  const supported = computed(() =>
    Boolean(Recognition && synthesis && browser?.SpeechSynthesisUtterance),
  )
  const unsupportedReason = computed(() =>
    supported.value ? '' : '当前浏览器不支持语音，请使用 Chrome / Edge；也可以继续文字作答。',
  )
  const listening = ref(false)
  const listenRequested = ref(false)
  const speaking = ref(false)
  const interimText = ref('')
  const error = ref('')
  const permissionDenied = ref(false)
  const canListen = computed(() => supported.value && !permissionDenied.value)
  let recognition: BrowserSpeechRecognition | undefined
  let utterance: SpeechSynthesisUtterance | undefined
  let silenceTimer: ReturnType<typeof setTimeout> | undefined
  let finishStop: (() => void) | undefined
  let stopping: Promise<void> | undefined

  function clearSilence() {
    clearTimeout(silenceTimer)
    silenceTimer = undefined
  }

  function abortRecognition() {
    const active = recognition
    recognition = undefined // Ignore callbacks from an old question or our own abort.
    listening.value = false
    interimText.value = ''
    clearSilence()
    finishStop?.()
    try {
      active?.abort()
    } catch {
      /* Already ended. */
    }
  }

  function recognitionError(code: string) {
    permissionDenied.value = code === 'not-allowed' || code === 'service-not-allowed'
    const messages: Record<string, string> = {
      'not-allowed': '麦克风权限被拒绝，请在浏览器设置中允许后刷新；可以继续文字作答。',
      'service-not-allowed': '浏览器未允许语音识别，可以继续文字作答。',
      'audio-capture': '未找到可用麦克风，请检查设备；可以继续文字作答。',
      'no-speech': '没有听到声音，请重新开始口述或文字作答。',
      network: '语音识别连接失败，请重试或继续文字作答。',
      aborted: '口述已中断，可以重新开始或继续文字作答。',
    }
    error.value = messages[code] ?? '语音识别暂不可用，请重试或继续文字作答。'
    listenRequested.value = false
    abortRecognition()
  }

  function armSilence() {
    clearSilence()
    silenceTimer = setTimeout(() => {
      void stopListening()
    }, options.silenceMs ?? 3000)
  }

  function startListening() {
    if (!canListen.value || !Recognition) {
      error.value = unsupportedReason.value || error.value
      return
    }
    listenRequested.value = true
    if (speaking.value || recognition || stopping) return
    error.value = ''
    interimText.value = ''
    try {
      const active = new Recognition()
      recognition = active
      active.lang = options.lang ?? 'zh-CN'
      active.continuous = options.continuous ?? true
      active.interimResults = options.interimResults ?? true
      const isActive = () => recognition === active
      const delivered = new Set<number>()
      let speechActive = false
      active.onstart = () => {
        if (!isActive()) return
        listening.value = true
        if (!stopping) armSilence()
      }
      active.onspeechstart = () => {
        if (!isActive()) return
        speechActive = true
        clearSilence()
      }
      active.onspeechend = () => {
        speechActive = false
        if (isActive() && !stopping) armSilence()
      }
      active.onresult = (event) => {
        if (!isActive()) return
        const interim: string[] = []
        for (let index = 0; index < event.results.length; index++) {
          const result = event.results[index]!
          const text = result[0]?.transcript.trim() ?? ''
          if (!result.isFinal) interim.push(text)
          else if (index >= event.resultIndex && !delivered.has(index)) {
            delivered.add(index)
            if (text) options.onFinal?.(text)
            if (!isActive()) return
          }
        }
        interimText.value = interim.join('')
        if (!stopping && !speechActive) armSilence()
      }
      active.onerror = (event) => {
        if (isActive()) recognitionError(event.error)
      }
      active.onend = () => {
        if (!isActive()) return
        recognition = undefined
        listening.value = false
        listenRequested.value = false
        interimText.value = ''
        clearSilence()
        finishStop?.()
      }
      active.start()
    } catch (cause) {
      recognitionError(
        cause instanceof DOMException && cause.name === 'NotAllowedError'
          ? 'not-allowed'
          : 'unavailable',
      )
    }
  }

  // stop() can deliver one last final result. Save/navigation waits for end,
  // with a bounded fallback for browsers that never send it.
  function stopListening(): Promise<void> {
    listenRequested.value = false
    clearSilence()
    if (stopping) return stopping
    const active = recognition
    if (!active) return Promise.resolve()
    let resolve!: () => void
    stopping = new Promise<void>((done) => {
      resolve = done
    })
    const pending = stopping
    const timeout = setTimeout(abortRecognition, 1000)
    finishStop = () => {
      clearTimeout(timeout)
      stopping = undefined
      finishStop = undefined
      resolve()
    }
    try {
      active.stop()
    } catch {
      abortRecognition()
    }
    return pending
  }

  function toggleListening() {
    if (listenRequested.value || listening.value) void stopListening()
    else startListening()
  }

  function stopSpeaking(resume = true) {
    utterance = undefined
    speaking.value = false
    try {
      synthesis?.cancel()
    } catch {
      /* Browser speech service unavailable. */
    }
    if (resume && listenRequested.value) startListening()
  }

  function speak(text: string) {
    if (!supported.value || !synthesis || !Utterance) {
      error.value = unsupportedReason.value
      return
    }
    if (!text.trim()) return
    // Preserve listen intent while aborting recognition before speaker audio.
    abortRecognition()
    stopSpeaking(false)
    if (canListen.value) error.value = ''
    try {
      const message = new Utterance(text)
      utterance = message
      message.lang = options.lang ?? 'zh-CN'
      const voices = synthesis.getVoices()
      const matching = voices.filter(
        (voice) => voice.lang.toLowerCase().replace('_', '-') === message.lang.toLowerCase(),
      )
      // Web Speech has no gender field; these are common Chinese female names.
      message.voice =
        matching.find((voice) =>
          /female|女|xiaoxiao|xiaoyi|ting.?ting|huihui|meijia|siri/i.test(voice.name),
        ) ??
        matching[0] ??
        null
      const finish = () => {
        if (utterance !== message) return
        utterance = undefined
        speaking.value = false
        if (listenRequested.value) startListening()
      }
      message.onend = finish
      message.onerror = () => {
        if (utterance !== message) return
        finish()
        error.value = '朗读暂不可用，请直接阅读题目。'
      }
      speaking.value = true
      synthesis.speak(message)
    } catch {
      stopSpeaking()
      error.value = '朗读暂不可用，请直接阅读题目。'
    }
  }

  function cancelAll() {
    listenRequested.value = false
    abortRecognition()
    stopSpeaking(false)
  }
  if (getCurrentScope()) onScopeDispose(cancelAll)

  return {
    supported,
    unsupportedReason,
    canListen,
    listening,
    listenRequested,
    speaking,
    interimText,
    error,
    startListening,
    stopListening,
    toggleListening,
    speak,
    stopSpeaking,
    cancelAll,
  }
}
