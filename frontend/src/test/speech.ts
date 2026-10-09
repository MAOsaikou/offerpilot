import { vi } from 'vitest'
import type { BrowserSpeechRecognition, SpeechResultEvent } from '@/shared/composables/useSpeech'

export function installSpeechMocks(prefix = false) {
  const instances: Recognition[] = []
  class Recognition implements BrowserSpeechRecognition {
    lang = ''
    continuous = false
    interimResults = false
    onstart: BrowserSpeechRecognition['onstart'] = null
    onend: BrowserSpeechRecognition['onend'] = null
    onspeechstart: BrowserSpeechRecognition['onspeechstart'] = null
    onspeechend: BrowserSpeechRecognition['onspeechend'] = null
    onresult: BrowserSpeechRecognition['onresult'] = null
    onerror: BrowserSpeechRecognition['onerror'] = null
    start = vi.fn(() => this.onstart?.())
    stop = vi.fn(() => this.onend?.())
    abort = vi.fn(() => this.onend?.())
    constructor() {
      instances.push(this)
    }
    result(parts: { text: string; final: boolean }[], resultIndex = 0) {
      const event: SpeechResultEvent = {
        resultIndex,
        results: parts.map(({ text, final }) => ({ isFinal: final, 0: { transcript: text } })),
      }
      this.onresult?.(event)
    }
  }
  class Utterance {
    lang = ''
    voice: SpeechSynthesisVoice | null = null
    onend: (() => void) | null = null
    onerror: (() => void) | null = null
    text: string
    constructor(text: string) {
      this.text = text
    }
  }
  const synthesis = {
    getVoices: vi.fn<() => SpeechSynthesisVoice[]>(() => []),
    speak: vi.fn<(message: SpeechSynthesisUtterance) => void>(),
    cancel: vi.fn(),
  }
  vi.stubGlobal('SpeechRecognition', prefix ? undefined : Recognition)
  vi.stubGlobal('webkitSpeechRecognition', prefix ? Recognition : undefined)
  vi.stubGlobal('SpeechSynthesisUtterance', Utterance)
  vi.stubGlobal('speechSynthesis', synthesis)
  return { instances, synthesis, latest: () => instances.at(-1)! }
}
