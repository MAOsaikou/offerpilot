<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { createInterview, listInterviews } from '@/shared/api/interviews'
import type { InterviewKind } from '@/shared/types/interview'
import type { Interview } from '@/shared/types/workspace'
import { questions } from '@/shared/utils/interviewQuestions'
import { exportInterview } from '@/shared/utils/interviewExport'

const workspace = useWorkspace()
const router = useRouter()
const cache = useQueryClient()
const query = useQuery({
  queryKey: ['interviews'],
  queryFn: ({ signal }) => listInterviews(signal),
  retry: false,
})
const role = ref(workspace.state.target)
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
      { role: role.value.trim(), kind: kind.value },
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
  </section>
</template>
