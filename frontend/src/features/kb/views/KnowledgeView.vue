<script setup lang="ts">
import {
  localizedText,
  translateLabel,
  translate as uiText,
  sourceText,
} from '@/shared/utils/language'

import { computed, onScopeDispose, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { downloadBlob, downloadText } from '@/shared/utils/download'
import { sessionRevision } from '@/shared/utils/authSession'
import {
  createKnowledgeNote,
  deleteKnowledgeNote,
  getKnowledgeNote,
  importKnowledgeNotes,
  listKnowledgeNotes,
  noteCategories,
  searchKnowledgeNotes,
  updateKnowledgeNote,
  uploadKnowledgeDocument,
  validateKnowledgeFile,
  getKnowledgeOriginal,
  indexStatusLabels,
  type IndexStatus,
  type NoteCategory,
} from '@/shared/api/knowledge'

const workspace = useWorkspace()
const cache = useQueryClient()
const search = ref('')
const queryText = ref('')
const page = ref(1)
const title = ref('')
const content = ref('')
const category = ref<NoteCategory>(sourceText('Interview review'))
const editing = ref<string | null>(null)
const deleting = ref<string | null>(null)
const message = localizedText()
const error = localizedText()
const busy = ref(false)
const confirmingImport = ref(false)
const fileInput = ref<HTMLInputElement>()
const uploading = ref(false)
let debounce: ReturnType<typeof setTimeout> | undefined
onScopeDispose(() => clearTimeout(debounce))
watch(search, (text) => {
  clearTimeout(debounce)
  debounce = setTimeout(() => {
    queryText.value = text.trim()
    page.value = 1
  }, 250)
})
const notes = useQuery({
  queryKey: computed(() => ['knowledge-notes', sessionRevision.value, queryText.value, page.value]),
  queryFn: async ({ signal }) => {
    if (queryText.value) {
      const result = await searchKnowledgeNotes(queryText.value, 50, signal)
      return {
        items: result.items.map((hit) => ({ ...hit, content: hit.excerpt })),
        total: result.items.length,
        vectorAvailable: result.vectorAvailable,
      }
    }
    const result = await listKnowledgeNotes(page.value, 20, signal)
    return { items: result.items, total: result.total, vectorAvailable: undefined }
  },
  refetchInterval: (query) =>
    query.state.data?.items.some(
      (note) => note.indexStatus === 'QUEUED' || note.indexStatus === 'INDEXING',
    )
      ? 3000
      : false,
})
const localNotes = computed(() => workspace.state.notes)
const pageCount = computed(() => Math.max(1, Math.ceil((notes.data.value?.total ?? 0) / 20)))
function reset() {
  editing.value = null
  title.value = ''
  content.value = ''
  category.value = sourceText('Interview review')
}
watch(sessionRevision, () => {
  reset()
  search.value = ''
  queryText.value = ''
  page.value = 1
  message.value = ''
  error.value = ''
  busy.value = false
  uploading.value = false
  if (fileInput.value) fileInput.value.value = ''
  deleting.value = null
  confirmingImport.value = false
})
async function refresh() {
  await cache.invalidateQueries({ queryKey: ['knowledge-notes'] })
}
async function perform(action: () => Promise<void>) {
  if (busy.value) return
  const revision = sessionRevision.value
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await action()
  } catch (cause) {
    if (revision === sessionRevision.value)
      error.value = () =>
        cause instanceof Error
          ? cause.message
          : uiText('Operation failed. Check your connection and retry.')
  } finally {
    if (revision === sessionRevision.value) busy.value = false
  }
}
async function save() {
  if (!title.value.trim() || !content.value.trim()) return
  const revision = sessionRevision.value
  const draft = {
    title: title.value.trim(),
    content: content.value.trim(),
    category: category.value,
  }
  await perform(async () => {
    if (editing.value) await updateKnowledgeNote(editing.value, draft)
    else await createKnowledgeNote(draft)
    if (revision !== sessionRevision.value) return
    message.value = () => uiText("Note saved to the current account's knowledge library")
    reset()
    await refresh()
  })
}
async function edit(id: string) {
  const revision = sessionRevision.value
  await perform(async () => {
    const note = await getKnowledgeNote(id)
    if (revision !== sessionRevision.value) return
    editing.value = id
    title.value = note.title
    content.value = note.content
    category.value = note.category
  })
}
async function exportNote(id: string) {
  const revision = sessionRevision.value
  await perform(async () => {
    const note = await getKnowledgeNote(id)
    if (revision === sessionRevision.value) downloadText(note.title + '.md', note.content)
  })
}
async function remove(id: string) {
  const revision = sessionRevision.value
  await perform(async () => {
    await deleteKnowledgeNote(id)
    if (revision !== sessionRevision.value) return
    deleting.value = null
    if (editing.value === id) reset()
    message.value = () => uiText('Note deleted')
    if (page.value > 1 && notes.data.value?.items.length === 1) page.value--
    await refresh()
  })
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const revision = sessionRevision.value
  await perform(async () => {
    validateKnowledgeFile(file)
    uploading.value = true
    try {
      const note = await uploadKnowledgeDocument(file)
      if (revision !== sessionRevision.value) return
      search.value = ''
      queryText.value = ''
      clearTimeout(debounce)
      page.value = 1
      message.value = () =>
        note.sourceTruncated
          ? uiText(
              'Resource uploaded. Only the first 30,000 characters were indexed; the full original file is retained.',
            )
          : uiText('Resource uploaded. Check indexing status in the list.')
      await refresh()
    } finally {
      if (revision === sessionRevision.value) uploading.value = false
    }
  })
}
async function downloadOriginal(id: string, filename: string) {
  const revision = sessionRevision.value
  await perform(async () => {
    const blob = await getKnowledgeOriginal(id)
    if (revision === sessionRevision.value) downloadBlob(filename, blob)
  })
}
function statusLabel(status?: IndexStatus) {
  return indexStatusLabels[status ?? 'READY']
}
async function importLocal() {
  const revision = sessionRevision.value
  // Freeze the original payload. Keep newly added/changed local notes, even during a retry.
  const snapshot = localNotes.value.map((note) => ({ ...note }))
  await perform(async () => {
    if (snapshot.some((note) => !noteCategories.includes(note.category as NoteCategory)))
      throw new Error(
        uiText(
          'Local notes contain unknown categories. Export a backup and check the original data first.',
        ),
      )
    for (let offset = 0; offset < snapshot.length; offset += 50) {
      if (revision !== sessionRevision.value) return
      const batch = snapshot.slice(offset, offset + 50)
      const result = await importKnowledgeNotes(
        batch.map((note) => ({
          sourceId: note.id,
          note: {
            title: note.title,
            content: note.content,
            category: note.category as NoteCategory,
          },
        })),
      )
      if (revision !== sessionRevision.value) return
      if (result.imported + result.alreadyImported !== batch.length)
        throw new Error(
          uiText('The server did not confirm all notes. Local copies are retained. Please retry.'),
        )
      workspace.state.notes = workspace.state.notes.filter(
        (note) =>
          !batch.some(
            (saved) =>
              saved.id === note.id &&
              saved.title === note.title &&
              saved.content === note.content &&
              saved.category === note.category,
          ),
      )
      await refresh()
    }
    if (revision !== sessionRevision.value) return
    confirmingImport.value = false
    message.value = () =>
      uiText('Server-confirmed notes imported. Reimporting does not overwrite existing notes.')
    await refresh()
  })
}
function exportLocal() {
  downloadText(
    uiText('local-knowledge-notes-backup.json'),
    JSON.stringify(localNotes.value, null, 2),
  )
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      :title="uiText('Save experience for your next step')"
      :description="
        uiText(
          'Record knowledge, project highlights and interview reflections to track your growth.',
        )
      "
    />
    <div class="helper-note">
      {{
        uiText(
          "Notes belong to the current account's knowledge library and may be cited in interview and growth guidance. Sign in to the same account across devices. Guest notes belong to the original guest identity.",
        )
      }}
    </div>
    <section class="panel form-stack" :aria-label="uiText('Upload knowledge resource')">
      <div class="card-footer">
        <button class="btn primary" :disabled="busy" @click="fileInput?.click()">
          {{ uploading ? uiText('Uploading and extracting…') : uiText('Upload resource') }}
        </button>
        <input
          ref="fileInput"
          type="file"
          accept=".pdf,.md,.txt"
          :aria-label="uiText('Choose a knowledge resource')"
          :disabled="busy"
          hidden
          @change="upload"
        />
        <span class="helper-note">{{
          uiText(
            'PDF / MD / TXT, up to 10 MB each. PDFs must contain selectable text and have no more than 10 pages.',
          )
        }}</span>
      </div>
      <p class="helper-note">
        {{
          uiText(
            'Defaults to Technical learning; edit the category after uploading. Only the first 30,000 characters are indexed. The full original file is retained.',
          )
        }}
      </p>
    </section>
    <section
      v-if="localNotes.length"
      class="panel form-stack"
      :aria-label="uiText('Import local notes')"
    >
      <p>
        {{
          uiText(
            "Found {0} older local notes that have not been imported into this account's knowledge library.",
            [localNotes.length],
          )
        }}
      </p>
      <p v-if="confirmingImport">
        {{
          uiText(
            'Upload these notes to the current account? Local copies are removed only after server confirmation. You can export a backup first.',
          )
        }}
      </p>
      <div class="card-footer">
        <button class="btn" :disabled="busy" @click="exportLocal">
          {{ uiText('Export local backup') }}
        </button>
        <button
          v-if="!confirmingImport"
          class="btn primary"
          :disabled="busy"
          @click="confirmingImport = true"
        >
          {{ uiText('Import local notes') }}
        </button>
        <template v-else>
          <button class="btn primary" :disabled="busy" @click="importLocal">
            {{ uiText('Confirm upload and import') }}
          </button>
          <button class="btn" :disabled="busy" @click="confirmingImport = false">
            {{ uiText('Not now') }}
          </button>
        </template>
      </div>
    </section>
    <p v-if="workspace.storageError" class="error-banner" role="alert">
      {{ workspace.storageError }}
    </p>
    <p v-if="message" class="success-banner" role="status">{{ message }}</p>
    <p v-if="error" class="error-banner" role="alert">
      {{ uiText('{0} Unconfirmed local content will be retained.', [error]) }}
    </p>
    <div class="split-content">
      <section
        class="panel form-stack"
        :aria-label="uiText('Knowledge notes')"
        :aria-busy="notes.isFetching.value"
      >
        <input
          v-model="search"
          class="input"
          :aria-label="uiText('Search knowledge notes')"
          maxlength="2000"
          :placeholder="uiText('Search title, content or category…')"
        />
        <p v-if="notes.isPending.value" role="status">{{ uiText('Loading knowledge notes…') }}</p>
        <div v-else-if="notes.isError.value" class="error-banner" role="alert">
          <p>
            {{
              notes.error.value?.message ||
              uiText('Could not load notes. Check your connection and retry.')
            }}
          </p>
          <button class="btn" @click="notes.refetch()">{{ uiText('Reload') }}</button>
        </div>
        <template v-else>
          <p v-if="queryText" class="helper-note" role="status">
            {{
              uiText('{0}, showing up to 50 relevant notes. Results are original excerpts.', [
                notes.data.value?.vectorAvailable
                  ? uiText('Full-text and semantic search')
                  : uiText('Full-text search'),
              ])
            }}
          </p>
          <div v-if="!notes.data.value?.items.length" class="empty-state">
            <AppIcon name="kb" :size="40" />
            <h2>
              {{
                queryText
                  ? uiText('No matching notes')
                  : uiText('Save your first piece of knowledge')
              }}
            </h2>
            <p>
              {{
                uiText('Record a problem you solved, a project experience or an interview insight.')
              }}
            </p>
          </div>
          <article v-for="note in notes.data.value?.items" :key="note.id" class="answer-card">
            <div class="section-heading">
              <h3 class="break-words">{{ note.title }}</h3>
              <span class="tag">{{ translateLabel(note.category) }}</span>
            </div>
            <div class="card-footer">
              <span class="tag" role="status">{{ statusLabel(note.indexStatus) }}</span>
              <span v-if="note.sourceFilename" class="helper-note break-words">{{
                note.sourceFilename
              }}</span>
            </div>
            <p v-if="note.indexError" class="error-banner" role="alert">{{ note.indexError }}</p>
            <p v-if="note.sourceTruncated" class="helper-note">
              {{
                uiText(
                  'Only the first 30,000 characters are indexed. The full original file is retained.',
                )
              }}
            </p>
            <p class="prose-content break-words">{{ note.content }}</p>
            <div class="card-footer">
              <button class="btn text" :disabled="busy" @click="edit(note.id)">
                {{ uiText('Edit note') }}
              </button>
              <button class="btn" :disabled="busy" @click="exportNote(note.id)">
                {{ uiText('Export') }}
              </button>
              <button
                v-if="note.sourceFilename"
                class="btn"
                :disabled="busy"
                @click="downloadOriginal(note.id, note.sourceFilename)"
              >
                {{ uiText('Download original file') }}
              </button>
              <button class="btn text" :disabled="busy" @click="deleting = note.id">
                {{ uiText('Delete note') }}
              </button>
            </div>
            <div
              v-if="deleting === note.id"
              class="form-stack"
              role="group"
              :aria-label="uiText('Confirm note deletion')"
            >
              <p>
                {{
                  uiText(
                    'Delete “{0}”? This cannot be undone and also deletes the uploaded original file. You can export it first.',
                    [note.title],
                  )
                }}
              </p>
              <div class="card-footer">
                <button class="btn" :disabled="busy" @click="remove(note.id)">
                  {{ uiText('Confirm deletion') }}
                </button>
                <button class="btn" :disabled="busy" @click="deleting = null">
                  {{ uiText('Keep note') }}
                </button>
              </div>
            </div>
          </article>
          <nav
            v-if="!queryText && pageCount > 1"
            class="card-footer"
            :aria-label="uiText('Note pagination')"
          >
            <button class="btn" :disabled="page <= 1 || notes.isFetching.value" @click="page--">
              {{ uiText('Previous page') }}
            </button>
            <span>{{
              uiText('Page {0} of {1} · {2} entries', [page, pageCount, notes.data.value?.total])
            }}</span>
            <button
              class="btn"
              :disabled="page >= pageCount || notes.isFetching.value"
              @click="page++"
            >
              {{ uiText('Next page') }}
            </button>
          </nav>
        </template>
      </section>
      <form class="panel form-stack" style="align-self: start" @submit.prevent="save">
        <h2>{{ editing ? uiText('Edit note') : uiText('New note') }}</h2>
        <label class="field"
          >{{ uiText('Title')
          }}<input
            v-model="title"
            :disabled="busy"
            required
            maxlength="120"
            :placeholder="uiText('Name this piece of knowledge')"
        /></label>
        <label class="field"
          >{{ uiText('Category')
          }}<select v-model="category" :disabled="busy">
            <option v-for="item in noteCategories" :key="item" :value="item">
              {{ translateLabel(item) }}
            </option>
          </select></label
        >
        <label class="field"
          >{{ uiText('Content')
          }}<textarea
            v-model="content"
            :disabled="busy"
            required
            rows="9"
            maxlength="30000"
            :placeholder="uiText('Write what you want to remember…')"
          />
        </label>
        <button class="btn primary" :disabled="busy">
          {{ busy ? uiText('Processing… #2') : uiText('Save note') }}
        </button>
        <button v-if="editing" type="button" class="btn" :disabled="busy" @click="reset">
          {{ uiText('Cancel editing') }}
        </button>
      </form>
    </div>
  </section>
</template>
