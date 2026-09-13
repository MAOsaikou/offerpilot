<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
const workspace = useWorkspace()
const router = useRouter()
const role = ref(workspace.state.target)
const kind = ref('项目与经历')
function start() {
  if (!role.value.trim()) return
  const id = crypto.randomUUID()
  workspace.state.interviews.unshift({
    id,
    role: role.value.trim(),
    kind: kind.value,
    answers: [],
    question: 0,
    completed: false,
    date: new Date().toLocaleString('zh-CN'),
  })
  void router.push('/interviews/' + id)
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="让表达，先于机会准备好"
      eyebrow="INTERVIEW PRACTICE"
      description="在轻松的练习里，把经历讲清楚，把信心建立起来。"
    />
    <div class="split-content">
      <form class="panel form-stack" @submit.prevent="start">
        <h2>开启一场文字练习</h2>
        <div class="form-grid">
          <label class="field"
            >目标岗位<input
              v-model="role"
              required
              maxlength="100"
              placeholder="输入想练习的岗位" /></label
          ><label class="field"
            >本次复盘侧重点<select v-model="kind">
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
        <button class="btn primary" type="submit">开始练习 →</button>
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
        <span class="subtle">{{ workspace.state.interviews.length }} 场练习</span>
      </div>
      <div v-if="!workspace.state.interviews.length" class="empty-state">
        <h2>第一场练习，从自我介绍开始</h2>
        <p>完成后的回答会留在这里，方便你反复复盘。</p>
      </div>
      <RouterLink
        v-for="session in workspace.state.interviews"
        :key="session.id"
        :to="'/interviews/' + session.id"
        class="list-row"
        ><span class="stat-icon"><AppIcon name="interviews" /></span>
        <div>
          <h3>{{ session.role }}</h3>
          <small>{{ session.kind }} · {{ session.date }}</small>
        </div>
        <span class="tag">{{ session.completed ? '已完成' : '进行中' }}</span
        ><span>{{ session.completed ? '查看复盘' : '继续练习' }} →</span></RouterLink
      >
    </section>
  </section>
</template>
