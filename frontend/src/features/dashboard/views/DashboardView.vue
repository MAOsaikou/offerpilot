<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import AppIcon from '@/shared/components/AppIcon.vue'
import { listResumes } from '@/shared/api/resumes'
import { useWorkspace, learningTasks } from '@/shared/stores/workspace'
import { useBoard } from '@/shared/composables/useBoard'
import { applicationStages } from '@/shared/types/board'
import RouteStrip from '../components/RouteStrip.vue'
import PendingAgentWrites from '@/shared/components/PendingAgentWrites.vue'
const workspace = useWorkspace()
const board = useBoard()
const router = useRouter()
const prompt = ref('')
const resumes = useQuery({
  queryKey: ['resumes', 1],
  queryFn: ({ signal }) => listResumes(1, signal),
})
const dueToday = new Intl.DateTimeFormat('sv-SE').format(new Date())
const dueActions = computed(() =>
  board.applications.value
    .filter(
      (app) =>
        app.status !== 'CLOSED' &&
        app.nextAction?.trim() &&
        app.nextActionAt &&
        app.nextActionAt <= dueToday,
    )
    .sort((a, b) => a.nextActionAt!.localeCompare(b.nextActionAt!) || a.id.localeCompare(b.id))
    .slice(0, 5),
)
const savedJobs = computed(() => board.applications.value.map((a) => a.job).slice(0, 4))
const stops = computed(() =>
  Object.entries(applicationStages).map(([status, stage]) => ({
    stage,
    count: board.applications.value.filter((a) => a.status === status).length,
  })),
)
const resumeCount = computed(() =>
  resumes.isError.value
    ? undefined
    : workspace.state.resumes.length + (resumes.data.value?.total ?? 0),
)
const nextTask = computed(() => learningTasks.find((t) => !workspace.state.tasks.includes(t)))
const progress = computed(() => (workspace.state.tasks.length / learningTasks.length) * 100)
const today = new Date().toLocaleDateString('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'long',
})
const suggestions = [
  { label: '梳理职业方向', text: '帮我梳理适合我的职业方向' },
  { label: '打磨项目经历', text: '帮我优化简历里的项目经历' },
  { label: '准备下一场面试', text: '帮我准备一场技术面试' },
]
const toolkit = [
  { to: '/resumes', icon: 'resumes', title: '简历', desc: '上传解析，或继续编辑本地草稿' },
  { to: '/interviews', icon: 'interviews', title: '模拟面试', desc: '从自我介绍开始练习表达' },
  { to: '/kb', icon: 'kb', title: '知识库', desc: '记下每一次复盘' },
]
function send() {
  if (prompt.value.trim())
    void router.push({ path: '/chat', query: { prompt: prompt.value.trim() } })
}
</script>
<template>
  <section class="page-stack dashboard">
    <header class="greeting">
      <h1>你好，{{ workspace.state.name }}</h1>
      <p class="date-label">{{ today }}</p>
    </header>

    <div class="ask-block">
      <form class="ask" @submit.prevent="send">
        <AppIcon name="spark" :size="18" />
        <input
          v-model="prompt"
          maxlength="4000"
          aria-label="告诉 AI 你的求职目标"
          placeholder="问 OfferPilot：今天想解决什么？"
          required
        />
        <button class="btn primary" :disabled="!prompt.trim()">提问</button>
      </form>
      <div class="chips">
        <button
          v-for="(item, index) in suggestions"
          :key="item.label"
          type="button"
          class="chip stagger-in"
          :style="{ '--i': index + 2 }"
          @click="prompt = item.text"
        >
          {{ item.label }}
        </button>
      </div>
    </div>

    <PendingAgentWrites />
    <section class="route-section">
      <div class="section-heading">
        <h2>你的航线</h2>
        <RouterLink to="/board">打开看板</RouterLink>
      </div>
      <p v-if="board.query.isError.value" class="error-banner" role="alert">
        {{ board.query.error.value?.message
        }}<button class="btn" @click="board.query.refetch()">重试加载</button>
      </p>
      <RouteStrip v-else :stops="stops" :loading="board.query.isPending.value" />
    </section>

    <section class="panel next-panel">
      <div class="section-heading">
        <h2>接下来要做</h2>
        <RouterLink to="/board">查看全部机会</RouterLink>
      </div>
      <p v-if="board.query.isPending.value" role="status">正在读取行动…</p>
      <p v-else-if="board.query.isError.value" role="alert">行动加载失败，请在上方重试。</p>
      <template v-else>
        <RouterLink
          v-for="app in dueActions"
          :key="app.id"
          :to="{ path: '/board', query: { application: app.id } }"
          class="next-action due-action"
        >
          <span class="next-dot" aria-hidden="true" />
          <span
            ><strong>{{ app.nextAction }}</strong
            ><small
              >{{ app.job.company }} · {{ app.job.title }} · {{ app.nextActionAt }}（{{
                app.nextActionAt! < dueToday ? '已逾期' : '今天到期'
              }}）</small
            ></span
          >
          <AppIcon name="arrow" :size="18" />
        </RouterLink>
        <p v-if="!dueActions.length" class="subtle">
          今天没有到期行动。在机会详情中安排下一步和日期。
        </p>
      </template>
    </section>

    <div class="dashboard-grid">
      <section class="panel next-panel">
        <div class="section-heading">
          <h2>下一步</h2>
          <RouterLink to="/career">成长路线</RouterLink>
        </div>
        <p class="subtle">目标方向</p>
        <p class="target-title">{{ workspace.state.target || '还没有设定目标' }}</p>
        <div class="focus-progress" role="presentation">
          <div :style="{ width: progress + '%' }" />
        </div>
        <p class="subtle">
          已完成 <span class="num">{{ workspace.state.tasks.length }}</span> /
          <span class="num">{{ learningTasks.length }}</span> 项成长行动。你有
          <span class="num">{{ resumeCount ?? '—' }}</span> 份简历、
          <span class="num">{{ workspace.state.interviews.length }}</span> 场面试练习。
        </p>
        <RouterLink to="/career" class="next-action">
          <span class="next-dot" aria-hidden="true" />
          <span>{{ nextTask || '本轮行动已完成，回顾一下成长记录' }}</span>
          <AppIcon name="arrow" :size="18" />
        </RouterLink>
        <div class="toolkit">
          <RouterLink v-for="entry in toolkit" :key="entry.to" :to="entry.to" class="list-row"
            ><span class="stat-icon"><AppIcon :name="entry.icon" :size="18" /></span>
            <div>
              <h3>{{ entry.title }}</h3>
              <small>{{ entry.desc }}</small>
            </div>
          </RouterLink>
        </div>
      </section>

      <section class="panel">
        <div class="section-heading">
          <h2>收藏的机会</h2>
          <RouterLink to="/jobs">搜索岗位</RouterLink>
        </div>
        <RouterLink
          v-for="(job, index) in savedJobs"
          :key="job.id"
          :to="'/jobs/' + job.id"
          class="job-row stagger-in"
          :style="{ '--i': index }"
          ><span class="company-logo">{{ job.company.slice(0, 1) }}</span>
          <div>
            <h3>{{ job.title }}</h3>
            <p>{{ job.company }}，{{ job.locations }}</p>
          </div>
          <AppIcon name="arrow" :size="16"
        /></RouterLink>
        <div
          v-if="!board.query.isPending.value && !board.query.isError.value && !savedJobs.length"
          class="empty-state"
        >
          <p>还没有收藏岗位。搜索企业官网的真实岗位，收藏后就会出现在航线上。</p>
          <RouterLink to="/jobs" class="btn">搜索岗位</RouterLink>
        </div>
      </section>
    </div>
  </section>
</template>
<style scoped>
.greeting {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
}
.greeting h1 {
  font-size: 40px;
  font-weight: 650;
  letter-spacing: -0.015em;
}
.ask-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 760px;
}
.ask {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 6px 6px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
  box-shadow: var(--shadow-float);
  transition:
    border-color var(--dur-fast),
    box-shadow var(--dur-mid);
}
.ask:focus-within {
  border-color: var(--primary);
  box-shadow:
    var(--shadow-float),
    0 0 0 3px color-mix(in srgb, var(--primary) 14%, transparent);
}
.ask > svg {
  flex-shrink: 0;
  color: var(--primary);
}
.ask input {
  flex: 1;
  min-width: 0;
  min-height: 44px;
  border: 0;
  background: transparent;
  color: var(--foreground);
  font: inherit;
  font-size: 16px;
  outline: none;
}
.ask input::placeholder {
  color: var(--muted-foreground);
}
.ask .btn {
  min-height: 44px;
  padding: 0 20px;
}
.route-section {
  padding: 24px 0 8px;
  border-top: 1px solid var(--border);
}
.route-section .section-heading {
  margin-bottom: 20px;
}
.dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}
.next-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.target-title {
  margin-top: -6px;
  font-size: 21px;
  font-weight: 650;
}
.next-action {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 6px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 10px;
  font-weight: 550;
  transition:
    border-color var(--dur-fast),
    color var(--dur-fast);
}
.due-action {
  overflow-wrap: anywhere;
}
.due-action strong,
.due-action small {
  display: block;
}
.due-action small {
  margin-top: 4px;
  color: var(--muted-foreground);
}
.next-action span:nth-child(2) {
  flex: 1;
}
.next-action > svg {
  color: var(--muted-foreground);
  transition: transform var(--dur-mid) var(--ease-out);
}
.next-action:hover {
  border-color: var(--primary);
}
.next-action:hover > svg {
  color: var(--primary);
  transform: translateX(3px);
}
.next-dot {
  width: 8px;
  height: 8px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--primary);
}
.toolkit {
  margin-top: 8px;
}
@media (max-width: 1000px) {
  .route-section {
    padding: 24px 0 8px;
    border-top: 1px solid var(--border);
  }
  .route-section .section-heading {
    margin-bottom: 20px;
  }
  .dashboard-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 640px) {
  .greeting {
    flex-direction: column;
    gap: 2px;
  }
  .greeting h1 {
    font-size: 30px;
  }
  .ask {
    padding-left: 12px;
  }
  .ask .btn {
    padding: 0 14px;
  }
}
</style>
