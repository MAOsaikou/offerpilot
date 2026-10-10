import { effectScope } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { useSpeech } from './useSpeech'
import { installSpeechMocks } from '@/test/speech'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

it('degrades safely without recognition or synthesis', () => {
  vi.stubGlobal('SpeechRecognition', undefined)
  vi.stubGlobal('webkitSpeechRecognition', undefined)
  vi.stubGlobal('speechSynthesis', undefined)
  const speech = useSpeech()
  expect(speech.supported.value).toBe(false)
  speech.startListening()
  speech.speak('问题')
  speech.cancelAll()
  expect(speech.listening.value).toBe(false)
  expect(speech.error.value).toContain('Chrome / Edge')
  installSpeechMocks()
  vi.stubGlobal('speechSynthesis', undefined)
  expect(useSpeech().supported.value).toBe(false)
})

it('detects the vendor prefix, replaces interim subtitles and delivers finals once', () => {
  const mock = installSpeechMocks(true)
  const onFinal = vi.fn()
  const speech = useSpeech({ onFinal })
  expect(speech.supported.value).toBe(true)
  speech.startListening()
  expect(mock.latest()).toMatchObject({ lang: 'zh-CN', continuous: true, interimResults: true })
  mock.latest().result([{ text: '背景', final: false }])
  expect(speech.interimText.value).toBe('背景')
  mock.latest().result([
    { text: '背景与任务', final: true },
    { text: '行动', final: false },
  ])
  expect(onFinal).toHaveBeenCalledExactlyOnceWith('背景与任务')
  expect(speech.interimText.value).toBe('行动')
  mock.latest().result(
    [
      { text: '背景与任务', final: true },
      { text: '行动与结果', final: true },
    ],
    1,
  )
  expect(onFinal.mock.calls).toEqual([['背景与任务'], ['行动与结果']])
  expect(speech.interimText.value).toBe('')
  speech.cancelAll()
})

it('uses a Chinese female voice and pauses ASR until TTS ends', () => {
  const mock = installSpeechMocks()
  const female = { lang: 'zh-CN', name: 'Microsoft Xiaoxiao' } as SpeechSynthesisVoice
  mock.synthesis.getVoices.mockReturnValue([
    { lang: 'en-US', name: 'English' } as SpeechSynthesisVoice,
    female,
  ])
  const onFinal = vi.fn()
  const speech = useSpeech({ onFinal })
  speech.startListening()
  const previous = mock.latest()
  speech.speak('介绍一下你的项目')
  expect(previous.abort).toHaveBeenCalledOnce()
  expect(speech.listening.value).toBe(false)
  expect(speech.speaking.value).toBe(true)
  const message = mock.synthesis.speak.mock.calls[0]![0]
  expect(message).toMatchObject({ text: '介绍一下你的项目', lang: 'zh-CN', voice: female })
  previous.result([{ text: '扬声器回声', final: true }])
  expect(onFinal).not.toHaveBeenCalled()
  message.onend?.(new Event('end') as SpeechSynthesisEvent)
  expect(speech.speaking.value).toBe(false)
  expect(speech.listening.value).toBe(true)
  expect(mock.instances).toHaveLength(2)
  speech.cancelAll()
})

it('does not resume a cancelled listen request and ignores old TTS callbacks', async () => {
  const mock = installSpeechMocks()
  const speech = useSpeech()
  speech.startListening()
  speech.speak('第一题')
  const first = mock.synthesis.speak.mock.calls[0]![0]
  speech.speak('第二题')
  const second = mock.synthesis.speak.mock.calls[1]![0]
  first.onend?.(new Event('end') as SpeechSynthesisEvent)
  expect(speech.speaking.value).toBe(true)
  await speech.stopListening()
  second.onend?.(new Event('end') as SpeechSynthesisEvent)
  expect(mock.instances).toHaveLength(1)
  speech.cancelAll()
})

it('stops after three seconds of silence and bounds a missing end event', async () => {
  vi.useFakeTimers()
  const mock = installSpeechMocks()
  const speech = useSpeech()
  speech.startListening()
  mock.latest().onspeechstart?.()
  mock.latest().result([{ text: '长回答的中间字幕', final: false }])
  await vi.advanceTimersByTimeAsync(4000)
  expect(mock.latest().stop).not.toHaveBeenCalled()
  mock.latest().onspeechend?.()
  await vi.advanceTimersByTimeAsync(3000)
  expect(mock.latest().stop).toHaveBeenCalledOnce()
  expect(speech.listening.value).toBe(false)
  speech.startListening()
  mock.latest().stop.mockImplementation(() => {})
  const pending = speech.stopListening()
  await vi.advanceTimersByTimeAsync(1000)
  await pending
  expect(mock.latest().abort).toHaveBeenCalledOnce()
  speech.cancelAll()
  expect(vi.getTimerCount()).toBe(0)
})

it('cleans up the microphone, speech and timers with its scope', () => {
  vi.useFakeTimers()
  const mock = installSpeechMocks()
  const scope = effectScope()
  const speech = scope.run(() => useSpeech())!
  speech.startListening()
  scope.stop()
  expect(mock.latest().abort).toHaveBeenCalledOnce()
  expect(mock.synthesis.cancel).toHaveBeenCalledOnce()
  expect(vi.getTimerCount()).toBe(0)
  expect(speech.listenRequested.value).toBe(false)
})

it('handles recognition and synthesis failures without crashing or trapping listen mode', () => {
  const mock = installSpeechMocks()
  const speech = useSpeech()
  speech.startListening()
  mock.latest().onerror?.({ error: 'network' })
  expect(speech.listenRequested.value).toBe(false)
  expect(speech.error.value).toContain('连接失败')
  speech.startListening()
  mock.synthesis.speak.mockImplementation(() => {
    throw new Error('Speech service unavailable')
  })
  expect(() => speech.speak('题目')).not.toThrow()
  expect(speech.speaking.value).toBe(false)
  expect(speech.listening.value).toBe(true)
  expect(speech.error.value).toContain('朗读暂不可用')
  speech.cancelAll()
  vi.stubGlobal(
    'SpeechRecognition',
    class {
      constructor() {
        throw new DOMException('Denied', 'NotAllowedError')
      }
    },
  )
  const failed = useSpeech()
  expect(() => failed.startListening()).not.toThrow()
  expect(failed.canListen.value).toBe(false)
  expect(failed.error.value).toContain('权限被拒绝')
})
