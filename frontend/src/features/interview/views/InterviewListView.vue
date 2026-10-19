<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRouter, useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import InterviewJobContext from '@/shared/components/InterviewJobContext.vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { listResumes, listResumeVersions } from '@/shared/api/resumes'
import { listApplications } from '@/shared/api/applications'
import { useSpeech } from '@/shared/composables/useSpeech'
import type { InterviewOptions } from '@/shared/types/interview'
import { createInterview, listInterviews } from '@/shared/api/interviews'
import { deleteStarStory, listStarStories } from '@/shared/api/starStories'
import type { InterviewKind } from '@/shared/types/interview'
import type { Interview } from '@/shared/types/workspace'
import { questions } from '@/shared/utils/interviewQuestions'
import { exportInterview } from '@/shared/utils/interviewExport'

const workspace = useWorkspace()
const router = useRouter()
const route = useRoute()
const cache = useQueryClient()
const query = useQuery({
  queryKey: ['interviews'],
  queryFn: ({ signal }) => listInterviews(signal),
  retry: false,
})
const stories = useQuery({
  queryKey: ['star-stories'],
  queryFn: ({ signal }) => listStarStories(signal),
  retry: false,
})
const storyBusy = ref('')
const storyError = ref('')
async function removeStory(id: string) {
  if (storyBusy.value) return
  storyBusy.value = id
  storyError.value = ''
  try {
    await deleteStarStory(id, controller.signal)
    if (controller.signal.aborted) return
    void cache.invalidateQueries({ queryKey: ['star-stories'] })
  } catch (e) {
    if (!controller.signal.aborted) storyError.value = (e as Error).message
  } finally {
    storyBusy.value = ''
  }
}
const role = ref(typeof route.query.role === 'string' ? route.query.role : '')
const kind = ref<InterviewKind>('综合')
const difficulty = ref<InterviewOptions['difficulty']>('STANDARD')
const mode = ref<InterviewOptions['mode']>('TEXT')
const durationMinutes = ref(30)
const resumeId = ref('')
const versionId = ref('')
const jobId = ref(typeof route.query.jobId === 'string' ? route.query.jobId : '')
const applications = useQuery({
  queryKey: ['applications'],
  queryFn: ({ signal }) => listApplications(signal),
  retry: false,
})
const resumes = useQuery({
  queryKey: ['resumes', 'interview'],
  queryFn: ({ signal }) => listResumes(1, signal),
  retry: false,
})
const versions = useQuery({
  queryKey: computed(() => ['resume-versions', resumeId.value]),
  queryFn: ({ signal }) => listResumeVersions(resumeId.value, signal),
  enabled: computed(() => !!resumeId.value),
  retry: false,
})
watch(resumeId, () => {
  versionId.value = ''
})
watch(jobId, (id) => {
  const selected = applications.data.value?.find((item) => item.jobId === id)
  if (selected?.job) role.value = selected.job.title
})
const speech = useSpeech()
const voiceAvailable = computed(
  () => speech.recognitionSupported.value && speech.synthesisSupported.value,
)
const invalidSelection = computed(
  () =>
    !!resumeId.value && (!versionId.value || versions.isPending.value || versions.isError.value),
)
const busy = ref(false)
const error = ref('')
const controller = new AbortController()
onBeforeUnmount(() => controller.abort())

async function start() {
  if (!role.value.trim() || busy.value || invalidSelection.value) return
  busy.value = true
  error.value = ''
  try {
    const session = await createInterview(
      {
        role: role.value.trim(),
        kind: kind.value,
        jobId: jobId.value || undefined,
        resumeId: resumeId.value || undefined,
        versionId: versionId.value || undefined,
        difficulty: difficulty.value,
        mode: mode.value,
        durationMinutes: durationMinutes.value,
      },
      controller.signal,
    )
    if (controller.signal.aborted) return
    void cache.invalidateQueries({ queryKey: ['interviews'] })
    await router.push('/interviews/' + session.id)
  } catch (e) {
    if (!controller.signal.aborted) error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

function exportLegacy(session: Interview) {
  exportInterview(
    session.role,
    questions.map((questionText, questionIndex) => ({
      questionIndex,
      questionText,
      answerText: session.answers[questionIndex] || '',
    })),
  )
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="让表达，先于机会准备好"
      description="在轻松的练习里，把经历讲清楚，把信心建立起来。"
    />
    <div class="split-content">
      <form class="panel form-stack" @submit.prevent="start">
        <h2>创建模拟面试</h2>
        <p v-if="typeof route.query.jobId === 'string'" class="helper-note">
          本次练习将关联到所选岗位的投递工作区。<RouterLink :to="'/jobs/' + route.query.jobId"
            >查看岗位</RouterLink
          >
        </p>
        <div class="form-grid">
          <label class="field"
            >关联岗位（可无）<select v-model="jobId" :disabled="busy">
              <option value="">不关联岗位</option>
              <option
                v-if="jobId && !applications.data.value?.some((item) => item.jobId === jobId)"
                :value="jobId"
              >
                {{ role }}
              </option>
              <option v-for="item in applications.data.value" :key="item.id" :value="item.jobId">
                {{ item.job?.title || '岗位信息不可用' }}
              </option>
            </select></label
          >
          <p v-if="applications.isPending.value" role="status">正在读取收藏岗位…</p>
          <p v-if="applications.isError.value" role="alert">
            {{ applications.error.value?.message }}
            <button type="button" class="btn" @click="applications.refetch()">重试岗位</button>
          </p>
          <label class="field"
            >简历<select v-model="resumeId" :disabled="busy || resumes.isPending.value">
              <option value="">不使用简历（通用练习）</option>
              <option
                v-for="item in resumes.data.value?.items.filter(
                  (item) => item.parseStatus === 'READY',
                )"
                :key="item.id"
                :value="item.id"
              >
                {{ item.title }}
              </option>
            </select></label
          >
          <p v-if="resumes.isPending.value" role="status">正在读取简历…</p>
          <p v-if="resumes.isError.value" role="alert">
            {{ resumes.error.value?.message }}
            <button type="button" class="btn" @click="resumes.refetch()">重试简历</button>
          </p>
          <label v-if="resumeId" class="field"
            >简历版本<select
              v-model="versionId"
              required
              :disabled="busy || versions.isPending.value"
            >
              <option value="">请选择已保存版本</option>
              <option v-for="item in versions.data.value" :key="item.id" :value="item.id">
                第 {{ item.versionNo }} 版 ·
                {{ new Date(item.createdAt).toLocaleDateString('zh-CN') }}
              </option>
            </select></label
          >
          <p v-if="resumeId && versions.isPending.value" role="status">正在读取版本…</p>
          <p v-if="resumeId && versions.isError.value" role="alert">
            {{ versions.error.value?.message }}
            <button type="button" class="btn" @click="versions.refetch()">重试版本</button>
          </p>
          <label class="field"
            >目标岗位<input
              v-model="role"
              :disabled="busy"
              required
              maxlength="100"
              placeholder="输入想练习的岗位" /></label
          ><label class="field"
            >面试类型<select v-model="kind" :disabled="busy">
              <option>技术</option>
              <option>行为</option>
              <option>HR</option>
              <option>综合</option>
            </select></label
          >
        </div>
        <div class="form-grid">
          <label class="field"
            >难度<select v-model="difficulty" :disabled="busy">
              <option value="EASY">基础</option>
              <option value="STANDARD">标准</option>
              <option value="HARD">进阶</option>
            </select></label
          >
          <label class="field"
            >模式<select v-model="mode" :disabled="busy">
              <option value="TEXT">文字</option>
              <option value="VOICE" :disabled="!voiceAvailable">
                {{ voiceAvailable ? '语音（浏览器识别与朗读）' : '语音（暂不支持）' }}
              </option>
            </select></label
          >
          <label class="field"
            >时长<select v-model="durationMinutes" :disabled="busy">
              <option :value="15">15 分钟 · 3 题</option>
              <option :value="30">30 分钟 · 5 题</option>
              <option :value="45">45 分钟 · 8 题</option>
              <option :value="60">60 分钟 · 10 题</option>
            </select></label
          >
        </div>
        <p class="helper-note">
          按类型、难度和简历内容组合规则题库。时长从创建起计时，到时提醒并保留回答。语音由浏览器识别与朗读，可能使用浏览器厂商在线服务；仅保存文字。复盘为文字规则评分。
        </p>
        <p v-if="error" role="alert">{{ error }}</p>
        <button
          class="btn primary"
          type="submit"
          :disabled="busy || !role.trim() || invalidSelection"
        >
          {{ busy ? '正在创建…' : '开始练习' }}
        </button>
      </form>
      <aside class="panel form-stack">
        <span class="stat-icon"><AppIcon name="interviews" /></span>
        <h2>先讲清楚，再讲精彩</h2>
        <p class="subtle">
          用 STAR
          组织项目故事：背景是什么、任务是什么、你采取了哪些行动、结果如何。尽量说出具体细节。
        </p>
        <span class="tag">按类型与难度练习 · 可保存草稿</span>
      </aside>
    </div>
    <section class="panel">
      <div class="section-heading">
        <h2>我的练习记录</h2>
        <span class="subtle">{{ query.data.value?.length ?? 0 }} 场练习</span>
      </div>
      <p v-if="query.isPending.value" role="status">正在加载练习记录…</p>
      <div v-else-if="query.isError.value" class="empty-state" role="alert">
        <p>{{ query.error.value?.message }}</p>
        <button class="btn" @click="query.refetch()">重新加载</button>
      </div>
      <div v-else-if="!query.data.value?.length" class="empty-state">
        <h2>第一场练习，从自我介绍开始</h2>
        <p>完成后的回答会留在这里，方便你反复复盘。</p>
      </div>
      <article
        v-for="session in query.data.value"
        :key="session.id"
        class="list-row interview-record"
      >
        <span class="stat-icon"><AppIcon name="interviews" /></span>
        <div>
          <h3>
            <RouterLink :to="'/interviews/' + session.id">{{ session.role }}</RouterLink>
          </h3>
          <InterviewJobContext :job-id="session.jobId" :fallback-title="session.role" />
          <small
            >{{ session.kind }} · {{ new Date(session.createdAt).toLocaleString('zh-CN') }}</small
          >
        </div>
        <div class="interview-record-actions">
          <span class="tag">{{ session.completed ? '已完成' : '进行中' }}</span>
          <RouterLink :to="'/interviews/' + session.id">{{
            session.completed ? '查看复盘' : '继续练习'
          }}</RouterLink>
        </div>
      </article>
    </section>
    <section v-if="workspace.state.interviews.length" class="panel">
      <div class="section-heading"><h2>本机旧练习</h2></div>
      <p class="helper-note">
        这些记录保留在当前浏览器，可导出原始回答。新练习与草稿保存到服务端。
      </p>
      <article v-for="session in workspace.state.interviews" :key="session.id" class="list-row">
        <div>
          <h3>{{ session.role }}</h3>
          <small>{{ session.kind }} · {{ session.date }}</small>
        </div>
        <button class="btn" @click="exportLegacy(session)">导出旧练习</button>
      </article>
    </section>

    <section class="panel">
      <div class="section-heading">
        <h2>STAR 故事库</h2>
        <span class="subtle"
          >{{ stories.data.value?.items.length ?? 0 }} / {{ stories.data.value?.limit ?? 30 }}</span
        >
      </div>
      <p class="helper-note">
        从已完成练习中确认保存的 STAR 回答，会在后续面试对话中作为可引用证据。
      </p>
      <p v-if="storyError" role="alert">{{ storyError }}</p>
      <p v-if="stories.isPending.value" role="status">正在加载故事库…</p>
      <div v-else-if="stories.isError.value" class="empty-state" role="alert">
        <p>{{ stories.error.value?.message }}</p>
        <button class="btn" @click="stories.refetch()">重新加载</button>
      </div>
      <div v-else-if="!stories.data.value?.items.length" class="empty-state">
        <h2>还没有入库的故事</h2>
        <p>完成练习后，在复盘页把 STAR 线索齐全的回答保存进来。</p>
      </div>
      <article
        v-for="story in stories.data.value?.items"
        :key="story.id"
        class="answer-card"
        style="margin-top: 12px"
      >
        <h3>{{ story.title }}</h3>
        <p class="subtle">{{ story.role || '未标注岗位' }} · {{ story.starCount }}/4 STAR</p>
        <p class="prose-content">
          {{ story.body.slice(0, 200) }}{{ story.body.length > 200 ? '…' : '' }}
        </p>
        <button
          type="button"
          class="btn"
          :disabled="Boolean(storyBusy)"
          @click="removeStory(story.id)"
        >
          {{ storyBusy === story.id ? '删除中…' : '删除' }}
        </button>
      </article>
    </section>
  </section>
</template>

<style scoped>
.interview-record > .interview-record-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
@media (max-width: 600px) {
  .interview-record {
    display: grid;
    grid-template-columns: 40px minmax(0, 1fr);
    align-items: start;
  }
  .interview-record-actions {
    grid-column: 2;
  }
}
</style>
