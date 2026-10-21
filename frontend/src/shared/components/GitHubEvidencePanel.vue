<script setup lang="ts">
import { formatDateTime, localizedText, translate as uiText } from '@/shared/utils/language'

import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { sessionRevision } from '@/shared/utils/authSession'
import {
  getGitHubEvidence,
  saveGitHubSettings,
  refreshGitHubEvidence,
  deleteGitHubSettings,
  adoptGitHubProject,
  type GitHubEvidence,
  type GitHubProject,
} from '@/shared/api/github-evidence'
import type { ServerResume } from '@/shared/types/resume'
const props = defineProps<{
  resumeId?: string
  baseVersionId?: string
  adoptionDisabled?: boolean
}>()
const emit = defineEmits<{ adopted: [resume: ServerResume]; adoptionBusy: [value: boolean] }>()
const data = ref<GitHubEvidence>(),
  username = ref(''),
  token = ref(''),
  removeToken = ref(false)
const busy = ref(false),
  error = localizedText(),
  message = localizedText(),
  selected = ref<GitHubProject>(),
  confirmed = ref(false)
let controller = new AbortController()
const settingsDirty = computed(
  () =>
    username.value.trim() !== (data.value?.username ?? '') ||
    Boolean(token.value) ||
    removeToken.value,
)
watch([username, token, removeToken], () => {
  selected.value = undefined
  confirmed.value = false
})
async function run(action: (signal: AbortSignal) => Promise<void>) {
  if (busy.value) return
  const request = controller
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await action(request.signal)
  } catch (e) {
    if (!request.signal.aborted)
      error.value = () =>
        e instanceof Error ? e.message : uiText('Operation failed. Please retry. #2')
  } finally {
    if (!request.signal.aborted) busy.value = false
  }
}
function load() {
  return run(async (signal) => {
    const result = await getGitHubEvidence(signal)
    if (signal.aborted) return
    data.value = result
    username.value = result.username
  })
}
watch(
  sessionRevision,
  () => {
    controller.abort()
    controller = new AbortController()
    data.value = undefined
    token.value = ''
    username.value = ''
    selected.value = undefined
    confirmed.value = false
    busy.value = false
    error.value = ''
    message.value = ''
    removeToken.value = false
    void load()
  },
  { immediate: true },
)
watch(
  () => props.baseVersionId,
  () => {
    selected.value = undefined
    confirmed.value = false
  },
)
onBeforeUnmount(() => {
  token.value = ''
  controller.abort()
})
function save() {
  return run(async (signal) => {
    const request = saveGitHubSettings(
      {
        username: username.value.trim(),
        token: token.value || undefined,
        removeToken: removeToken.value,
      },
      signal,
    )
    token.value = ''
    const result = await request
    if (signal.aborted) return
    data.value = result
    removeToken.value = false
    selected.value = undefined
    message.value = () => uiText('Settings saved. Fetch public repositories to continue.')
  })
}
function refresh(force: boolean) {
  return run(async (signal) => {
    const result = await refreshGitHubEvidence(force, signal)
    if (signal.aborted) return
    data.value = result
    selected.value = undefined
    confirmed.value = false
  })
}
function disconnect() {
  return run(async (signal) => {
    await deleteGitHubSettings(signal)
    if (signal.aborted) return
    data.value = undefined
    username.value = ''
    token.value = ''
    selected.value = undefined
    removeToken.value = false
    message.value = () => uiText('GitHub settings, credentials and cache removed.')
  })
}
function preview(project: GitHubProject) {
  selected.value = project
  confirmed.value = false
}
function adopt() {
  if (
    !props.resumeId ||
    !props.baseVersionId ||
    props.adoptionDisabled ||
    settingsDirty.value ||
    !confirmed.value ||
    !selected.value ||
    !data.value?.snapshot
  )
    return
  const body = {
    resumeId: props.resumeId,
    baseVersionId: props.baseVersionId,
    snapshotId: data.value.snapshot.id,
    repository: selected.value.name,
    confirmed: true,
  }
  return run(async (signal) => {
    emit('adoptionBusy', true)
    try {
      const result = await adoptGitHubProject(body, signal)
      if (signal.aborted) return
      emit('adopted', result)
      selected.value = undefined
      confirmed.value = false
      message.value = () =>
        uiText(
          'Project suggestion saved as a new resume version. Add your personal contribution in the editor.',
        )
    } finally {
      emit('adoptionBusy', false)
    }
  })
}
</script>
<template>
  <section class="panel form-stack min-w-0" :aria-label="uiText('GitHub skill evidence')">
    <h2>{{ uiText('GitHub skill evidence') }}</h2>
    <p class="helper-note">
      {{
        uiText(
          'Reads public repositories only. README highlights are sent to FLASH. Repository ownership does not prove personal contribution. Review before accepting.',
        )
      }}
    </p>
    <p v-if="busy" role="status">{{ uiText('Processing GitHub request…') }}</p>
    <p v-if="error" class="error-banner" role="alert">
      {{ error }} <button class="btn" :disabled="busy" @click="load">{{ uiText('Reload') }}</button>
    </p>
    <p v-if="message" role="status">{{ message }}</p>
    <form class="form-stack" @submit.prevent="save">
      <fieldset :disabled="busy" class="form-stack min-w-0">
        <label class="field"
          >{{ uiText('Public GitHub username')
          }}<input
            v-model="username"
            :placeholder="uiText('Enter your public GitHub username. A token is optional.')"
            aria-describedby="github-username-help"
            maxlength="39"
            required
            autocomplete="off"
        /></label>
        <label class="field"
          >{{
            uiText('Optional token ({0})', [
              data?.tokenConfigured
                ? uiText('Configured, never displayed; leave blank to keep')
                : uiText('Not configured; optional'),
            ])
          }}
          <input
            v-model="token"
            type="password"
            maxlength="255"
            autocomplete="new-password"
            :disabled="removeToken"
          />
        </label>
        <label
          ><input v-model="removeToken" type="checkbox" @change="token = ''" />{{
            uiText('Remove saved token')
          }}</label
        >
        <p class="helper-note">
          {{
            uiText(
              'Tokens are encrypted on the server and never stored in the browser. Grant only public repository read access. Changing username removes the old token.',
            )
          }}
        </p>
        <button class="btn" type="submit" :disabled="!username.trim()">
          {{ uiText('Save GitHub settings') }}
        </button>
      </fieldset>
    </form>
    <div class="flex flex-wrap gap-2">
      <button
        class="btn"
        :disabled="busy || settingsDirty || !data?.username"
        @click="refresh(false)"
      >
        {{ uiText('Fetch public repositories (prefer cache)') }}
      </button>
      <button
        class="btn"
        :disabled="busy || settingsDirty || !data?.username"
        @click="refresh(true)"
      >
        {{ uiText('Force refresh') }}
      </button>
      <button class="btn" :disabled="busy || !data?.username" @click="disconnect">
        {{ uiText('Remove GitHub connection') }}
      </button>
    </div>
    <p v-if="settingsDirty" class="helper-note">
      {{ uiText('Save GitHub settings before fetching or accepting project suggestions.') }}
    </p>
    <p v-if="data" id="github-username-help" class="helper-note" role="status">
      {{
        data.status === 'NOT_CONFIGURED'
          ? uiText('Enter your public GitHub username. A token is optional.')
          : data.notice
      }}
    </p>
    <template v-if="data?.snapshot">
      <p>
        {{
          uiText('Fetched: {0} · Valid until: {1}', [
            formatDateTime(data.fetchedAt),
            formatDateTime(data.expiresAt),
          ])
        }}
        <strong v-if="data.stale">{{ uiText('Historical cache; refresh needed') }}</strong>
      </p>
      <p>
        {{
          uiText(
            '{0} public repositories · {1} stars · {2} personal commits in 90 days (a lower bound if pagination is capped)',
            [data.snapshot.repositories.length, data.snapshot.stars, data.snapshot.commits90d],
          )
        }}
      </p>
      <p v-if="data.snapshot.repositoriesTruncated">
        {{
          uiText(
            'Only the {0} fully fetched repositories are counted; not all repositories are covered.',
            [data.snapshot.repositories.length],
          )
        }}
      </p>
      <p class="break-words">
        {{
          uiText('Language distribution (bytes): {0}', [
            Object.entries(data.snapshot.languages)
              .map(([name, bytes]) => `${name} ${bytes}`)
              .join(' · ') || uiText('No language data'),
          ])
        }}
      </p>
      <p v-if="!data.snapshot.repositories.length">
        {{
          data.snapshot.repositoriesTruncated
            ? uiText(
                'Repositories were not fully fetched this time. Try again later or configure a token.',
              )
            : uiText('This user has no public repositories.')
        }}
      </p>
      <article
        v-for="project in data.snapshot.repositories"
        :key="project.name"
        class="form-stack min-w-0 rounded-lg border border-border p-3"
      >
        <h3 class="break-words">
          <a :href="project.url" target="_blank" rel="noopener noreferrer">{{ project.name }}</a>
          <span v-if="project.fork">（fork）</span>
        </h3>
        <p>
          {{
            uiText('{0} stars · {1}{2} personal commits in 90 days', [
              project.stars,
              project.commitsTruncated ? '≥' : '',
              project.commits90d,
            ])
          }}
        </p>
        <p class="break-words">
          {{
            Object.entries(project.languages)
              .map(([name, bytes]) => uiText('{0} {1} bytes', [name, bytes]))
              .join(' · ') || uiText('No language data')
          }}
        </p>
        <ul v-if="project.highlights.length">
          <li v-for="quote in project.highlights" :key="quote" class="break-words">{{ quote }}</li>
        </ul>
        <p v-else>
          {{ uiText('No usable README highlights. No project descriptions are invented.') }}
        </p>
        <button
          v-if="resumeId && project.highlights.length"
          class="btn"
          :disabled="busy || settingsDirty || data.stale || adoptionDisabled"
          @click="preview(project)"
        >
          {{ uiText('Suggest as a resume project') }}
        </button>
      </article>
      <p v-if="resumeId && adoptionDisabled" class="helper-note">
        {{ uiText('Save or discard your resume draft before accepting a project suggestion.') }}
      </p>
      <section
        v-if="selected"
        class="form-stack"
        :aria-label="uiText('Confirm GitHub project suggestion')"
      >
        <h3>{{ uiText('Project to add after review: {0}', [selected.name]) }}</h3>
        <p>
          {{
            uiText('Public project description (add your own contribution): {0}', [selected.url])
          }}
        </p>
        <ul>
          <li v-for="quote in selected.highlights" :key="quote" class="break-words">{{ quote }}</li>
        </ul>
        <label
          ><input v-model="confirmed" type="checkbox" />{{
            uiText(
              'I checked the source and project association. These are project descriptions. I will add my own contribution and will not present repository metrics as personal achievements.',
            )
          }}</label
        >
        <div class="flex flex-wrap gap-2">
          <button
            class="btn primary"
            :disabled="busy || settingsDirty || !confirmed || adoptionDisabled"
            @click="adopt"
          >
            {{ uiText('Confirm addition and save new version') }}
          </button>
          <button class="btn" :disabled="busy" @click="selected = undefined">
            {{ uiText('Cancel') }}
          </button>
        </div>
      </section>
    </template>
  </section>
</template>
