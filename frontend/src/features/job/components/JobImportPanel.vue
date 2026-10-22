<script setup lang="ts">
import { formatDisplay, localizedText, translate as uiText } from '@/shared/utils/language'

import BackgroundTaskStatus from '@/shared/components/BackgroundTaskStatus.vue'
import { generationResult } from '@/shared/api/generationTasks'

import { computed, ref, onBeforeUnmount } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  listImportedJobs,
  parseJobImport,
  saveJobImport,
  uploadJobScreenshot,
  validateJobScreenshot,
  readJobScreenshot,
} from '@/shared/api/jobs'
import type { JobImportFields, JobImportParse } from '@/shared/types/jobs'

const router = useRouter()
const queryClient = useQueryClient()
const open = ref(false)
const mode = ref<'text' | 'url' | 'screenshot'>('text')
const text = ref('')
const url = ref('')
const screenshots = ref<Array<{ file: File; preview: string; fileId?: string }>>([])
const restoredPreviews = ref<string[]>([])
let previewRevision = 0
const uploaded = ref(0)
const uploading = ref(false)
function addFiles(files: File[]) {
  if (parsing.value || !files.length) return
  error.value = ''
  if (screenshots.value.length + files.length > 3) {
    error.value = () => uiText('Select up to 3 consecutive screenshots of the same job.')
    return
  }
  const invalid = files.map(validateJobScreenshot).find(Boolean)
  if (invalid) {
    error.value = invalid
    return
  }
  screenshots.value.push(...files.map((file) => ({ file, preview: URL.createObjectURL(file) })))
}
function chooseFiles(event: Event) {
  const input = event.target as HTMLInputElement
  addFiles(Array.from(input.files || []))
  input.value = ''
}
function pasteImages(event: ClipboardEvent) {
  if (mode.value !== 'screenshot' || form.value || parsing.value) return
  const files = Array.from(event.clipboardData?.files || [])
  if (files.length) {
    event.preventDefault()
    addFiles(files)
  }
}
function removeImage(index: number) {
  const [image] = screenshots.value.splice(index, 1)
  if (image) URL.revokeObjectURL(image.preview)
}
onBeforeUnmount(() => {
  previewRevision++
  screenshots.value.forEach((image) => URL.revokeObjectURL(image.preview))
  restoredPreviews.value.forEach(URL.revokeObjectURL)
})
const parsing = ref(false)
const saving = ref(false)
const error = localizedText()
const parsed = ref<JobImportParse | null>(null)
const form = ref<JobImportFields | null>(null)

const imported = useQuery({
  queryKey: ['jobs-imported'],
  queryFn: ({ signal }) => listImportedJobs(signal),
  retry: false,
})
const canParse = computed(() =>
  mode.value === 'text'
    ? text.value.trim().length >= 30
    : mode.value === 'url'
      ? /^https?:\/\/\S+$/i.test(url.value.trim())
      : screenshots.value.length > 0,
)
const canSave = computed(
  () =>
    !!form.value?.title.trim() &&
    !!(form.value.description.trim() || form.value.requirement.trim()) &&
    !saving.value,
)

async function parse() {
  if (!canParse.value || parsing.value) return
  parsing.value = true
  error.value = ''
  try {
    let source: { text?: string; url?: string; fileIds?: string[] }
    if (mode.value === 'screenshot') {
      uploading.value = true
      uploaded.value = 0
      for (const image of screenshots.value) {
        image.fileId ??= (await uploadJobScreenshot(image.file)).fileId
        uploaded.value++
      }
      uploading.value = false
      source = { fileIds: screenshots.value.map((image) => image.fileId!) }
    } else source = mode.value === 'text' ? { text: text.value.trim() } : { url: url.value.trim() }
    const result = await parseJobImport(source)
    parsed.value = result
    const { parser: _parser, ...fields } = result.draft
    form.value = { ...fields, skills: [...fields.skills], url: result.url }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    uploading.value = false
    parsing.value = false
  }
}

async function save() {
  if (!form.value || !canSave.value) return
  saving.value = true
  error.value = ''
  try {
    const job = await saveJobImport(form.value)
    void queryClient.invalidateQueries({ queryKey: ['jobs-imported'] })
    await router.push('/jobs/' + job.id)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    saving.value = false
  }
}

function restart() {
  previewRevision++
  restoredPreviews.value.forEach(URL.revokeObjectURL)
  restoredPreviews.value = []
  parsed.value = null
  form.value = null
  error.value = ''
}
async function restoreTask(id: string) {
  if (parsing.value || saving.value || form.value) return
  try {
    const result = await generationResult<JobImportParse>(id)
    parsed.value = result
    const { parser: _parser, ...fields } = result.draft
    form.value = { ...fields, skills: [...fields.skills], url: result.url }
    open.value = true
    if (result.sourceKind === 'screenshot') {
      const revision = ++previewRevision
      mode.value = 'screenshot'
      restoredPreviews.value.forEach(URL.revokeObjectURL)
      restoredPreviews.value = []
      for (const id of result.fileIds || []) {
        try {
          const blob = await readJobScreenshot(id)
          if (revision !== previewRevision) return
          restoredPreviews.value.push(URL.createObjectURL(blob))
        } catch {
          error.value = () =>
            uiText('Draft restored. Screenshot previews are unavailable; refresh later to retry.')
        }
      }
    }
  } catch (cause) {
    error.value = () => (cause instanceof Error ? cause.message : uiText('Could not restore draft'))
  }
}
</script>

<template>
  <section
    class="panel form-stack job-import"
    aria-labelledby="job-import-title"
    @paste="pasteImages"
  >
    <BackgroundTaskStatus kind="JOB_IMPORT" :entity-id="'current'" @completed="restoreTask" />
    <div class="section-heading">
      <h2 id="job-import-title">{{ uiText('Import job') }}</h2>
      <button type="button" class="btn" :aria-expanded="open" @click="open = !open">
        {{ open ? uiText('Collapse') : uiText('Paste description or URL / Screenshots') }}
      </button>
    </div>
    <p class="subtle">
      {{
        uiText(
          'Found a job elsewhere? Paste its description, enter a URL or upload screenshots. Review and save it to generate match reports and add it to your board. Imported jobs are private to you.',
        )
      }}
    </p>
    <template v-if="open">
      <div v-if="!form" class="form-stack">
        <fieldset class="mode-switch" :disabled="parsing" :aria-label="uiText('Import method')">
          <label
            ><input v-model="mode" type="radio" value="text" /> {{ uiText('Paste text') }}</label
          >
          <label><input v-model="mode" type="radio" value="url" /> {{ uiText('Job URL') }}</label>
          <label
            ><input v-model="mode" type="radio" value="screenshot" />
            {{ uiText('Screenshots') }}</label
          >
        </fieldset>
        <label v-if="mode === 'text'" class="field"
          >{{ uiText('Job description')
          }}<textarea
            v-model="text"
            rows="8"
            maxlength="20000"
            :placeholder="
              uiText(
                'Copy the job title, responsibilities and requirements from the listing (at least 30 characters)',
              )
            "
          />
        </label>
        <template v-else-if="mode === 'url'">
          <label class="field"
            >{{ uiText('Job URL')
            }}<input
              v-model="url"
              type="url"
              maxlength="2000"
              placeholder="https://jobs.example.com/position/123"
          /></label>
          <p class="subtle">
            {{
              uiText(
                'Only public pages can be read. Pages requiring sign-in or browser scripts may not work. Paste the text instead.',
              )
            }}
          </p>
        </template>
        <div
          v-else
          class="form-stack screenshot-input"
          tabindex="0"
          :aria-label="uiText('Select or paste job screenshots')"
        >
          <p class="subtle">
            {{
              uiText(
                'Add consecutive screenshots of the same job in reading order. PNG / JPG / WebP, up to 5 MB each, 3 maximum. Only you can view them.',
              )
            }}
          </p>
          <input
            type="file"
            accept="image/png,image/jpeg,image/webp"
            multiple
            :disabled="parsing"
            :aria-label="uiText('Select job screenshots')"
            @change="chooseFiles"
          />
          <p v-if="!screenshots.length" class="subtle">
            {{
              uiText(
                'No screenshots selected. Make sure the title, responsibilities and requirements are legible.',
              )
            }}
          </p>
          <ol v-else class="screenshot-list">
            <li v-for="(image, index) in screenshots" :key="image.preview">
              <img
                :src="image.preview"
                :alt="uiText('Screenshots') + (index + 1) + '：' + image.file.name"
              />
              <span>{{ formatDisplay(index + 1) }}. {{ image.file.name }}</span>
              <button
                type="button"
                class="btn"
                :disabled="parsing"
                :aria-label="uiText('Remove screenshot') + (index + 1)"
                @click="removeImage(index)"
              >
                {{ uiText('Delete') }}
              </button>
            </li>
          </ol>
          <div v-if="uploading" role="status">
            {{ uiText('Uploading {0} of {1} images', [uploaded, screenshots.length]) }}
            <progress
              :value="uploaded"
              :max="screenshots.length"
              :aria-label="uiText('Screenshot upload progress')"
            />
          </div>
        </div>
        <div class="card-footer">
          <button
            type="button"
            class="btn primary"
            :disabled="!canParse || parsing"
            :aria-busy="parsing"
            @click="parse"
          >
            {{
              uploading ? uiText('Uploading…') : parsing ? uiText('Parsing…') : uiText('Parse job')
            }}
          </button>
        </div>
      </div>
      <form v-else class="form-stack" @submit.prevent="save">
        <p class="helper-note" role="status">
          {{
            parsed?.sourceKind === 'screenshot'
              ? uiText(
                  'Text was transcribed and fields extracted from the screenshots. There may be omissions or recognition errors. Compare with the screenshots before saving.',
                )
              : parsed?.draft.parser === 'model'
                ? uiText(
                    'AI extracted these fields from the source and discarded unsupported content. Review before saving.',
                  )
                : uiText(
                    'AI is unavailable. The content below was split using headings such as Responsibilities and Requirements. Review before saving.',
                  )
          }}
        </p>
        <div v-if="parsed?.sourceKind === 'screenshot'" class="screenshot-list">
          <img
            v-for="(preview, index) in restoredPreviews.length
              ? restoredPreviews
              : screenshots.map((image) => image.preview)"
            :key="preview"
            :src="preview"
            :alt="uiText('Review screenshots') + ' ' + (index + 1)"
          />
        </div>
        <p v-if="parsed?.securityNotices?.length" class="security-note" role="alert">
          {{
            uiText(
              'The source contains possible instructions to AI ({0}). They are treated only as job text and will not be executed. Review the fields before saving.',
              [parsed.securityNotices.join('、')],
            )
          }}
        </p>
        <div class="import-grid">
          <label class="field"
            >{{ uiText('Job title') }}<input v-model="form.title" required maxlength="100"
          /></label>
          <label class="field"
            >{{ uiText('Company')
            }}<input
              v-model="form.company"
              maxlength="100"
              :placeholder="uiText('Not specified in source')"
          /></label>
          <label class="field"
            >{{ uiText('City')
            }}<input
              v-model="form.locations"
              maxlength="100"
              :placeholder="uiText('Not specified in source')"
          /></label>
          <label class="field"
            >{{ uiText('Department')
            }}<input
              v-model="form.department"
              maxlength="100"
              :placeholder="uiText('Not specified in source')"
          /></label>
          <label class="field"
            >{{ uiText('Recruitment type')
            }}<select v-model="form.nature">
              <option value="campus">{{ uiText('Graduate') }}</option>
              <option value="intern">{{ uiText('Internship') }}</option>
              <option value="social">{{ uiText('Experienced hire') }}</option>
            </select></label
          >
        </div>
        <label class="field"
          >{{ uiText('Responsibilities')
          }}<textarea v-model="form.description" rows="6" maxlength="30000" />
        </label>
        <label class="field"
          >{{ uiText('Requirements')
          }}<textarea v-model="form.requirement" rows="6" maxlength="30000" />
        </label>
        <p v-if="form.skills.length" class="subtle">
          {{ uiText('Skills mentioned in the source:')
          }}<span v-for="skill in form.skills" :key="skill" class="tag">{{ skill }}</span>
        </p>
        <p v-if="form.url" class="subtle">{{ uiText('Source URL: {0}', [form.url]) }}</p>
        <p v-if="!form.description.trim() && !form.requirement.trim()" class="error-banner">
          {{ uiText('Keep at least responsibilities or requirements as evidence for matching.') }}
        </p>
        <div class="card-footer">
          <button type="button" class="btn" :disabled="saving" @click="restart">
            {{ uiText('Import again') }}
          </button>
          <button class="btn primary" :disabled="!canSave" :aria-busy="saving">
            {{ saving ? uiText('Saving…') : uiText('Save and view match') }}
          </button>
        </div>
      </form>
      <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    </template>
    <div v-if="imported.data.value?.length" class="imported-list">
      <h3>{{ uiText('My imported jobs') }}</h3>
      <ul>
        <li v-for="job in imported.data.value" :key="job.id">
          <RouterLink :to="'/jobs/' + job.id">{{ job.title }}</RouterLink>
          <span class="subtle"
            >{{ job.company || uiText('Company not specified') }} ·
            {{ job.locations || uiText('City not specified') }}</span
          >
        </li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.job-import {
  overflow-wrap: anywhere;
}
.mode-switch {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.screenshot-input input {
  max-width: 100%;
  min-width: 0;
}
.screenshot-list {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 0;
  list-style: none;
}
.screenshot-list li {
  display: grid;
  gap: 6px;
  width: min(100%, 180px);
}
.screenshot-list img {
  width: min(100%, 180px);
  max-height: 200px;
  object-fit: contain;
  border: 1px solid var(--border);
}
.security-note {
  margin: 0;
  padding: 8px 12px;
  border-left: 3px solid var(--warning);
  color: var(--warning);
}
.import-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.imported-list ul {
  margin: 0;
  padding-left: 18px;
  display: grid;
  gap: 6px;
}
.imported-list li {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
}
.tag + .tag {
  margin-left: 6px;
}
</style>
