<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { isGenerationCancelled } from '@/shared/api/client'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { analyzeResume, cancelDiagnosis, getDiagnosis, listDiagnoses } from '@/shared/api/diagnoses'
import type { ResumeDiagnosisReport as Report } from '@/shared/types/diagnosis'
import ResumeDiagnosisReport from '@/shared/components/ResumeDiagnosisReport.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
const props = defineProps<{
  resumeId: string
  versionId: string
  dirty: boolean
  saveFirst: () => Promise<boolean>
}>()
const cache = useQueryClient()
const history = useQuery({
  queryKey: ['diagnoses', props.resumeId],
  queryFn: () => listDiagnoses(props.resumeId),
  refetchInterval: (q) => (q.state.data?.some((t) => t.status === 'RUNNING') ? 2000 : false),
})
const report = ref<Report>()
const reportElement = ref<HTMLElement>()
const error = ref('')
const message = ref('')
const running = ref(false)
const selecting = ref(false)
const runningId = ref('')
const existing = computed(() => history.data.value?.find((task) => task.status === 'RUNNING'))
let locallyCancelled = false
let controller: AbortController | undefined
let alive = true
let selection = 0
const labels = { RUNNING: '分析中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' }
const refresh = () => cache.invalidateQueries({ queryKey: ['diagnoses', props.resumeId] })
async function revealReport(value: Report, epoch: number) {
  if (!alive || epoch !== selection) return
  report.value = value
  await nextTick()
  if (!alive || epoch !== selection) return
  reportElement.value?.focus({ preventScroll: true })
  reportElement.value?.scrollIntoView?.({
    block: 'start',
    behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
  })
}
async function show(id: string) {
  const epoch = ++selection
  selecting.value = true
  error.value = ''
  try {
    const task = await getDiagnosis(id)
    if (!task.report) throw new Error('报告尚未完成，请刷新诊断记录后重试。')
    await revealReport(task.report, epoch)
  } catch (cause) {
    if (alive && epoch === selection)
      error.value = cause instanceof Error ? cause.message : '报告加载失败'
  } finally {
    if (alive && epoch === selection) selecting.value = false
  }
}
async function start(save: boolean) {
  if (running.value || existing.value) return
  running.value = true
  locallyCancelled = false
  error.value = ''
  runningId.value = ''
  message.value = ''
  const epoch = ++selection
  let requestId = ''
  try {
    if (save && !(await props.saveFirst())) return
    await nextTick()
    if (!alive) return
    controller = new AbortController()
    requestId = crypto.randomUUID()
    const result = await analyzeResume(
      props.resumeId,
      props.versionId,
      requestId,
      controller.signal,
      (id) => {
        runningId.value = id
        void refresh()
      },
    )
    await revealReport(result, epoch)
  } catch (cause) {
    if (alive) {
      if (
        isGenerationCancelled(cause) ||
        (locallyCancelled && cause instanceof DOMException && cause.name === 'AbortError')
      ) {
        error.value = ''
        message.value = '诊断已取消，可重新发起。'
        return
      }
      error.value =
        cause instanceof DOMException && cause.name === 'AbortError'
          ? '诊断连接已停止，请查看报告记录。'
          : cause instanceof Error
            ? cause.message
            : '诊断失败'
      // A disconnect may happen after the report committed. Recover this exact request before retrying.
      if (requestId)
        try {
          const tasks = await listDiagnoses(props.resumeId)
          const task = tasks.find((t) => t.requestId === requestId && t.status === 'SUCCEEDED')
          if (task) {
            await show(task.id)
            error.value = ''
          }
        } catch {
          /* Keep the original actionable error. */
        }
    }
  } finally {
    if (alive) {
      running.value = false
      runningId.value = ''
      void refresh()
    }
  }
}
async function stop(id = runningId.value || existing.value?.id) {
  if (!id) {
    locallyCancelled = true
    message.value = '诊断已取消，可重新发起。'
    error.value = ''
    controller?.abort()
    return
  }
  try {
    await cancelDiagnosis(id)
    locallyCancelled = true
    message.value = '诊断已取消，可重新发起。'
    error.value = ''
    controller?.abort()
    await refresh()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '取消失败，请重试'
  }
}
onBeforeUnmount(() => {
  alive = false
  selection++
  controller?.abort()
  if (runningId.value) void cancelDiagnosis(runningId.value).catch(() => {})
})
</script>
<template>
  <section class="panel form-stack diagnosis-panel">
    <header class="diagnosis-intro">
      <span class="diagnosis-icon"><AppIcon name="spark" :size="24" /></span>
      <div>
        <h2>AI 简历诊断</h2>
        <p>找到值得保留的亮点，把改进建议变成下一步行动。</p>
        <small>使用设置中的文字模型分析已保存版本，不会自动修改简历。</small>
      </div>
    </header>
    <div v-if="dirty" class="helper-note">
      有未保存的修改。请选择保存后诊断，或仅分析已保存版本。
    </div>
    <div class="diagnosis-actions">
      <button
        v-if="dirty"
        class="btn primary"
        :disabled="running || !!existing"
        @click="start(true)"
      >
        保存新版本后诊断
      </button>
      <button
        class="btn"
        :class="{ primary: !dirty }"
        :disabled="running || !!existing"
        @click="start(false)"
      >
        {{ running || existing ? '正在分析…' : dirty ? '仅诊断已保存版本' : '开始 AI 诊断' }}
      </button>
      <button v-if="running || existing" class="btn" @click="stop()">取消诊断</button>
    </div>
    <p v-if="running || existing" role="status">
      正在分析已保存版本，通常需要几十秒；离开当前分析页面会停止本次连接。
    </p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <p v-if="selecting" role="status">正在加载报告…</p>
    <p v-if="report && report.versionId !== versionId" class="helper-note">
      这是旧版本的诊断。当前编辑版本不同，引用仍指向报告原版本。
    </p>
    <div
      v-if="report"
      ref="reportElement"
      class="report-result"
      tabindex="-1"
      aria-label="简历诊断报告"
    >
      <ResumeDiagnosisReport :report="report" />
    </div>
    <details class="diagnosis-history-group" :open="!report">
      <summary>
        <AppIcon name="history" :size="18" /><span>诊断记录</span
        ><span class="subtle">{{ history.data.value?.length || 0 }} 次</span>
      </summary>
      <div class="history-toolbar">
        <p class="subtle">查看已保存的报告，不会重新调用模型。</p>
        <button class="btn" :disabled="history.isFetching.value" @click="history.refetch()">
          刷新记录
        </button>
      </div>
      <p v-if="history.isPending.value" role="status">正在加载记录…</p>
      <p v-else-if="history.error.value" class="error-banner" role="alert">
        {{ history.error.value.message }}
      </p>
      <p v-else-if="!history.data.value?.length" class="subtle">
        还没有诊断报告，点击上方按钮开始。
      </p>
      <div v-for="task in history.data.value" :key="task.id" class="diagnosis-history">
        <div>
          <strong>版本 {{ task.versionNo }} · {{ labels[task.status] }}</strong>
          <p class="subtle">{{ new Date(task.createdAt).toLocaleString('zh-CN') }}</p>
          <p v-if="task.error">{{ task.error }}</p>
        </div>
        <button
          v-if="task.status === 'SUCCEEDED'"
          class="btn"
          :disabled="selecting || running"
          @click="show(task.id)"
        >
          查看报告
        </button>
      </div>
    </details>
  </section>
</template>
<style scoped>
.diagnosis-panel {
  min-width: 0;
  overflow-wrap: anywhere;
}
.diagnosis-intro {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.diagnosis-icon {
  display: grid;
  place-items: center;
  flex: 0 0 44px;
  height: 44px;
  background: var(--primary-tint);
  color: var(--primary);
  border-radius: var(--radius-control);
}
.diagnosis-intro h2 {
  font-size: 18px;
  font-weight: 600;
}
.diagnosis-intro p {
  margin-top: 4px;
  font-size: 14px;
}
.diagnosis-intro small {
  color: var(--muted-foreground);
  font-size: 12px;
}
.report-result {
  scroll-margin-top: 24px;
}
.diagnosis-history-group {
  border-top: 1px solid var(--border);
  padding-top: 12px;
}
.diagnosis-history-group summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  cursor: pointer;
  font-weight: 550;
}
.diagnosis-history-group summary::after {
  content: '展开';
  margin-left: auto;
  font-size: 12px;
  color: var(--muted-foreground);
}
.diagnosis-history-group[open] summary::after {
  content: '收起';
}
.history-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.diagnosis-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.diagnosis-history {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}
@media (max-width: 480px) {
  .diagnosis-history {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
