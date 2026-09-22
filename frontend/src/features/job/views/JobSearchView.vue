<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import PageHeading from '@/shared/components/PageHeading.vue'
import { listJobSites, searchJobs } from '@/shared/api/jobs'
import { useWorkspace } from '@/shared/stores/workspace'
import { localJobSnapshot } from '@/shared/utils/jobs'
const route = useRoute(),
  router = useRouter(),
  workspace = useWorkspace()
const query = ref(String(route.query.q || '')),
  site = ref(String(route.query.site || ''))
const nature = ref(String(route.query.nature || 'campus')),
  location = ref(String(route.query.location || ''))
const criteria = computed(() => ({
  query: String(route.query.q || ''),
  site: String(route.query.site || ''),
  nature: String(route.query.nature || 'campus'),
  location: String(route.query.location || ''),
}))
const sites = useQuery({
  queryKey: ['job-sites'],
  queryFn: ({ signal }) => listJobSites(signal),
  retry: false,
  staleTime: 600000,
})
const search = useQuery({
  queryKey: computed(() => ['jobs', criteria.value]),
  queryFn: ({ signal }) => searchJobs(criteria.value, signal),
  enabled: computed(() => !!criteria.value.query.trim()),
  retry: false,
  staleTime: 600000,
})
watch(criteria, (value) => {
  query.value = value.query
  site.value = value.site
  nature.value = value.nature
  location.value = value.location
})
const selectedSite = computed(() => sites.data.value?.sites.find((s) => s.id === site.value))
const unsupported = computed(
  () =>
    !!selectedSite.value?.supportedNatures.length &&
    nature.value !== 'all' &&
    !selectedSite.value.supportedNatures.includes(nature.value),
)
function submit() {
  if (query.value.trim() && !unsupported.value) {
    const next = {
      q: query.value.trim(),
      site: site.value,
      nature: nature.value,
      location: location.value.trim(),
    }
    if (
      JSON.stringify(criteria.value) ===
      JSON.stringify({
        query: next.q,
        site: next.site,
        nature: next.nature,
        location: next.location,
      })
    )
      void search.refetch()
    else void router.push({ path: '/jobs', query: next })
  }
}
const saved = (id: string) => workspace.state.applications.some((a) => a.jobId === id)
const name = (id: string) => sites.data.value?.sites.find((s) => s.id === id)?.name || id
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="发现适合你的下一站"
      eyebrow="FIND YOUR OPPORTUNITY"
      description="查询企业官网岗位，把真实机会与你的简历连接起来。"
      ><RouterLink to="/board" class="btn">求职看板 →</RouterLink></PageHeading
    >
    <form class="panel form-stack" @submit.prevent="submit">
      <div class="job-filters">
        <label class="field"
          >岗位关键词<input
            v-model="query"
            required
            maxlength="100"
            placeholder="例如：Java、前端、产品经理"
        /></label>
        <label class="field"
          >企业<select v-model="site">
            <option value="">主要企业</option>
            <option v-for="item in sites.data.value?.sites" :key="item.id" :value="item.id">
              {{ item.name }}
            </option>
          </select></label
        >
        <label class="field"
          >招聘类型<select v-model="nature">
            <option value="campus">校招</option>
            <option value="intern">实习</option>
            <option value="social">社招</option>
            <option value="all">全部</option>
          </select></label
        >
        <label class="field"
          >城市<input v-model="location" maxlength="60" placeholder="不限城市"
        /></label>
      </div>
      <p v-if="unsupported" class="error-banner" role="alert">
        该企业未声明支持此招聘类型，请选择其他类型或企业。
      </p>
      <p v-if="sites.isError.value" class="subtle">
        企业列表暂不可用。你仍可搜索主要企业。<button
          type="button"
          class="btn"
          @click="sites.refetch()"
        >
          重试企业列表
        </button>
      </p>
      <div class="card-footer">
        <span class="subtle">最多展示 30 个结果；企业接口的筛选支持可能不同。</span
        ><button
          class="btn primary"
          :disabled="!query.trim() || unsupported || search.isFetching.value"
        >
          {{ search.isFetching.value ? '正在查询企业官网…' : '搜索真实岗位' }}
        </button>
      </div>
    </form>
    <div v-if="!criteria.query" class="panel empty-state">
      <h2>从一个岗位关键词开始</h2>
      <p>输入方向，选择企业与招聘类型。找到岗位后可以用已解析简历生成匹配报告。</p>
    </div>
    <div v-else-if="search.isFetching.value" class="panel" role="status">
      正在查询岗位，部分企业响应可能需要十几秒…
    </div>
    <div v-else-if="search.isError.value" class="error-banner" role="alert">
      <p>{{ search.error.value?.message }}</p>
      <button class="btn" @click="search.refetch()">重新查询</button>
    </div>
    <template v-else-if="search.data.value">
      <div class="section-heading">
        <span>本次返回 {{ search.data.value.items.length }} 个岗位</span
        ><span class="tag"
          >企业官网 · {{ search.data.value.meta.cached ? '近期缓存' : '本次查询' }}</span
        >
      </div>
      <p class="subtle">
        查询时间：{{ new Date(search.data.value.fetchedAt).toLocaleString('zh-CN') }} · 覆盖
        {{ search.data.value.meta.sites.map(name).join('、') }}
      </p>
      <div v-if="search.data.value.meta.skipped.length" class="helper-note" role="status">
        部分企业未返回结果：{{
          search.data.value.meta.skipped.map((s) => name(s.site)).join('、')
        }}。当前结果不包含这些企业，可稍后重试。
      </div>
      <p v-if="search.data.value.meta.degradedSites.length" class="helper-note">
        {{
          search.data.value.meta.degradedSites.map(name).join('、')
        }}
        使用企业返回列表进行关键词筛选，可能未覆盖所有岗位。
      </p>
      <p v-if="search.data.value.meta.truncated" class="helper-note">
        结果超过展示上限，请缩小关键词或选择具体企业。
      </p>
      <div v-if="!search.data.value.items.length" class="panel empty-state">
        <h2>本次没有找到匹配岗位</h2>
        <p>可以缩短关键词、更换企业或招聘类型。空结果不代表企业没有在招岗位。</p>
      </div>
      <div v-else class="card-grid">
        <article v-for="job in search.data.value.items" :key="job.id" class="panel job-card">
          <div class="section-heading">
            <span>{{ job.company }}</span
            ><span class="tag">{{
              job.nature === 'campus' ? '校招' : job.nature === 'intern' ? '实习' : '企业招聘'
            }}</span>
          </div>
          <RouterLink :to="'/jobs/' + job.id"
            ><h2>{{ job.title }}</h2></RouterLink
          >
          <p class="subtle">
            {{ job.locations || '地点未提供' }} · {{ job.category || '分类未提供' }}
          </p>
          <p class="subtle">企业更新时间：{{ job.updatedAt || '未提供' }}</p>
          <div class="card-footer">
            <RouterLink :to="'/jobs/' + job.id" class="btn primary">查看详情与匹配</RouterLink
            ><button
              class="btn"
              :disabled="saved(job.id)"
              @click="workspace.saveJob(localJobSnapshot(job))"
            >
              {{ saved(job.id) ? '已收藏' : '收藏' }}
            </button>
          </div>
        </article>
      </div>
    </template>
    <p v-if="workspace.storageError" class="error-banner" role="alert">
      {{ workspace.storageError }}
    </p>
  </section>
</template>
<style scoped>
.job-filters {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr;
  gap: 16px;
}
.job-card {
  min-width: 0;
  overflow-wrap: anywhere;
}
@media (max-width: 760px) {
  .job-filters {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 440px) {
  .job-filters {
    grid-template-columns: 1fr;
  }
}
</style>
