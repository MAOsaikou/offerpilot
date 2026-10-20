<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { RouterLink } from 'vue-router'
import { getJobRecommendations } from '@/shared/api/jobs'
import { useBoard } from '@/shared/composables/useBoard'
import JobSourceBadge from '@/shared/components/JobSourceBadge.vue'
const board = useBoard()
const feed = useQuery({
  queryKey: ['job-recommendations'],
  queryFn: ({ signal }) => getJobRecommendations(signal),
  retry: false,
  staleTime: 60000,
})
</script>
<template>
  <section class="panel form-stack" aria-label="每日岗位推荐">
    <div class="section-heading">
      <h2>今日推荐</h2>
      <button class="btn" :disabled="feed.isFetching.value" @click="feed.refetch()">
        刷新推荐
      </button>
    </div>
    <p class="subtle">
      基于你的画像、目标职业与最近一份已保存简历，从本地真实岗位记录排序；每天缓存，资料变化后更新。推荐分是规则相关度。
    </p>
    <p v-if="feed.isPending.value" role="status">正在读取推荐…</p>
    <div v-else-if="feed.isError.value" class="error-banner" role="alert">
      {{ feed.error.value?.message }}<button class="btn" @click="feed.refetch()">重试</button>
    </div>
    <p v-else-if="!feed.data.value?.items.length" class="helper-note">
      暂无可推荐的真实岗位。请完善画像、保存简历，或先搜索积累岗位记录。
    </p>
    <template v-else>
      <p class="subtle">
        {{ feed.data.value.date }} · {{ feed.data.value.cached ? '今日缓存' : '已更新' }}
      </p>
      <article v-for="item in feed.data.value.items" :key="item.jobId" class="recommendation">
        <RouterLink :to="'/jobs/' + item.jobId"
          ><h3>{{ item.job.title }}</h3></RouterLink
        >
        <p>
          {{ item.job.company || '公司未提供' }} · {{ item.job.locations || '城市未提供' }} · 相关度
          {{ item.score }}
        </p>
        <JobSourceBadge :source="item.job.source" :cached="true" />
        <p class="subtle">岗位更新时间：{{ item.job.updatedAt || '未提供' }}</p>
        <ul>
          <li v-for="reason in item.reasons" :key="reason">{{ reason }}</li>
        </ul>
        <button
          class="btn"
          :disabled="
            board.saved(item.jobId) ||
            board.busy.value ||
            board.query.isPending.value ||
            board.query.isError.value
          "
          @click="board.favorite(item.jobId)"
        >
          {{ board.saved(item.jobId) ? '已加入看板' : '加入看板' }}
        </button>
      </article>
    </template>
    <p v-if="board.query.isError.value" role="alert">
      看板读取失败。<button class="btn" @click="board.query.refetch()">重试看板</button>
    </p>
    <p v-if="board.error.value" class="error-banner" role="alert">{{ board.error.value }}</p>
  </section>
</template>
<style scoped>
.recommendation {
  min-width: 0;
  overflow-wrap: anywhere;
  border-top: 1px solid var(--border);
  padding-top: 16px;
}
.section-heading {
  flex-wrap: wrap;
}
</style>
