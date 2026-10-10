<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, RouterLink, useRoute } from 'vue-router'
import { useQueryClient } from '@tanstack/vue-query'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { getInterview, saveInterviewAnswer, updateInterview } from '@/shared/api/interviews'
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
const loading = ref(true)
const loadError = ref('')
const notFound = ref(false)
const error = ref('')
const busy = ref(false)
const starCandidates = ref<StarStoryCandidate[]>([])
const starBusy = ref(false)
const starMessage = ref('')
const current = computed(() =>
  session.value?.answers.find((a) => a.questionIndex === session.value?.questionIndex),
)
const dirty = computed(() => Boolean(session.value) && answer.value !== current.value?.answerText)
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
    const separator = answer.value && !/\s$/.test(answer.value) ? '\n' : ''
    let addition = ''
    for (const character of separator + text) {
      if (answer.value.length + addition.length + character.length > 12000) break
      addition += character
    }
    answer.value += addition
    if (answer.value.length >= 12000 || addition.length < separator.length + text.length)
      void speech.stopListening()
  },
})
const {
  supported,
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
      ? `${session.value.id}:${session.value.questionIndex}:${current.value?.questionText}`
      : '',
  (question) => {
    speech.cancelAll()
    if (question && voiceAutoPlay.value && supported.value)
      speech.speak(current.value?.questionText ?? '')
  },
)
let controller = new AbortController()
let revision = 0

async function load() {
  speech.cancelAll()
  controller.abort()
  controller = new AbortController()
  const requestRevision = ++revision
  loading.value = true
  loadError.value = ''
  notFound.value = false
  session.value = undefined
  error.value = ''
  try {
    const loaded = await getInterview(String(route.params.id), controller.signal)
    if (requestRevision !== revision) return
    session.value = loaded
    answer.value = current.value?.answerText || ''
    starCandidates.value = []
    starMessage.value = ''
    if (loaded.completed) {
      try {
        starCandidates.value = await listStarStoryCandidates(loaded.id, controller.signal)
      } catch {
        /* Story bank is optional on the room page. */
      }
    }
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
    if (dirty.value) {
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
    if (questionIndex !== undefined || completed !== undefined) {
      const progress: { questionIndex?: number; completed?: boolean } = {}
      if (questionIndex !== undefined) progress.questionIndex = questionIndex
      if (completed !== undefined) progress.completed = completed
      const saved = await updateInterview(id, progress, controller.signal)
      if (requestRevision !== revision) return false
      session.value = saved
      answer.value = current.value?.answerText || ''
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

async function submit() {
  if (!session.value || !answer.value.trim()) return
  if (session.value.questionIndex === session.value.answers.length - 1)
    await persist(undefined, true)
  else await persist(session.value.questionIndex + 1)
}

async function saveCandidate(candidate: StarStoryCandidate) {
  if (!session.value || starBusy.value || candidate.alreadySaved) return
  starBusy.value = true
  starMessage.value = ''
  error.value = ''
  try {
    await saveStarStory({ sessionId: session.value.id, questionIndex: candidate.questionIndex })
    starCandidates.value = await listStarStoryCandidates(session.value.id)
    starMessage.value = '已保存到 STAR 故事库。'
    void cache.invalidateQueries({ queryKey: ['star-stories'] })
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    starBusy.value = false
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
    <PageHeading
      :title="session.completed ? '一次练习，一份积累' : session.role + ' · 面试练习'"
      :description="session.kind + ' · 固定题库自练'"
    >
      <RouterLink to="/interviews" class="btn">返回练习列表</RouterLink>
      <button
        class="btn"
        :disabled="busy || dirty"
        @click="exportInterview(session.role, session.answers, session.id)"
      >
        导出复盘
      </button>
    </PageHeading>
    <p v-if="error" role="alert">{{ error }} · 回答已保留，请重试保存。</p>
    <template v-if="!session.completed">
      <section class="panel interview-stage">
        <span class="tag"
          >第 {{ session.questionIndex + 1 }} / {{ session.answers.length }} 题</span
        >
        <div class="interview-avatar"><AppIcon name="interviews" :size="38" /></div>
        <h2>{{ current?.questionText }}</h2>
        <p class="subtle">不急着给出完美答案。先把你的真实经历讲清楚。</p>
        <div v-if="supported" class="voice-controls">
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
            busy ? '正在保存' : speaking ? '提问中' : listening ? '倾听中' : '等待回答'
          }}</span>
        </div>
      </section>
      <form class="panel form-stack" @submit.prevent="submit">
        <div v-if="supported" class="voice-controls">
          <button
            type="button"
            class="btn"
            :aria-pressed="listenRequested"
            :disabled="busy || !canListen || answer.length >= 12000"
            @click="speech.toggleListening"
          >
            {{ listenRequested ? '停止口述' : '开始口述' }}
          </button>
          <span class="subtle">{{
            speaking && listenRequested
              ? '朗读后继续倾听'
              : listenRequested && !listening
                ? '正在启动麦克风…'
                : '口述结束后可编辑，再保存回答。'
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
        <p v-if="interimText" class="subtle speech-subtitle" role="status" aria-live="polite">
          {{ interimText }}
        </p>
        <p v-if="!supported" class="helper-note">{{ unsupportedReason }}</p>
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
          <button class="btn primary" :disabled="busy || !answer.trim()">
            {{
              session.questionIndex === session.answers.length - 1 ? '完成并复盘' : '保存，下一题'
            }}
          </button>
        </div>
        <p class="helper-note">保存草稿、切题或返回列表时会保存到服务端；刷新前请先保存。</p>
      </form>
    </template>
    <template v-else>
      <div class="success-banner">
        已完成全部 {{ session.answers.length }} 题。下面是原始回答；点「导出复盘」可下载含 STAR
        线索分的复盘包。
      </div>
      <div class="split-content">
        <section class="panel">
          <article v-for="item in session.answers" :key="item.questionIndex" class="answer-card">
            <h3>{{ item.questionIndex + 1 }}. {{ item.questionText }}</h3>
            <p class="prose-content">{{ item.answerText || '未作答' }}</p>
          </article>
        </section>
        <aside class="panel form-stack" style="align-self: start">
          <h2>STAR 故事库</h2>
          <p class="subtle">
            把 STAR 线索齐全的回答保存下来，之后面试练习会引用这些故事。保存需你确认，不会自动入库。
          </p>
          <p v-if="starMessage" class="success-banner" role="status">{{ starMessage }}</p>
          <div v-if="!starCandidates.length" class="helper-note">
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
              :disabled="starBusy || item.alreadySaved"
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
.speech-subtitle {
  overflow-wrap: anywhere;
}
</style>
