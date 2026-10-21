import { localizedText, language, translate as uiText } from '@/shared/utils/language'
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
  const recognitionSupported = computed(() => Boolean(Recognition))
  const synthesisSupported = computed(() => Boolean(synthesis && Utterance))
  const unsupportedReason = computed(() =>
    [
      !recognitionSupported.value &&
        uiText("This browser does not support speech input. Try Chrome / Edge or continue with text."),
      !synthesisSupported.value && uiText("This browser does not support reading questions aloud. Read the question directly."),
    ]
      .filter(Boolean)
      .join(' '),
  )
  const listening = ref(false)
  const listenRequested = ref(false)
  const speaking = ref(false)
  const interimText = ref('')
  const error = localizedText()
  const permissionDenied = ref(false)
  const finishing = ref(false)
  const canListen = computed(() => recognitionSupported.value && !permissionDenied.value)
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
      'not-allowed': uiText("Microphone permission denied. Allow it in browser settings and refresh, or continue with text."),
      'service-not-allowed': uiText("Speech recognition is not allowed by the browser. You can continue with text."),
      'audio-capture': uiText("No microphone found. Check your device or continue with text."),
      'no-speech': uiText("No speech detected. Start dictation again or answer with text."),
      network: uiText("Speech recognition connection failed. Retry or continue with text."),
      aborted: uiText("Dictation interrupted. Restart or continue with text."),
    }
    error.value = () => (messages[code] ?? uiText("Speech recognition is unavailable. Retry or continue with text."))
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
      active.lang = options.lang ?? language.value
      active.continuous = options.continuous ?? true
      active.interimResults = options.interimResults ?? true
      const isActive = () => recognition === active
      const delivered = new Set<number>()
      let speechActive = false
      active.onstart = () => {
        if (!isActive()) return
        listening.value = !stopping
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
    finishing.value = true
    listening.value = false
    const timeout = setTimeout(abortRecognition, 1000)
    finishStop = () => {
      clearTimeout(timeout)
      stopping = undefined
      finishing.value = false
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
    if (finishing.value) return
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
    if (!synthesisSupported.value || !synthesis || !Utterance) {
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
      message.lang = options.lang ?? language.value
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
        error.value = () => (uiText("Reading aloud is unavailable. Read the question directly."))
      }
      speaking.value = true
      synthesis.speak(message)
    } catch {
      stopSpeaking()
      error.value = () => (uiText("Reading aloud is unavailable. Read the question directly."))
    }
  }

  function cancelAll() {
    listenRequested.value = false
    abortRecognition()
    stopSpeaking(false)
  }
  if (getCurrentScope()) onScopeDispose(cancelAll)

  return {
    recognitionSupported,
    synthesisSupported,
    unsupportedReason,
    finishing,
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
