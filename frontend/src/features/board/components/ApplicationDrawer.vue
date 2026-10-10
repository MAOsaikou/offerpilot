<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { getApplication } from '@/shared/api/applications'
import { getJobMatch, exportJobEvalMarkdown } from '@/shared/api/jobs'
import { exportResumePdf } from '@/shared/api/resumes'
import { exportInterviewRecap } from '@/shared/api/interviews'
import { useBoard } from '@/shared/composables/useBoard'
import {
  applicationStages,
  type ApplicationEvent,
  type ApplicationStatus,
} from '@/shared/types/board'
import type { JobEvaluation } from '@/shared/types/jobs'

const props = defineProps<{ id: string }>()
const emit = defineEmits<{ close: [] }>()
const board = useBoard()
const dialog = ref<HTMLDialogElement>()
const opener = document.activeElement as HTMLElement | null
const query = useQuery({
  queryKey: computed(() => ['applications', 'detail', props.id]),
  queryFn: ({ signal }) => getApplication(props.id, signal),
  retry: false,
})
const app = computed(() => query.data.value?.application)
const status = ref<ApplicationStatus>('TODO')
const note = ref('')
const nextAction = ref('')
const nextActionAt = ref('')
const dirty = ref(false)
const actionBusy = ref(false)
const actionError = ref('')
const evaluation = ref<JobEvaluation>()
let active = true
watch(
  app,
  (value) => {
    if (!value || dirty.value) return
    status.value = value.status
    note.value = value.note
    nextAction.value = value.nextAction
    nextActionAt.value = value.nextActionAt ?? ''
  },
  { immediate: true },
)
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => {
  active = false
  dialog.value?.close()
  opener?.focus()
})
async function save() {
  const saved = await board.update(props.id, {
    status: status.value,
    note: note.value,
    nextAction: nextAction.value,
    nextActionAt: nextActionAt.value,
  })
  if (saved && active) {
    dirty.value = false
    await query.refetch()
  }
}
async function action(work: () => Promise<void>) {
  if (actionBusy.value) return
  actionBusy.value = true
  actionError.value = ''
  try {
    await work()
  } catch (error) {
    if (active) actionError.value = (error as Error).message
  } finally {
    if (active) actionBusy.value = false
  }
}
async function openReport() {
  const match = query.data.value?.latestMatch
  if (!match) return
  await action(async () => {
    const report = await getJobMatch(match.id)
    if (active) evaluation.value = report.evaluation
  })
}
const rewriteStates: Record<string, string> = {
  RUNNING: '生成中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}
const interviewStates: Record<string, string> = { IN_PROGRESS: '练习中', COMPLETED: '已完成' }

function eventText(event: ApplicationEvent) {
  if (event.kind === 'STATUS_CHANGED')
    return `${applicationStages[event.fromStatus!]} → ${applicationStages[event.toStatus!]}`
  if (event.kind === 'CREATED') return `${event.detail} · ${applicationStages[event.toStatus!]}`
  if (event.kind === 'NOTE_EDITED') return `更新备注：${event.detail || '已清空'}`
  if (event.kind === 'NEXT_ACTION_EDITED') return `下一步：${event.detail || '已清空'}`
  return event.detail
}
</script>

<template>
  <Teleport to="body">
    <dialog
      ref="dialog"
      class="application-drawer"
      aria-labelledby="application-detail-title"
      @cancel.prevent="emit('close')"
    >
      <header class="drawer-heading">
        <h2 id="application-detail-title">机会工作区</h2>
        <button class="btn" aria-label="关闭机会详情" @click="emit('close')">关闭</button>
      </header>
      <p v-if="query.isPending.value" role="status">正在加载机会详情…</p>
      <div v-else-if="query.isError.value" class="error-banner" role="alert">
        {{ query.error.value?.message
        }}<button class="btn" @click="query.refetch()">重试加载详情</button>
      </div>
      <div v-else-if="query.data.value && app" class="form-stack">
        <section>
          <p class="subtle">{{ app.job.company }} · {{ app.job.locations || '地点未提供' }}</p>
          <h3>{{ app.job.title }}</h3>
          <div class="artifact-actions">
            <RouterLink :to="'/jobs/' + app.jobId" class="btn">查看岗位 / 去匹配</RouterLink>
            <RouterLink
              :to="{
                path: '/chat',
                query: {
                  applicationId: app.id,
                  mode: 'JOB',
                  prompt: '查看这条求职记录，并帮我考虑下一步行动',
                },
              }"
              class="btn"
              >在聊天中讨论跟进</RouterLink
            >
          </div>
        </section>
        <form
          class="panel form-stack"
          :aria-busy="board.busy.value"
          @input="dirty = true"
          @change="dirty = true"
          @submit.prevent="save"
        >
          <label class="field"
            >当前阶段<select v-model="status" :disabled="board.busy.value">
              <option v-for="(label, value) in applicationStages" :key="value" :value="value">
                {{ label }}
              </option>
            </select></label
          >
          <label class="field"
            >下一步行动<textarea
              v-model="nextAction"
              maxlength="1000"
              rows="2"
              :disabled="board.busy.value"
              placeholder="例如：核对改写后的简历，准备项目 STAR 复盘"
            />
          </label>
          <label class="field"
            >行动日期<input
              v-model="nextActionAt"
              type="date"
              min="0001-01-01"
              max="9999-12-31"
              :disabled="board.busy.value"
          /></label>
          <label class="field"
            >跟进备注<textarea
              v-model="note"
              maxlength="2000"
              rows="3"
              :disabled="board.busy.value"
            />
          </label>
          <p v-if="board.error.value" class="error-banner" role="alert">
            {{ board.error.value }}。草稿已保留，可重试。
          </p>
          <button class="btn primary" :disabled="!dirty || board.busy.value">
            {{ board.busy.value ? '正在保存…' : '保存跟进' }}
          </button>
          <p v-if="dirty" class="subtle">有未保存的修改。</p>
        </form>
        <section class="panel form-stack" aria-label="岗位匹配报告">
          <h3>岗位匹配 · A–F 评估</h3>
          <template v-if="query.data.value.latestMatch">
            <p>
              最近评估：{{ query.data.value.latestMatch.grade }} ·
              {{ query.data.value.latestMatch.createdAt.slice(0, 10) }}
            </p>
            <ul v-if="query.data.value.latestMatch.topGaps.length">
              <li v-for="gap in query.data.value.latestMatch.topGaps" :key="gap">{{ gap }}</li>
            </ul>
            <p v-else class="subtle">报告未列出缺口，投递前仍需核对原文。</p>
            <div class="artifact-actions">
              <button class="btn" :disabled="actionBusy" @click="openReport">打开 A–F 报告</button
              ><button
                class="btn"
                :disabled="actionBusy"
                @click="action(() => exportJobEvalMarkdown(query.data.value!.latestMatch!.id))"
              >
                下载评估.md
              </button>
            </div>
            <div v-if="evaluation" class="form-stack">
              <p>{{ evaluation.summary }}</p>
              <dl class="evaluation-dimensions">
                <template v-for="dimension in evaluation.dimensions" :key="dimension.code"
                  ><dt>{{ dimension.code }} · {{ dimension.name }} · {{ dimension.score }}/100</dt>
                  <dd>{{ dimension.reason }}</dd></template
                >
              </dl>
              <p class="subtle">{{ evaluation.note }}</p>
            </div>
          </template>
          <p v-else class="subtle">
            还没有匹配报告 →
            <RouterLink :to="'/jobs/' + app.jobId">去匹配</RouterLink>，先看要求覆盖与证据缺口。
          </p>
        </section>
        <section class="panel form-stack">
          <h3>定向改写与简历 PDF</h3>
          <article v-for="resume in query.data.value.resumeOptimizations" :key="resume.id">
            <p>
              {{ resume.createdAt.slice(0, 10) }} ·
              {{ rewriteStates[resume.status] || '状态待更新' }}
            </p>
            <div class="artifact-actions">
              <RouterLink
                :to="{
                  path: '/resumes/' + resume.resumeId,
                  query: {
                    tab: 'optimize',
                    optimizationReportId: resume.id,
                    resumeVersionId: resume.resumeVersionId,
                    jobId: app.jobId,
                  },
                }"
                class="btn"
                >{{ resume.status === 'SUCCEEDED' ? '打开改写建议' : '查看改写记录' }}</RouterLink
              ><button
                v-if="resume.pdfAvailable"
                class="btn"
                :disabled="actionBusy"
                @click="action(() => exportResumePdf(resume.resumeId))"
              >
                下载当前简历 PDF
              </button>
            </div>
          </article>
          <p v-if="query.data.value.resumeOptimizations.length" class="subtle">
            PDF 导出当前保存版本；改写建议需核对并采纳后才会进入保存版本。
          </p>
          <p v-if="!query.data.value.resumeOptimizations.length" class="subtle">
            尚无可确认属于此岗位的改写版本 →
            <RouterLink :to="'/jobs/' + app.jobId">按此岗位改写简历</RouterLink>，核对并采纳后导出
            PDF。已有改写记录仍可在简历页查看。
          </p>
        </section>
        <section class="panel form-stack">
          <h3>面试练习与复盘</h3>
          <RouterLink
            class="btn"
            :to="{ path: '/interviews', query: { jobId: app.jobId, role: app.job.title } }"
            >针对该岗位模拟面试</RouterLink
          >
          <article v-for="interview in query.data.value.interviews" :key="interview.id">
            <p>
              {{ interview.createdAt.slice(0, 10) }} ·
              {{ interviewStates[interview.status] || '状态待更新' }} ·
              {{ interview.overallScore ?? '尚未评分' }}
            </p>
            <div class="artifact-actions">
              <RouterLink :to="'/interviews/' + interview.id" class="btn">打开面试室</RouterLink
              ><button
                v-if="interview.recapExportUrl"
                class="btn"
                :disabled="actionBusy"
                @click="action(() => exportInterviewRecap(interview.id))"
              >
                下载复盘.md
              </button>
            </div>
          </article>
          <p v-if="!query.data.value.interviews.length" class="subtle">
            尚无可确认属于此岗位的面试 →
            <RouterLink
              :to="{ path: '/interviews', query: { jobId: app.jobId, role: app.job.title } }"
              >去练习面试</RouterLink
            >，用真实项目回答并复盘。
          </p>
          <h4>STAR 故事</h4>
          <p v-for="story in query.data.value.starStories" :key="story.id">{{ story.title }}</p>
          <p v-if="!query.data.value.starStories.length" class="subtle">
            此岗位尚无关联的 STAR 故事。面试复盘中的真实回答可确认入库。
          </p>
        </section>
        <p class="helper-note">{{ query.data.value.linkageNote }}</p>
        <p v-if="actionBusy" role="status">正在读取材料…</p>
        <p v-if="actionError" class="error-banner" role="alert">{{ actionError }}。请重试。</p>
        <section class="panel form-stack">
          <h3>跟进时间线</h3>
          <ol v-if="query.data.value.timeline.length" class="timeline">
            <li v-for="event in query.data.value.timeline" :key="event.id">
              <time :datetime="event.createdAt">{{
                new Date(event.createdAt).toLocaleString('zh-CN')
              }}</time>
              <p>{{ eventText(event) }}</p>
              <small>{{
                event.actor === 'AGENT'
                  ? '聊天审批'
                  : event.actor === 'SYSTEM'
                    ? '系统记录'
                    : '手动跟进'
              }}</small>
            </li>
          </ol>
          <p v-else class="subtle">暂时没有事件。保存阶段或行动后，进度会记录在这里。</p>
        </section>
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.application-drawer {
  position: fixed;
  inset: 0 0 0 auto;
  margin: 0;
  width: min(640px, 100vw);
  max-width: 100vw;
  height: 100dvh;
  max-height: 100dvh;
  overflow: auto;
  padding: 24px;
  border: 0;
  border-left: 1px solid var(--border);
  background: var(--background);
  color: var(--foreground);
  overflow-wrap: anywhere;
}
.application-drawer::backdrop {
  background: rgb(0 0 0 / 40%);
}
.drawer-heading,
.artifact-actions {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.drawer-heading {
  align-items: center;
  margin-bottom: 24px;
}
.artifact-actions {
  justify-content: flex-start;
}
.timeline {
  padding-left: 20px;
}
.timeline li {
  padding: 0 0 16px 8px;
  border-left: 2px solid var(--border);
}
.timeline time,
.timeline small,
.evaluation-dimensions dd {
  color: var(--muted-foreground);
}
.evaluation-dimensions dt {
  font-weight: 600;
}
.evaluation-dimensions dd {
  margin: 4px 0 16px;
}
@media (max-width: 640px) {
  .application-drawer {
    padding: 16px;
  }
  .application-drawer .panel {
    padding: 16px;
  }
  .artifact-actions .btn {
    flex: 1 1 auto;
  }
}
</style>
