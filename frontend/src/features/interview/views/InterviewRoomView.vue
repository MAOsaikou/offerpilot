<script setup lang="ts">
import BackgroundTaskStatus from '@/shared/components/BackgroundTaskStatus.vue'

import InterviewReportPanel from '../components/InterviewReportPanel.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, RouterLink, useRoute } from 'vue-router'
import { useQueryClient } from '@tanstack/vue-query'
import InterviewJobContext from '@/shared/components/InterviewJobContext.vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import {
  getInterview,
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

const route = useRoute()
const cache = useQueryClient()
const session = ref<InterviewSession>()
const answer = ref('')
const followUpDraft = ref('')
const askingFollowUp = ref(false)
const loading = ref(true)
const loadError = ref('')
const notFound = ref(false)
const error = ref('')
const busy = ref(false)
const starCandidates = ref<StarStoryCandidate[]>([])
const starBusy = ref(false)
const starMessage = ref('')
const starLoading = ref(false)
const starError = ref('')
const exportBusy = ref(false)
const exportError = ref('')
const current = computed(() =>
  session.value?.answers.find((a) => a.questionIndex === session.value?.questionIndex),
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
      ? '本次面试时间已到，请保存回答并完成复盘。'
      : `剩余 ${Math.floor(remainingSeconds.value / 60)} 分 ${remainingSeconds.value % 60} 秒`,
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
  speech.cancelAll()
  controller.abort()
  controller = new AbortController()
  const requestRevision = ++revision
  loading.value = true
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
  if (session.value.questionIndex === session.value.answers.length - 1)
    await persist(undefined, true)
  else await persist(session.value.questionIndex + 1)
}

/** IV-02: first submit saves the answer and lets the interviewer follow up; the second moves on. */
async function submit() {
  if (!session.value || !answer.value.trim()) return
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
      error.value = `面试官暂时无法追问：${(e as Error).message}。可以重试，或跳过追问进入下一题`
  } finally {
    if (requestRevision === revision) {
      askingFollowUp.value = false
      busy.value = false
    }
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
    starMessage.value = '已保存到 STAR 故事库。'
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
  const saved = await persist()
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
      session.value = saved
      syncDrafts()
    }
  } catch (cause) {
    if (version === revision) error.value = cause instanceof Error ? cause.message : '面试恢复失败'
  }
}
</script>
<template>
  <div v-if="loading" class="panel empty-state" role="status">正在加载练习…</div>
  <div v-else-if="loadError" class="panel empty-state">
    <h2>{{ notFound ? '未找到这场练习' : '练习加载失败' }}</h2>
    <p role="alert">{{ loadError }}</p>
    <button v-if="!notFound" class="btn" @click="load">重新加载</button>
    <RouterLink to="/interviews" class="btn">返回练习列表</RouterLink>
  </div>
  <section v-else-if="session" class="page-stack">
    <BackgroundTaskStatus
      kind="INTERVIEW_FOLLOW_UP"
      :entity-id="String(route.params.id)"
      @completed="restoreTask"
    />
    <PageHeading
      :title="session.completed ? '一次练习，一份积累' : session.role + ' · 面试练习'"
      :description="
        session.kind + ' · ' + session.answers.length + ' 道主问题，每题面试官根据你的回答追问一次'
      "
    >
      <RouterLink to="/interviews" class="btn">返回练习列表</RouterLink>
      <button
        class="btn"
        :disabled="busy || dirty || exportBusy"
        :aria-busy="exportBusy"
        @click="downloadRecap"
      >
        {{ exportBusy ? '正在导出…' : '导出复盘' }}
      </button>
    </PageHeading>
    <InterviewJobContext :job-id="session.jobId" :fallback-title="session.role" />
    <p v-if="exportError" role="alert">{{ exportError }}</p>
    <p v-if="error" role="alert">{{ error }} · 回答已保留，请重试保存。</p>
    <template v-if="!session.completed">
      <p v-if="timerLabel" role="timer" aria-live="off">{{ timerLabel }}</p>
      <button
        v-if="remainingSeconds === 0"
        class="btn"
        :disabled="busy"
        @click="persist(undefined, true)"
      >
        保存并结束面试
      </button>
      <section class="panel interview-stage">
        <span class="tag"
          >第 {{ session.questionIndex + 1 }} / {{ session.answers.length }} 题</span
        >
        <div class="interview-avatar"><AppIcon name="interviews" :size="38" /></div>
        <h2>{{ current?.questionText }}</h2>
        <p class="subtle">不急着给出完美答案。先把你的真实经历讲清楚。</p>
        <div v-if="hasFollowUp" class="follow-up-bubble" aria-live="polite">
          <span class="tag"
            >面试官追问{{ current?.followUpSource === 'rule' ? ' · 规则' : '' }}</span
          >
          <p>{{ current?.followUpQuestion }}</p>
          <button
            v-if="synthesisSupported"
            type="button"
            class="btn text"
            :disabled="busy"
            @click="speech.speak(current?.followUpQuestion ?? '')"
          >
            朗读追问
          </button>
        </div>
        <p v-else-if="askingFollowUp" class="subtle" role="status">
          面试官正在根据你的回答准备追问…
        </p>
        <div v-if="synthesisSupported" class="voice-controls">
          <button
            type="button"
            class="btn"
            :disabled="busy"
            @click="speaking ? speech.stopSpeaking() : speech.speak(current?.questionText ?? '')"
          >
            {{ speaking ? '停止朗读' : '朗读题目' }}
          </button>
          <label class="voice-preference"
            ><input v-model="voiceAutoPlay" type="checkbox" :disabled="busy" />自动朗读</label
          >
          <span class="tag" role="status">{{
            askingFollowUp ? '思考追问' : busy ? '正在保存' : speaking ? '提问中' : '等待回答'
          }}</span>
        </div>
      </section>
      <form class="panel form-stack" @submit.prevent="submit">
        <div v-if="recognitionSupported" class="voice-controls">
          <button
            type="button"
            class="btn"
            :aria-pressed="listenRequested"
            :disabled="busy || finishing || !canListen || answer.length >= 12000"
            @click="speech.toggleListening"
          >
            {{ finishing ? '正在结束口述…' : listenRequested ? '停止口述' : '开始口述' }}
          </button>
          <span class="subtle" role="status" aria-live="polite">{{
            finishing
              ? '麦克风已停止，正在确认最后一句…'
              : speaking && listenRequested
                ? '朗读后继续倾听'
                : listenRequested && !listening
                  ? '正在启动麦克风…'
                  : listening
                    ? '麦克风正在倾听；停止口述后可编辑回答。'
                    : '麦克风未开启；口述结束后可编辑，再保存回答。'
          }}</span>
        </div>
        <label class="field"
          >你的回答<textarea
            v-model="answer"
            rows="6"
            required
            maxlength="12000"
            :disabled="busy"
            placeholder="可以从背景、任务、行动、结果四个角度展开…"
          />
        </label>
        <label v-if="hasFollowUp" class="field"
          >回答追问<textarea
            v-model="followUpDraft"
            rows="4"
            maxlength="12000"
            :disabled="busy"
            placeholder="针对追问补充细节；也可以留空，直接进入下一题。"
          />
        </label>
        <p v-if="interimText" class="subtle speech-subtitle" role="status" aria-live="polite">
          {{ interimText }}
        </p>
        <p v-if="unsupportedReason" class="helper-note">{{ unsupportedReason }}</p>
        <p v-if="recognitionSupported" class="helper-note">
          语音由浏览器识别，可能使用浏览器厂商的在线服务。OfferPilot
          不接收或保存音频，保存时仅提交回答文字。
        </p>
        <p v-if="speechError" class="helper-note" role="status">{{ speechError }}</p>
        <p v-if="answer.length >= 12000" class="helper-note">回答已达到长度上限，请编辑后继续。</p>
        <div class="card-footer">
          <span class="subtle" role="status"
            >{{ [...answer].length }} 字 ·
            {{ busy ? '正在保存…' : dirty ? '草稿未保存' : '已保存到服务端' }}</span
          >
          <button type="button" class="btn" :disabled="busy || !dirty" @click="persist()">
            保存草稿
          </button>
          <button
            type="button"
            class="btn"
            :disabled="busy || session.questionIndex === 0"
            @click="persist(session.questionIndex - 1)"
          >
            上一题
          </button>
          <button
            v-if="!hasFollowUp"
            type="button"
            class="btn"
            :disabled="busy || !answer.trim()"
            @click="advance"
          >
            跳过追问
          </button>
          <button
            class="btn primary"
            :disabled="busy || !answer.trim()"
            :aria-busy="askingFollowUp"
          >
            {{
              !hasFollowUp
                ? askingFollowUp
                  ? '面试官思考中…'
                  : '提交回答'
                : session.questionIndex === session.answers.length - 1
                  ? '完成并复盘'
                  : '保存，下一题'
            }}
          </button>
        </div>
        <p class="helper-note">保存草稿、切题或返回列表时会保存到服务端；刷新前请先保存。</p>
      </form>
    </template>
    <template v-else>
      <InterviewReportPanel :key="session.id" :session-id="session.id" />
      <div class="success-banner">
        已完成全部 {{ session.answers.length }} 题。下面是原始回答；点「导出复盘」可下载含 STAR
        线索分的复盘包。
      </div>
      <div class="split-content">
        <section class="panel">
          <article v-for="item in session.answers" :key="item.questionIndex" class="answer-card">
            <h3>{{ item.questionIndex + 1 }}. {{ item.questionText }}</h3>
            <p class="prose-content">{{ item.answerText || '未作答' }}</p>
            <template v-if="item.followUpQuestion">
              <p class="subtle">追问：{{ item.followUpQuestion }}</p>
              <p class="prose-content">{{ item.followUpAnswer || '未回答追问' }}</p>
            </template>
          </article>
        </section>
        <aside class="panel form-stack" style="align-self: start">
          <h2>STAR 故事库</h2>
          <p class="subtle">
            把 STAR 线索齐全的回答保存下来，之后面试练习会引用这些故事。保存需你确认，不会自动入库。
          </p>
          <p v-if="starMessage" class="success-banner" role="status">{{ starMessage }}</p>
          <p v-if="starLoading" role="status">正在加载 STAR 候选…</p>
          <div v-else-if="starError" role="alert">
            <p>{{ starError }}</p>
            <button class="btn" :disabled="starBusy" @click="loadCandidates">重新加载候选</button>
          </div>
          <div v-else-if="!starCandidates.length" class="helper-note">
            本题练习暂无满足 3 项 STAR 线索的候选。
          </div>
          <article v-for="item in starCandidates" :key="item.questionIndex" class="answer-card">
            <h3>第 {{ item.questionIndex + 1 }} 题 · {{ item.starCount }}/4 STAR</h3>
            <p class="subtle">{{ item.title }}</p>
            <p class="prose-content">
              {{ item.body.slice(0, 160) }}{{ item.body.length > 160 ? '…' : '' }}
            </p>
            <button
              type="button"
              class="btn primary"
              :disabled="starBusy || starLoading || item.alreadySaved"
              @click="saveCandidate(item)"
            >
              {{ item.alreadySaved ? '已入库' : starBusy ? '保存中…' : '保存到故事库' }}
            </button>
          </article>
          <h2>复盘时，问自己三个问题</h2>
          <p class="subtle">
            ① 我是否直接回答了问题？<br />② 我是否说清自己的贡献？<br />③ 我的结果是否具体、可验证？
          </p>
          <RouterLink
            :to="{
              path: '/chat',
              query: {
                mode: 'INTERVIEWER',
                interviewSessionId: session.id,
                interviewAction: 'recallStarStories',
                prompt: '我正在准备' + session.role + '的面试，请帮我练习回答项目经历类问题。',
              },
            }"
            class="btn primary"
            >继续和 AI 练习</RouterLink
          >
          <RouterLink to="/career" class="btn">更新成长行动</RouterLink>
        </aside>
      </div>
    </template>
  </section>
</template>

<style scoped>
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
