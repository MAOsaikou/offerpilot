<script setup lang="ts">
import {
  formatDisplay,
  localizedText,
  translateLabel,
  translate as uiText,
} from '@/shared/utils/language'

import BackgroundTaskStatus from '@/shared/components/BackgroundTaskStatus.vue'

import PresenceGauges from '../components/PresenceGauges.vue'
import { useMediaPipe } from '@/shared/composables/useMediaPipe'
import InterviewFeedback from '../components/InterviewFeedback.vue'
import { questionTypeLabels, difficultyLabels } from '@/shared/types/interview'
import InterviewReportPanel from '../components/InterviewReportPanel.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, RouterLink, useRoute } from 'vue-router'
import { useQueryClient } from '@tanstack/vue-query'
import InterviewJobContext from '@/shared/components/InterviewJobContext.vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import {
  getInterview,
  postInterviewMetrics,
  generateInterviewQuestions,
  scoreInterviewAnswer,
  requestInterviewFollowUp,
  saveInterviewAnswer,
  saveInterviewFollowUpAnswer,
  updateInterview,
} from '@/shared/api/interviews'
import {
  listStarStoryCandidates,
  saveStarStory,
  type StarStoryCandidate,
} from '@/shared/api/starStories'
import { ApiError } from '@/shared/api/client'
import type { InterviewSession } from '@/shared/types/interview'
import { exportInterview } from '@/shared/utils/interviewExport'
import { useSpeech } from '@/shared/composables/useSpeech'

const camera = useMediaPipe({ upload: postInterviewMetrics })
const {
  status: presenceStatus,
  starting: presenceStarting,
  latest: presenceValues,
  uploadFailed: presenceUploadFailed,
} = camera
const route = useRoute()
const cache = useQueryClient()
const session = ref<InterviewSession>()
const answer = ref('')
const followUpDraft = ref('')
const askingFollowUp = ref(false)
const loading = ref(true)
const loadError = ref('')
const notFound = ref(false)
const error = localizedText()
const busy = ref(false)
const generating = ref(false)
const scoring = ref(false)
const reportRequested = ref(false)
const revealed = ref(false)
const redesigned = computed(() => Boolean(session.value?.generationSource))
const questionStarted = ref(Date.now())
const questionSeconds = computed(() =>
  Math.max(0, Math.floor((now.value - questionStarted.value) / 1000)),
)
watch(
  () => session.value?.questionIndex,
  () => {
    questionStarted.value = Date.now()
    revealed.value = false
  },
)
async function generateQuestions() {
  if (!session.value || generating.value) return
  generating.value = true
  error.value = ''
  const version = revision
  try {
    await generateInterviewQuestions(session.value.id, controller.signal)
    const saved = await getInterview(String(route.params.id), controller.signal)
    if (version === revision) {
      session.value = saved
      syncDrafts()
    }
  } catch (cause) {
    if (version === revision) error.value = (cause as Error).message
  } finally {
    if (version === revision) generating.value = false
  }
}
async function scoreCurrent() {
  if (!session.value || scoring.value || !(await persist())) return
  scoring.value = true
  error.value = ''
  const version = revision
  try {
    await scoreInterviewAnswer(session.value.id, session.value.questionIndex, controller.signal)
    const saved = await getInterview(String(route.params.id), controller.signal)
    if (version === revision) {
      session.value = saved
      syncDrafts()
      revealed.value = true
    }
  } catch (cause) {
    if (version === revision) error.value = (cause as Error).message
  } finally {
    if (version === revision) scoring.value = false
  }
}
async function finish() {
  reportRequested.value = redesigned.value
  if (!(await persist(undefined, true))) reportRequested.value = false
}
const starCandidates = ref<StarStoryCandidate[]>([])
const starBusy = ref(false)
const starMessage = localizedText()
const starLoading = ref(false)
const starError = ref('')
const exportBusy = ref(false)
const exportError = ref('')
const current = computed(() =>
  session.value?.answers.find((a) => a.questionIndex === session.value?.questionIndex),
)
watch(
  () => current.value?.evaluation,
  (evaluation) => {
    if (evaluation) revealed.value = true
  },
)
const hasFollowUp = computed(() => Boolean(current.value?.followUpQuestion))
const followUpDirty = computed(
  () => hasFollowUp.value && followUpDraft.value !== (current.value?.followUpAnswer ?? ''),
)
const dirty = computed(
  () =>
    Boolean(session.value) && (answer.value !== current.value?.answerText || followUpDirty.value),
)
function syncDrafts() {
  answer.value = current.value?.answerText || ''
  followUpDraft.value = current.value?.followUpAnswer || ''
}
const now = ref(Date.now())
const timer = setInterval(() => {
  now.value = Date.now()
}, 1000)
onBeforeUnmount(() => clearInterval(timer))
const remainingSeconds = computed(() => {
  if (!session.value?.options) return null
  return Math.max(
    0,
    Math.ceil(
      (Date.parse(session.value.createdAt) +
        session.value.options.durationMinutes * 60000 -
        now.value) /
        1000,
    ),
  )
})
const timerLabel = computed(() =>
  remainingSeconds.value === null
    ? ''
    : remainingSeconds.value === 0
      ? uiText('Interview time is up. Save your answers and complete the review.')
      : uiText('{0} min {1} sec remaining', [
          Math.floor(remainingSeconds.value / 60),
          remainingSeconds.value % 60,
        ]),
)
const voiceAutoPlay = ref(false)
try {
  voiceAutoPlay.value = localStorage.getItem('offerpilot.voiceAutoPlay') === 'true'
} catch {
  /* Text entry works with storage disabled. */
}
watch(voiceAutoPlay, (enabled) => {
  try {
    localStorage.setItem('offerpilot.voiceAutoPlay', String(enabled))
  } catch {
    /* Session preference only. */
  }
})
const speech = useSpeech({
  onFinal(text) {
    if (!session.value || session.value.completed || loading.value) return
    // Once the interviewer has followed up, dictation answers the follow-up.
    const target = hasFollowUp.value ? followUpDraft : answer
    const separator = target.value && !/\s$/.test(target.value) ? '\n' : ''
    let addition = ''
    for (const character of separator + text) {
      if (target.value.length + addition.length + character.length > 12000) break
      addition += character
    }
    target.value += addition
    if (target.value.length >= 12000 || addition.length < separator.length + text.length)
      void speech.stopListening()
  },
})
const {
  recognitionSupported,
  synthesisSupported,
  finishing,
  unsupportedReason,
  canListen,
  listening,
  listenRequested,
  speaking,
  interimText,
  error: speechError,
} = speech
watch(
  () =>
    session.value && !session.value.completed
      ? `${session.value.id}:${session.value.questionIndex}:${current.value?.questionText}:${current.value?.followUpQuestion ?? ''}`
      : '',
  (question, previous) => {
    speech.cancelAll()
    if (!question || !voiceAutoPlay.value || !synthesisSupported.value) return
    // A newly asked follow-up is read on its own; a new question reads the main question.
    const followUpOnly = previous && question.startsWith(previous) && hasFollowUp.value
    speech.speak(
      followUpOnly ? (current.value?.followUpQuestion ?? '') : (current.value?.questionText ?? ''),
    )
  },
)
watch(voiceAutoPlay, (enabled) => {
  if (!enabled) speech.stopSpeaking()
})
let controller = new AbortController()
let revision = 0

async function load() {
  camera.stop()
  speech.cancelAll()
  controller.abort()
  controller = new AbortController()
  const requestRevision = ++revision
  loading.value = true
  generating.value = false
  scoring.value = false
  reportRequested.value = false
  revealed.value = false
  starCandidates.value = []
  starMessage.value = ''
  starError.value = ''
  starLoading.value = false
  starBusy.value = false
  exportBusy.value = false
  exportError.value = ''
  loadError.value = ''
  notFound.value = false
  session.value = undefined
  error.value = ''
  try {
    const loaded = await getInterview(String(route.params.id), controller.signal)
    if (requestRevision !== revision) return
    session.value = loaded
    if (loaded.options?.mode === 'VOICE') voiceAutoPlay.value = true
    syncDrafts()
    if (loaded.completed) void loadCandidates()
    else void camera.start(loaded.id)
  } catch (e) {
    if (requestRevision !== revision) return
    notFound.value = e instanceof ApiError && e.status === 404
    loadError.value = (e as Error).message
  } finally {
    if (requestRevision === revision) loading.value = false
  }
}
watch(() => route.params.id, load, { immediate: true })
onBeforeUnmount(() => {
  speech.cancelAll()
  revision++
  controller.abort()
})

async function loadCandidates() {
  if (!session.value || starLoading.value) return
  const requestRevision = revision
  const id = session.value.id
  starLoading.value = true
  starError.value = ''
  try {
    const items = await listStarStoryCandidates(id, controller.signal)
    if (requestRevision === revision) starCandidates.value = items
  } catch (e) {
    if (requestRevision === revision) starError.value = (e as Error).message
  } finally {
    if (requestRevision === revision) starLoading.value = false
  }
}

watch(
  () => session.value?.completed,
  (completed) => {
    if (completed) void loadCandidates()
  },
)

async function downloadRecap() {
  if (!session.value || exportBusy.value || dirty.value) return
  const requestRevision = revision
  exportBusy.value = true
  exportError.value = ''
  try {
    await exportInterview(
      session.value.role,
      session.value.answers,
      session.value.id,
      controller.signal,
    )
  } catch (e) {
    if (requestRevision === revision) exportError.value = (e as Error).message
  } finally {
    if (requestRevision === revision) exportBusy.value = false
  }
}

async function persist(questionIndex?: number, completed?: boolean): Promise<boolean> {
  if (!session.value || busy.value) return false
  busy.value = true
  error.value = ''
  const requestRevision = revision
  const id = session.value.id
  try {
    speech.stopSpeaking(false)
    await speech.stopListening()
    if (requestRevision !== revision) return false
    const pendingFollowUp = followUpDirty.value ? followUpDraft.value : undefined
    if (answer.value !== current.value?.answerText) {
      const saved = await saveInterviewAnswer(
        id,
        session.value.questionIndex,
        answer.value,
        controller.signal,
      )
      if (requestRevision !== revision) return false
      session.value = saved
      answer.value = current.value?.answerText || ''
    }
    if (pendingFollowUp !== undefined && current.value?.followUpQuestion) {
      const saved = await saveInterviewFollowUpAnswer(
        id,
        session.value.questionIndex,
        pendingFollowUp,
        controller.signal,
      )
      if (requestRevision !== revision) return false
      session.value = saved
    }
    followUpDraft.value = current.value?.followUpAnswer || ''
    if (completed) {
      camera.stop()
      await camera.flush()
      if (requestRevision !== revision) return false
    }
    if (questionIndex !== undefined || completed !== undefined) {
      const progress: { questionIndex?: number; completed?: boolean } = {}
      if (questionIndex !== undefined) progress.questionIndex = questionIndex
      if (completed !== undefined) progress.completed = completed
      const saved = await updateInterview(id, progress, controller.signal)
      if (requestRevision !== revision) return false
      session.value = saved
      syncDrafts()
    }
    void cache.invalidateQueries({ queryKey: ['interviews'] })
    return true
  } catch (e) {
    if (requestRevision === revision) error.value = (e as Error).message
    return false
  } finally {
    if (requestRevision === revision) busy.value = false
  }
}

async function advance() {
  if (!session.value) return
  if (session.value.questionIndex === session.value.answers.length - 1) await finish()
  else await persist(session.value.questionIndex + 1)
}

/** IV-02: first submit saves the answer and lets the interviewer follow up; the second moves on. */
async function submit() {
  if (!session.value || !answer.value.trim()) return
  if (redesigned.value) {
    if (session.value.options?.feedbackMode === 'END') return advance()
    if (!revealed.value || dirty.value) return scoreCurrent()
    return advance()
  }
  if (hasFollowUp.value) return advance()
  if (!(await persist())) return
  const requestRevision = revision
  const id = session.value.id
  askingFollowUp.value = true
  busy.value = true
  try {
    const saved = await requestInterviewFollowUp(id, session.value.questionIndex)
    if (requestRevision !== revision) return
    session.value = saved
    syncDrafts()
  } catch (e) {
    if (requestRevision === revision)
      error.value = () =>
        uiText(
          'The interviewer could not ask a follow-up: {0}. Retry or skip to the next question.',
          [(e as Error).message],
        )
  } finally {
    if (requestRevision === revision) {
      askingFollowUp.value = false
      busy.value = false
    }
  }
}

async function askFollowUp() {
  if (!session.value || !(await persist())) return
  const version = revision
  askingFollowUp.value = true
  try {
    const saved = await requestInterviewFollowUp(
      session.value.id,
      session.value.questionIndex,
      controller.signal,
    )
    if (version === revision) {
      session.value = saved
      syncDrafts()
    }
  } catch (cause) {
    if (version === revision) error.value = (cause as Error).message
  } finally {
    if (version === revision) askingFollowUp.value = false
  }
}
async function saveCandidate(candidate: StarStoryCandidate) {
  if (!session.value || starBusy.value || candidate.alreadySaved) return
  starBusy.value = true
  starMessage.value = ''
  starError.value = ''
  const requestRevision = revision
  const id = session.value.id
  try {
    await saveStarStory(
      { sessionId: id, questionIndex: candidate.questionIndex },
      controller.signal,
    )
    if (requestRevision !== revision) return
    candidate.alreadySaved = true
    starMessage.value = () => uiText('Saved to the STAR story library.')
    void cache.invalidateQueries({ queryKey: ['star-stories'] })
    await loadCandidates()
  } catch (e) {
    if (requestRevision === revision) starError.value = (e as Error).message
  } finally {
    if (requestRevision === revision) starBusy.value = false
  }
}

async function saveBeforeLeave() {
  if (busy.value) return false
  if (!session.value) {
    speech.cancelAll()
    return true
  }
  const saved = session.value.completed || (await persist())
  if (saved) {
    camera.stop()
    await camera.flush()
  }
  speech.cancelAll()
  return saved
}
onBeforeRouteLeave(saveBeforeLeave)
onBeforeRouteUpdate(saveBeforeLeave)
async function restoreTask() {
  if (busy.value || loading.value) return
  const version = revision
  try {
    const saved = await getInterview(String(route.params.id))
    if (version === revision) {
      const keepAnswer = dirty.value ? answer.value : undefined
      const keepFollowUp = followUpDirty.value ? followUpDraft.value : undefined
      session.value = saved
      syncDrafts()
      if (keepAnswer !== undefined) answer.value = keepAnswer
      if (keepFollowUp !== undefined) followUpDraft.value = keepFollowUp
      void cache.invalidateQueries({ queryKey: ['interview-report', saved.id] })
    }
  } catch (cause) {
    if (version === revision)
      error.value = () =>
        cause instanceof Error ? cause.message : uiText('Could not restore interview')
  }
}
</script>
<template>
  <div v-if="loading" class="panel empty-state" role="status">
    {{ uiText('Loading practice…') }}
  </div>
  <div v-else-if="loadError" class="panel empty-state">
    <h2>
      {{ notFound ? uiText('Practice session not found') : uiText('Could not load practice') }}
    </h2>
    <p role="alert">{{ loadError }}</p>
    <button v-if="!notFound" class="btn" @click="load">{{ uiText('Reload #2') }}</button>
    <RouterLink to="/interviews" class="btn">{{ uiText('Back to practice list') }}</RouterLink>
  </div>
  <section v-else-if="session" class="page-stack">
    <BackgroundTaskStatus
      kind="INTERVIEW_FOLLOW_UP"
      :entity-id="String(route.params.id)"
      @completed="restoreTask"
    />
    <BackgroundTaskStatus
      v-for="taskKind in ['INTERVIEW_QUESTIONS', 'INTERVIEW_SCORE'] as const"
      :key="taskKind"
      :kind="taskKind"
      :entity-id="String(route.params.id)"
      @completed="restoreTask"
    />
    <PageHeading
      :title="
        session.completed
          ? uiText('Build experience through practice')
          : session.role + uiText('· Interview practice')
      "
      :description="
        uiText('{0} · {1} questions, with optional follow-ups', [
          translateLabel(session.kind),
          session.answers.length,
        ])
      "
    >
      <RouterLink to="/interviews" class="btn">{{ uiText('Back to practice list') }}</RouterLink>
      <button
        v-if="!session.completed"
        class="btn"
        :disabled="busy || dirty || exportBusy"
        :aria-busy="exportBusy"
        @click="downloadRecap"
      >
        {{ exportBusy ? uiText('Exporting…') : uiText('Export review') }}
      </button>
    </PageHeading>
    <InterviewJobContext :job-id="session.jobId" :fallback-title="session.role" />
    <p v-if="exportError" role="alert">{{ exportError }}</p>
    <p v-if="error" role="alert">
      {{ uiText('{0} · Answers are retained. Please retry saving.', [error]) }}
    </p>
    <PresenceGauges
      v-if="!session.completed"
      :status="presenceStatus"
      :values="presenceValues"
      :starting="presenceStarting"
      :upload-failed="presenceUploadFailed"
    />
    <section v-if="session.generationSource === 'pending'" class="panel form-stack">
      <h2>{{ uiText('Prepare your interview questions') }}</h2>
      <p>
        {{
          uiText(
            'Generate {0} questions based on the job, industry and resume. Generation continues after you leave; return to restore it.',
            [session.answers.length],
          )
        }}
      </p>
      <button class="btn primary" :disabled="generating" @click="generateQuestions">
        {{ generating ? uiText('Generating questions…') : uiText('Generate interview questions') }}
      </button>
    </section>
    <template v-else-if="!session.completed">
      <p v-if="timerLabel" role="timer" aria-live="off">{{ timerLabel }}</p>
      <button
        v-if="remainingSeconds === 0"
        class="btn"
        :disabled="busy || scoring || askingFollowUp"
        @click="finish"
      >
        {{ uiText('Save and end interview') }}
      </button>
      <section class="panel interview-stage">
        <progress
          :value="session.questionIndex + 1"
          :max="session.answers.length"
          :aria-label="uiText('Interview progress')"
        />
        <div v-if="redesigned" class="question-meta">
          <span v-if="current?.questionType" class="tag">{{
            questionTypeLabels[current.questionType]
          }}</span>
          <span v-if="current?.difficulty" class="tag">{{
            difficultyLabels[current.difficulty]
          }}</span>
          <span class="tag">{{
            current?.questionSource === 'model'
              ? uiText('Model-generated')
              : uiText('Rule-based questions · Model unavailable')
          }}</span>
          <span role="timer">{{
            uiText('This question: {0} min {1} sec', [
              Math.floor(questionSeconds / 60),
              questionSeconds % 60,
            ])
          }}</span>
        </div>
        <span class="tag">{{
          uiText('Question {0} of {1} #2', [session.questionIndex + 1, session.answers.length])
        }}</span>
        <div class="interview-avatar"><AppIcon name="interviews" :size="38" /></div>
        <h2>{{ current?.questionText }}</h2>
        <p class="subtle">
          {{
            uiText(
              'You do not need a perfect answer. Start by explaining your real experience clearly.',
            )
          }}
        </p>
        <div v-if="hasFollowUp" class="follow-up-bubble" aria-live="polite">
          <span class="tag">{{
            uiText('Interviewer follow-up {0}', [
              current?.followUpSource === 'rule' ? uiText('· Rule-based') : '',
            ])
          }}</span>
          <p>{{ current?.followUpQuestion }}</p>
          <button
            v-if="synthesisSupported"
            type="button"
            class="btn text"
            :disabled="busy || scoring || askingFollowUp"
            @click="speech.speak(current?.followUpQuestion ?? '')"
          >
            {{ uiText('Read follow-up aloud') }}
          </button>
        </div>
        <p v-else-if="askingFollowUp" class="subtle" role="status">
          {{ uiText('The interviewer is preparing a follow-up based on your answer…') }}
        </p>
        <div v-if="synthesisSupported" class="voice-controls">
          <button
            type="button"
            class="btn"
            :disabled="busy || scoring || askingFollowUp"
            @click="speaking ? speech.stopSpeaking() : speech.speak(current?.questionText ?? '')"
          >
            {{ speaking ? uiText('Stop reading') : uiText('Read question aloud') }}
          </button>
          <label class="voice-preference"
            ><input
              v-model="voiceAutoPlay"
              type="checkbox"
              :disabled="busy || scoring || askingFollowUp"
            />{{ uiText('Read aloud automatically') }}</label
          >
          <span class="tag" role="status">{{
            askingFollowUp
              ? uiText('Considering a follow-up')
              : busy
                ? uiText('Saving')
                : speaking
                  ? uiText('Asking a question')
                  : uiText('Waiting for an answer')
          }}</span>
        </div>
      </section>
      <InterviewFeedback
        v-if="redesigned && session.options?.feedbackMode !== 'END' && current?.evaluation"
        :evaluation="current.evaluation"
      />
      <form class="panel form-stack" @submit.prevent="submit">
        <div v-if="recognitionSupported" class="voice-controls">
          <button
            type="button"
            class="btn"
            :aria-pressed="listenRequested"
            :disabled="busy || finishing || !canListen || answer.length >= 12000"
            @click="speech.toggleListening"
          >
            {{
              finishing
                ? uiText('Finishing dictation…')
                : listenRequested
                  ? uiText('Stop dictation')
                  : uiText('Start dictation')
            }}
          </button>
          <span class="subtle" role="status" aria-live="polite">{{
            finishing
              ? uiText('Microphone stopped. Finalizing the last sentence…')
              : speaking && listenRequested
                ? uiText('Resume listening after reading')
                : listenRequested && !listening
                  ? uiText('Starting microphone…')
                  : listening
                    ? uiText('The microphone is listening. Stop dictation to edit your answer.')
                    : uiText('Microphone is off. Edit your answer after dictation, then save.')
          }}</span>
        </div>
        <label class="field"
          >{{ uiText('Your answer')
          }}<textarea
            v-model="answer"
            rows="6"
            required
            maxlength="12000"
            :disabled="busy || scoring || askingFollowUp"
            :placeholder="uiText('Start with the situation, task, action and result…')"
          />
        </label>
        <label v-if="hasFollowUp" class="field"
          >{{ uiText('Answer the follow-up')
          }}<textarea
            v-model="followUpDraft"
            rows="4"
            maxlength="12000"
            :disabled="busy || scoring || askingFollowUp"
            :placeholder="
              uiText(
                'Add details for the follow-up, or leave it blank and continue to the next question.',
              )
            "
          />
        </label>
        <p v-if="interimText" class="subtle speech-subtitle" role="status" aria-live="polite">
          {{ interimText }}
        </p>
        <p v-if="unsupportedReason" class="helper-note">{{ unsupportedReason }}</p>
        <p v-if="recognitionSupported" class="helper-note">
          {{
            uiText(
              "Speech recognition is provided by your browser and may use the browser vendor's online services. OfferPilot does not receive or store audio; saving submits answer text only.",
            )
          }}
        </p>
        <p v-if="speechError" class="helper-note" role="status">{{ speechError }}</p>
        <p v-if="answer.length >= 12000" class="helper-note">
          {{ uiText('Your answer has reached the length limit. Edit it to continue.') }}
        </p>
        <div class="card-footer">
          <span class="subtle" role="status">{{
            uiText('{0} characters · {1}', [
              [...answer].length,
              busy
                ? uiText('Saving…')
                : dirty
                  ? uiText('Draft not saved')
                  : uiText('Saved to server'),
            ])
          }}</span>
          <button type="button" class="btn" :disabled="busy || !dirty" @click="persist()">
            {{ uiText('Save draft') }}
          </button>
          <button
            type="button"
            class="btn"
            :disabled="busy || scoring || session.questionIndex === 0"
            @click="persist(session.questionIndex - 1)"
          >
            {{ uiText('Previous question') }}
          </button>
          <button
            v-if="!hasFollowUp && !redesigned"
            type="button"
            class="btn"
            :disabled="busy || !answer.trim()"
            @click="advance"
          >
            {{ uiText('Skip follow-up') }}
          </button>
          <button
            v-if="redesigned"
            type="button"
            class="btn"
            :disabled="busy || scoring"
            @click="advance"
          >
            {{ uiText('Skip question') }}
          </button>
          <button
            v-if="redesigned"
            type="button"
            class="btn"
            :disabled="busy || scoring"
            @click="finish"
          >
            {{ uiText('Finish and generate report') }}
          </button>
          <button
            v-if="redesigned && !hasFollowUp"
            type="button"
            class="btn"
            :disabled="busy || scoring || !answer.trim()"
            @click="askFollowUp"
          >
            {{ uiText('Ask for a follow-up') }}
          </button>
          <button
            class="btn primary"
            :disabled="busy || scoring || !answer.trim()"
            :aria-busy="askingFollowUp"
          >
            {{
              redesigned
                ? scoring
                  ? uiText('Evaluating…')
                  : session.options?.feedbackMode === 'END'
                    ? uiText('Save and next question')
                    : revealed && !dirty
                      ? uiText('Next question')
                      : current?.evaluation
                        ? uiText('Re-evaluate')
                        : uiText('Submit and view feedback')
                : !hasFollowUp
                  ? askingFollowUp
                    ? uiText('Interviewer is thinking…')
                    : uiText('Submit answer')
                  : session.questionIndex === session.answers.length - 1
                    ? uiText('Finish and review')
                    : uiText('Save and next question')
            }}
          </button>
        </div>
        <p class="helper-note">
          {{
            uiText(
              'Drafts are saved to the server when you save, change questions or return to the list. Save before refreshing.',
            )
          }}
        </p>
      </form>
    </template>
    <template v-else>
      <InterviewReportPanel
        :key="session.id"
        :session-id="session.id"
        :can-generate="redesigned"
        :auto-generate="reportRequested"
      >
        <button class="btn" :disabled="exportBusy" :aria-busy="exportBusy" @click="downloadRecap">
          {{ exportBusy ? uiText('Exporting…') : uiText('Export review') }}
        </button>
      </InterviewReportPanel>
      <div class="success-banner">
        {{
          uiText(
            'This {0}-question session has ended. Original answers and feedback are saved and can be exported. Unanswered questions receive no fabricated model scores.',
            [session.answers.length],
          )
        }}
      </div>
      <div class="split-content">
        <section class="panel">
          <article v-for="item in session.answers" :key="item.questionIndex" class="answer-card">
            <h3>{{ item.questionIndex + 1 }}. {{ item.questionText }}</h3>
            <p class="prose-content">{{ item.answerText || uiText('Unanswered') }}</p>
            <template v-if="item.followUpQuestion">
              <p class="subtle">{{ uiText('Follow-up: {0}', [item.followUpQuestion]) }}</p>
              <p class="prose-content">
                {{ item.followUpAnswer || uiText('Follow-up unanswered') }}
              </p>
            </template>
          </article>
        </section>
        <aside class="panel form-stack" style="align-self: start">
          <h2>{{ uiText('STAR story library') }}</h2>
          <p class="subtle">
            {{
              uiText(
                'Save answers with sufficient STAR details for future practice. Saving requires your confirmation; nothing is added automatically.',
              )
            }}
          </p>
          <p v-if="starMessage" class="success-banner" role="status">{{ starMessage }}</p>
          <p v-if="starLoading" role="status">{{ uiText('Loading STAR candidates…') }}</p>
          <div v-else-if="starError" role="alert">
            <p>{{ starError }}</p>
            <button class="btn" :disabled="starBusy" @click="loadCandidates">
              {{ uiText('Reload candidates') }}
            </button>
          </div>
          <div v-else-if="!starCandidates.length" class="helper-note">
            {{ uiText('No candidates in this practice have at least 3 STAR elements.') }}
          </div>
          <article v-for="item in starCandidates" :key="item.questionIndex" class="answer-card">
            <h3>
              {{ uiText('Question {0} · {1}/4 STAR', [item.questionIndex + 1, item.starCount]) }}
            </h3>
            <p class="subtle">{{ item.title }}</p>
            <p class="prose-content">
              {{ item.body.slice(0, 160) }}{{ formatDisplay(item.body.length > 160 ? '…' : '') }}
            </p>
            <button
              type="button"
              class="btn primary"
              :disabled="starBusy || starLoading || item.alreadySaved"
              @click="saveCandidate(item)"
            >
              {{
                item.alreadySaved
                  ? uiText('Saved to library')
                  : starBusy
                    ? uiText('Saving… #2')
                    : uiText('Save to story library')
              }}
            </button>
          </article>
          <h2>{{ uiText('Three questions for your review') }}</h2>
          <p class="subtle">
            {{ uiText('① Did I answer the question directly?') }}<br />{{
              uiText('② Did I explain my own contribution?')
            }}<br />{{ uiText('③ Were my results specific and verifiable?') }}
          </p>
          <RouterLink
            :to="{
              path: '/chat',
              query: {
                mode: 'INTERVIEWER',
                interviewSessionId: session.id,
                interviewAction: 'recallStarStories',
                prompt: uiText(
                  'I am preparing for an interview for {0}. Please help me practice project experience questions.',
                  [session.role],
                ),
              },
            }"
            class="btn primary"
            >{{ uiText('Keep practicing with AI') }}</RouterLink
          >
          <RouterLink to="/career" class="btn">{{ uiText('Update growth actions') }}</RouterLink>
        </aside>
      </div>
    </template>
  </section>
</template>

<style scoped>
.question-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 16px;
}
.interview-stage progress {
  width: 100%;
  accent-color: var(--primary);
}
.interview-stage h2 {
  overflow-wrap: anywhere;
}
.voice-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.voice-preference {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.voice-preference input {
  width: auto;
  accent-color: var(--primary);
}
.follow-up-bubble {
  margin-top: 12px;
  padding: 12px 16px;
  border-left: 3px solid var(--primary);
  background: var(--surface-muted, rgba(0, 0, 0, 0.03));
  border-radius: 8px;
  text-align: left;
}
.follow-up-bubble p {
  margin: 6px 0;
  font-weight: 600;
}
.speech-subtitle {
  overflow-wrap: anywhere;
}
</style>
