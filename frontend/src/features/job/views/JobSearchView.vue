<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import { jobs, useWorkspace } from '@/shared/stores/workspace'
const workspace = useWorkspace()
const query = ref('')
const city = ref('全部城市')
const category = ref('全部方向')
const savedOnly = ref(false)
function resetFilters() {
  query.value = ''
  city.value = '全部城市'
  category.value = '全部方向'
  savedOnly.value = false
}
const saved = (id: string) => workspace.state.applications.some((a) => a.jobId === id)
const filtered = computed(() =>
  jobs.filter(
    (j) =>
      (!query.value ||
        [j.title, j.company, ...j.tags]
          .join(' ')
          .toLowerCase()
          .includes(query.value.toLowerCase())) &&
      (city.value === '全部城市' || j.city === city.value) &&
      (category.value === '全部方向' || j.category === category.value) &&
      (!savedOnly.value || saved(j.id)),
  ),
)
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="发现适合你的下一站"
      eyebrow="FIND YOUR OPPORTUNITY"
      description="从感兴趣的岗位出发，明确准备的方向。"
      ><RouterLink to="/board" class="btn">查看求职看板 →</RouterLink></PageHeading
    >
    <div class="panel form-stack">
      <div class="filter-bar">
        <input
          v-model="query"
          class="input"
          aria-label="搜索岗位、公司或技能"
          placeholder="搜索岗位、公司或技能…"
        /><select v-model="city" class="input" aria-label="城市">
          <option v-for="c in ['全部城市', '杭州', '上海', '北京', '深圳']" :key="c">
            {{ c }}
          </option></select
        ><label class="subtle"><input v-model="savedOnly" type="checkbox" /> 仅看已收藏</label>
      </div>
      <div class="chips">
        <button
          v-for="c in ['全部方向', '研发', '产品', '数据', '设计']"
          :key="c"
          class="chip"
          :class="{ selected: category === c }"
          @click="category = c"
        >
          {{ c }}
        </button>
      </div>
    </div>
    <div class="section-heading">
      <span class="subtle">找到 {{ filtered.length }} 个岗位</span
      ><span class="tag">虚构示例 · 用于体验筛选与看板流程</span>
    </div>
    <div v-if="filtered.length" class="card-grid">
      <article v-for="job in filtered" :key="job.id" class="panel job-card">
        <div class="card-top">
          <span class="company-logo" :style="{ background: job.color }">{{
            job.company.slice(0, 1)
          }}</span>
          <div>
            <h3>{{ job.company }}</h3>
            <p class="subtle">{{ job.city }} · {{ job.category }}</p>
          </div>
          <span class="tag">示例</span>
        </div>
        <RouterLink :to="'/jobs/' + job.id"
          ><h2>{{ job.title }}</h2></RouterLink
        >
        <p class="salary">{{ job.salary }}</p>
        <div class="chips">
          <span v-for="tag in job.tags" :key="tag" class="tag">{{ tag }}</span>
        </div>
        <p class="subtle">{{ job.description }}</p>
        <div class="card-footer">
          <RouterLink :to="'/jobs/' + job.id">了解岗位 ↗</RouterLink
          ><button class="btn" :disabled="saved(job.id)" @click="workspace.saveJob(job.id)">
            {{ saved(job.id) ? '已在看板' : '＋ 收藏岗位' }}
          </button>
        </div>
      </article>
    </div>
    <div v-else class="panel empty-state">
      <h2>暂时没有符合条件的岗位</h2>
      <p>试试其他关键词，或放宽筛选条件。</p>
      <button class="btn" @click="resetFilters">清除筛选</button>
    </div>
  </section>
</template>
