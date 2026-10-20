<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import PageHeading from '@/shared/components/PageHeading.vue'
import { sessionRevision } from '@/shared/utils/authSession'
import {
  getBuiltinFacets,
  listBuiltinOccupations,
  getBuiltinOccupation,
  listBuiltinEntries,
  searchBuiltinKnowledge,
  builtinKinds,
  questionTypes,
  difficulties,
  type BuiltinKind,
} from '@/shared/api/builtin-knowledge'
import { useBuiltinFilters } from '../stores/builtinFilters'
const route = useRoute(),
  filters = useBuiltinFilters()
const queryDraft = ref(filters.q)
const tabs = { OCCUPATION: '职业条目', ...builtinKinds }
const id = computed(() => String(route.params.occupationId || ''))
const criteria = computed(() => ({
  q: filters.q,
  page: filters.page,
  size: 20,
  family: filters.family,
  holland: filters.holland,
}))
const entryCriteria = computed(() => ({
  q: filters.q,
  page: filters.page,
  size: 20,
  kind: filters.tab,
  industry: filters.tab === 'CAMPUS' ? '' : filters.industry,
  role: filters.tab === 'REPORT' || filters.tab === 'CAMPUS' ? '' : filters.role,
  questionType: filters.tab === 'QUESTION' ? filters.questionType : '',
  difficulty: filters.tab === 'QUESTION' ? filters.difficulty : '',
}))
const facets = useQuery({
  queryKey: computed(() => ['builtin-facets', sessionRevision.value]),
  queryFn: ({ signal }) => getBuiltinFacets(signal),
  retry: false,
})
const occupations = useQuery({
  queryKey: computed(() => ['builtin-occupations', sessionRevision.value, criteria.value]),
  queryFn: ({ signal }) => listBuiltinOccupations(criteria.value, signal),
  enabled: computed(() => !id.value && filters.tab === 'OCCUPATION'),
  retry: false,
})
const entries = useQuery({
  queryKey: computed(() => ['builtin-entries', sessionRevision.value, entryCriteria.value]),
  queryFn: ({ signal }) => listBuiltinEntries(entryCriteria.value, signal),
  enabled: computed(() => !id.value && filters.tab !== 'OCCUPATION'),
  retry: false,
})
const detail = useQuery({
  queryKey: computed(() => ['builtin-occupation', sessionRevision.value, id.value]),
  queryFn: ({ signal }) => getBuiltinOccupation(id.value, signal),
  enabled: computed(() => !!id.value),
  retry: false,
})
const referenceText = ref('')
const references = useQuery({
  queryKey: computed(() => [
    'builtin-reference-search',
    sessionRevision.value,
    filters.tab,
    referenceText.value,
  ]),
  queryFn: ({ signal }) =>
    searchBuiltinKnowledge(referenceText.value, filters.tab as BuiltinKind, signal),
  enabled: computed(
    () =>
      !id.value &&
      filters.tab !== 'OCCUPATION' &&
      filters.tab !== 'REPORT' &&
      !!referenceText.value,
  ),
  retry: false,
})
const active = computed(() => (filters.tab === 'OCCUPATION' ? occupations : entries))
const total = computed(() => active.value.data.value?.total ?? 0)
const busy = computed(() => active.value.isPending.value)
watch(
  () => [
    filters.tab,
    filters.family,
    filters.holland,
    filters.industry,
    filters.role,
    filters.questionType,
    filters.difficulty,
  ],
  () => {
    filters.page = 1
    referenceText.value = ''
  },
)
function submit() {
  const same = filters.q === queryDraft.value.trim() && filters.page === 1
  filters.q = queryDraft.value.trim()
  filters.page = 1
  if (same) void active.value.refetch()
}
</script>
<template>
  <section class="page-stack builtin-catalog">
    <PageHeading
      title="内置知识库"
      description="探索职业、理解能力要求，用通用题型练习。来源与缺失数据逐项披露。"
    >
      <RouterLink class="btn" to="/kb">我的资料与笔记</RouterLink>
    </PageHeading>
    <p v-if="facets.data.value" class="subtle" role="status">
      {{ facets.data.value.directOnetOccupations }} 条直接 O*NET 职业 ·
      {{ facets.data.value.occupations }} 个目录 ID（含中文映射） ·
      {{ facets.data.value.counts.QUESTION ?? 0 }} 道 curated 通用题
    </p>
    <div v-if="facets.isError.value" role="alert" class="error-banner">
      分类读取失败。<button class="btn" @click="facets.refetch()">重试分类</button>
    </div>
    <template v-if="id">
      <RouterLink to="/kb/builtin" class="btn">返回职业目录</RouterLink>
      <p v-if="detail.isPending.value" role="status">
        {{
          detail.fetchStatus.value === 'paused' ? '当前离线，恢复连接后重试。' : '正在加载职业详情…'
        }}
      </p>
      <div v-else-if="detail.isError.value" role="alert" class="error-banner">
        职业详情读取失败，条目可能不存在或连接不可用。<button class="btn" @click="detail.refetch()">
          重试详情
        </button>
      </div>
      <article v-else-if="detail.data.value" class="panel form-stack">
        <h2>{{ detail.data.value.name }}</h2>
        <p>{{ detail.data.value.family }}</p>
        <p class="catalog-copy">{{ detail.data.value.description }}</p>
        <h3>RIASEC 兴趣代码：{{ detail.data.value.hollandCode ?? '暂无数据' }}</h3>
        <dl class="catalog-grid">
          <div v-for="(value, axis) in detail.data.value.riasec" :key="axis">
            <dt>{{ axis }}</dt>
            <dd>{{ value }}</dd>
          </div>
        </dl>
        <p v-if="!detail.data.value.riasec">兴趣分数暂无数据</p>
        <h3>核心技能</h3>
        <p v-if="detail.data.value.skillsSource === 'curated'" class="subtle">
          curated：编辑建议，非 O*NET 官方技术技能标准
        </p>
        <ul v-if="detail.data.value.coreSkills?.length">
          <li v-for="skill in detail.data.value.coreSkills" :key="skill.name">
            {{ skill.name }} · {{ skill.level }}
          </li>
        </ul>
        <p v-else>暂无技能数据</p>
        <h3>薪资</h3>
        <p>{{ detail.data.value.salaryNotice }}</p>
        <pre v-if="detail.data.value.salaryRange" class="catalog-copy">{{
          JSON.stringify(detail.data.value.salaryRange, null, 2)
        }}</pre>
        <h3>成长路径</h3>
        <p>{{ detail.data.value.growthPath ?? '暂无数据：尚无可信路径资料' }}</p>
        <p v-if="detail.data.value.growthPathSource === 'curated'" class="subtle">
          curated：编辑提供的方向建议，不保证实际晋升路径
        </p>
        <p>{{ detail.data.value.notice }}</p>
        <p>
          来源：{{ detail.data.value.source }} · O*NET-SOC {{ detail.data.value.onetCode }} ·
          {{ detail.data.value.license }}
        </p>
        <a
          v-if="detail.data.value.sourceUrl"
          :href="detail.data.value.sourceUrl"
          target="_blank"
          rel="noopener noreferrer"
          >查看 O*NET 原始职业</a
        >
        <p class="subtle catalog-copy">{{ detail.data.value.attribution }}</p>
      </article>
    </template>
    <template v-else>
      <nav class="catalog-tabs" aria-label="内置知识分类">
        <button
          v-for="(label, key) in tabs"
          :key="key"
          class="btn"
          :aria-pressed="filters.tab === key"
          @click="filters.tab = key"
        >
          {{ label }}
        </button>
      </nav>
      <p v-if="filters.tab === 'QUESTION'" class="subtle">
        source=curated：通用题型与岗位情境组合（AI 辅助整理），非真实候选人面经；未逐题专家校审。
      </p>
      <p v-if="filters.tab === 'REPORT'" class="subtle">
        计划接入可信报告：当前只有结构与入口，无行业规模、薪资或增长结论。
      </p>
      <form class="panel form-stack" @submit.prevent="submit">
        <label class="field"
          >搜索<input v-model="queryDraft" maxlength="2000" placeholder="职业、技能或练习主题"
        /></label>
        <div v-if="filters.tab === 'OCCUPATION'" class="catalog-grid">
          <label class="field"
            >职业分类<select v-model="filters.family">
              <option value="">全部分类</option>
              <option v-for="family in facets.data.value?.families" :key="family">
                {{ family }}
              </option>
            </select></label
          >
          <label class="field"
            >兴趣代码（前缀）<input
              v-model="filters.holland"
              maxlength="3"
              pattern="[RIASECriasec]{0,3}"
              placeholder="如 I 或 IRC"
          /></label>
        </div>
        <div v-else-if="filters.tab !== 'CAMPUS'" class="catalog-grid">
          <label class="field"
            >行业<select v-model="filters.industry">
              <option value="">全部行业</option>
              <option v-for="industry in facets.data.value?.industries" :key="industry">
                {{ industry }}
              </option>
            </select></label
          >
          <label v-if="filters.tab !== 'REPORT'" class="field"
            >岗位<select v-model="filters.role">
              <option value="">全部岗位</option>
              <option v-for="role in facets.data.value?.roles" :key="role">{{ role }}</option>
            </select></label
          >
          <template v-if="filters.tab === 'QUESTION'">
            <label class="field"
              >题型<select v-model="filters.questionType">
                <option value="">全部题型</option>
                <option v-for="(label, key) in questionTypes" :key="key" :value="key">
                  {{ label }}
                </option>
              </select></label
            >
            <label class="field"
              >难度<select v-model="filters.difficulty">
                <option value="">全部难度</option>
                <option v-for="(label, key) in difficulties" :key="key" :value="key">
                  {{ label }}
                </option>
              </select></label
            >
          </template>
        </div>
        <div class="catalog-tabs">
          <button class="btn primary" type="submit">搜索目录</button
          ><button
            v-if="filters.tab !== 'OCCUPATION' && filters.tab !== 'REPORT'"
            class="btn"
            type="button"
            :disabled="!queryDraft.trim()"
            @click="referenceText = queryDraft.trim()"
          >
            检索参考资料
          </button>
        </div>
      </form>
      <p v-if="busy" role="status">
        {{ active.fetchStatus.value === 'paused' ? '当前离线，恢复连接后重试。' : '正在加载目录…' }}
      </p>
      <div v-else-if="active.isError.value" class="error-banner" role="alert">
        读取失败，请确认登录状态和网络连接。<button class="btn" @click="active.refetch()">
          重试目录
        </button>
      </div>
      <template v-else>
        <p role="status">共 {{ total }} 条，第 {{ filters.page }} 页</p>
        <p v-if="!total">暂无匹配条目，请调整筛选条件。</p>
        <div v-if="filters.tab === 'OCCUPATION'" class="catalog-cards">
          <article
            v-for="item in occupations.data.value?.items"
            :key="item.id"
            class="panel form-stack min-w-0"
          >
            <h2>
              <RouterLink :to="`/kb/builtin/${encodeURIComponent(item.id)}`">{{
                item.name
              }}</RouterLink>
            </h2>
            <p>{{ item.family }} · {{ item.hollandCode }}</p>
            <p class="catalog-copy">{{ item.description }}</p>
            <p>{{ item.salaryNotice }}</p>
            <p class="subtle">{{ item.source }} · {{ item.license }}</p>
          </article>
        </div>
        <article
          v-for="item in filters.tab === 'OCCUPATION' ? [] : entries.data.value?.items"
          :key="item.id"
          class="panel form-stack min-w-0"
        >
          <h2>{{ item.title }}</h2>
          <p>
            {{ item.industry }} {{ item.role }}
            <span v-if="item.questionType">· {{ questionTypes[item.questionType] }}</span>
            <span v-if="item.difficulty">· {{ difficulties[item.difficulty] }}</span>
          </p>
          <p v-if="item.status === 'PLANNED'" class="subtle">计划 · 暂无报告数据</p>
          <p class="catalog-copy">{{ item.content }}</p>
          <details v-if="item.payload.answerGuide">
            <summary>练习思路（非标准答案）</summary>
            <ul>
              <li v-for="guide in item.payload.answerGuide" :key="guide">{{ guide }}</li>
            </ul>
          </details>
          <p v-if="item.payload.plannedSections">
            计划栏目：{{ item.payload.plannedSections.join('、') }}
          </p>
          <p class="subtle catalog-copy">
            {{ item.source }} · {{ item.license }} · {{ item.notice }}
          </p>
        </article>
        <nav class="catalog-tabs" aria-label="分页">
          <button class="btn" :disabled="filters.page <= 1" @click="filters.page--">上一页</button
          ><button class="btn" :disabled="filters.page * 20 >= total" @click="filters.page++">
            下一页
          </button>
        </nav>
      </template>
      <aside
        v-if="referenceText && filters.tab !== 'OCCUPATION' && filters.tab !== 'REPORT'"
        class="panel form-stack"
        aria-label="参考检索"
      >
        <h2>参考资料：{{ referenceText }}</h2>
        <p v-if="references.isPending.value" role="status">
          {{
            references.fetchStatus.value === 'paused' ? '当前离线，连接恢复后可检索。' : '检索中…'
          }}
        </p>
        <p v-else-if="references.isError.value" role="alert">
          参考检索失败。<button class="btn" @click="references.refetch()">重试检索</button>
        </p>
        <template v-else
          ><p>{{ references.data.value?.notice }}</p>
          <p v-if="!references.data.value?.items.length">暂无参考结果</p>
          <article v-for="hit in references.data.value?.items" :key="hit.id">
            <h3>{{ hit.title }}</h3>
            <p class="catalog-copy">{{ hit.excerpt }}</p>
            <p class="subtle">引用 ID：{{ hit.id }} · {{ hit.source }} · {{ hit.notice }}</p>
          </article></template
        >
      </aside>
    </template>
  </section>
</template>
<style scoped>
.catalog-grid,
.catalog-cards {
  display: grid;
  gap: 1rem;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 16rem), 1fr));
}
.catalog-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.catalog-tabs button[aria-pressed='true'] {
  outline: 2px solid var(--color-primary, #315c55);
  outline-offset: 2px;
}
.builtin-catalog {
  min-width: 0;
}
.catalog-copy {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.builtin-catalog input,
.builtin-catalog select {
  max-width: 100%;
  min-width: 0;
}
.builtin-catalog button,
.builtin-catalog summary {
  min-height: 44px;
}
</style>
