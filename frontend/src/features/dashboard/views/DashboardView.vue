<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace, jobs, learningTasks } from '@/shared/stores/workspace'
const workspace = useWorkspace()
const router = useRouter()
const prompt = ref('')
const stats = computed(() => [
  {
    label: '我的简历',
    value: workspace.state.resumes.length,
    icon: 'resumes',
    to: '/resumes',
    detail: '为下一次机会做好准备',
  },
  {
    label: '跟进中的岗位',
    value: workspace.state.applications.filter((a) => a.stage !== '已结束').length,
    icon: 'jobs',
    to: '/board',
    detail: '所有进展，一目了然',
  },
  {
    label: '面试练习',
    value: workspace.state.interviews.length,
    icon: 'interviews',
    to: '/interviews',
    detail: '每一次练习都算数',
  },
  {
    label: '已完成的行动',
    value: workspace.state.tasks.length,
    icon: 'career',
    to: '/career',
    detail: '积累属于你的竞争力',
  },
])
const nextTask = computed(() => learningTasks.find((t) => !workspace.state.tasks.includes(t)))
function send() {
  if (prompt.value.trim())
    void router.push({ path: '/chat', query: { prompt: prompt.value.trim() } })
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      :title="`你好，${workspace.state.name} 👋`"
      eyebrow="YOUR NEXT CHAPTER"
      description="理想的下一站，从今天的一小步开始。"
      ><span class="date-label">{{
        new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' })
      }}</span></PageHeading
    >
    <div class="dashboard-top">
      <section class="copilot-card">
        <div class="copilot-orbit" aria-hidden="true"><span /><span /><AppIcon :size="64" /></div>
        <div class="copilot-copy">
          <span class="light-eyebrow">OFFERPILOT AI</span>
          <h2>你的求职旅程，<br />有一位新搭档。</h2>
          <p>聊聊职业方向、简历或下一场面试。</p>
        </div>
        <form class="quick-prompt" @submit.prevent="send">
          <input
            v-model="prompt"
            maxlength="4000"
            aria-label="告诉 AI 你的求职目标"
            placeholder="今天，想一起解决什么？"
            required
          /><button aria-label="发送到 AI 对话"><AppIcon name="arrow" /></button>
        </form>
        <div class="prompt-chips">
          <button @click="prompt = '帮我梳理适合我的职业方向'">探索职业方向 ↗</button
          ><button @click="prompt = '帮我优化简历里的项目经历'">打磨项目经历 ↗</button
          ><button @click="prompt = '帮我准备一场技术面试'">准备下一场面试 ↗</button>
        </div>
      </section>
      <section class="panel today-card">
        <div class="section-label">
          <span class="eyebrow">TODAY'S FOCUS</span><AppIcon name="discover" />
        </div>
        <h2>让目标更近一点</h2>
        <p class="subtle">目标方向</p>
        <strong class="target-title">{{ workspace.state.target || '还没有设定目标' }}</strong>
        <div class="focus-progress">
          <div
            :style="{ width: (workspace.state.tasks.length / learningTasks.length) * 100 + '%' }"
          />
        </div>
        <p class="subtle">
          {{ workspace.state.tasks.length }} / {{ learningTasks.length }} 项成长行动已完成
        </p>
        <RouterLink to="/career" class="next-action"
          ><span><small>下一步</small>{{ nextTask || '本轮行动已完成，回顾成长记录' }}</span
          ><AppIcon name="arrow"
        /></RouterLink>
      </section>
    </div>
    <div class="stat-grid">
      <RouterLink v-for="stat in stats" :key="stat.label" :to="stat.to" class="panel stat-card"
        ><span class="stat-icon"><AppIcon :name="stat.icon" /></span>
        <p>{{ stat.label }}</p>
        <strong>{{ stat.value.toString().padStart(2, '0') }}</strong
        ><small>{{ stat.detail }}</small></RouterLink
      >
    </div>
    <div class="dashboard-bottom">
      <section class="panel">
        <div class="section-heading">
          <h2>发现你的下一站 <span class="tag">示例岗位</span></h2>
          <RouterLink to="/jobs">查看全部 →</RouterLink>
        </div>
        <RouterLink
          v-for="job in jobs.slice(0, 3)"
          :key="job.id"
          :to="'/jobs/' + job.id"
          class="job-row"
          ><span class="company-logo" :style="{ background: job.color }">{{
            job.company.slice(0, 1)
          }}</span>
          <div>
            <h3>{{ job.title }}</h3>
            <p>{{ job.company }} · {{ job.city }} · {{ job.tags[0] }}</p>
          </div>
          <span class="salary">{{ job.salary }}</span
          ><AppIcon name="arrow" :size="17"
        /></RouterLink>
      </section>
      <section class="panel toolkit">
        <div class="section-heading">
          <h2>求职工具箱</h2>
          <span class="subtle">准备好，再出发</span>
        </div>
        <RouterLink
          v-for="entry in [
            {
              to: '/resumes',
              icon: 'resumes',
              title: '让经历被看见',
              desc: '整理简历，呈现你的真实优势',
            },
            {
              to: '/interviews',
              icon: 'interviews',
              title: '把下一场，当作主场',
              desc: '从自我介绍开始练习表达',
            },
            { to: '/kb', icon: 'kb', title: '把经验变成积累', desc: '收藏知识，留住每一次复盘' },
          ]"
          :key="entry.to"
          :to="entry.to"
          class="tool-row"
          ><span class="stat-icon"><AppIcon :name="entry.icon" /></span>
          <div>
            <h3>{{ entry.title }}</h3>
            <p>{{ entry.desc }}</p>
          </div>
          <span>↗</span></RouterLink
        >
      </section>
    </div>
  </section>
</template>
