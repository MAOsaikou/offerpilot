<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import PageHeading from '@/shared/components/PageHeading.vue'
import { identitySignal, sessionRevision } from '@/shared/utils/authSession'
import {
  formatDateTime,
  formatNumber,
  localizedText,
  translate as uiText,
} from '@/shared/utils/language'
import { indexStatusLabels, validateKnowledgeFile } from '@/shared/api/knowledge'
import {
  listStaffDocuments,
  uploadStaffDocument,
  reindexStaffDocument,
  deleteStaffDocument,
} from '@/shared/api/staff-knowledge'

const page = ref(1)
const busy = ref(false)
const error = localizedText()
const notice = localizedText()
const confirmingDelete = ref<string | null>(null)
const input = ref<HTMLInputElement>()
const session = identitySignal()
let mounted = true
onScopeDispose(() => {
  mounted = false
})
const current = () => mounted && !session.aborted
const documents = useQuery({
  queryKey: computed(() => ['staff-knowledge', sessionRevision.value, page.value]),
  queryFn: ({ signal }) => listStaffDocuments(page.value, signal),
  refetchInterval: (query) =>
    query.state.data?.items.some((d) => d.indexStatus === 'QUEUED' || d.indexStatus === 'INDEXING')
      ? 3000
      : false,
})
const pageCount = computed(() => Math.max(1, Math.ceil((documents.data.value?.total ?? 0) / 20)))
async function perform(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
  } catch (cause) {
    if (current())
      error.value = () =>
        cause instanceof Error
          ? cause.message
          : uiText('Operation failed. Check your connection and retry.')
  } finally {
    if (current()) busy.value = false
  }
}
async function upload(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  await perform(async () => {
    validateKnowledgeFile(file)
    await uploadStaffDocument(file)
    if (!current()) return
    notice.value = () => uiText('Document uploaded. Indexing will run in the background.')
    if (page.value !== 1) page.value = 1
    else await documents.refetch()
  })
  target.value = ''
}
function reindex(id: string) {
  void perform(async () => {
    await reindexStaffDocument(id)
    if (!current()) return
    notice.value = () => uiText('Reindex queued.')
    await documents.refetch()
  })
}
function remove(id: string) {
  void perform(async () => {
    await deleteStaffDocument(id)
    if (!current()) return
    confirmingDelete.value = null
    notice.value = () => uiText('Document deleted.')
    const result = await documents.refetch()
    if (current() && !result.data?.items.length && page.value > 1) page.value--
  })
}
</script>
<template>
  <main class="staff-knowledge page-stack">
    <PageHeading
      :title="uiText('Staff knowledge documents')"
      :description="
        uiText('Manage shared reference documents independently of private user notes.')
      "
    >
      <RouterLink to="/staff" class="btn">{{ uiText('Back to staff console') }}</RouterLink>
      <RouterLink to="/staff/retrieval-debug" class="btn">{{
        uiText('Retrieval diagnostics')
      }}</RouterLink>
    </PageHeading>
    <section class="panel form-stack" :aria-label="uiText('Upload document')" :aria-busy="busy">
      <p class="subtle">
        {{
          uiText(
            'PDF / MD / TXT, up to 10 MB. PDF text layer: 1–10 pages; no OCR. Indexes the first 30,000 characters and keeps the original.',
          )
        }}
      </p>
      <input
        ref="input"
        type="file"
        accept=".pdf,.md,.txt"
        hidden
        :disabled="busy"
        @change="upload"
      />
      <div class="actions">
        <button class="btn primary" :disabled="busy" @click="input?.click()">
          {{ busy ? uiText('Processing…') : uiText('Upload document') }}
        </button>
        <button
          class="btn"
          :disabled="busy || documents.isFetching.value"
          @click="documents.refetch()"
        >
          {{ documents.isFetching.value ? uiText('Loading…') : uiText('Refresh') }}
        </button>
      </div>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    </section>
    <p v-if="documents.isPending.value" role="status">{{ uiText('Loading documents…') }}</p>
    <div v-else-if="documents.isError.value" class="error-banner" role="alert">
      <p>{{ uiText('Could not load documents. Check staff access and retry.') }}</p>
      <button class="btn" :disabled="documents.isFetching.value" @click="documents.refetch()">
        {{ uiText('Retry') }}
      </button>
    </div>
    <template v-else>
      <p v-if="!documents.data.value?.items.length" class="panel subtle">
        {{ uiText('No staff documents yet. Upload a reference document to begin.') }}
      </p>
      <ul v-else class="documents" :aria-label="uiText('Staff knowledge documents')">
        <li
          v-for="document in documents.data.value?.items"
          :key="document.id"
          class="panel form-stack"
        >
          <div class="document-head">
            <h2>{{ document.title }}</h2>
            <span class="index-badge" :class="document.indexStatus" role="status">{{
              indexStatusLabels[document.indexStatus]
            }}</span>
          </div>
          <p class="subtle">
            {{ document.filename }} ·
            {{ formatNumber(document.byteSize / 1024, { maximumFractionDigits: 1 }) }} KB ·
            {{ uiText('{0} chunks', [document.chunkCount]) }}
          </p>
          <p class="subtle">{{ uiText('Updated: {0}', [formatDateTime(document.updatedAt)]) }}</p>
          <p
            v-if="document.indexError"
            :role="document.indexStatus === 'FAILED' ? 'alert' : 'status'"
          >
            {{ document.indexError }}
          </p>
          <p v-if="document.sourceTruncated" class="subtle">
            {{
              uiText(
                'Index text was truncated to 30,000 characters. The original file is preserved.',
              )
            }}
          </p>
          <div class="actions">
            <button class="btn" :disabled="busy" @click="reindex(document.id)">
              {{ uiText('Reindex') }}
            </button>
            <button
              v-if="confirmingDelete !== document.id"
              class="btn danger"
              :disabled="busy"
              @click="confirmingDelete = document.id"
            >
              {{ uiText('Delete') }}
            </button>
            <template v-else>
              <span>{{ uiText('Delete this document and its index?') }}</span>
              <button class="btn danger" :disabled="busy" @click="remove(document.id)">
                {{ uiText('Confirm deletion') }}
              </button>
              <button class="btn" :disabled="busy" @click="confirmingDelete = null">
                {{ uiText('Cancel') }}
              </button>
            </template>
          </div>
        </li>
      </ul>
      <nav class="actions pagination" :aria-label="uiText('Document pagination')">
        <button
          class="btn"
          :disabled="busy || documents.isFetching.value || page <= 1"
          @click="page--"
        >
          {{ uiText('Previous page') }}
        </button>
        <span role="status">{{
          uiText('Page {0} of {1} · {2} documents', [
            page,
            pageCount,
            documents.data.value?.total ?? 0,
          ])
        }}</span>
        <button
          class="btn"
          :disabled="busy || documents.isFetching.value || page >= pageCount"
          @click="page++"
        >
          {{ uiText('Next page') }}
        </button>
      </nav>
    </template>
  </main>
</template>
<style scoped>
.staff-knowledge {
  width: 100%;
  max-width: 64rem;
  margin-inline: auto;
  padding: 32px 24px 48px;
  min-width: 0;
  overflow-wrap: anywhere;
}
.documents {
  display: grid;
  gap: 16px;
  padding: 0;
  list-style: none;
}
.documents > li {
  min-width: 0;
}
.actions,
.document-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.document-head {
  justify-content: space-between;
}
.document-head h2 {
  min-width: 0;
  font-size: 18px;
}
.index-badge {
  border-radius: 999px;
  padding: 4px 12px;
  background: var(--muted);
  font-size: 13px;
}
.index-badge.READY {
  color: var(--primary);
  background: color-mix(in srgb, var(--primary) 10%, var(--card));
}
.index-badge.FAILED {
  color: var(--destructive);
}
.pagination {
  justify-content: center;
}
@media (max-width: 640px) {
  .staff-knowledge {
    padding: 24px 16px;
  }
}
</style>
