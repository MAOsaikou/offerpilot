<script setup lang="ts">
import { formatDisplay, localizedText, formatDateTime, language, translate as uiText } from '@/shared/utils/language'

import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { checkPrivacyFile } from '@/shared/api/privacy-settings'
import { listResumes, uploadResume, validateResumeFile } from '@/shared/api/resumes'
import { resumeStatus } from '@/shared/types/resume'
import { useWorkspace } from '@/shared/stores/workspace'
const workspace = useWorkspace()
const router = useRouter()
const fileInput = ref<HTMLInputElement | null>(null)
const error = localizedText()
const importing = ref(false)
const page = ref(1)
const cache = useQueryClient()
const server = useQuery({
  queryKey: ['resumes', page],
  queryFn: ({ signal }) => listResumes(page.value, signal),
  refetchInterval: (query) =>
    query.state.error
      ? false
      : query.state.data?.items.some((r) => ['QUEUED', 'PARSING'].includes(r.parseStatus))
        ? 2000
        : false,
})
async function create(
  content = uiText("# Personal information Name: Email: Target role: # Education School / Major / Graduation date # Projects Project name: My role: Actions taken: Actual results: # Professional skills"),
  title = uiText("My job search resume"),
) {
  const id = crypto.randomUUID()
  workspace.state.resumes.unshift({
    id,
    title,
    content,
    updated: new Date().toISOString(),
    versions: [],
  })
  workspace.notify(uiText("Created resume:") + title)
  await router.push('/resumes/' + id)
}
async function importFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  error.value = validateResumeFile(file) || ''
  if (error.value) {
    input.value = ''
    return
  }
  importing.value = true
  try {
    const risk = await checkPrivacyFile(file)
    const uploaded = await uploadResume(file)
    void cache.invalidateQueries({ queryKey: ['resumes'] })
    await router.push({
      path: '/resumes/' + uploaded.id,
      query: risk.warning ? { piiWarning: '1' } : {},
    })
  } catch (cause) {
    error.value = () => (cause instanceof Error ? cause.message : uiText("Upload failed. Please retry."))
  } finally {
    importing.value = false
    input.value = ''
  }
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      :title="uiText('Make every experience count')"
      :description="uiText('Organize real experience and prepare different resumes for different opportunities.')"
      ><button class="btn" :disabled="importing" @click="fileInput?.click()">
        {{ importing ? uiText("Uploading… #2") : uiText("Upload and parse") }}</button
      ><button class="btn primary" :disabled="importing" @click="create()"> {{ uiText("＋ Local draft") }} </button></PageHeading
    ><input
      ref="fileInput"
      type="file"
      accept=".pdf,.docx,.png,.jpg,.jpeg,.txt,.md"
      hidden
      :aria-label="uiText('Upload resume')"
      @change="importFile"
    />
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <div class="helper-note"> {{ uiText("PDF, DOCX, PNG, JPG, TXT / Markdown, up to 10 MB per file and 10 pages per PDF. The original is saved on the server and sent to the configured model for parsing. Review the results before saving.") }} <RouterLink to="/settings/models">{{ uiText("Change parsing model") }}</RouterLink>
    </div>
    <template v-if="server.isPending.value">
      <h2>{{ uiText("Uploaded resumes") }} <span class="subtle">{{ uiText("— resumes") }}</span></h2>
      <div class="card-grid" role="status" :aria-label="uiText('Loading server resumes…')">
        <div
          v-for="index in 3"
          :key="index"
          class="panel job-card resume-skeleton"
          aria-hidden="true"
        >
          <div class="skeleton-line skeleton-tag" />
          <div class="skeleton-line skeleton-title" />
          <div class="skeleton-line" />
          <div class="skeleton-line" />
        </div>
      </div>
    </template>
    <div v-else-if="server.isError.value" class="error-banner" role="alert">
      {{ formatDisplay(server.error.value?.message) }}
      <button class="btn" @click="server.refetch()">{{ uiText("Reload #2") }}</button>
    </div>
    <template v-else>
      <h2> {{ uiText("Uploaded resumes") }} <span class="subtle">{{ uiText("{0} resumes", [server.data.value?.total ?? 0]) }}</span>
      </h2>
      <div v-if="server.data.value?.items.length" class="card-grid">
        <RouterLink
          v-for="item in server.data.value.items"
          :key="item.id"
          :to="'/resumes/' + item.id"
          class="panel job-card"
        >
          <div class="card-top">
            <AppIcon name="resumes" /><span class="tag">{{ resumeStatus[item.parseStatus] }}</span>
          </div>
          <h2>{{ item.title }}</h2>
          <p class="subtle">{{ item.fileName }}</p>
          <p v-if="item.parseError" class="error-banner">{{ item.parseError }}</p>
          <progress
            v-if="item.parseStatus === 'PARSING' || item.parseStatus === 'QUEUED'"
            :value="item.parseProgress"
            max="100"
            :aria-label="uiText('Parsing progress')"
          />
          <div class="card-footer">
            <span>{{ new Date(item.updatedAt).toLocaleString(language) }}</span
            ><span>{{ uiText("View") }}</span>
          </div>
        </RouterLink>
      </div>
      <div v-else class="panel empty-state">
        <h3>{{ uiText("Upload your first resume") }}</h3>
        <p>{{ uiText("After parsing, edit personal information, education, projects, internships and skills, then save a new version.") }}</p>
        <button class="btn primary" :disabled="importing" @click="fileInput?.click()"> {{ uiText("Choose file") }} </button>
      </div>
      <div v-if="(server.data.value?.total ?? 0) > 20" class="card-footer">
        <button class="btn" :disabled="page === 1" @click="page--">{{ uiText("Previous page") }}</button
        ><span>{{ uiText("Page {0}", [page]) }}</span>
        <button
          class="btn"
          :disabled="page * 20 >= (server.data.value?.total ?? 0)"
          @click="page++"
        > {{ uiText("Next page") }} </button>
      </div>
    </template>
    <h2>{{ uiText("Local drafts") }}</h2>
    <p class="subtle">{{ uiText("Existing drafts remain in this browser and have not been uploaded. Continue editing or exporting them.") }}</p>
    <div v-if="workspace.state.resumes.length" class="card-grid">
      <RouterLink
        v-for="resume in workspace.state.resumes"
        :key="resume.id"
        :to="'/resumes/' + resume.id"
        class="panel job-card"
        ><div class="card-top">
          <span class="stat-icon"><AppIcon name="resumes" /></span
          ><span class="tag">{{ uiText("{0} versions", [resume.versions.length]) }}</span>
        </div>
        <h2>{{ resume.title }}</h2>
        <p class="subtle">{{ resume.content.slice(0, 100) }}…</p>
        <div class="card-footer">
          <span class="subtle">{{ formatDateTime(resume.updated) }}</span
          ><span>{{ uiText("Edit #2") }}</span>
        </div></RouterLink
      >
    </div>
    <div v-else class="panel empty-state">
      <AppIcon name="resumes" :size="44" />
      <h2>{{ uiText("Your story deserves a good resume") }}</h2>
      <p>{{ uiText("You can also start a local draft and organize your experience before uploading.") }}</p>
      <button class="btn primary" @click="create()">{{ uiText("Create your first resume") }}</button>
    </div>
  </section>
</template>

<style scoped>
/* Match one ordinary uploaded-resume row; reserve three cards during the first read. */
.resume-skeleton {
  min-height: 186px;
}
.skeleton-line {
  height: 22px;
  width: 70%;
  background: var(--muted);
  border-radius: var(--radius-tag);
}
.skeleton-tag {
  height: 26px;
  width: 30%;
}
.skeleton-title {
  height: 28px;
  width: 60%;
}
@media (max-width: 640px) {
  .resume-skeleton {
    min-height: 174px;
  }
}
</style>
