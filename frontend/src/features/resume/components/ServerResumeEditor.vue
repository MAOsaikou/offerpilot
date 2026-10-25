<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

import ResumeVersionTree from './ResumeVersionTree.vue'
import GitHubEvidencePanel from '@/shared/components/GitHubEvidencePanel.vue'
import type { ServerResume } from '@/shared/types/resume'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  RouterLink,
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  useRoute,
  useRouter,
} from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  deleteResume,
  exportResumePdf,
  getResume,
  listResumeVersions,
  restoreResumeVersion,
  renameResume,
  retryResume,
  saveResume,
} from '@/shared/api/resumes'
import { resumeStatus, type ResumeStructure, type ResumeVersion } from '@/shared/types/resume'
import PageHeading from '@/shared/components/PageHeading.vue'
import ResumeSectionEditor from './ResumeSectionEditor.vue'
import ResumeCertificateEditor from './ResumeCertificateEditor.vue'
import ResumeDiagnosisPanel from './ResumeDiagnosisPanel.vue'
import ResumeOptimizationPanel from './ResumeOptimizationPanel.vue'
import { downloadText } from '@/shared/utils/download'

const props = defineProps<{ id: string }>()
const route = useRoute()
const cache = useQueryClient()
const query = useQuery({
  queryKey: ['resume', props.id],
  queryFn: ({ signal }) => getResume(props.id, signal),
  refetchOnMount: 'always',
  refetchInterval: (q) =>
    q.state.error
      ? false
      : q.state.data && ['QUEUED', 'PARSING'].includes(q.state.data.parseStatus)
        ? 2000
        : false,
})
const resume = computed(() => query.data.value)
const draft = ref<ResumeStructure>()
const original = ref('')
const title = ref('')
const savedTitle = ref('')
const baseVersion = ref('')
const busy = ref(false)
const exporting = ref(false)
const pdfTemplate = ref<'classic' | 'compact'>('classic')
const error = localizedText()
const message = localizedText()
const tab = ref(
  route.query.tab === 'github'
    ? 'GitHub'
    : route.query.tab === 'optimize'
      ? 'Rewrite'
      : route.query.tab === 'diagnosis'
        ? 'Diagnosis'
        : 'Content',
)
watch(
  () => route.query.tab,
  (value) => {
    if (!busy.value && (value === 'optimize' || value === 'diagnosis' || value === 'github'))
      tab.value = value === 'github' ? 'GitHub' : value === 'optimize' ? 'Rewrite' : 'Diagnosis'
  },
)
const history = ref<ResumeVersion[]>([])
const historyLoading = ref(false)
const selected = ref<ResumeVersion>()
const dirty = computed(
  () =>
    (draft.value && JSON.stringify(draft.value) !== original.value) ||
    title.value !== savedTitle.value,
)
function populate() {
  if (!resume.value) return
  title.value = savedTitle.value = resume.value.title
  if (resume.value.currentVersion) {
    draft.value = JSON.parse(
      JSON.stringify(resume.value.currentVersion.structured),
    ) as ResumeStructure
    draft.value.basics.title ??= ''
    original.value = JSON.stringify(draft.value)
    baseVersion.value = resume.value.currentVersion.id
  }
}
watch(
  () => resume.value?.currentVersionId,
  () => {
    if (!dirty.value) populate()
  },
  { immediate: true },
)
watch(
  () => resume.value?.id,
  () => {
    if (!dirty.value) populate()
  },
  { immediate: true },
)
const deleted = ref(false)
function confirmLeave() {
  return (
    deleted.value ||
    !dirty.value ||
    window.confirm(uiText('You have unsaved resume edits. Leave this page?'))
  )
}
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate(confirmLeave)
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await action()
  } catch (cause) {
    error.value = () =>
      cause instanceof Error ? cause.message : uiText('Operation failed. Please retry.')
  } finally {
    busy.value = false
  }
}
const router = useRouter()
async function remove() {
  const name = resume.value?.title ?? uiText('this resume')
  if (
    !window.confirm(
      uiText(
        'Delete “{0}”? All versions, diagnoses, rewrites, match reports and the original file will be deleted permanently.',
        [name],
      ),
    )
  )
    return
  await run(async () => {
    await deleteResume(props.id)
    deleted.value = true
    cache.removeQueries({ queryKey: ['resume', props.id] })
    await cache.invalidateQueries({ queryKey: ['resumes'] })
    await router.push('/resumes')
  })
}
async function save() {
  let success = false
  await run(async () => {
    if (!draft.value || !baseVersion.value) return
    if (!title.value.trim()) throw new Error(uiText('Enter a resume name.'))
    if (title.value.trim() !== savedTitle.value) {
      await renameResume(props.id, title.value.trim())
      savedTitle.value = title.value = title.value.trim()
    }
    const updated = await saveResume(props.id, baseVersion.value, draft.value)
    original.value = JSON.stringify(draft.value)
    cache.setQueryData(['resume', props.id], updated)
    populate()
    message.value = () => uiText('Saved as a new version. View it in version history.')
    void cache.invalidateQueries({ queryKey: ['resumes'] })
    success = true
  })
  return success
}
async function exportPdf() {
  if (
    busy.value ||
    exporting.value ||
    resume.value?.parseStatus !== 'READY' ||
    !resume.value.currentVersion
  )
    return
  exporting.value = true
  error.value = ''
  try {
    await exportResumePdf(props.id, pdfTemplate.value)
    message.value = () => uiText("Exported the server's current saved resume version as PDF.")
  } catch (cause) {
    error.value = () =>
      cause instanceof Error ? cause.message : uiText('PDF export failed. Please retry.')
  } finally {
    exporting.value = false
  }
}
async function retry() {
  await run(async () => {
    cache.setQueryData(['resume', props.id], await retryResume(props.id))
  })
}
async function reload() {
  if (!confirmLeave()) return
  await run(async () => {
    const result = await query.refetch()
    if (result.error) throw result.error
    populate()
  })
}
async function showHistory() {
  tab.value = 'Versions'
  selected.value = undefined
  historyLoading.value = true
  error.value = ''
  try {
    history.value = await listResumeVersions(props.id)
  } catch (cause) {
    error.value = () => (cause instanceof Error ? cause.message : uiText('Could not load version'))
  } finally {
    historyLoading.value = false
  }
}
function restore(version: ResumeVersion) {
  if (dirty.value && !window.confirm(uiText('Replace unsaved edits with this version?'))) return
  draft.value = JSON.parse(JSON.stringify(version.structured)) as ResumeStructure
  draft.value.basics.title ??= ''
  tab.value = 'Content'
  message.value = () => uiText('Historical version loaded. Saving will create a new version.')
}
async function rollback(version: ResumeVersion) {
  if (!resume.value?.currentVersionId) return
  if (dirty.value) {
    error.value = () => uiText('Save or discard your unsaved edits before restoring.')
    return
  }
  if (
    !window.confirm(
      uiText(
        'Create a new current version using version {0}? All existing versions will be retained.',
        [version.versionNo],
      ),
    )
  )
    return
  await run(async () => {
    const updated = await restoreResumeVersion(
      props.id,
      version.id,
      resume.value!.currentVersionId!,
    )
    cache.setQueryData(['resume', props.id], updated)
    populate()
    history.value = await listResumeVersions(props.id)
    message.value = () =>
      uiText('Restored: version {0} uses content from version {1}.', [
        updated.currentVersion?.versionNo ?? '',
        version.versionNo,
      ])
    void cache.invalidateQueries({ queryKey: ['resumes'] })
  })
}
function optimized(version: ResumeVersion) {
  if (!resume.value || dirty.value) return
  cache.setQueryData(['resume', props.id], {
    ...resume.value,
    currentVersionId: version.id,
    currentVersion: version,
    updatedAt: version.createdAt,
  })
  populate()
  message.value = () => uiText('Rewrite saved as a new version.')
}
function githubAdopted(updated: ServerResume) {
  cache.setQueryData(['resume', props.id], updated)
  populate()
  history.value = []
  void cache.invalidateQueries({ queryKey: ['resumes'] })
}
function lines(value: string) {
  return value.split('\n')
}
const sections = computed(() => [
  {
    key: 'education' as const,
    title: uiText('Education #2'),
    fields: [
      { key: 'school', label: uiText('School') },
      { key: 'degree', label: uiText('Education') },
      { key: 'major', label: uiText('Major') },
      { key: 'start', label: uiText('Start date') },
      { key: 'end', label: uiText('End date') },
      { key: 'gpa', label: uiText('GPA / Grades') },
    ],
  },
  {
    key: 'experience' as const,
    title: uiText('Internships and work'),
    fields: [
      { key: 'org', label: uiText('Company / Organization') },
      { key: 'title', label: uiText('Position') },
      { key: 'start', label: uiText('Start date') },
      { key: 'end', label: uiText('End date') },
      { key: 'bullets', label: uiText('Responsibilities and achievements'), array: true },
    ],
  },
  {
    key: 'projects' as const,
    title: uiText('Projects'),
    fields: [
      { key: 'name', label: uiText('Project name') },
      { key: 'role', label: uiText('Role #2') },
      { key: 'start', label: uiText('Start date') },
      { key: 'end', label: uiText('End date') },
      { key: 'tech', label: uiText('Technology stack'), array: true },
      { key: 'bullets', label: uiText('Actions and results'), array: true },
    ],
  },
  {
    key: 'skills' as const,
    title: uiText('Professional skills'),
    fields: [
      { key: 'category', label: uiText('Skill category') },
      { key: 'items', label: uiText('Skills'), array: true },
    ],
  },
])
const basics = computed(() => [
  { key: 'name' as const, label: uiText('Name') },
  { key: 'title' as const, label: uiText('Title / job objective') },
  { key: 'phone' as const, label: uiText('Phone') },
  { key: 'email' as const, label: uiText('Email') },
  { key: 'city' as const, label: uiText('City') },
])
</script>
<template>
  <section class="page-stack resume-editor">
    <PageHeading
      :title="resume?.title ?? uiText('Resume details')"
      :description="uiText('Review every experience and make your real strengths clear.')"
    >
      <RouterLink class="btn" to="/resumes">{{ uiText('Back to resumes') }}</RouterLink>
      <select
        v-model="pdfTemplate"
        class="input pdf-template"
        :aria-label="uiText('PDF template')"
        :disabled="exporting"
      >
        <option value="classic">{{ uiText('Classic template') }}</option>
        <option value="compact">{{ uiText('Compact single-page template') }}</option>
      </select>
      <button
        class="btn"
        :disabled="busy || exporting || resume?.parseStatus !== 'READY' || !resume?.currentVersion"
        :aria-busy="exporting"
        :title="
          uiText(
            'Exports the server\'s current saved version. Unsaved edits are excluded from the PDF.',
          )
        "
        @click="exportPdf"
      >
        {{ exporting ? uiText('Exporting… #2') : uiText('Export PDF') }}
      </button>
      <button class="btn" :disabled="busy || query.isFetching.value" @click="reload">
        {{ uiText('Reload #2') }}
      </button>
      <button class="btn danger" :disabled="busy || !resume" @click="remove">
        {{ uiText('Delete resume') }}
      </button>
    </PageHeading>
    <div v-if="query.isPending.value" class="panel form-stack">
      <p role="status" class="subtle">{{ uiText('Loading resumes… #2') }}</p>
      <div class="skeleton-stack" aria-hidden="true">
        <span class="skeleton" /><span class="skeleton" /><span class="skeleton" />
      </div>
    </div>
    <p v-if="query.error.value" class="error-banner" role="alert">
      {{ uiText('{0}. Click reload to retry.', [query.error.value.message]) }}
    </p>
    <p v-if="error && (!draft || tab !== 'Content')" class="error-banner" role="alert">
      {{ error }}
    </p>
    <template v-if="resume">
      <div class="panel form-stack" aria-live="polite">
        <div class="card-top">
          <span class="tag">{{ resumeStatus[resume.parseStatus] }}</span
          ><span class="subtle">{{ resume.fileName }}</span>
        </div>
        <template v-if="resume.parseStatus === 'QUEUED' || resume.parseStatus === 'PARSING'">
          <h2 aria-live="polite" role="status">
            {{
              resume.parseStatus === 'QUEUED'
                ? uiText('File saved, queued for parsing')
                : resume.parseProgress < 50
                  ? uiText('Reading original text and page images')
                  : uiText('Extracting and validating structured fields')
            }}
          </h2>
          <progress
            :value="resume.parseProgress"
            max="100"
            :aria-label="uiText('Resume parsing progress')"
          />
          <p class="subtle">
            {{ uiText('Progress indicates the processing stage, not time remaining.') }}
          </p>
          <p>
            {{
              uiText(
                'Parsing continues in the background. You can leave and return later. Images and scans may take longer.',
              )
            }}
          </p>
        </template>
        <template v-if="resume.parseStatus === 'FAILED'">
          <p class="error-banner" role="alert">{{ resume.parseError }}</p>
          <div>
            <button class="btn primary" :disabled="busy" @click="retry">
              {{ busy ? uiText('Submitting…') : uiText('Parse again') }}
            </button>
          </div>
          <p class="subtle">
            {{
              uiText(
                'The original file is saved. Retry without uploading again. If the file is damaged, upload a corrected file from the resume center.',
              )
            }}
          </p>
        </template>
        <p v-if="resume.parseStatus === 'READY'" class="subtle">
          {{
            uiText(
              'Parsing may miss details. Check against the original file. Saving creates a new version. PDF export uses the current saved version.',
            )
          }}
        </p>
      </div>
      <template v-if="resume.parseStatus === 'READY' && draft">
        <div class="tabs no-print">
          <button
            :disabled="busy"
            :class="{ selected: tab === 'Content' }"
            @click="tab = 'Content'"
          >
            {{ uiText('Edit content') }}</button
          ><button
            :disabled="busy"
            :class="{ selected: tab === 'Diagnosis' }"
            @click="tab = 'Diagnosis'"
          >
            {{ uiText('AI diagnosis') }}</button
          ><button
            :disabled="busy"
            :class="{ selected: tab === 'Rewrite' }"
            @click="tab = 'Rewrite'"
          >
            {{ uiText('Targeted rewrite') }}</button
          ><button :disabled="busy" :class="{ selected: tab === 'Versions' }" @click="showHistory">
            {{ uiText('Version history') }}
          </button>
          <button :disabled="busy" :class="{ selected: tab === 'GitHub' }" @click="tab = 'GitHub'">
            {{ uiText('GitHub evidence') }}
          </button>
        </div>
        <form v-if="tab === 'Content'" class="panel form-stack" @submit.prevent="save">
          <fieldset :disabled="busy" class="form-stack resume-fields">
            <label class="field"
              >{{ uiText('Resume name') }}<input v-model="title" maxlength="100" required
            /></label>
            <h2>{{ uiText('Personal information') }}</h2>
            <div class="resume-basics">
              <label v-for="field in basics" :key="field.key" class="field"
                >{{ field.label }}<input v-model="draft.basics[field.key]" maxlength="5000"
              /></label>
            </div>
            <label class="field"
              >{{ uiText('Personal links (one per line)')
              }}<textarea
                :value="draft.basics.links.join('\n')"
                rows="2"
                maxlength="5000"
                @input="draft.basics.links = lines(($event.target as HTMLTextAreaElement).value)"
              />
            </label>
            <label class="field"
              >{{ uiText('Professional summary')
              }}<textarea v-model="draft.summary" rows="4" maxlength="5000" />
            </label>
            <ResumeSectionEditor
              v-for="section in sections"
              :key="section.key"
              v-model="draft[section.key]"
              :title="section.title"
              :fields="section.fields"
              :disabled="busy"
            />
            <ResumeCertificateEditor v-model="draft.certificates" :disabled="busy" />
          </fieldset>
          <div class="card-footer resume-save" :class="{ 'is-dirty': dirty }">
            <div class="form-stack">
              <span v-if="error" class="error-banner" role="alert">{{ error }}</span>
              <span class="save-state" role="status"
                ><i class="save-dot" aria-hidden="true" />{{
                  dirty ? uiText('Unsaved changes') : message || uiText('Synced with server')
                }}</span
              >
            </div>
            <button class="btn primary" :disabled="busy || !dirty" type="submit">
              {{ busy ? uiText('Saving… #2') : uiText('Save new version') }}
            </button>
          </div>
        </form>
        <ResumeDiagnosisPanel
          v-else-if="tab === 'Diagnosis'"
          :resume-id="id"
          :version-id="baseVersion"
          :dirty="!!dirty"
          :save-first="save"
        />
        <ResumeOptimizationPanel
          v-else-if="tab === 'Rewrite'"
          :key="route.fullPath"
          :resume-id="id"
          :version-id="baseVersion"
          :target-version-id="
            typeof route.query.resumeVersionId === 'string'
              ? route.query.resumeVersionId
              : undefined
          "
          :job-id="typeof route.query.jobId === 'string' ? route.query.jobId : undefined"
          :optimization-report-id="
            typeof route.query.optimizationReportId === 'string'
              ? route.query.optimizationReportId
              : undefined
          "
          :dirty="!!dirty"
          :save-first="save"
          @saved="optimized"
          @saving="busy = $event"
        >
          <template #optimization-card="card"
            ><slot name="optimization-card" v-bind="card"
          /></template>
        </ResumeOptimizationPanel>
        <GitHubEvidencePanel
          v-else-if="tab === 'GitHub'"
          :resume-id="id"
          :base-version-id="baseVersion"
          :adoption-disabled="Boolean(dirty) || busy"
          @adopted="githubAdopted"
          @adoption-busy="busy = $event"
        />
        <section v-else class="panel form-stack">
          <p v-if="historyLoading" role="status">{{ uiText('Loading versions… #3') }}</p>
          <ResumeVersionTree
            :versions="history"
            :current-version-id="resume.currentVersionId"
            :busy="busy"
            @view="selected = $event"
            @load="restore"
            @restore="rollback"
          />
          <article v-if="selected" class="form-stack">
            <h2>{{ uiText('Version {0} content', [selected.versionNo]) }}</h2>
            <pre class="resume-text">{{ selected.plainText }}</pre>
            <button
              class="btn"
              @click="
                downloadText(
                  resume.title + '-v' + selected.versionNo + '.json',
                  JSON.stringify(selected.structured, null, 2),
                )
              "
            >
              {{ uiText('Download structured content') }}
            </button>
          </article>
        </section>
      </template>
    </template>
  </section>
</template>
<style scoped>
.resume-editor {
  max-width: 1100px;
  margin-inline: auto;
  width: 100%;
}
.resume-fields {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.resume-basics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.resume-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.resume-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
}
.resume-editor > .tabs ~ * {
  animation-delay: 0ms;
  animation-duration: 240ms;
}
.pdf-template {
  width: auto;
  max-width: 100%;
}
.resume-save {
  position: sticky;
  bottom: 12px;
  background: var(--card);
  padding: 12px 12px 12px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  box-shadow: var(--shadow-float);
  z-index: 2;
  transition: border-color var(--dur-mid) var(--ease-out);
}
.resume-save.is-dirty {
  border-color: color-mix(in srgb, var(--primary) 45%, var(--border));
}
.save-state {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.save-dot {
  width: 7px;
  height: 7px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--success);
  transition: background-color var(--dur-mid);
}
.is-dirty .save-dot {
  background: var(--primary);
  animation: pop-in 280ms var(--ease-out);
}
progress {
  width: 100%;
}
@media (max-width: 640px) {
  .resume-basics {
    grid-template-columns: 1fr;
  }
  .resume-save {
    align-items: stretch;
    gap: 12px;
    flex-direction: column;
  }
}
</style>
