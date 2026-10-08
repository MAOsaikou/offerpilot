<script setup lang="ts">
import { RouterLink } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import { useBoard } from '@/shared/composables/useBoard'
import { applicationStages } from '@/shared/types/board'
import ApplicationCard from '../components/ApplicationCard.vue'
const board = useBoard()
</script>
<template>
  <section class="page-stack">
    <PageHeading title="每一个机会，都有迹可循" description="用清晰的进度，管理你的求职节奏。">
      <RouterLink to="/jobs" class="btn primary">＋ 发现岗位</RouterLink>
    </PageHeading>
    <div class="helper-note">
      共 {{ board.applications.value.length }} 个机会 · 收藏、状态和备注保存在服务端。
      更新状态不会发送真实投递。使用同一身份可读取这些记录。
      旧本地收藏仍保留在工作区导出中，可在岗位页重新收藏真实岗位。
    </div>
    <p v-if="board.query.isPending.value" class="panel" role="status">正在加载看板…</p>
    <div v-else-if="board.query.isError.value" class="error-banner" role="alert">
      {{ board.query.error.value?.message }}
      <button class="btn" @click="board.query.refetch()">重试加载</button>
    </div>
    <div v-else-if="!board.applications.value.length" class="panel empty-state">
      <h2>把第一个机会放进看板</h2>
      <p>在岗位页点击收藏，随后就能在这里记录投递、面试和 Offer 进度。</p>
      <RouterLink to="/jobs" class="btn primary">去发现岗位</RouterLink>
    </div>
    <div v-else class="kanban">
      <section v-for="(label, status) in applicationStages" :key="status" class="kanban-column">
        <h2>
          {{ label }}
          <span>{{ board.applications.value.filter((a) => a.status === status).length }}</span>
        </h2>
        <ApplicationCard
          v-for="app in board.applications.value.filter((a) => a.status === status)"
          :key="app.id"
          :application="app"
        />
        <p v-if="!board.applications.value.some((a) => a.status === status)" class="subtle">
          暂无机会
        </p>
      </section>
    </div>
  </section>
</template>
