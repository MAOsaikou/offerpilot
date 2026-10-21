<script setup lang="ts">
import {
  formatDisplay,
  formatDate,
  localizedText,
  language,
  translate as uiText,
} from '@/shared/utils/language'

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
  error = localizedText(),
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
  if (versions.isError.value)
    return uiText('Could not load resume versions. Retry before generating a match report.')
  if (versions.isFetching.value) return uiText('Loading resume versions…')
  if (!resumeId.value) return uiText('Select a fully parsed resume first.')
  if (!versionId.value) return uiText('Select a saved version to match first.')
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
      if (value.jobId !== id.value)
        throw new Error(uiText('This report does not match the current job. Please select again.'))
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
    if (epoch === generation)
      error.value = () =>
        cause instanceof Error ? cause.message : uiText('Could not restore report')
  }
}
</script>
<template>
  <LegacyJobDetail v-if="!real" />
  <section v-else class="page-stack">
    <BackgroundTaskStatus kind="JOB_MATCH" :entity-id="id" @completed="restoreTask" />
    <p v-if="board.query.isPending.value" role="status">
      {{ uiText('Loading application board…') }}
    </p>
    <div v-if="board.query.isError.value" class="error-banner" role="alert">
      {{ uiText('Could not load saved jobs: {0}', [board.query.error.value?.message]) }}
      <button class="btn" @click="board.query.refetch()">{{ uiText('Retry loading') }}</button>
    </div>
    <p v-if="board.error.value" class="error-banner" role="alert">
      {{ uiText('Could not save job: {0}. Please retry.', [board.error.value]) }}
    </p>
    <PageHeading
      :title="job.data.value?.title || uiText('Job details')"
      :description="
        job.data.value
          ? job.data.value.company +
            ' · ' +
            (job.data.value.locations || uiText('Location not provided'))
          : uiText('Loading job record')
      "
      ><RouterLink to="/jobs" class="btn">{{ uiText('← Back to search') }}</RouterLink></PageHeading
    >
    <p v-if="job.isPending.value" class="panel" role="status">{{ uiText('Loading job…') }}</p>
    <div v-else-if="job.isError.value" class="error-banner" role="alert">
      {{ formatDisplay(job.error.value?.message)
      }}<button class="btn" @click="job.refetch()">{{ uiText('Retry') }}</button>
    </div>
    <template v-else-if="job.data.value">
      <div class="panel form-stack">
        <div class="card-footer">
          <span>{{ formatDisplay(job.data.value.company) }}</span>
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
            >{{ uiText('View original listing') }}</a
          ><button
            class="btn"
            :disabled="
              saved || board.busy.value || board.query.isPending.value || board.query.isError.value
            "
            @click="board.favorite(job.data.value.id)"
          >
            {{ saved ? uiText('Saved to board') : uiText('Save to board') }}</button
          ><button
            v-if="!['LOCAL_SNAPSHOT', 'demo', 'USER_IMPORT'].includes(job.data.value.source)"
            class="btn"
            :disabled="refreshing || busy"
            @click="refresh"
          >
            {{ refreshing ? uiText('Fetching…') : uiText('Fetch / Update full details') }}
          </button>
        </div>
        <p v-if="job.data.value.source === 'USER_IMPORT'" class="subtle">
          {{
            uiText(
              'This imported job is private to you. Imported: {0}. Check the original listing for responsibilities and requirements before applying.',
              [
                job.data.value.importedAt
                  ? new Date(job.data.value.importedAt).toLocaleString(language)
                  : uiText('Unknown'),
              ],
            )
          }}
        </p>
        <p v-else class="subtle">
          {{
            job.data.value.source === 'LOCAL_SNAPSHOT'
              ? uiText('Snapshot date')
              : uiText('Company update time')
          }}：{{
            job.data.value.source === 'LOCAL_SNAPSHOT'
              ? formatDate(job.data.value.snapshotDate)
              : (job.data.value.updatedAt && formatDate(job.data.value.updatedAt)) ||
                uiText('Not provided #2')
          }}
          <template v-if="job.data.value.source !== 'LOCAL_SNAPSHOT'">
            {{
              uiText('· Details fetched: {0}', [
                job.data.value.detailFetchedAt
                  ? new Date(job.data.value.detailFetchedAt).toLocaleString(language)
                  : uiText('Full details have not been fetched'),
              ])
            }}
          </template>
        </p>
        <p v-if="job.data.value.source === 'LOCAL_SNAPSHOT'" class="subtle">
          {{
            uiText(
              'Manually curated sample snapshot, not live data. Matching uses sample responsibilities and requirements and does not represent a current opening.',
            )
          }}
        </p>
        <p v-if="job.data.value.source === 'STORED_SNAPSHOT'" class="subtle" role="status">
          {{
            uiText(
              'Live details are unavailable. Saved details are shown and can still be used for matching. Check the company website for current requirements and availability.',
            )
          }}
        </p>
        <p v-if="job.data.value.source === 'demo'" class="subtle">
          {{
            uiText(
              'Fictional demo job for matching and application practice only. No real applications accepted.',
            )
          }}
        </p>
        <p v-if="refreshError" class="error-banner" role="alert">{{ refreshError }}</p>
        <dl class="job-facts">
          <div>
            <dt>{{ uiText('City') }}</dt>
            <dd>{{ job.data.value.locations || uiText('Not provided #2') }}</dd>
          </div>
          <div>
            <dt>{{ uiText('Department') }}</dt>
            <dd>{{ job.data.value.department || uiText('Not provided #2') }}</dd>
          </div>
          <div>
            <dt>{{ uiText('Deadline') }}</dt>
            <dd>
              {{
                (job.data.value.deadline && formatDate(job.data.value.deadline)) ||
                uiText('Not provided #2')
              }}
            </dd>
          </div>
          <div>
            <dt>{{ uiText('Official link') }}</dt>
            <dd>
              <a
                v-if="officialJobUrl(job.data.value.url)"
                :href="officialJobUrl(job.data.value.url)"
                target="_blank"
                rel="noopener noreferrer"
                >{{ uiText('View original listing') }}</a
              ><span v-else>{{ uiText('Not provided #2') }}</span>
            </dd>
          </div>
        </dl>
        <h2>{{ uiText('Responsibilities #2') }}</h2>
        <p class="prose-content">
          {{ job.data.value.description || uiText('Not provided #2') }}
        </p>
        <h2>{{ uiText('Requirements') }}</h2>
        <p class="prose-content">
          {{ job.data.value.requirement || uiText('Not provided #2') }}
        </p>
        <p class="helper-note">
          {{
            uiText(
              'Jobs may change or close. Check the company website. Saved jobs and board stages are stored on the server; no application is submitted for you.',
            )
          }}
        </p>
      </div>
      <section class="panel form-stack">
        <h2>{{ uiText('How well does your resume fit this job?') }}</h2>
        <p class="subtle">
          {{
            uiText(
              'Select a parsed, saved resume version. Analysis uses that version and the job content and usually takes tens of seconds.',
            )
          }}
        </p>
        <p v-if="resumes.isPending.value" role="status">{{ uiText('Loading resumes… #2') }}</p>
        <div v-else-if="resumes.isError.value" class="error-banner" role="alert">
          {{ formatDisplay(resumes.error.value?.message)
          }}<button class="btn" @click="resumes.refetch()">
            {{ uiText('Retry resume list') }}
          </button>
        </div>
        <template v-else>
          <label class="field"
            >{{ uiText('Analysis mode')
            }}<select v-model="modelTier" :disabled="busy">
              <option value="FLASH">{{ uiText('Fast') }}</option>
              <option value="PRO">{{ uiText('Deep') }}</option>
            </select></label
          >
          <p v-if="modelTier === 'PRO'" class="helper-note">
            {{
              uiText(
                'Deep mode is slower. Generation and evidence checks may take up to 3 minutes.',
              )
            }}
          </p>
          <div class="match-selectors">
            <label class="field"
              >{{ uiText('Resume')
              }}<select v-model="resumeId" :disabled="busy">
                <option value="">{{ uiText('Select a parsed resume') }}</option>
                <option
                  v-for="resume in resumes.data.value?.items"
                  :key="resume.id"
                  :value="resume.id"
                  :disabled="resume.parseStatus !== 'READY'"
                >
                  {{ resume.title
                  }}{{ resume.parseStatus === 'READY' ? '' : uiText('(Parsing incomplete)') }}
                </option>
              </select></label
            >
            <label class="field"
              >{{ uiText('Saved version')
              }}<select
                v-model="versionId"
                :disabled="busy || versions.isFetching.value || !resumeId"
              >
                <option value="">
                  {{
                    versions.isFetching.value
                      ? uiText('Loading versions… #2')
                      : uiText('Select a version')
                  }}
                </option>
                <option
                  v-for="version in versions.data.value"
                  :key="version.id"
                  :value="version.id"
                >
                  {{
                    uiText('Version {0} · {1} #2', [
                      version.versionNo,
                      new Date(version.createdAt).toLocaleString(language),
                    ])
                  }}
                </option>
              </select></label
            >
          </div>
          <p v-if="versions.isError.value" class="error-banner" role="alert">
            {{ formatDisplay(versions.error.value?.message)
            }}<button class="btn" @click="versions.refetch()">
              {{ uiText('Retry versions') }}
            </button>
          </p>
          <div v-if="(resumes.data.value?.total || 0) > 20" class="card-footer">
            <button class="btn" :disabled="page === 1 || busy" @click="changeResumePage(-1)">
              {{ uiText('Previous resume page') }}</button
            ><span>{{ uiText('Page {0}', [page]) }}</span
            ><button
              class="btn"
              :disabled="page * 20 >= (resumes.data.value?.total || 0) || busy"
              @click="changeResumePage(1)"
            >
              {{ uiText('Next resume page') }}
            </button>
          </div>
          <div class="card-footer">
            <RouterLink to="/resumes" class="btn">{{ uiText('Upload or edit resume') }}</RouterLink
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
                  prompt: uiText(
                    'Match the selected job with this saved resume version and explain existing evidence and gaps.',
                  ),
                },
              }"
              >{{ uiText('Match in chat') }}</RouterLink
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
              >{{ uiText('Rewrite for this role') }}</RouterLink
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
                  prompt: uiText(
                    'Generate targeted rewrite suggestions for the selected saved resume version and this job. Do not accept them automatically.',
                  ),
                },
              }"
              >{{ uiText('Rewrite in chat') }}</RouterLink
            ><RouterLink
              class="btn"
              :to="{ path: '/interviews', query: { jobId: id, role: job.data.value.title } }"
              >{{ uiText('Practice for this job') }}</RouterLink
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
              {{ busy ? uiText('Analyzing original evidence…') : uiText('Generate match report') }}
            </button>
          </div>
          <p v-if="matchBlockedReason" class="helper-note" role="status">
            {{ matchBlockedReason }}
          </p>
        </template>
        <p v-if="busy" class="helper-note" role="status">
          {{
            uiText(
              '{0} · Waiting {1} seconds. The report saves automatically. After leaving this page, check match history for the result.',
              [
                modelTier === 'PRO'
                  ? uiText('Deep mode is analyzing original evidence')
                  : uiText('Analyzing original evidence'),
                elapsed,
              ],
            )
          }}
        </p>
        <p v-if="cancelled" role="status">
          {{ uiText('Matching canceled. You can generate another report.') }}
        </p>
        <div v-if="error" class="error-banner" role="alert">
          {{ error }}
          <button class="btn" @click="history.refetch()">
            {{ uiText('Refresh match history') }}
          </button>
          <button
            class="btn"
            :disabled="
              busy || !resumeId || !versionId || versions.isFetching.value || versions.isError.value
            "
            @click="analyze"
          >
            {{ uiText('Analyze again') }}
          </button>
        </div>
      </section>
      <p v-if="reportLoading" class="panel" role="status">{{ uiText('Loading report…') }}</p>
      <CardRenderer v-if="report" card-type="job_evidence_match" :payload="report" />
      <JobEvalReport v-if="report?.evaluation" :payload="report.evaluation" :match-id="report.id" />
      <section class="panel form-stack">
        <div class="section-heading">
          <h2>{{ uiText('Recent match history') }}</h2>
          <button class="btn" @click="history.refetch()">{{ uiText('Refresh history') }}</button>
        </div>
        <p v-if="history.isPending.value">{{ uiText('Loading history…') }}</p>
        <p v-else-if="history.isError.value" class="error-banner" role="alert">
          {{ formatDisplay(history.error.value?.message) }}
        </p>
        <p v-else-if="!history.data.value?.length" class="subtle">
          {{ uiText('No match reports yet. Generate one to revisit it here.') }}
        </p>
        <div v-for="item in history.data.value" :key="item.id" class="card-footer">
          <span
            >{{ item.resumeTitle || uiText('Resume matching') }} ·
            {{ new Date(item.createdAt).toLocaleString(language) }} ·
            {{
              item.status === 'COMPLETED'
                ? item.score + '/100'
                : item.status === 'RUNNING'
                  ? uiText('Processing')
                  : item.status === 'CANCELLED'
                    ? uiText('Canceled. You can generate again.')
                    : uiText('Analysis failed. You can generate again.')
            }}</span
          ><button
            v-if="item.status === 'COMPLETED'"
            class="btn"
            :disabled="busy || reportLoading"
            @click="openReport(item.id)"
          >
            {{ uiText('View report') }}
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
