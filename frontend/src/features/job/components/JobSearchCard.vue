<script setup lang="ts">
import { computed } from 'vue'
import JobSourceBadge from '@/shared/components/JobSourceBadge.vue'
import { RouterLink } from 'vue-router'
import type { ChatJobSearch } from '@/shared/types/jobs'
import type { JobCardItem } from '@/shared/types/cards'

const props = defineProps<{ payload: ChatJobSearch | JobCardItem }>()
const search = computed(() => ('schemaVersion' in props.payload ? props.payload : null))
const legacy = computed(() => ('id' in props.payload ? props.payload : null))
const more = computed(() => ({
  path: '/jobs',
  query: {
    q: search.value?.criteria.query || '',
    site: search.value?.criteria.site || '',
    nature: search.value?.criteria.nature || 'all',
    location: search.value?.criteria.location || '',
  },
}))
const natureLabel = (value: string) =>
  ({ campus: '校招', intern: '实习', social: '社招', all: '全部类型' })[value.toLowerCase()] ||
  '企业招聘'
</script>

<template>
  <section v-if="search" class="panel form-stack search-card" aria-label="岗位搜索结果">
    <div class="section-heading">
      <h3>找到的岗位</h3>
      <JobSourceBadge
        :source="search.meta.source"
        :snapshot-date="search.meta.snapshotDate"
        :cached="search.meta.cached"
      />
    </div>
    <p class="subtle">
      {{ search.criteria.query }} · {{ search.criteria.location || '不限城市' }} ·
      {{ natureLabel(search.criteria.nature)
      }}<span v-if="search.criteria.site"> · {{ search.criteria.site }}</span>
    </p>
    <p class="subtle">
      查询时间：{{ new Date(search.fetchedAt).toLocaleString('zh-CN') }} · 本次返回
      {{ search.returnedCount }} 条，展示 {{ search.items.length }} 条。此数量不是全部在招岗位总数。
    </p>
    <p v-if="search.meta.source === 'LOCAL_SNAPSHOT'" class="subtle">
      人工整理的示例岗位快照，非实时数据；当前是否开放请到企业官网核实。
    </p>
    <p v-if="search.meta.skipped.length" class="helper-note" role="status">
      部分企业暂不可用：{{
        search.meta.skipped.map((s) => s.site).join('、')
      }}。本次结果不包含这些企业。
    </p>
    <p v-if="search.meta.degradedSites.length" class="helper-note">
      {{ search.meta.degradedSites.join('、') }} 使用降级过滤，可能未覆盖全部岗位。
    </p>
    <p v-if="search.meta.truncated" class="helper-note">
      上游结果已截断，请缩小关键词或选择具体企业。
    </p>
    <p v-if="!search.items.length" role="status">
      本次没有找到岗位。可以调整关键词、企业或招聘类型；空结果不代表企业没有在招岗位。
    </p>
    <article v-for="job in search.items" :key="job.id" class="search-item">
      <h4>{{ job.title }}</h4>
      <p>
        {{ job.company || job.site }} · {{ job.locations || '地点未提供' }} ·
        {{ natureLabel(job.nature) }}
      </p>
      <p class="subtle">
        {{ search.meta.source === 'LOCAL_SNAPSHOT' ? '快照日期' : '企业更新时间' }}：{{
          search.meta.source === 'LOCAL_SNAPSHOT'
            ? search.meta.snapshotDate
            : job.updatedAt || '未提供'
        }}
      </p>
      <RouterLink :to="'/jobs/' + encodeURIComponent(job.id)" class="btn"
        >查看详情与选择简历匹配</RouterLink
      >
    </article>
    <div class="card-footer">
      <RouterLink :to="more" class="btn">按这些条件继续搜索</RouterLink>
    </div>
    <p class="subtle">继续搜索会重新查询，结果可能变化。岗位是否开放以企业官网为准。</p>
  </section>
  <section v-else-if="legacy" class="panel form-stack search-card">
    <h3>{{ legacy.title }}</h3>
    <p>{{ legacy.company }} · {{ legacy.cities.join('、') }}</p>
    <RouterLink :to="'/jobs/' + encodeURIComponent(legacy.id)" class="btn">查看岗位详情</RouterLink>
  </section>
</template>

<style scoped>
.search-card {
  min-width: 0;
  overflow-wrap: anywhere;
}
.search-item {
  padding-block: 14px;
  border-top: 1px solid var(--border);
  display: grid;
  gap: 8px;
}
.search-item .btn {
  justify-self: start;
  white-space: normal;
}
.section-heading {
  flex-wrap: wrap;
  gap: 8px;
}
</style>
