<script setup lang="ts">
import {
  formatDisplay,
  localizedText,
  language,
  translate as uiText,
} from '@/shared/utils/language'

import BackgroundTaskStatus from '@/shared/components/BackgroundTaskStatus.vue'
import { generationResult } from '@/shared/api/generationTasks'

import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { isGenerationCancelled } from '@/shared/api/client'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { analyzeResume, cancelDiagnosis, getDiagnosis, listDiagnoses } from '@/shared/api/diagnoses'
import type { ResumeDiagnosisReport as Report } from '@/shared/types/diagnosis'
import ResumeDiagnosisReport from '@/shared/components/ResumeDiagnosisReport.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
const props = defineProps<{
  resumeId: string
  versionId: string
  dirty: boolean
  saveFirst: () => Promise<boolean>
}>()
const cache = useQueryClient()
const history = useQuery({
  queryKey: ['diagnoses', props.resumeId],
  queryFn: () => listDiagnoses(props.resumeId),
  refetchInterval: (q) => (q.state.data?.some((t) => t.status === 'RUNNING') ? 2000 : false),
})
const report = ref<Report>()
const reportElement = ref<HTMLElement>()
const error = localizedText()
const message = localizedText()
const running = ref(false)
const selecting = ref(false)
const runningId = ref('')
const existing = computed(() => history.data.value?.find((task) => task.status === 'RUNNING'))
let locallyCancelled = false
let controller: AbortController | undefined
let alive = true
let selection = 0
const labels = computed(() => ({
  RUNNING: uiText('Analyzing'),
  SUCCEEDED: uiText('Completed'),
  FAILED: uiText('Failed'),
  CANCELLED: uiText('Canceled'),
}))
const refresh = () => cache.invalidateQueries({ queryKey: ['diagnoses', props.resumeId] })
async function revealReport(value: Report, epoch: number) {
  if (!alive || epoch !== selection) return
  report.value = value
  await nextTick()
  if (!alive || epoch !== selection) return
  reportElement.value?.focus({ preventScroll: true })
  reportElement.value?.scrollIntoView?.({
    block: 'start',
    behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
  })
}
async function show(id: string) {
  const epoch = ++selection
  selecting.value = true
  error.value = ''
  try {
    const task = await getDiagnosis(id)
    if (!task.report)
      throw new Error(uiText('The report is incomplete. Refresh diagnosis history and retry.'))
    await revealReport(task.report, epoch)
  } catch (cause) {
    if (alive && epoch === selection)
      error.value = () => (cause instanceof Error ? cause.message : uiText('Could not load report'))
  } finally {
    if (alive && epoch === selection) selecting.value = false
  }
}
async function start(save: boolean) {
  if (running.value || existing.value) return
  running.value = true
  locallyCancelled = false
  error.value = ''
  runningId.value = ''
  message.value = ''
  const epoch = ++selection
  let requestId = ''
  try {
    if (save && !(await props.saveFirst())) return
    await nextTick()
    if (!alive) return
    controller = new AbortController()
    requestId = crypto.randomUUID()
    const result = await analyzeResume(
      props.resumeId,
      props.versionId,
      requestId,
      controller.signal,
      (id) => {
        runningId.value = id
        if (locallyCancelled) void cancelDiagnosis(id).catch(() => {})
        void refresh()
      },
    )
    await revealReport(result, epoch)
  } catch (cause) {
    if (alive) {
      if (
        isGenerationCancelled(cause) ||
        (locallyCancelled && cause instanceof DOMException && cause.name === 'AbortError')
      ) {
        error.value = ''
        message.value = () => uiText('Diagnosis canceled. You can start again.')
        return
      }
      error.value = () =>
        cause instanceof DOMException && cause.name === 'AbortError'
          ? uiText('Diagnosis connection stopped. Check report history.')
          : cause instanceof Error
            ? cause.message
            : uiText('Diagnosis failed')
      // A disconnect may happen after the report committed. Recover this exact request before retrying.
      if (requestId)
        try {
          const tasks = await listDiagnoses(props.resumeId)
          const task = tasks.find((t) => t.requestId === requestId && t.status === 'SUCCEEDED')
          if (task) {
            await show(task.id)
            error.value = ''
          }
        } catch {
          /* Keep the original actionable error. */
        }
    }
  } finally {
    if (alive) {
      running.value = false
      runningId.value = ''
      void refresh()
    }
  }
}
async function stop(id = runningId.value || existing.value?.id) {
  if (!id) {
    locallyCancelled = true
    message.value = () => uiText('Diagnosis canceled. You can start again.')
    error.value = ''
    controller?.abort()
    return
  }
  try {
    await cancelDiagnosis(id)
    locallyCancelled = true
    message.value = () => uiText('Diagnosis canceled. You can start again.')
    error.value = ''
    controller?.abort()
    await refresh()
  } catch (cause) {
    error.value = () =>
      cause instanceof Error ? cause.message : uiText('Could not cancel. Please retry.')
  }
}
onBeforeUnmount(() => {
  alive = false
  selection++
})
async function restoreTask(id: string) {
  if (running.value || selecting.value) return
  try {
    const saved = await generationResult<Report>(id)
    if (alive && report.value?.reportId !== saved.reportId) await show(saved.reportId)
  } catch (cause) {
    if (alive)
      error.value = () =>
        cause instanceof Error ? cause.message : uiText('Could not restore report')
  }
}
</script>
<template>
  <section class="panel form-stack diagnosis-panel">
    <BackgroundTaskStatus kind="RESUME_DIAGNOSIS" :entity-id="resumeId" @completed="restoreTask" />
    <header class="diagnosis-intro">
      <span class="diagnosis-icon"><AppIcon name="spark" :size="24" /></span>
      <div>
        <h2>{{ uiText('AI resume diagnosis') }}</h2>
        <p>{{ uiText('Find strengths worth keeping and turn suggestions into action.') }}</p>
        <small>{{
          uiText(
            'Analyzes the saved version using the text model in settings. Your resume is not changed automatically.',
          )
        }}</small>
      </div>
    </header>
    <div v-if="dirty" class="helper-note">
      {{
        uiText(
          'You have unsaved edits. Save them before diagnosis or analyze only the saved version.',
        )
      }}
    </div>
    <div class="diagnosis-actions">
      <button
        v-if="dirty"
        class="btn primary"
        :disabled="running || !!existing"
        @click="start(true)"
      >
        {{ uiText('Save new version and diagnose') }}
      </button>
      <button
        class="btn"
        :class="{ primary: !dirty }"
        :disabled="running || !!existing"
        @click="start(false)"
      >
        {{
          running || existing
            ? uiText('Analyzing…')
            : dirty
              ? uiText('Diagnose saved version only')
              : uiText('Start AI diagnosis')
        }}
      </button>
      <button v-if="running || existing" class="btn" @click="stop()">
        {{ uiText('Cancel diagnosis') }}
      </button>
    </div>
    <p v-if="running || existing" role="status">
      {{
        uiText(
          'Analyzing the saved version. This usually takes tens of seconds and continues after you leave. Return to view the report.',
        )
      }}
    </p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <p v-if="selecting" role="status">{{ uiText('Loading report…') }}</p>
    <p v-if="report && report.versionId !== versionId" class="helper-note">
      {{
        uiText(
          "This diagnosis is for an older version. Your editing version differs; citations still point to the report's original version.",
        )
      }}
    </p>
    <div
      v-if="report"
      ref="reportElement"
      class="report-result"
      tabindex="-1"
      :aria-label="uiText('Resume diagnosis report')"
    >
      <ResumeDiagnosisReport :report="report" />
    </div>
    <details class="diagnosis-history-group" :open="!report">
      <summary :data-expand="uiText('Expand')" :data-collapse="uiText('Collapse')">
        <AppIcon name="history" :size="18" /><span>{{ uiText('Diagnosis history') }}</span
        ><span class="subtle">{{ uiText('{0} times', [history.data.value?.length || 0]) }}</span>
      </summary>
      <div class="history-toolbar">
        <p class="subtle">{{ uiText('View saved reports without calling the model again.') }}</p>
        <button class="btn" :disabled="history.isFetching.value" @click="history.refetch()">
          {{ uiText('Refresh history') }}
        </button>
      </div>
      <p v-if="history.isPending.value" role="status">{{ uiText('Loading history…') }}</p>
      <p v-else-if="history.error.value" class="error-banner" role="alert">
        {{ formatDisplay(history.error.value.message) }}
      </p>
      <p v-else-if="!history.data.value?.length" class="subtle">
        {{ uiText('No diagnosis reports yet. Use the button above to start.') }}
      </p>
      <div v-for="task in history.data.value" :key="task.id" class="diagnosis-history">
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
          :disabled="selecting || running"
          @click="show(task.id)"
        >
          {{ uiText('View report') }}
        </button>
      </div>
    </details>
  </section>
</template>
<style scoped>
.diagnosis-panel {
  min-width: 0;
  overflow-wrap: anywhere;
}
.diagnosis-intro {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.diagnosis-icon {
  display: grid;
  place-items: center;
  flex: 0 0 44px;
  height: 44px;
  background: var(--primary-tint);
  color: var(--primary);
  border-radius: var(--radius-control);
}
.diagnosis-intro h2 {
  font-size: 18px;
  font-weight: 600;
}
.diagnosis-intro p {
  margin-top: 4px;
  font-size: 14px;
}
.diagnosis-intro small {
  color: var(--muted-foreground);
  font-size: 12px;
}
.report-result {
  scroll-margin-top: 24px;
}
.diagnosis-history-group {
  border-top: 1px solid var(--border);
  padding-top: 12px;
}
.diagnosis-history-group summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  cursor: pointer;
  font-weight: 550;
}
.diagnosis-history-group summary::after {
  content: attr(data-expand);
  margin-left: auto;
  font-size: 12px;
  color: var(--muted-foreground);
}
.diagnosis-history-group[open] summary::after {
  content: attr(data-collapse);
}
.history-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.diagnosis-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.diagnosis-history {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}
@media (max-width: 480px) {
  .diagnosis-history {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
