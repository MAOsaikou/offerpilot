<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import PageHeading from '@/shared/components/PageHeading.vue'
import CardRenderer from '@/widgets/CardRenderer/CardRenderer.vue'
import LegacyJobDetail from '../components/LegacyJobDetail.vue'
import { getJob, refreshJob, matchJob, listJobMatches, getJobMatch } from '@/shared/api/jobs'
import { listResumes, listResumeVersions } from '@/shared/api/resumes'
import { useWorkspace } from '@/shared/stores/workspace'
import { localJobSnapshot, officialJobUrl } from '@/shared/utils/jobs'
import type { EvidenceMatch } from '@/shared/types/jobs'
const route = useRoute(),
  workspace = useWorkspace()
const id = computed(() => String(route.params.id)),
  real = computed(() => id.value.startsWith('job_'))
const job = useQuery({
  queryKey: computed(() => ['job', id.value]),
  queryFn: ({ signal }) => getJob(id.value, signal),
  enabled: real,
  retry: false,
})
const page = ref(1),
  resumeId = ref(''),
  versionId = ref('')
const resumes = useQuery({
  queryKey: computed(() => ['resumes', page.value]),
  queryFn: ({ signal }) => listResumes(page.value, signal),
  enabled: real,
  retry: false,
})
const versions = useQuery({
  queryKey: computed(() => ['resume-versions', resumeId.value]),
  queryFn: ({ signal }) => listResumeVersions(resumeId.value, signal),
  enabled: computed(() => !!resumeId.value && real.value),
  retry: false,
})
const history = useQuery({
  queryKey: computed(() => ['job-matches', id.value]),
  queryFn: ({ signal }) => listJobMatches(id.value, signal),
  enabled: real,
  retry: false,
  refetchInterval: (query) =>
    query.state.data?.some((r) => r.status === 'RUNNING') ? 4000 : false,
})
const busy = ref(false),
  refreshing = ref(false),
  reportLoading = ref(false),
  error = ref(''),
  refreshError = ref(''),
  report = ref<EvidenceMatch>()
let generation = 0,
  reportRequest = 0
watch(id, () => {
  generation++
  reportRequest++
  report.value = undefined
  busy.value = false
  refreshing.value = false
  reportLoading.value = false
  error.value = ''
  refreshError.value = ''
})
watch(resumeId, () => {
  versionId.value = ''
})
watch(
  () => versions.data.value,
  (items) => {
    if (items?.length && !versionId.value) versionId.value = items[0]!.id
  },
)
const saved = computed(() => workspace.state.applications.some((a) => a.jobId === id.value))
function changeResumePage(delta: number) {
  page.value += delta
  resumeId.value = ''
}
async function refresh() {
  const epoch = generation
  refreshing.value = true
  refreshError.value = ''
  try {
    await refreshJob(id.value)
    if (epoch === generation) await job.refetch()
  } catch (e) {
    if (epoch === generation) refreshError.value = (e as Error).message
  } finally {
    if (epoch === generation) refreshing.value = false
  }
}
async function analyze() {
  if (!resumeId.value || !versionId.value || busy.value) return
  const epoch = generation
  busy.value = true
  error.value = ''
  report.value = undefined
  reportRequest++
  try {
    const value = await matchJob(id.value, resumeId.value, versionId.value, crypto.randomUUID())
    if (epoch === generation) report.value = value
  } catch (e) {
    if (epoch === generation) error.value = (e as Error).message
  } finally {
    if (epoch === generation) {
      busy.value = false
      void history.refetch()
      void job.refetch()
    }
  }
}
async function openReport(reportId: string) {
  const request = ++reportRequest
  reportLoading.value = true
  error.value = ''
  report.value = undefined
  try {
    const value = await getJobMatch(reportId)
    if (request === reportRequest) report.value = value
  } catch (e) {
    if (request === reportRequest) error.value = (e as Error).message
  } finally {
    if (request === reportRequest) reportLoading.value = false
  }
}
</script>
<template>
  <LegacyJobDetail v-if="!real" />
  <section v-else class="page-stack">
    <PageHeading
      :title="job.data.value?.title || '岗位详情'"
      :description="
        job.data.value
          ? job.data.value.company + ' · ' + (job.data.value.locations || '地点未提供')
          : '正在读取岗位记录'
      "
      ><RouterLink to="/jobs" class="btn">← 返回搜索</RouterLink></PageHeading
    >
    <p v-if="job.isPending.value" class="panel" role="status">正在加载岗位…</p>
    <div v-else-if="job.isError.value" class="error-banner" role="alert">
      {{ job.error.value?.message }}<button class="btn" @click="job.refetch()">重试</button>
    </div>
    <template v-else-if="job.data.value">
      <div class="panel form-stack">
        <div class="card-footer">
          <span class="tag">企业官网 · {{ job.data.value.company }}</span
          ><a
            v-if="officialJobUrl(job.data.value.url)"
            :href="officialJobUrl(job.data.value.url)"
            target="_blank"
            rel="noopener noreferrer"
            class="btn"
            >查看官网原岗位 ↗</a
          ><button
            class="btn"
            :disabled="saved"
            @click="workspace.saveJob(localJobSnapshot(job.data.value))"
          >
            {{ saved ? '已收藏至看板' : '收藏至看板' }}</button
          ><button class="btn" :disabled="refreshing || busy" @click="refresh">
            {{ refreshing ? '正在获取…' : '获取 / 更新完整详情' }}
          </button>
        </div>
        <p class="subtle">
          企业更新时间：{{ job.data.value.updatedAt || '未提供' }} · 详情获取时间：{{
            job.data.value.detailFetchedAt
              ? new Date(job.data.value.detailFetchedAt).toLocaleString('zh-CN')
              : '尚未获取完整详情'
          }}
        </p>
        <p v-if="refreshError" class="error-banner" role="alert">{{ refreshError }}</p>
        <h2>工作职责</h2>
        <p class="prose-content">
          {{ job.data.value.description || '搜索结果未包含完整职责，请获取详情。' }}
        </p>
        <h2>任职要求</h2>
        <p class="prose-content">
          {{ job.data.value.requirement || '企业未提供独立要求字段，请结合工作职责和官网查看。' }}
        </p>
        <p class="helper-note">
          岗位可能调整或关闭，以企业官网为准。收藏与看板状态仅保存在此浏览器，不会代你投递。
        </p>
      </div>
      <section class="panel form-stack">
        <h2>这份岗位与你的简历有多匹配？</h2>
        <p class="subtle">
          选择已解析并保存的简历版本。分析将使用该版本及企业岗位内容，通常需要几十秒。
        </p>
        <p v-if="resumes.isPending.value" role="status">正在加载简历…</p>
        <div v-else-if="resumes.isError.value" class="error-banner" role="alert">
          {{ resumes.error.value?.message
          }}<button class="btn" @click="resumes.refetch()">重试简历列表</button>
        </div>
        <template v-else>
          <div class="match-selectors">
            <label class="field"
              >简历<select v-model="resumeId" :disabled="busy">
                <option value="">请选择已解析简历</option>
                <option
                  v-for="resume in resumes.data.value?.items"
                  :key="resume.id"
                  :value="resume.id"
                  :disabled="resume.parseStatus !== 'READY'"
                >
                  {{ resume.title }}{{ resume.parseStatus === 'READY' ? '' : '（未完成解析）' }}
                </option>
              </select></label
            >
            <label class="field"
              >保存版本<select
                v-model="versionId"
                :disabled="busy || versions.isFetching.value || !resumeId"
              >
                <option value="">
                  {{ versions.isFetching.value ? '加载版本中…' : '请选择版本' }}
                </option>
                <option
                  v-for="version in versions.data.value"
                  :key="version.id"
                  :value="version.id"
                >
                  版本 {{ version.versionNo }} ·
                  {{ new Date(version.createdAt).toLocaleString('zh-CN') }}
                </option>
              </select></label
            >
          </div>
          <p v-if="versions.isError.value" class="error-banner" role="alert">
            {{ versions.error.value?.message
            }}<button class="btn" @click="versions.refetch()">重试版本</button>
          </p>
          <div v-if="(resumes.data.value?.total || 0) > 20" class="card-footer">
            <button class="btn" :disabled="page === 1 || busy" @click="changeResumePage(-1)">
              上一页简历</button
            ><span>第 {{ page }} 页</span
            ><button
              class="btn"
              :disabled="page * 20 >= (resumes.data.value?.total || 0) || busy"
              @click="changeResumePage(1)"
            >
              下一页简历
            </button>
          </div>
          <div class="card-footer">
            <RouterLink to="/resumes" class="btn">上传或编辑简历</RouterLink
            ><RouterLink
              v-if="
                resumeId &&
                versionId &&
                !versions.isFetching.value &&
                !versions.isError.value &&
                !busy
              "
              class="btn"
              :to="{
                path: '/chat',
                query: {
                  mode: 'JOB',
                  jobId: id,
                  resumeId,
                  resumeVersionId: versionId,
                  prompt: '请将选定岗位与这份已保存的简历版本进行匹配，说明已有证据和差距。',
                },
              }"
              >在聊天中匹配</RouterLink
            ><RouterLink
              v-if="
                resumeId &&
                versionId &&
                !versions.isFetching.value &&
                !versions.isError.value &&
                !busy
              "
              class="btn"
              :to="{
                path: '/resumes/' + resumeId,
                query: { tab: 'optimize', jobId: id, resumeVersionId: versionId },
              }"
              >针对岗位改写</RouterLink
            ><RouterLink
              v-if="
                resumeId &&
                versionId &&
                !versions.isFetching.value &&
                !versions.isError.value &&
                !busy
              "
              class="btn"
              :to="{
                path: '/chat',
                query: {
                  mode: 'RESUME',
                  jobId: id,
                  resumeId,
                  resumeVersionId: versionId,
                  prompt: '请针对这个岗位，为选定的简历保存版本生成定向改写建议，不要自动采纳。',
                },
              }"
              >在聊天中改写</RouterLink
            ><button
              class="btn primary"
              :disabled="
                busy ||
                refreshing ||
                !resumeId ||
                !versionId ||
                versions.isFetching.value ||
                versions.isError.value
              "
              @click="analyze"
            >
              {{ busy ? '正在分析原文证据…' : '生成匹配报告' }}
            </button>
          </div>
        </template>
        <p v-if="busy" class="helper-note" role="status">
          正在获取岗位并逐项分析。报告完成后自动保存；离开页面不会保证取消已提交的分析。
        </p>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
      </section>
      <p v-if="reportLoading" class="panel" role="status">正在加载报告…</p>
      <CardRenderer v-if="report" card-type="job_evidence_match" :payload="report" />
      <section class="panel form-stack">
        <div class="section-heading">
          <h2>最近的匹配记录</h2>
          <button class="btn" @click="history.refetch()">刷新记录</button>
        </div>
        <p v-if="history.isPending.value">正在加载记录…</p>
        <p v-else-if="history.isError.value" class="error-banner" role="alert">
          {{ history.error.value?.message }}
        </p>
        <p v-else-if="!history.data.value?.length" class="subtle">
          还没有匹配报告。生成后可以在这里重新查看。
        </p>
        <div v-for="item in history.data.value" :key="item.id" class="card-footer">
          <span
            >{{ item.resumeTitle || '简历匹配' }} ·
            {{ new Date(item.createdAt).toLocaleString('zh-CN') }} ·
            {{
              item.status === 'COMPLETED'
                ? item.score + '/100'
                : item.status === 'RUNNING'
                  ? '处理中'
                  : '分析失败，可重新生成'
            }}</span
          ><button
            v-if="item.status === 'COMPLETED'"
            class="btn"
            :disabled="busy || reportLoading"
            @click="openReport(item.id)"
          >
            查看报告
          </button>
        </div>
      </section>
    </template>
    <p v-if="workspace.storageError" class="error-banner" role="alert">
      {{ workspace.storageError }}
    </p>
  </section>
</template>
<style scoped>
.match-selectors {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.page-stack {
  overflow-wrap: anywhere;
}
@media (max-width: 600px) {
  .match-selectors {
    grid-template-columns: 1fr;
  }
}
</style>
