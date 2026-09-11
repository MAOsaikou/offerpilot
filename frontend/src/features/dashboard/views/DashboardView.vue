<script setup lang="ts">
import { RouterLink } from 'vue-router'

const entries = [
  { name: 'chat', title: '对话', description: '描述你的目标，Agent 会规划并调用工具完成' },
  { name: 'discover', title: '职业探索', description: '对话式测评，生成画像与职业推荐' },
  { name: 'resumes', title: '简历中心', description: '上传解析、诊断、定向改写与版本管理' },
  { name: 'jobs', title: '岗位搜索', description: '校招岗位检索、匹配报告与日历' },
  { name: 'board', title: '求职看板', description: '投递进度拖拽管理，Agent 自动同步' },
  { name: 'interviews', title: '模拟面试', description: '文字 / 语音面试与表现报告' },
] as const
</script>

<template>
  <section class="mx-auto max-w-5xl px-2 py-8">
    <header class="rise-in space-y-3">
      <p class="font-mono text-xs tracking-[0.22em] text-muted-foreground uppercase">
        {{ 'OfferPilot' }}
      </p>
      <h1 class="text-4xl font-semibold tracking-tight">
        从一个<span class="text-gradient">目标</span>开始
      </h1>
      <p class="max-w-xl text-sm leading-relaxed text-muted-foreground">
        Agent 会拆解任务、调用工具、需要你确认时再开口问——剩下的交给它。
      </p>
    </header>

    <div class="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <RouterLink
        v-for="(entry, index) in entries"
        :key="entry.name"
        :to="{ name: entry.name }"
        :style="{ animationDelay: `${140 + index * 70}ms` }"
        class="glass-card rise-in rounded-2xl p-5"
      >
        <span
          class="mb-4 flex size-9 items-center justify-center rounded-xl bg-primary/15 text-sm font-medium text-primary"
        >
          {{ entry.title.slice(0, 1) }}
        </span>
        <h2 class="text-sm font-medium">{{ entry.title }}</h2>
        <p class="mt-1.5 text-sm leading-relaxed text-muted-foreground">
          {{ entry.description }}
        </p>
      </RouterLink>
    </div>
  </section>
</template>
