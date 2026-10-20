<script setup lang="ts">
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
    error.value = '最多选择同一岗位的 3 张连续截图。'
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
const error = ref('')
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
          error.value = '草稿已恢复，截图预览暂不可用，请稍后刷新重试。'
        }
      }
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '草稿恢复失败'
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
      <h2 id="job-import-title">导入岗位</h2>
      <button type="button" class="btn" :aria-expanded="open" @click="open = !open">
        {{ open ? '收起' : '粘贴 JD 或网址 / 截图' }}
      </button>
    </div>
    <p class="subtle">
      在其他招聘站看到的岗位，搜索里没有？粘贴岗位描述、填写网址或上传截图。核对无误后保存，就能像搜索结果一样生成匹配报告、加入看板。导入的岗位只有你自己可见。
    </p>
    <template v-if="open">
      <div v-if="!form" class="form-stack">
        <fieldset class="mode-switch" :disabled="parsing" aria-label="导入方式">
          <label><input v-model="mode" type="radio" value="text" /> 粘贴文本</label>
          <label><input v-model="mode" type="radio" value="url" /> 岗位网址</label>
          <label><input v-model="mode" type="radio" value="screenshot" /> 截图</label>
        </fieldset>
        <label v-if="mode === 'text'" class="field"
          >岗位描述<textarea
            v-model="text"
            rows="8"
            maxlength="20000"
            placeholder="从招聘页复制岗位名称、职责、要求等内容（至少 30 字）"
          />
        </label>
        <template v-else-if="mode === 'url'">
          <label class="field"
            >岗位网址<input
              v-model="url"
              type="url"
              maxlength="2000"
              placeholder="https://jobs.example.com/position/123"
          /></label>
          <p class="subtle">
            只能读取公开网页。需要登录或依靠浏览器脚本渲染的招聘站读不到正文，这种情况请改用粘贴文本。
          </p>
        </template>
        <div
          v-else
          class="form-stack screenshot-input"
          tabindex="0"
          aria-label="选择或粘贴岗位截图"
        >
          <p class="subtle">
            选择或在此粘贴同一岗位的连续截图，按阅读顺序添加。支持 PNG / JPG / WebP，单张 ≤5MB，最多
            3 张。仅本人可读。
          </p>
          <input
            type="file"
            accept="image/png,image/jpeg,image/webp"
            multiple
            :disabled="parsing"
            aria-label="选择岗位截图"
            @change="chooseFiles"
          />
          <p v-if="!screenshots.length" class="subtle">
            尚未选择截图，请确保岗位名称、职责与要求清晰可见。
          </p>
          <ol v-else class="screenshot-list">
            <li v-for="(image, index) in screenshots" :key="image.preview">
              <img :src="image.preview" :alt="'截图 ' + (index + 1) + '：' + image.file.name" />
              <span>{{ index + 1 }}. {{ image.file.name }}</span>
              <button
                type="button"
                class="btn"
                :disabled="parsing"
                :aria-label="'删除截图 ' + (index + 1)"
                @click="removeImage(index)"
              >
                删除
              </button>
            </li>
          </ol>
          <div v-if="uploading" role="status">
            正在上传 {{ uploaded }} / {{ screenshots.length }} 张
            <progress :value="uploaded" :max="screenshots.length" aria-label="截图上传进度" />
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
            {{ uploading ? '正在上传…' : parsing ? '正在解析…' : '解析岗位' }}
          </button>
        </div>
      </div>
      <form v-else class="form-stack" @submit.prevent="save">
        <p class="helper-note" role="status">
          {{
            parsed?.sourceKind === 'screenshot'
              ? '已从截图逐行转写并摘录字段。转写可能有遗漏或识别错误，请对照截图核对后保存。'
              : parsed?.draft.parser === 'model'
                ? 'AI 已从原文摘录下列字段，原文中找不到的内容都已丢弃。请核对后保存。'
                : 'AI 暂不可用，下列内容是按「职责 / 要求」等标题拆分的原文。请核对后保存。'
          }}
        </p>
        <div v-if="parsed?.sourceKind === 'screenshot'" class="screenshot-list">
          <img
            v-for="(preview, index) in restoredPreviews.length
              ? restoredPreviews
              : screenshots.map((image) => image.preview)"
            :key="preview"
            :src="preview"
            :alt="'核对截图 ' + (index + 1)"
          />
        </div>
        <p v-if="parsed?.securityNotices?.length" class="security-note" role="alert">
          原文里有疑似写给 AI 的指令（{{
            parsed.securityNotices.join('、')
          }}）。系统只把它当作岗位文本，不会执行；请核对字段后再保存。
        </p>
        <div class="import-grid">
          <label class="field"
            >岗位名称<input v-model="form.title" required maxlength="100"
          /></label>
          <label class="field"
            >公司<input v-model="form.company" maxlength="100" placeholder="原文未写明"
          /></label>
          <label class="field"
            >城市<input v-model="form.locations" maxlength="100" placeholder="原文未写明"
          /></label>
          <label class="field"
            >部门<input v-model="form.department" maxlength="100" placeholder="原文未写明"
          /></label>
          <label class="field"
            >招聘类型<select v-model="form.nature">
              <option value="campus">校招</option>
              <option value="intern">实习</option>
              <option value="social">社招</option>
            </select></label
          >
        </div>
        <label class="field"
          >岗位职责<textarea v-model="form.description" rows="6" maxlength="30000" />
        </label>
        <label class="field"
          >任职要求<textarea v-model="form.requirement" rows="6" maxlength="30000" />
        </label>
        <p v-if="form.skills.length" class="subtle">
          原文提到的技能：<span v-for="skill in form.skills" :key="skill" class="tag">{{
            skill
          }}</span>
        </p>
        <p v-if="form.url" class="subtle">来源网址：{{ form.url }}</p>
        <p v-if="!form.description.trim() && !form.requirement.trim()" class="error-banner">
          职责和要求至少保留一项，匹配分析需要依据。
        </p>
        <div class="card-footer">
          <button type="button" class="btn" :disabled="saving" @click="restart">重新导入</button>
          <button class="btn primary" :disabled="!canSave" :aria-busy="saving">
            {{ saving ? '正在保存…' : '保存并查看匹配' }}
          </button>
        </div>
      </form>
      <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    </template>
    <div v-if="imported.data.value?.length" class="imported-list">
      <h3>我导入的岗位</h3>
      <ul>
        <li v-for="job in imported.data.value" :key="job.id">
          <RouterLink :to="'/jobs/' + job.id">{{ job.title }}</RouterLink>
          <span class="subtle"
            >{{ job.company || '公司未写明' }} · {{ job.locations || '城市未写明' }}</span
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
