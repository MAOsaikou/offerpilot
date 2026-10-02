<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  applyPatches,
  cancelOptimization,
  getOptimization,
  getPatchBatch,
  listOptimizations,
  optimizeResume,
} from '@/shared/api/optimizations'
import { getResumeVersion } from '@/shared/api/resumes'
import { getJob } from '@/shared/api/jobs'
import type { OptimizationReport, PatchDecision } from '@/shared/types/optimization'
import type { ResumeVersion } from '@/shared/types/resume'

const props = defineProps<{
  resumeId: string
  versionId: string
  targetVersionId?: string
  jobId?: string
  dirty: boolean
  saveFirst: () => Promise<boolean>
}>()
const emit = defineEmits<{ saved: [version: ResumeVersion]; saving: [value: boolean] }>()
const cache = useQueryClient()
const jobDescription = ref(''),
  reportJd = ref(''),
  targetVersion = ref(props.targetVersionId || '')
const sourceVersion = computed(() => targetVersion.value || props.versionId)
const report = ref<OptimizationReport>()
const appliedVersionId = ref<string | null>(null)
const error = ref(''),
  message = ref(''),
  running = ref(false),
  selecting = ref(false),
  saving = ref(false),
  runningId = ref('')
const history = useQuery({
  queryKey: ['optimizations', props.resumeId],
  queryFn: () => listOptimizations(props.resumeId),
  retry: false,
  refetchInterval: (q) => (q.state.data?.some((t) => t.status === 'RUNNING') ? 2000 : false),
})
const job = useQuery({
  queryKey: computed(() => ['job', props.jobId]),
  queryFn: ({ signal }) => getJob(props.jobId!, signal),
  enabled: computed(() => !!props.jobId),
  retry: false,
})
watch(
  () => job.data.value,
  (value) => {
    if (value && !jobDescription.value.trim() && (value.description || value.requirement))
      jobDescription.value =
        `岗位：${value.title}\n职责：\n${value.description}\n要求：\n${value.requirement}`.slice(
          0,
          20000,
        )
  },
  { immediate: true },
)
watch(
  () => props.targetVersionId,
  (value) => {
    targetVersion.value = value || ''
  },
)
const existing = computed(() => history.data.value?.find((t) => t.status === 'RUNNING'))
const occupied = computed(
  () => running.value || selecting.value || saving.value || !!existing.value,
)
const canApply = computed(
  () =>
    !!report.value &&
    report.value.resumeVersionId === props.versionId &&
    !props.dirty &&
    !appliedVersionId.value,
)
const labels = { RUNNING: '生成中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' }
let controller: AbortController | undefined,
  alive = true,
  epoch = 0
const refresh = () => cache.invalidateQueries({ queryKey: ['optimizations', props.resumeId] })
async function reveal(value: OptimizationReport, jd: string, request: number) {
  let applied: string | null = null
  if (value.batchId)
    applied = (await getPatchBatch(props.resumeId, value.resumeVersionId, value.batchId))
      .appliedVersionId
  if (!alive || request !== epoch) return
  report.value = value
  reportJd.value = jd
  appliedVersionId.value = applied
}
async function show(id: string) {
  if (running.value || saving.value) return
  const request = ++epoch
  selecting.value = true
  error.value = ''
  message.value = ''
  try {
    const task = await getOptimization(id)
    if (!task.report) throw new Error('建议尚未完成，请刷新改写记录。')
    await reveal(task.report, task.jobDescription || '', request)
  } catch (cause) {
    if (alive && request === epoch)
      error.value = cause instanceof Error ? cause.message : '建议加载失败'
  } finally {
    if (alive && request === epoch) selecting.value = false
  }
}
async function start(save: boolean) {
  if (occupied.value || !jobDescription.value.trim()) return
  running.value = true
  error.value = ''
  message.value = ''
  runningId.value = ''
  const request = ++epoch
  let requestId = ''
  try {
    if (save) {
      if (!(await props.saveFirst())) return
      targetVersion.value = ''
    }
    await nextTick()
    if (!alive) return
    controller = new AbortController()
    requestId = crypto.randomUUID()
    const jd = jobDescription.value.trim()
    const value = await optimizeResume(
      props.resumeId,
      sourceVersion.value,
      requestId,
      jd,
      controller.signal,
      (id) => {
        runningId.value = id
        void refresh()
      },
    )
    await reveal(value, jd, request)
  } catch (cause) {
    if (alive && request === epoch) {
      error.value =
        cause instanceof DOMException && cause.name === 'AbortError'
          ? '改写连接已停止，请查看改写记录。'
          : cause instanceof Error
            ? cause.message
            : '改写失败'
      if (requestId)
        try {
          const tasks = await listOptimizations(props.resumeId)
          const completed = tasks.find((t) => t.requestId === requestId && t.status === 'SUCCEEDED')
          if (completed) {
            const task = await getOptimization(completed.id)
            if (task.report) {
              await reveal(task.report, task.jobDescription || '', request)
              if (alive) error.value = ''
            }
          }
        } catch {
          /* Preserve the actionable error if recovery is unavailable. */
        }
    }
  } finally {
    if (alive && request === epoch) {
      running.value = false
      runningId.value = ''
      void refresh()
    }
  }
}
async function stop() {
  const id = runningId.value || existing.value?.id
  try {
    if (id) await cancelOptimization(id)
    controller?.abort()
    void refresh()
  } catch (cause) {
    if (alive) error.value = cause instanceof Error ? cause.message : '取消失败，请重试'
  }
}
function saved(version: ResumeVersion) {
  appliedVersionId.value = version.id
  emit('saved', version)
  message.value = `已保存为版本 ${version.versionNo}，原版本保持可查看。`
}
async function apply(decision: PatchDecision) {
  if (
    !canApply.value ||
    occupied.value ||
    !report.value ||
    decision.batchId !== report.value.batchId
  )
    return
  const value = report.value
  saving.value = true
  error.value = ''
  message.value = ''
  emit('saving', true)
  try {
    const result = await applyPatches(props.resumeId, value.resumeVersionId, decision)
    if (alive) saved(result.version)
  } catch (cause) {
    if (alive) {
      error.value = cause instanceof Error ? cause.message : '保存失败，请重试同一组决策。'
      // The response may be lost after commit: restore the batch instead of posting again.
      try {
        const batch = await getPatchBatch(props.resumeId, value.resumeVersionId, decision.batchId)
        if (batch.appliedVersionId) {
          const version = await getResumeVersion(props.resumeId, batch.appliedVersionId)
          if (alive) {
            saved(version)
            error.value = ''
          }
        }
      } catch {
        /* Keep the original error and current selection. */
      }
    }
  } finally {
    emit('saving', false)
    if (alive) saving.value = false
    void cache.invalidateQueries({ queryKey: ['resume', props.resumeId] })
    void cache.invalidateQueries({ queryKey: ['resumes'] })
  }
}
onBeforeUnmount(() => {
  alive = false
  epoch++
  controller?.abort()
  if (runningId.value) void cancelOptimization(runningId.value).catch(() => {})
})
</script>

<template>
  <section class="panel form-stack optimization-panel">
    <header>
      <h2>按岗位定向改写</h2>
      <p class="subtle">只整理已有经历的表达。先生成建议，再逐条选择、编辑和确认，保存为新版本。</p>
    </header>
    <p v-if="job.isPending.value && jobId" role="status">正在读取目标岗位…</p>
    <p v-if="job.error.value" class="error-banner" role="alert">
      {{ job.error.value.message }}。可以粘贴岗位描述，或<button
        class="btn"
        :disabled="occupied"
        @click="job.refetch()"
      >
        重试读取岗位
      </button>
    </p>
    <p v-if="job.data.value && jobId" class="helper-note">
      目标岗位：{{ job.data.value.title }} · {{ job.data.value.company }}。
      <RouterLink :to="'/jobs/' + jobId">查看完整岗位</RouterLink>
    </p>
    <label class="field"
      >岗位职责与要求（JD）
      <textarea
        v-model="jobDescription"
        rows="7"
        maxlength="20000"
        :disabled="occupied"
        placeholder="粘贴目标岗位的职责与要求，建议只保留与能力相关的内容。"
      />
    </label>
    <p class="subtle">
      使用设置中的文字模型；岗位描述与已保存简历内容将发送给该模型，个人信息栏目不发送。
    </p>
    <p v-if="dirty" class="helper-note">
      有未保存的修改。可保存后生成，或仅使用已保存版本；未保存草稿不会被覆盖。
    </p>
    <div v-if="sourceVersion !== versionId" class="helper-note">
      当前选择的是历史保存版本。可生成和查看建议，采纳时需使用当前保存版本。
      <button class="btn" :disabled="occupied" @click="targetVersion = ''">改用当前保存版本</button>
    </div>
    <div class="optimization-actions">
      <button
        v-if="dirty"
        class="btn primary"
        :disabled="occupied || !jobDescription.trim()"
        @click="start(true)"
      >
        保存新版本后生成
      </button>
      <button
        class="btn"
        :class="{ primary: !dirty }"
        :disabled="occupied || !jobDescription.trim()"
        @click="start(false)"
      >
        {{ running || existing ? '正在生成…' : dirty ? '仅使用已保存版本生成' : '生成改写建议' }}
      </button>
      <button v-if="running || existing" class="btn" @click="stop">取消生成</button>
    </div>
    <p v-if="running || existing" role="status">
      正在核对简历与岗位内容，通常需要几十秒。离开面板会停止当前连接。
    </p>
    <p v-if="selecting" role="status">正在读取已保存建议…</p>
    <p v-if="saving" role="status">正在保存已选择的改写…</p>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <p v-if="message" class="helper-note" role="status">{{ message }}</p>
    <template v-if="report">
      <p>{{ report.summary }}</p>
      <p class="subtle">
        保存版本 {{ report.versionNo }} · {{ report.model }} ·
        {{ new Date(report.createdAt).toLocaleString('zh-CN') }}
      </p>
      <details v-if="reportJd">
        <summary>本次使用的岗位描述</summary>
        <pre class="jd-snapshot">{{ reportJd }}</pre>
      </details>
      <p v-if="!appliedVersionId && !canApply && report.patches.length" class="helper-note">
        当前简历版本不同或有未保存修改，此批建议仅供查看。请保存当前内容并重新生成，避免覆盖后续编辑。
      </p>
      <p v-if="!report.patches.length" class="helper-note" role="status">
        没有需要采纳的改写建议。未创建简历新版本。
      </p>
      <slot
        v-else
        :key="report.reportId"
        name="optimization-card"
        :report="report"
        :interactive="canApply"
        :disabled="occupied"
        :applied-version-id="appliedVersionId"
        :apply="apply"
      />
    </template>
    <details :open="!report" class="optimization-history">
      <summary>改写记录 · {{ history.data.value?.length || 0 }} 次</summary>
      <button
        class="btn"
        :disabled="history.isFetching.value || occupied"
        @click="history.refetch()"
      >
        刷新改写记录
      </button>
      <p v-if="history.isPending.value" role="status">正在加载改写记录…</p>
      <p v-else-if="history.error.value" class="error-banner" role="alert">
        {{ history.error.value.message }}
      </p>
      <p v-else-if="!history.data.value?.length" class="subtle">
        还没有改写记录，输入岗位描述后开始。
      </p>
      <div v-for="task in history.data.value" :key="task.id" class="optimization-record">
        <div>
          <strong>版本 {{ task.versionNo }} · {{ labels[task.status] }}</strong>
          <p class="subtle">{{ new Date(task.createdAt).toLocaleString('zh-CN') }}</p>
          <p v-if="task.error">{{ task.error }}</p>
        </div>
        <button
          v-if="task.status === 'SUCCEEDED'"
          class="btn"
          :disabled="occupied"
          @click="show(task.id)"
        >
          查看建议
        </button>
      </div>
    </details>
  </section>
</template>
<style scoped>
.optimization-panel {
  min-width: 0;
  overflow-wrap: anywhere;
}
.optimization-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.optimization-history {
  border-top: 1px solid var(--border);
  padding-top: 16px;
}
.optimization-history summary {
  cursor: pointer;
  margin-bottom: 12px;
}
.optimization-record {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}
.jd-snapshot {
  white-space: pre-wrap;
  font: inherit;
  margin-top: 12px;
}
@media (max-width: 480px) {
  .optimization-record {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
