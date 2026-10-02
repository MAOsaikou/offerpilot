<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  getResume,
  listResumeVersions,
  renameResume,
  retryResume,
  saveResume,
} from '@/shared/api/resumes'
import {
  resumeStatus,
  resumeVersionSource,
  type ResumeStructure,
  type ResumeVersion,
} from '@/shared/types/resume'
import PageHeading from '@/shared/components/PageHeading.vue'
import ResumeSectionEditor from './ResumeSectionEditor.vue'
import ResumeDiagnosisPanel from './ResumeDiagnosisPanel.vue'
import { downloadText } from '@/shared/utils/download'

const props = defineProps<{ id: string }>()
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
const error = ref('')
const message = ref('')
const tab = ref('内容')
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
function confirmLeave() {
  return !dirty.value || window.confirm('有尚未保存的简历修改，确定离开吗？')
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
    error.value = cause instanceof Error ? cause.message : '操作失败，请重试。'
  } finally {
    busy.value = false
  }
}
async function save() {
  let success = false
  await run(async () => {
    if (!draft.value || !baseVersion.value) return
    if (!title.value.trim()) throw new Error('请填写简历名称。')
    if (title.value.trim() !== savedTitle.value) {
      await renameResume(props.id, title.value.trim())
      savedTitle.value = title.value = title.value.trim()
    }
    const updated = await saveResume(props.id, baseVersion.value, draft.value)
    original.value = JSON.stringify(draft.value)
    cache.setQueryData(['resume', props.id], updated)
    populate()
    message.value = '已保存为新版本，可在版本记录中查看。'
    void cache.invalidateQueries({ queryKey: ['resumes'] })
    success = true
  })
  return success
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
  tab.value = '版本'
  selected.value = undefined
  historyLoading.value = true
  error.value = ''
  try {
    history.value = await listResumeVersions(props.id)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '版本加载失败'
  } finally {
    historyLoading.value = false
  }
}
function restore(version: ResumeVersion) {
  if (dirty.value && !window.confirm('用此版本替换尚未保存的编辑内容吗？')) return
  draft.value = JSON.parse(JSON.stringify(version.structured)) as ResumeStructure
  tab.value = '内容'
  message.value = '已载入历史版本，点击保存将创建新版本。'
}
function lines(value: string) {
  return value.split('\n')
}
const sections = [
  {
    key: 'education' as const,
    title: '教育经历',
    fields: [
      { key: 'school', label: '学校' },
      { key: 'degree', label: '学历' },
      { key: 'major', label: '专业' },
      { key: 'start', label: '开始时间' },
      { key: 'end', label: '结束时间' },
      { key: 'gpa', label: 'GPA / 成绩' },
    ],
  },
  {
    key: 'experience' as const,
    title: '实习与工作',
    fields: [
      { key: 'org', label: '公司 / 组织' },
      { key: 'title', label: '职位' },
      { key: 'start', label: '开始时间' },
      { key: 'end', label: '结束时间' },
      { key: 'bullets', label: '职责与成果', array: true },
    ],
  },
  {
    key: 'projects' as const,
    title: '项目经历',
    fields: [
      { key: 'name', label: '项目名称' },
      { key: 'role', label: '担任角色' },
      { key: 'start', label: '开始时间' },
      { key: 'end', label: '结束时间' },
      { key: 'tech', label: '技术栈', array: true },
      { key: 'bullets', label: '行动与成果', array: true },
    ],
  },
  {
    key: 'skills' as const,
    title: '专业技能',
    fields: [
      { key: 'category', label: '技能类别' },
      { key: 'items', label: '技能', array: true },
    ],
  },
]
const basics = [
  { key: 'name' as const, label: '姓名' },
  { key: 'phone' as const, label: '电话' },
  { key: 'email' as const, label: '邮箱' },
  { key: 'city' as const, label: '城市' },
]
</script>
<template>
  <section class="page-stack resume-editor">
    <PageHeading
      :title="resume?.title ?? '简历详情'"
      description="核对每一段经历，让真实的优势清晰可见。"
    >
      <RouterLink class="btn" to="/resumes">返回简历中心</RouterLink>
      <button class="btn" :disabled="busy || query.isFetching.value" @click="reload">
        重新加载
      </button>
    </PageHeading>
    <p v-if="query.isPending.value" role="status">正在加载简历…</p>
    <p v-if="query.error.value" class="error-banner" role="alert">
      {{ query.error.value.message }}。可点击重新加载。
    </p>
    <p v-if="error && (!draft || tab !== '内容')" class="error-banner" role="alert">{{ error }}</p>
    <template v-if="resume">
      <div class="panel form-stack" aria-live="polite">
        <div class="card-top">
          <span class="tag">{{ resumeStatus[resume.parseStatus] }}</span
          ><span class="subtle">{{ resume.fileName }}</span>
        </div>
        <template v-if="resume.parseStatus === 'QUEUED' || resume.parseStatus === 'PARSING'">
          <h2>
            {{
              resume.parseStatus === 'QUEUED' ? '文件已保存，等待解析' : '正在识别与整理简历内容'
            }}
          </h2>
          <progress :value="resume.parseProgress" max="100" aria-label="简历解析进度" />
          <p>解析会在后台继续，你可以离开页面，稍后返回查看。图片与扫描件可能需要更长时间。</p>
        </template>
        <template v-if="resume.parseStatus === 'FAILED'">
          <p class="error-banner" role="alert">{{ resume.parseError }}</p>
          <div>
            <button class="btn primary" :disabled="busy" @click="retry">
              {{ busy ? '提交中…' : '重新解析' }}
            </button>
          </div>
          <p class="subtle">
            原文件已保存，重试无需重复上传。如果文件损坏，请返回简历中心上传修正后的文件。
          </p>
        </template>
        <p v-if="resume.parseStatus === 'READY'" class="subtle">
          解析结果可能存在遗漏，请对照原文件核对。手动保存后会生成新版本。
        </p>
      </div>
      <template v-if="resume.parseStatus === 'READY' && draft">
        <div class="tabs no-print">
          <button :disabled="busy" :class="{ selected: tab === '内容' }" @click="tab = '内容'">
            内容编辑</button
          ><button :disabled="busy" :class="{ selected: tab === '诊断' }" @click="tab = '诊断'">
            AI 诊断</button
          ><button :disabled="busy" :class="{ selected: tab === '版本' }" @click="showHistory">
            版本记录
          </button>
        </div>
        <form v-if="tab === '内容'" class="panel form-stack" @submit.prevent="save">
          <fieldset :disabled="busy" class="form-stack resume-fields">
            <label class="field">简历名称<input v-model="title" maxlength="100" required /></label>
            <h2>个人信息</h2>
            <div class="resume-basics">
              <label v-for="field in basics" :key="field.key" class="field"
                >{{ field.label }}<input v-model="draft.basics[field.key]" maxlength="5000"
              /></label>
            </div>
            <label class="field"
              >个人链接（每行一项）<textarea
                :value="draft.basics.links.join('\n')"
                rows="2"
                maxlength="5000"
                @input="draft.basics.links = lines(($event.target as HTMLTextAreaElement).value)"
              />
            </label>
            <label class="field"
              >个人简介<textarea v-model="draft.summary" rows="4" maxlength="5000" />
            </label>
            <ResumeSectionEditor
              v-for="section in sections"
              :key="section.key"
              v-model="draft[section.key]"
              :title="section.title"
              :fields="section.fields"
              :disabled="busy"
            />
            <label class="field"
              >证书与奖项（每行一项）<textarea
                :value="draft.certificates.join('\n')"
                rows="3"
                maxlength="5000"
                @input="draft.certificates = lines(($event.target as HTMLTextAreaElement).value)"
              />
            </label>
          </fieldset>
          <div class="card-footer resume-save">
            <div class="form-stack">
              <span v-if="error" class="error-banner" role="alert">{{ error }}</span>
              <span role="status">{{
                dirty ? '有未保存的修改' : message || '已与服务端同步'
              }}</span>
            </div>
            <button class="btn primary" :disabled="busy || !dirty" type="submit">
              {{ busy ? '保存中…' : '保存新版本' }}
            </button>
          </div>
        </form>
        <ResumeDiagnosisPanel
          v-else-if="tab === '诊断'"
          :resume-id="id"
          :version-id="baseVersion"
          :dirty="!!dirty"
          :save-first="save"
        />
        <section v-else class="panel form-stack">
          <p v-if="historyLoading" role="status">正在加载版本…</p>
          <div v-for="version in history" :key="version.id" class="list-row">
            <div>
              <h3>
                版本 {{ version.versionNo }}
                {{ version.id === resume.currentVersionId ? '· 当前' : '' }}
              </h3>
              <small
                >{{ new Date(version.createdAt).toLocaleString('zh-CN') }} ·
                {{ resumeVersionSource[version.source] ?? '其他来源' }}</small
              >
            </div>
            <div class="resume-actions">
              <button class="btn" @click="selected = version">查看</button
              ><button class="btn" :disabled="busy" @click="restore(version)">载入编辑</button>
            </div>
          </div>
          <article v-if="selected" class="form-stack">
            <h2>版本 {{ selected.versionNo }} 的内容</h2>
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
              下载结构化内容
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
.resume-save {
  position: sticky;
  bottom: 12px;
  background: var(--surface, #fff);
  padding: 16px;
  border: 1px solid var(--border, #ddd);
  border-radius: 12px;
  z-index: 2;
}
progress {
  width: 100%;
  accent-color: #427964;
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
