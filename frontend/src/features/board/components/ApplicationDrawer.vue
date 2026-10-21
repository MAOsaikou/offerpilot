<script setup lang="ts">
import { formatDisplay, formatDate, language, translate as uiText } from '@/shared/utils/language'

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { getApplication } from '@/shared/api/applications'
import { getJobMatch, exportJobEvalMarkdown } from '@/shared/api/jobs'
import { exportResumePdf } from '@/shared/api/resumes'
import { exportInterviewRecap } from '@/shared/api/interviews'
import { useBoard } from '@/shared/composables/useBoard'
import {
  applicationStageLabels as applicationStages,
  type ApplicationEvent,
  type ApplicationStatus,
} from '@/shared/types/board'
import type { JobEvaluation } from '@/shared/types/jobs'

const props = defineProps<{ id: string }>()
const emit = defineEmits<{ close: [] }>()
const board = useBoard()
const dialog = ref<HTMLDialogElement>()
const opener = document.activeElement as HTMLElement | null
const query = useQuery({
  queryKey: computed(() => ['applications', 'detail', props.id]),
  queryFn: ({ signal }) => getApplication(props.id, signal),
  refetchOnMount: 'always',
  retry: false,
})
const app = computed(() => query.data.value?.application)
const status = ref<ApplicationStatus>('TODO')
const note = ref('')
const nextAction = ref('')
const nextActionAt = ref('')
const dirty = ref(false)
const actionBusy = ref(false)
const actionError = ref('')
const evaluation = ref<JobEvaluation>()
let active = true
watch(
  app,
  (value) => {
    if (!value || dirty.value) return
    status.value = value.status
    note.value = value.note
    nextAction.value = value.nextAction
    nextActionAt.value = value.nextActionAt ?? ''
  },
  { immediate: true },
)
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => {
  active = false
  dialog.value?.close()
  opener?.focus()
})
async function save() {
  const saved = await board.update(props.id, {
    status: status.value,
    note: note.value,
    nextAction: nextAction.value,
    nextActionAt: nextActionAt.value,
  })
  if (saved && active) {
    dirty.value = false
    await query.refetch()
  }
}
async function action(work: () => Promise<void>) {
  if (actionBusy.value) return
  actionBusy.value = true
  actionError.value = ''
  try {
    await work()
  } catch (error) {
    if (active) actionError.value = (error as Error).message
  } finally {
    if (active) actionBusy.value = false
  }
}
async function openReport() {
  const match = query.data.value?.latestMatch
  if (!match) return
  await action(async () => {
    const report = await getJobMatch(match.id)
    if (active) evaluation.value = report.evaluation
  })
}
const rewriteStates = computed<Record<string, string>>(() => ({
  RUNNING: uiText('Generating'),
  SUCCEEDED: uiText('Completed'),
  FAILED: uiText('Failed'),
  CANCELLED: uiText('Canceled'),
}))
const interviewStates = computed<Record<string, string>>(() => ({
  IN_PROGRESS: uiText('In practice'),
  COMPLETED: uiText('Completed'),
}))

function eventText(event: ApplicationEvent) {
  if (event.kind === 'STATUS_CHANGED')
    return `${applicationStages[event.fromStatus!]} → ${applicationStages[event.toStatus!]}`
  if (event.kind === 'CREATED') return `${event.detail} · ${applicationStages[event.toStatus!]}`
  if (event.kind === 'NOTE_EDITED')
    return uiText('Notes updated: {0}', [event.detail || uiText('Cleared')])
  if (event.kind === 'NEXT_ACTION_EDITED')
    return uiText('Next action: {0}', [event.detail || uiText('Cleared')])
  return event.detail
}
</script>

<template>
  <Teleport to="body">
    <dialog
      ref="dialog"
      class="application-drawer"
      aria-labelledby="application-detail-title"
      @cancel.prevent="emit('close')"
    >
      <header class="drawer-heading">
        <h2 id="application-detail-title">{{ uiText('Application workspace') }}</h2>
        <button
          class="btn"
          :aria-label="uiText('Close application details')"
          @click="emit('close')"
        >
          {{ uiText('Off') }}
        </button>
      </header>
      <p v-if="query.isPending.value" role="status">{{ uiText('Loading application details…') }}</p>
      <div v-else-if="query.isError.value" class="error-banner" role="alert">
        {{ formatDisplay(query.error.value?.message)
        }}<button class="btn" @click="query.refetch()">
          {{ uiText('Retry loading details') }}
        </button>
      </div>
      <div v-else-if="query.data.value && app" class="form-stack">
        <section>
          <p class="subtle">
            {{ app.job.company }} · {{ app.job.locations || uiText('Location not provided') }}
          </p>
          <h3>{{ app.job.title }}</h3>
          <div class="artifact-actions">
            <RouterLink :to="'/jobs/' + app.jobId" class="btn">{{
              uiText('View job / Match resume')
            }}</RouterLink>
            <RouterLink
              :to="{
                path: '/chat',
                query: {
                  applicationId: app.id,
                  mode: 'JOB',
                  prompt: uiText('Review this application and help me plan the next step'),
                },
              }"
              class="btn"
              >{{ uiText('Discuss follow-up in chat') }}</RouterLink
            >
          </div>
        </section>
        <form
          class="panel form-stack"
          :aria-busy="board.busy.value"
          @input="dirty = true"
          @change="dirty = true"
          @submit.prevent="save"
        >
          <label class="field"
            >{{ uiText('Current stage')
            }}<select v-model="status" :disabled="board.busy.value">
              <option v-for="(label, value) in applicationStages" :key="value" :value="value">
                {{ label }}
              </option>
            </select></label
          >
          <label class="field"
            >{{ uiText('Next action')
            }}<textarea
              v-model="nextAction"
              maxlength="1000"
              rows="2"
              :disabled="board.busy.value"
              :placeholder="
                uiText('For example: review the rewritten resume and prepare a STAR project story')
              "
            />
          </label>
          <label class="field"
            >{{ uiText('Action date')
            }}<input
              v-model="nextActionAt"
              type="date"
              min="0001-01-01"
              max="9999-12-31"
              :disabled="board.busy.value"
          /></label>
          <label class="field"
            >{{ uiText('Follow-up notes')
            }}<textarea v-model="note" maxlength="2000" rows="3" :disabled="board.busy.value" />
          </label>
          <p v-if="board.error.value" class="error-banner" role="alert">
            {{ uiText('{0}. Your draft is retained. Please retry.', [board.error.value]) }}
          </p>
          <button class="btn primary" :disabled="!dirty || board.busy.value">
            {{ board.busy.value ? uiText('Saving…') : uiText('Save follow-up') }}
          </button>
          <p v-if="dirty" class="subtle">{{ uiText('You have unsaved changes.') }}</p>
        </form>
        <section class="panel form-stack" :aria-label="uiText('Job match report')">
          <h3>{{ uiText('Job match · A–F assessment') }}</h3>
          <template v-if="query.data.value.latestMatch">
            <p>
              {{
                uiText('Latest assessment: {0} · {1}', [
                  query.data.value.latestMatch.grade,
                  formatDate(query.data.value.latestMatch.createdAt),
                ])
              }}
            </p>
            <ul v-if="query.data.value.latestMatch.topGaps.length">
              <li v-for="gap in query.data.value.latestMatch.topGaps" :key="gap">{{ gap }}</li>
            </ul>
            <p v-else class="subtle">
              {{ uiText('No gaps were listed. Check the original requirements before applying.') }}
            </p>
            <div class="artifact-actions">
              <button class="btn" :disabled="actionBusy" @click="openReport">
                {{ uiText('Open A–F report') }}</button
              ><button
                class="btn"
                :disabled="actionBusy"
                @click="action(() => exportJobEvalMarkdown(query.data.value!.latestMatch!.id))"
              >
                {{ uiText('Download assessment.md') }}
              </button>
            </div>
            <div v-if="evaluation" class="form-stack">
              <p>{{ evaluation.summary }}</p>
              <dl class="evaluation-dimensions">
                <template v-for="dimension in evaluation.dimensions" :key="dimension.code"
                  ><dt>
                    {{ dimension.code }} · {{ dimension.name }} ·
                    {{ formatDisplay(dimension.score) }}/100
                  </dt>
                  <dd>{{ dimension.reason }}</dd></template
                >
              </dl>
              <p class="subtle">{{ evaluation.note }}</p>
            </div>
          </template>
          <p v-else class="subtle">
            {{ uiText('No match report yet.') }}
            <RouterLink :to="'/jobs/' + app.jobId">{{ uiText('Match resume') }}</RouterLink
            >{{ uiText(' to review requirement coverage and evidence gaps.') }}
          </p>
        </section>
        <section class="panel form-stack">
          <h3>{{ uiText('Targeted rewrites and resume PDF') }}</h3>
          <article v-for="resume in query.data.value.resumeOptimizations" :key="resume.id">
            <p>
              {{ formatDate(resume.createdAt) }} ·
              {{ rewriteStates[resume.status] || uiText('Status pending refresh') }}
            </p>
            <div class="artifact-actions">
              <RouterLink
                :to="{
                  path: '/resumes/' + resume.resumeId,
                  query: {
                    tab: 'optimize',
                    optimizationReportId: resume.id,
                    resumeVersionId: resume.resumeVersionId,
                    jobId: app.jobId,
                  },
                }"
                class="btn"
                >{{
                  resume.status === 'SUCCEEDED'
                    ? uiText('Open rewrite suggestions')
                    : uiText('View rewrite history')
                }}</RouterLink
              ><button
                v-if="resume.pdfAvailable"
                class="btn"
                :disabled="actionBusy"
                @click="action(() => exportResumePdf(resume.resumeId))"
              >
                {{ uiText('Download current resume PDF') }}
              </button>
            </div>
          </article>
          <p v-if="query.data.value.resumeOptimizations.length" class="subtle">
            {{
              uiText(
                'PDF export uses the saved version. Review and accept rewrite suggestions to include them in the saved version.',
              )
            }}
          </p>
          <p v-if="!query.data.value.resumeOptimizations.length" class="subtle">
            {{ uiText('No rewrite is confirmed for this job.') }}
            <RouterLink :to="'/jobs/' + app.jobId">{{
              uiText('Rewrite resume for this job')
            }}</RouterLink
            >{{
              uiText(
                ', review and accept changes, then export a PDF. Previous rewrites remain on the resume page.',
              )
            }}
          </p>
        </section>
        <section class="panel form-stack">
          <h3>{{ uiText('Interview practice and review') }}</h3>
          <RouterLink
            class="btn"
            :to="{ path: '/interviews', query: { jobId: app.jobId, role: app.job.title } }"
            >{{ uiText('Practice for this job') }}</RouterLink
          >
          <article v-for="interview in query.data.value.interviews" :key="interview.id">
            <p>
              {{ formatDate(interview.createdAt) }} ·
              {{ interviewStates[interview.status] || uiText('Status pending refresh') }} ·
              {{ interview.overallScore ?? uiText('Not yet scored') }}
            </p>
            <div class="artifact-actions">
              <RouterLink :to="'/interviews/' + interview.id" class="btn">{{
                uiText('Open interview room')
              }}</RouterLink
              ><button
                v-if="interview.recapExportUrl"
                class="btn"
                :disabled="actionBusy"
                @click="action(() => exportInterviewRecap(interview.id))"
              >
                {{ uiText('Download review.md') }}
              </button>
            </div>
          </article>
          <p v-if="!query.data.value.interviews.length" class="subtle">
            {{ uiText('No interview is confirmed for this job.') }}
            <RouterLink
              :to="{ path: '/interviews', query: { jobId: app.jobId, role: app.job.title } }"
              >{{ uiText('Practice an interview') }}</RouterLink
            >{{ uiText(' using real project experience, then review your answers.') }}
          </p>
          <h4>{{ uiText('STAR stories') }}</h4>
          <p v-for="story in query.data.value.starStories" :key="story.id">{{ story.title }}</p>
          <p v-if="!query.data.value.starStories.length" class="subtle">
            {{
              uiText(
                'No STAR stories are linked to this job. Confirm real answers from interview reviews to save them here.',
              )
            }}
          </p>
        </section>
        <p class="helper-note">{{ formatDisplay(query.data.value.linkageNote) }}</p>
        <p v-if="actionBusy" role="status">{{ uiText('Loading materials…') }}</p>
        <p v-if="actionError" class="error-banner" role="alert">
          {{ uiText('{0}. Please retry. #2', [actionError]) }}
        </p>
        <section class="panel form-stack">
          <h3>{{ uiText('Follow-up timeline') }}</h3>
          <ol v-if="query.data.value.timeline.length" class="timeline">
            <li v-for="event in query.data.value.timeline" :key="event.id">
              <time :datetime="event.createdAt">{{
                new Date(event.createdAt).toLocaleString(language)
              }}</time>
              <p>{{ eventText(event) }}</p>
              <small>{{
                event.actor === 'AGENT'
                  ? uiText('Chat approval')
                  : event.actor === 'SYSTEM'
                    ? uiText('System record')
                    : uiText('Manual follow-up')
              }}</small>
            </li>
          </ol>
          <p v-else class="subtle">
            {{ uiText('No events yet. Save a stage or next action to record progress here.') }}
          </p>
        </section>
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.application-drawer {
  position: fixed;
  inset: 0 0 0 auto;
  margin: 0;
  width: min(640px, 100vw);
  max-width: 100vw;
  height: 100dvh;
  max-height: 100dvh;
  overflow: auto;
  padding: 24px;
  border: 0;
  border-left: 1px solid var(--border);
  background: var(--background);
  color: var(--foreground);
  overflow-wrap: anywhere;
}
.application-drawer::backdrop {
  background: rgb(0 0 0 / 40%);
}
.drawer-heading,
.artifact-actions {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.drawer-heading {
  align-items: center;
  margin-bottom: 24px;
}
.artifact-actions {
  justify-content: flex-start;
}
.timeline {
  padding-left: 20px;
}
.timeline li {
  padding: 0 0 16px 8px;
  border-left: 2px solid var(--border);
}
.timeline time,
.timeline small,
.evaluation-dimensions dd {
  color: var(--muted-foreground);
}
.evaluation-dimensions dt {
  font-weight: 600;
}
.evaluation-dimensions dd {
  margin: 4px 0 16px;
}
@media (max-width: 640px) {
  .application-drawer {
    padding: 16px;
  }
  .application-drawer .panel {
    padding: 16px;
  }
  .artifact-actions .btn {
    flex: 1 1 auto;
  }
}
</style>
