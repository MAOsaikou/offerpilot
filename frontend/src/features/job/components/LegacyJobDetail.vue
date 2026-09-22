<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import { jobs, useWorkspace } from '@/shared/stores/workspace'
const route = useRoute()
const workspace = useWorkspace()
const job = computed(() => jobs.find((j) => j.id === route.params.id))
const saved = computed(() => workspace.state.applications.some((a) => a.jobId === job.value?.id))
</script>
<template>
  <section v-if="job" class="page-stack">
    <PageHeading
      :title="job.title"
      :description="job.company + ' · ' + job.city"
      eyebrow="OPPORTUNITY DETAIL"
      ><RouterLink to="/jobs" class="btn">← 返回岗位</RouterLink
      ><button class="btn primary" :disabled="saved" @click="workspace.saveJob(job.id)">
        {{ saved ? '已收藏至看板' : '收藏至看板' }}
      </button></PageHeading
    >
    <div class="helper-note">这是虚构示例岗位，仅用于本地体验。收藏不会向企业发送投递。</div>
    <div class="split-content">
      <article class="panel form-stack">
        <h2>岗位介绍</h2>
        <p class="prose-content">{{ job.description }}</p>
        <h2>能力关键词</h2>
        <div class="chips">
          <span v-for="tag in job.tags" :key="tag" class="chip">{{ tag }}</span>
        </div>
        <h2>准备建议</h2>
        <p class="subtle">
          整理与你申请方向相关的项目经历，说明自己的职责、具体行动与可验证成果。先核实企业官方招聘信息，再决定投递。
        </p>
        <RouterLink
          :to="{
            path: '/chat',
            query: {
              mode: 'JOB',
              prompt: '请帮我分析以下岗位需要准备哪些能力：' + job.title + '。' + job.description,
            },
          }"
          class="btn"
          >在 AI 对话中讨论这个方向 ↗</RouterLink
        >
      </article>
      <aside class="panel form-stack">
        <span class="company-logo" :style="{ background: job.color }">{{
          job.company.slice(0, 1)
        }}</span>
        <h2>{{ job.company }}</h2>
        <p class="large-number" style="font-size: 1.5rem">{{ job.salary }}</p>
        <p class="subtle">
          工作城市：{{ job.city }}<br />岗位方向：{{ job.category }}<br />数据来源：本地示例
        </p>
        <RouterLink to="/resumes" class="btn primary">准备我的简历</RouterLink
        ><RouterLink to="/board" class="btn">跟进投递进展</RouterLink>
      </aside>
    </div>
  </section>
  <div v-else class="panel empty-state">
    <h2>没有找到这个岗位</h2>
    <RouterLink to="/jobs" class="btn">返回岗位列表</RouterLink>
  </div>
</template>
