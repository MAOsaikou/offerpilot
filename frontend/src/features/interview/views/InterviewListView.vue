<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { RouterLink, useRouter, useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
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
const role = ref(typeof route.query.role === 'string' ? route.query.role : workspace.state.target)
const kind = ref<InterviewKind>('项目与经历')
const busy = ref(false)
const error = ref('')
const controller = new AbortController()
onBeforeUnmount(() => controller.abort())

async function start() {
  if (!role.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const session = await createInterview(
      {
        role: role.value.trim(),
        kind: kind.value,
        jobId: typeof route.query.jobId === 'string' ? route.query.jobId : undefined,
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
        <h2>开启一场文字练习</h2>
        <p v-if="typeof route.query.jobId === 'string'" class="helper-note">
          本次练习将关联到所选岗位的投递工作区。<RouterLink :to="'/jobs/' + route.query.jobId"
            >查看岗位</RouterLink
          >
        </p>
        <div class="form-grid">
          <label class="field"
            >目标岗位<input
              v-model="role"
              :disabled="busy"
              required
              maxlength="100"
              placeholder="输入想练习的岗位" /></label
          ><label class="field"
            >本次复盘侧重点<select v-model="kind" :disabled="busy">
              <option>项目与经历</option>
              <option>表达与结构</option>
              <option>职业动机</option>
            </select></label
          >
        </div>
        <p class="helper-note">
          使用 5 道固定通用题，自主作答并保存记录。不会生成 AI 评分；需要个性化讨论时可转到 AI
          对话。
        </p>
        <p v-if="error" role="alert">{{ error }}</p>
        <button class="btn primary" type="submit" :disabled="busy || !role.trim()">
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
        <span class="tag">文字练习 · 5 题 · 可随时继续</span>
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
      <RouterLink
        v-for="session in query.data.value"
        :key="session.id"
        :to="'/interviews/' + session.id"
        class="list-row"
        ><span class="stat-icon"><AppIcon name="interviews" /></span>
        <div>
          <h3>{{ session.role }}</h3>
          <small
            >{{ session.kind }} · {{ new Date(session.createdAt).toLocaleString('zh-CN') }}</small
          >
        </div>
        <span class="tag">{{ session.completed ? '已完成' : '进行中' }}</span
        ><span>{{ session.completed ? '查看复盘' : '继续练习' }}</span></RouterLink
      >
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
