<script setup lang="ts">
import { RouterLink } from 'vue-router'

const entries = [
  {
    name: 'chat',
    title: '对话',
    description: '描述你的目标，Agent 会规划并调用工具完成',
  },
  { name: 'discover', title: '职业探索', description: '对话式测评，生成画像与职业推荐' },
  { name: 'resumes', title: '简历中心', description: '上传解析、诊断、定向改写与版本管理' },
  { name: 'jobs', title: '岗位搜索', description: '校招岗位检索、匹配报告与日历' },
  { name: 'board', title: '求职看板', description: '投递进度拖拽管理，Agent 自动同步' },
  { name: 'interviews', title: '模拟面试', description: '文字 / 语音面试与表现报告' },
] as const
</script>

<template>
  <section class="mx-auto max-w-5xl px-6 py-10">
    <header class="space-y-1">
      <h1 class="text-2xl font-semibold text-foreground">工作台</h1>
      <p class="text-sm text-muted-foreground">从一个目标开始，剩下的交给 Agent</p>
    </header>

    <div class="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <RouterLink
        v-for="entry in entries"
        :key="entry.name"
        :to="{ name: entry.name }"
        class="rounded-xl border border-border bg-card p-5 transition-colors hover:border-primary"
      >
        <h2 class="text-sm font-medium text-foreground">{{ entry.title }}</h2>
        <p class="mt-1.5 text-sm text-muted-foreground">{{ entry.description }}</p>
      </RouterLink>
    </div>
  </section>
</template>
