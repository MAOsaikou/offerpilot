<script setup lang="ts">
import BackgroundTaskStatus from '@/shared/components/BackgroundTaskStatus.vue'
import { generationResult } from '@/shared/api/generationTasks'

import { isGenerationCancelled } from '@/shared/api/client'
import { computed, onUnmounted, ref, watch } from 'vue'
import type { ModelTier } from '@/shared/types/model-tier'
import { RouterLink, useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import JobSourceBadge from '@/shared/components/JobSourceBadge.vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import CardRenderer from '@/widgets/CardRenderer/CardRenderer.vue'
import LegacyJobDetail from '../components/LegacyJobDetail.vue'
import JobEvalReport from '../components/JobEvalReport.vue'
import { getJob, refreshJob, matchJob, listJobMatches, getJobMatch } from '@/shared/api/jobs'
import { listResumes, listResumeVersions } from '@/shared/api/resumes'
import { useBoard } from '@/shared/composables/useBoard'
import { officialJobUrl } from '@/shared/utils/jobs'
import type { EvidenceMatch } from '@/shared/types/jobs'
const route = useRoute(),
  board = useBoard(),
  queryClient = useQueryClient()
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
const modelTier = ref<ModelTier>('FLASH')
const elapsed = ref(0)
let progressTimer: ReturnType<typeof setInterval> | undefined
function stopProgress() {
  clearInterval(progressTimer)
  progressTimer = undefined
}
onUnmounted(() => {
  generation++
  reportRequest++
  stopProgress()
})
const busy = ref(false),
  refreshing = ref(false),
  reportLoading = ref(false),
  error = ref(''),
  refreshError = ref(''),
  cancelled = ref(false),
  report = ref<EvidenceMatch>()
let generation = 0,
  reportRequest = 0
watch(id, () => {
  generation++
  reportRequest++
  stopProgress()
  report.value = undefined
  busy.value = false
  refreshing.value = false
  reportLoading.value = false
  error.value = ''
  cancelled.value = false
  refreshError.value = ''
})
watch(resumeId, () => {
  versionId.value = ''
})
watch(
  () => resumes.data.value?.items,
  (items) => {
    if (resumeId.value || !items?.length) return
    const ready = items.find((resume) => resume.parseStatus === 'READY')
    if (ready) resumeId.value = ready.id
  },
  { immediate: true },
)
watch(
  () => versions.data.value,
  (items) => {
    if (items?.length && !versionId.value) versionId.value = items[0]!.id
  },
  { immediate: true },
)
const matchBlockedReason = computed(() => {
  if (busy.value || refreshing.value) return ''
  if (versions.isError.value) return '简历版本加载失败，请重试后再生成匹配报告。'
  if (versions.isFetching.value) return '正在加载简历版本…'
  if (!resumeId.value) return '请先选择一份已解析完成的简历。'
  if (!versionId.value) return '请先选择要用于匹配的保存版本。'
  return ''
})
const saved = computed(() => board.saved(id.value))
function changeResumePage(delta: number) {
  page.value += delta
  resumeId.value = ''
}
async function refresh() {
  const epoch = generation
  refreshing.value = true
  refreshError.value = ''
  try {
    const updated = await refreshJob(id.value)
    if (epoch === generation) queryClient.setQueryData(['job', id.value], updated)
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
  elapsed.value = 0
  stopProgress()
  const started = Date.now()
  progressTimer = setInterval(() => {
    elapsed.value = Math.floor((Date.now() - started) / 1000)
  }, 1000)
  error.value = ''
  cancelled.value = false
  report.value = undefined
  reportRequest++
  try {
    const value = await matchJob(
      id.value,
      resumeId.value,
      versionId.value,
      crypto.randomUUID(),
      modelTier.value,
    )
    if (epoch === generation) report.value = value
  } catch (e) {
    if (epoch === generation) {
      cancelled.value = isGenerationCancelled(e)
      if (!cancelled.value) error.value = (e as Error).message
    }
  } finally {
    if (epoch === generation) {
      stopProgress()
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
  cancelled.value = false
  report.value = undefined
  try {
    const value = await getJobMatch(reportId)
    if (request === reportRequest) {
      if (value.jobId !== id.value) throw new Error('报告与当前岗位不匹配，请重新选择。')
      report.value = value
    }
  } catch (e) {
    if (request === reportRequest) {
      cancelled.value = isGenerationCancelled(e)
      if (!cancelled.value) error.value = (e as Error).message
    }
  } finally {
    if (request === reportRequest) reportLoading.value = false
  }
}
watch(
  () => [id.value, route.query.matchId] as const,
  ([, matchId]) => {
    if (real.value && typeof matchId === 'string' && matchId) void openReport(matchId)
  },
  { immediate: true },
)
async function restoreTask(taskId: string) {
  if (busy.value || reportLoading.value || route.query.matchId) return
  const epoch = generation
  try {
    const saved = await generationResult<EvidenceMatch>(taskId)
    if (epoch === generation && saved.jobId === id.value) report.value = saved
  } catch (cause) {
    if (epoch === generation) error.value = cause instanceof Error ? cause.message : '报告恢复失败'
  }
}
</script>
<template>
  <LegacyJobDetail v-if="!real" />
  <section v-else class="page-stack">
    <BackgroundTaskStatus kind="JOB_MATCH" :entity-id="id" @completed="restoreTask" />
    <p v-if="board.query.isPending.value" role="status">正在读取看板…</p>
    <div v-if="board.query.isError.value" class="error-banner" role="alert">
      收藏读取失败：{{ board.query.error.value?.message }}
      <button class="btn" @click="board.query.refetch()">重试加载</button>
    </div>
    <p v-if="board.error.value" class="error-banner" role="alert">
      收藏失败：{{ board.error.value }}，请重试。
    </p>
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
          <span>{{ job.data.value.company }}</span>
          <JobSourceBadge
            :source="job.data.value.source"
            :snapshot-date="job.data.value.snapshotDate"
            :cached="true"
          /><a
            v-if="officialJobUrl(job.data.value.url)"
            :href="officialJobUrl(job.data.value.url)"
            target="_blank"
            rel="noopener noreferrer"
            class="btn"
            >查看官网原岗位 ↗</a
          ><button
            class="btn"
            :disabled="
              saved || board.busy.value || board.query.isPending.value || board.query.isError.value
            "
            @click="board.favorite(job.data.value.id)"
          >
            {{ saved ? '已收藏至看板' : '收藏至看板' }}</button
          ><button
            v-if="!['LOCAL_SNAPSHOT', 'demo', 'USER_IMPORT'].includes(job.data.value.source)"
            class="btn"
            :disabled="refreshing || busy"
            @click="refresh"
          >
            {{ refreshing ? '正在获取…' : '获取 / 更新完整详情' }}
          </button>
        </div>
        <p v-if="job.data.value.source === 'USER_IMPORT'" class="subtle">
          你导入的岗位，只有你可见。导入时间：{{
            job.data.value.importedAt
              ? new Date(job.data.value.importedAt).toLocaleString('zh-CN')
              : '未知'
          }}。职责与要求以原招聘页为准，投递前请再次核对。
        </p>
        <p v-else class="subtle">
          {{ job.data.value.source === 'LOCAL_SNAPSHOT' ? '快照日期' : '企业更新时间' }}：{{
            job.data.value.source === 'LOCAL_SNAPSHOT'
              ? job.data.value.snapshotDate
              : job.data.value.updatedAt || '未提供'
          }}
          <template v-if="job.data.value.source !== 'LOCAL_SNAPSHOT'">
            · 详情获取时间：{{
              job.data.value.detailFetchedAt
                ? new Date(job.data.value.detailFetchedAt).toLocaleString('zh-CN')
                : '尚未获取完整详情'
            }}
          </template>
        </p>
        <p v-if="job.data.value.source === 'LOCAL_SNAPSHOT'" class="subtle">
          人工整理的示例岗位快照，非实时数据。匹配分析基于示例职责与要求，不代表当前开放岗位。
        </p>
        <p v-if="job.data.value.source === 'STORED_SNAPSHOT'" class="subtle" role="status">
          实时详情暂不可用，当前展示已保存的岗位，仍可进行匹配；最新要求与开放状态请核对企业官网。
        </p>
        <p v-if="job.data.value.source === 'demo'" class="subtle">
          虚构体验岗位，仅供练习匹配与求职流程，不接受实际投递。
        </p>
        <p v-if="refreshError" class="error-banner" role="alert">{{ refreshError }}</p>
        <dl class="job-facts">
          <div>
            <dt>城市</dt>
            <dd>{{ job.data.value.locations || '未提供' }}</dd>
          </div>
          <div>
            <dt>部门</dt>
            <dd>{{ job.data.value.department || '未提供' }}</dd>
          </div>
          <div>
            <dt>截止日</dt>
            <dd>{{ job.data.value.deadline || '未提供' }}</dd>
          </div>
          <div>
            <dt>官网链接</dt>
            <dd>
              <a
                v-if="officialJobUrl(job.data.value.url)"
                :href="officialJobUrl(job.data.value.url)"
                target="_blank"
                rel="noopener noreferrer"
                >查看官网原岗位 ↗</a
              ><span v-else>未提供</span>
            </dd>
          </div>
        </dl>
        <h2>工作职责</h2>
        <p class="prose-content">
          {{ job.data.value.description || '未提供' }}
        </p>
        <h2>任职要求</h2>
        <p class="prose-content">
          {{ job.data.value.requirement || '未提供' }}
        </p>
        <p class="helper-note">
          岗位可能调整或关闭，以企业官网为准。收藏与看板状态保存在服务端，不会代你投递。
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
          <label class="field"
            >分析模式<select v-model="modelTier" :disabled="busy">
              <option value="FLASH">快速</option>
              <option value="PRO">深度</option>
            </select></label
          >
          <p v-if="modelTier === 'PRO'" class="helper-note">
            深度模式较慢，生成及证据校验最多等待 3 分钟。
          </p>
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
                  jobAction: 'match',
                  modelTier,
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
            ><RouterLink
              class="btn"
              :to="{ path: '/interviews', query: { jobId: id, role: job.data.value.title } }"
              >针对该岗位模拟面试</RouterLink
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
          <p v-if="matchBlockedReason" class="helper-note" role="status">
            {{ matchBlockedReason }}
          </p>
        </template>
        <p v-if="busy" class="helper-note" role="status">
          {{ modelTier === 'PRO' ? '深度模式正在分析原文证据' : '正在分析原文证据' }} · 已等待
          {{ elapsed }} 秒。 报告完成后自动保存；离开页面后可从匹配记录查看结果。
        </p>
        <p v-if="cancelled" role="status">匹配已取消，可重新生成报告。</p>
        <div v-if="error" class="error-banner" role="alert">
          {{ error }}
          <button class="btn" @click="history.refetch()">刷新匹配记录</button>
          <button
            class="btn"
            :disabled="
              busy || !resumeId || !versionId || versions.isFetching.value || versions.isError.value
            "
            @click="analyze"
          >
            重新分析
          </button>
        </div>
      </section>
      <p v-if="reportLoading" class="panel" role="status">正在加载报告…</p>
      <CardRenderer v-if="report" card-type="job_evidence_match" :payload="report" />
      <JobEvalReport v-if="report?.evaluation" :payload="report.evaluation" :match-id="report.id" />
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
                  : item.status === 'CANCELLED'
                    ? '已取消，可重新生成'
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
  </section>
</template>
<style scoped>
.job-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 16px 32px;
  overflow-wrap: anywhere;
}
.job-facts dd {
  margin: 4px 0 0;
}
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
