<script setup lang="ts">
import {
  formatDisplay,
  localizedText,
  language,
  translate as uiText,
} from '@/shared/utils/language'

import BackgroundTaskStatus from '@/shared/components/BackgroundTaskStatus.vue'
import { generationResult } from '@/shared/api/generationTasks'

import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { isGenerationCancelled } from '@/shared/api/client'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  applyPatches,
  cancelOptimization,
  getOptimization,
  getPatchBatch,
  listOptimizations,
  optimizeResume,
} from '@/shared/api/optimizations'
import { getResumeVersion } from '@/shared/api/resumes'
import { getJob } from '@/shared/api/jobs'
import type { OptimizationReport, PatchDecision } from '@/shared/types/optimization'
import type { ResumeVersion } from '@/shared/types/resume'

const props = defineProps<{
  resumeId: string
  versionId: string
  targetVersionId?: string
  jobId?: string
  optimizationReportId?: string
  dirty: boolean
  saveFirst: () => Promise<boolean>
}>()
const emit = defineEmits<{ saved: [version: ResumeVersion]; saving: [value: boolean] }>()
const cache = useQueryClient()
const jobDescription = localizedText(),
  reportJd = ref(''),
  targetVersion = ref(props.targetVersionId || '')
const sourceVersion = computed(() => targetVersion.value || props.versionId)
const report = ref<OptimizationReport>()
const appliedVersionId = ref<string | null>(null)
const error = localizedText(),
  message = localizedText(),
  running = ref(false),
  selecting = ref(false),
  saving = ref(false),
  runningId = ref('')
const history = useQuery({
  queryKey: ['optimizations', props.resumeId],
  queryFn: () => listOptimizations(props.resumeId),
  retry: false,
  refetchInterval: (q) => (q.state.data?.some((t) => t.status === 'RUNNING') ? 2000 : false),
})
const job = useQuery({
  queryKey: computed(() => ['job', props.jobId]),
  queryFn: ({ signal }) => getJob(props.jobId!, signal),
  enabled: computed(() => !!props.jobId),
  retry: false,
})
watch(
  () => job.data.value,
  (value) => {
    if (value && !jobDescription.value.trim() && (value.description || value.requirement))
      jobDescription.value = () =>
        uiText('Job: {0} Responsibilities: {1} Requirements: {2}', [
          value.title,
          value.description,
          value.requirement,
        ]).slice(0, 20000)
  },
  { immediate: true },
)
watch(
  () => props.targetVersionId,
  (value) => {
    targetVersion.value = value || ''
  },
)
const existing = computed(() => history.data.value?.find((t) => t.status === 'RUNNING'))
const occupied = computed(
  () => running.value || selecting.value || saving.value || !!existing.value,
)
const canApply = computed(
  () =>
    !!report.value &&
    report.value.resumeVersionId === props.versionId &&
    !props.dirty &&
    !appliedVersionId.value,
)
const labels = computed(() => ({
  RUNNING: uiText('Generating'),
  SUCCEEDED: uiText('Completed'),
  FAILED: uiText('Failed'),
  CANCELLED: uiText('Canceled'),
}))
let locallyCancelled = false
let controller: AbortController | undefined,
  alive = true,
  epoch = 0
const refresh = () => cache.invalidateQueries({ queryKey: ['optimizations', props.resumeId] })
async function reveal(value: OptimizationReport, jd: string, request: number) {
  let applied: string | null = null
  if (value.batchId)
    applied = (await getPatchBatch(props.resumeId, value.resumeVersionId, value.batchId))
      .appliedVersionId
  if (!alive || request !== epoch) return
  report.value = value
  reportJd.value = jd
  appliedVersionId.value = applied
}
async function show(id: string, expectedVersion?: string) {
  if (running.value || saving.value) return
  const request = ++epoch
  selecting.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await getOptimization(id)
    if (task.resumeId !== props.resumeId || (expectedVersion && task.versionId !== expectedVersion))
      throw new Error(
        uiText(
          'This rewrite does not belong to the selected resume version. Open it from the corresponding resume.',
        ),
      )
    if (!task.report)
      throw new Error(uiText('Suggestions are incomplete. Refresh rewrite history.'))
    await reveal(task.report, task.jobDescription || '', request)
    if (alive && request === epoch && !jobDescription.value.trim())
      jobDescription.value = task.jobDescription || ''
  } catch (cause) {
    if (alive && request === epoch)
      error.value = () =>
        cause instanceof Error ? cause.message : uiText('Could not load suggestions')
  } finally {
    if (alive && request === epoch) selecting.value = false
  }
}
watch(
  () => props.optimizationReportId,
  (id) => {
    if (id) void show(id, props.targetVersionId)
  },
  { immediate: true },
)
async function start(save: boolean) {
  if (occupied.value || !jobDescription.value.trim()) return
  running.value = true
  locallyCancelled = false
  error.value = ''
  message.value = ''
  runningId.value = ''
  const request = ++epoch
  let requestId = ''
  try {
    if (save) {
      if (!(await props.saveFirst())) return
      targetVersion.value = ''
    }
    await nextTick()
    if (!alive) return
    controller = new AbortController()
    requestId = crypto.randomUUID()
    const jd = jobDescription.value.trim()
    const value = await optimizeResume(
      props.resumeId,
      sourceVersion.value,
      requestId,
      jd,
      controller.signal,
      (id) => {
        runningId.value = id
        if (locallyCancelled) void cancelOptimization(id).catch(() => {})
        void refresh()
      },
      props.jobId,
    )
    await reveal(value, jd, request)
  } catch (cause) {
    if (alive && request === epoch) {
      if (
        isGenerationCancelled(cause) ||
        (locallyCancelled && cause instanceof DOMException && cause.name === 'AbortError')
      ) {
        error.value = ''
        message.value = () => uiText('Rewrite canceled. You can start again.')
        return
      }
      error.value = () =>
        cause instanceof DOMException && cause.name === 'AbortError'
          ? uiText('Rewrite connection stopped. Check rewrite history.')
          : cause instanceof Error
            ? cause.message
            : uiText('Rewrite failed')
      if (requestId)
        try {
          const tasks = await listOptimizations(props.resumeId)
          const completed = tasks.find((t) => t.requestId === requestId && t.status === 'SUCCEEDED')
          if (completed) {
            const task = await getOptimization(completed.id)
            if (task.report) {
              await reveal(task.report, task.jobDescription || '', request)
              if (alive) error.value = ''
            }
          }
        } catch {
          /* Preserve the actionable error if recovery is unavailable. */
        }
    }
  } finally {
    if (alive && request === epoch) {
      running.value = false
      runningId.value = ''
      void refresh()
    }
  }
}
async function stop() {
  const id = runningId.value || existing.value?.id
  try {
    if (id) await cancelOptimization(id)
    locallyCancelled = true
    message.value = () => uiText('Rewrite canceled. You can start again.')
    error.value = ''
    controller?.abort()
    void refresh()
  } catch (cause) {
    if (alive)
      error.value = () =>
        cause instanceof Error ? cause.message : uiText('Could not cancel. Please retry.')
  }
}
function saved(version: ResumeVersion) {
  appliedVersionId.value = version.id
  emit('saved', version)
  message.value = () =>
    uiText('Saved as version {0}. The original version remains available.', [version.versionNo])
}
async function apply(decision: PatchDecision) {
  if (
    !canApply.value ||
    occupied.value ||
    !report.value ||
    decision.batchId !== report.value.batchId
  )
    return
  const value = report.value
  saving.value = true
  error.value = ''
  message.value = ''
  emit('saving', true)
  try {
    const result = await applyPatches(props.resumeId, value.resumeVersionId, decision)
    if (alive) saved(result.version)
  } catch (cause) {
    if (alive) {
      error.value = () =>
        cause instanceof Error
          ? cause.message
          : uiText('Save failed. Retry with the same decisions.')
      // The response may be lost after commit: restore the batch instead of posting again.
      try {
        const batch = await getPatchBatch(props.resumeId, value.resumeVersionId, decision.batchId)
        if (batch.appliedVersionId) {
          const version = await getResumeVersion(props.resumeId, batch.appliedVersionId)
          if (alive) {
            saved(version)
            error.value = ''
          }
        }
      } catch {
        /* Keep the original error and current selection. */
      }
    }
  } finally {
    emit('saving', false)
    if (alive) saving.value = false
    void cache.invalidateQueries({ queryKey: ['resume', props.resumeId] })
    void cache.invalidateQueries({ queryKey: ['resumes'] })
  }
}
onBeforeUnmount(() => {
  alive = false
  epoch++
})
async function restoreTask(id: string) {
  if (running.value || selecting.value || saving.value || props.optimizationReportId) return
  try {
    const saved = await generationResult<OptimizationReport>(id)
    if (alive && report.value?.reportId !== saved.reportId) await show(saved.reportId)
  } catch (cause) {
    if (alive)
      error.value = () =>
        cause instanceof Error ? cause.message : uiText('Could not restore suggestions')
  }
}
</script>

<template>
  <section class="panel form-stack optimization-panel">
    <BackgroundTaskStatus kind="RESUME_OPTIMIZE" :entity-id="resumeId" @completed="restoreTask" />
    <header>
      <h2>{{ uiText('Targeted job rewrite') }}</h2>
      <p class="subtle">
        {{
          uiText(
            'Improve the expression of existing experience only. Generate suggestions, then select, edit and confirm each one to save a new version.',
          )
        }}
      </p>
    </header>
    <p v-if="job.isPending.value && jobId" role="status">{{ uiText('Loading target job…') }}</p>
    <p v-if="job.error.value" class="error-banner" role="alert">
      {{ uiText('{0}. You can paste the job description, or', [job.error.value.message])
      }}<button class="btn" :disabled="occupied" @click="job.refetch()">
        {{ uiText('Retry loading job') }}
      </button>
    </p>
    <p v-if="job.data.value && jobId" class="helper-note">
      {{ uiText('Target job: {0} · {1}.', [job.data.value.title, job.data.value.company]) }}
      <RouterLink :to="'/jobs/' + jobId">{{ uiText('View full job') }}</RouterLink>
    </p>
    <label class="field"
      >{{ uiText('Responsibilities and requirements (JD)') }}
      <textarea
        v-model="jobDescription"
        rows="7"
        maxlength="20000"
        :disabled="occupied"
        :placeholder="
          uiText(
            'Paste the target job\'s responsibilities and requirements, preferably only skill-related content.',
          )
        "
      />
    </label>
    <p class="subtle">
      {{
        uiText(
          'Uses the text model in settings. The job description and saved resume content are sent to that model. The personal information section is excluded.',
        )
      }}
    </p>
    <p v-if="dirty" class="helper-note">
      {{
        uiText(
          'You have unsaved edits. Save first or use the saved version only. Your unsaved draft will not be overwritten.',
        )
      }}
    </p>
    <div v-if="sourceVersion !== versionId" class="helper-note">
      {{
        uiText(
          'You selected a historical saved version. Suggestions can be generated and viewed, but accepting them requires the current saved version.',
        )
      }}
      <button class="btn" :disabled="occupied" @click="targetVersion = ''">
        {{ uiText('Use current saved version') }}
      </button>
    </div>
    <div class="optimization-actions">
      <button
        v-if="dirty"
        class="btn primary"
        :disabled="occupied || !jobDescription.trim()"
        @click="start(true)"
      >
        {{ uiText('Save new version and generate') }}
      </button>
      <button
        class="btn"
        :class="{ primary: !dirty }"
        :disabled="occupied || !jobDescription.trim()"
        @click="start(false)"
      >
        {{
          running || existing
            ? uiText('Generating…')
            : dirty
              ? uiText('Generate using saved version only')
              : uiText('Generate rewrite suggestions')
        }}
      </button>
      <button v-if="running || existing" class="btn" @click="stop">
        {{ uiText('Cancel generation') }}
      </button>
    </div>
    <p v-if="running || existing" role="status">
      {{
        uiText(
          'Checking resume and job content. This usually takes tens of seconds and continues after you leave. Return to view suggestions.',
        )
      }}
    </p>
    <p v-if="selecting" role="status">{{ uiText('Loading saved suggestions…') }}</p>
    <p v-if="saving" role="status">{{ uiText('Saving selected rewrites…') }}</p>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <p v-if="message" class="helper-note" role="status">{{ message }}</p>
    <template v-if="report">
      <p>{{ report.summary }}</p>
      <p class="subtle">
        {{
          uiText('Saved version {0} · {1} · {2}', [
            report.versionNo,
            report.model,
            new Date(report.createdAt).toLocaleString(language),
          ])
        }}
      </p>
      <details v-if="reportJd">
        <summary>{{ uiText('Job description used') }}</summary>
        <pre class="jd-snapshot">{{ reportJd }}</pre>
      </details>
      <RouterLink
        class="btn"
        :to="{
          path: '/chat',
          query: {
            mode: 'RESUME',
            resumeId,
            resumeVersionId: report.resumeVersionId,
            optimizationReportId: report.reportId,
            prompt: uiText(
              'Explain these rewrite suggestions and their evidence without regenerating or accepting them.',
            ),
          },
        }"
        >{{ uiText('Discuss these suggestions') }}</RouterLink
      >
      <p v-if="!appliedVersionId && !canApply && report.patches.length" class="helper-note">
        {{
          uiText(
            'The current resume version differs or has unsaved edits. These suggestions are read-only. Save current content and regenerate to avoid overwriting later changes.',
          )
        }}
      </p>
      <p v-if="!report.patches.length" class="helper-note" role="status">
        {{ uiText('No rewrite suggestions need accepting. No new resume version was created. #2') }}
      </p>
      <slot
        v-else
        :key="report.reportId"
        name="optimization-card"
        :report="report"
        :interactive="canApply"
        :disabled="occupied"
        :applied-version-id="appliedVersionId"
        :apply="apply"
      />
    </template>
    <details :open="!report" class="optimization-history">
      <summary>{{ uiText('Rewrite history · {0}', [history.data.value?.length || 0]) }}</summary>
      <button
        class="btn"
        :disabled="history.isFetching.value || occupied"
        @click="history.refetch()"
      >
        {{ uiText('Refresh rewrite history') }}
      </button>
      <p v-if="history.isPending.value" role="status">{{ uiText('Loading rewrite history…') }}</p>
      <p v-else-if="history.error.value" class="error-banner" role="alert">
        {{ formatDisplay(history.error.value.message) }}
      </p>
      <p v-else-if="!history.data.value?.length" class="subtle">
        {{ uiText('No rewrite history yet. Enter a job description to start.') }}
      </p>
      <div v-for="task in history.data.value" :key="task.id" class="optimization-record">
        <div>
          <strong>{{
            uiText('Version {0} · {1} #2', [task.versionNo, labels[task.status]])
          }}</strong>
          <p class="subtle">{{ new Date(task.createdAt).toLocaleString(language) }}</p>
          <p v-if="task.error">{{ task.error }}</p>
        </div>
        <button
          v-if="task.status === 'SUCCEEDED'"
          class="btn"
          :disabled="occupied"
          @click="show(task.id)"
        >
          {{ uiText('View suggestions') }}
        </button>
      </div>
    </details>
  </section>
</template>
<style scoped>
.optimization-panel {
  min-width: 0;
  overflow-wrap: anywhere;
}
.optimization-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.optimization-history {
  border-top: 1px solid var(--border);
  padding-top: 16px;
}
.optimization-history summary {
  cursor: pointer;
  margin-bottom: 12px;
}
.optimization-record {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}
.jd-snapshot {
  white-space: pre-wrap;
  font: inherit;
  margin-top: 12px;
}
@media (max-width: 480px) {
  .optimization-record {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
