<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import ApplicationDrawer from '../components/ApplicationDrawer.vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import { useBoard } from '@/shared/composables/useBoard'
import { applicationStages } from '@/shared/types/board'
import ApplicationCard from '../components/ApplicationCard.vue'
const board = useBoard()
const route = useRoute()
const router = useRouter()
const selected = computed(() =>
  typeof route.query.application === 'string' ? route.query.application : '',
)
function closeDetail() {
  const query = { ...route.query }
  delete query.application
  void router.replace({ query })
}
</script>
<template>
  <section class="page-stack">
    <PageHeading title="每一个机会，都有迹可循" description="用清晰的进度，管理你的求职节奏。">
      <RouterLink to="/jobs" class="btn primary">＋ 发现岗位</RouterLink>
    </PageHeading>
    <div class="helper-note">
      共 {{ board.applications.value.length }} 个机会 ·
      点击机会查看材料、时间线和下一步行动。更新阶段不会发送真实投递。
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
    <ApplicationDrawer v-if="selected" :id="selected" :key="selected" @close="closeDetail" />
    <div
      v-if="
        !board.query.isPending.value &&
        !board.query.isError.value &&
        board.applications.value.length
      "
      class="kanban"
    >
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
