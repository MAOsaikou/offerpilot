<script setup lang="ts">
import { checkPrivacyText } from '@/shared/api/privacy-settings'
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Composer from '../components/Composer.vue'
import type { ChatImage } from '@/shared/api/attachments'
import MessageList from '../components/MessageList.vue'
import ConversationHistory from '../components/ConversationHistory.vue'
import ChatWelcome from '../components/ChatWelcome.vue'
import ChatToolbar from '../components/ChatToolbar.vue'
import { useRunReplay } from '@/shared/composables/useRunReplay'
import { useConversationChat } from '../composables/useConversationChat'
import { optimizationContext } from '../optimizationContext'
import { normalizeModelTier, type ModelTier } from '@/shared/types/model-tier'
import type { AgentMode } from '@/shared/types/agent-events'
import type { Conversation } from '@/shared/types/conversation'
import { getPreferences } from '@/shared/api/preferences'
import type { PatchDecision } from '@/shared/types/optimization'
import type { CareerTargetSelection } from '@/shared/types/cards'
const replay = useRunReplay()
const privacyWarning = ref('')
const route = useRoute()
const router = useRouter()
const chat = useConversationChat()
const {
  conversations,
  current,
  viewMessages,
  loading,
  sending,
  busy,
  listLoading,
  error,
  listError,
  needsReload,
  hasMoreConversations,
  hasMoreMessages,
  activeRun,
  remoteRunId,
} = chat
const modes: { value: AgentMode; label: string }[] = [
  { value: 'AUTO', label: '智能助手' },
  { value: 'CAREER_DISCOVERY', label: '职业探索' },
  { value: 'RESUME', label: '简历指导' },
  { value: 'JOB', label: '岗位分析' },
  { value: 'INTERVIEWER', label: '面试练习' },
  { value: 'COACH', label: '成长教练' },
]
const displayedMessages = computed(() =>
  viewMessages.value.map((message) => {
    if (!replay.runId.value || message.role !== 'assistant' || message.runId !== replay.runId.value)
      return message
    const run = replay.run.value
    return {
      ...message,
      text: run?.text ?? '',
      cards: run?.cards ?? [],
      reasoning: run?.reasoning ?? '',
      citations: run?.citations ?? [],
      usage: run?.usage ?? null,
      model: run?.model ?? undefined,
      durationMs: run?.durationMs,
      status: run?.status ?? ('RUNNING' as const),
      streaming: false,
    }
  }),
)
watch(
  () => route.params.conversationId,
  () => replay.close(),
)
watch(sending, (value) => {
  if (value) replay.close()
})
async function openReplay(id: string) {
  panelOpen.value = true
  await replay.open(id)
}
const mode = ref<AgentMode>('AUTO')
watch(
  [() => route.query.diagnosisReportId, () => route.query.optimizationReportId],
  (ids) => {
    if (
      ids.some((id) => typeof id === 'string') &&
      !modes.some((m) => m.value === route.query.mode)
    )
      mode.value = 'RESUME'
  },
  { immediate: true },
)
const tier = ref<ModelTier>('FLASH')
let tierTouched = false
watch(
  () => route.query.modelTier ?? route.query.tier,
  (value) => {
    tier.value = normalizeModelTier(value)
    if (value) tierTouched = true
  },
  { immediate: true },
)
watch(tier, (_, previous) => {
  if (previous) tierTouched = true
})
// AC-03: without an explicit tier in the link, start from the account's default tier.
getPreferences()
  .then((preferences) => {
    if (!tierTouched) tier.value = preferences.defaultModelTier
  })
  .catch(() => undefined)
const seed = ref('')
const matchingSelection = computed(
  () => route.query.jobAction === 'match' && ['AUTO', 'JOB'].includes(mode.value),
)
async function clearMatchAction() {
  const query = { ...route.query }
  delete query.jobAction
  await router.replace({ path: route.path, query })
}
const lastAttempt = ref('')
const preparingSend = ref(false)
const panelOpen = ref(false)
watch(
  () => activeRun.value?.approvals.length,
  (count) => {
    if (count) panelOpen.value = true
  },
)
const historyOpen = ref(false)
const scrollArea = ref<HTMLElement | null>(null)
const missingConversation = computed(
  () => Boolean(route.params.conversationId) && !current.value && !loading.value,
)
const lastQuestion = computed(
  () =>
    lastAttempt.value ||
    [...chat.messages.value].reverse().find((message) => message.role === 'user')?.text,
)
const mock = import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCK === 'true'
watch(
  () => route.params.conversationId,
  async (value) => {
    const id = typeof value === 'string' ? value : undefined
    if (id && id === current.value?.id) return
    lastAttempt.value = ''
    await chat.open(id)
    seed.value = typeof route.query.prompt === 'string' ? route.query.prompt : ''
    mode.value =
      modes.find((m) => m.value === route.query.mode)?.value ||
      (route.query.optimizationReportId || route.query.diagnosisReportId
        ? 'RESUME'
        : current.value?.mode || 'AUTO')
    await nextTick()
    if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
  },
  { immediate: true },
)
watch(
  () => route.query.prompt,
  (value) => {
    seed.value = typeof value === 'string' ? value : ''
  },
  { immediate: true },
)
watch(
  () => activeRun.value?.text,
  async () => {
    const el = scrollArea.value
    if (!el || el.scrollHeight - el.scrollTop - el.clientHeight > 180) return
    await nextTick()
    el.scrollTop = el.scrollHeight
  },
)
void chat.refreshList()
async function select(id?: string) {
  if (sending.value) await chat.stop()
  historyOpen.value = false
  if (!id && !route.params.conversationId) await chat.open()
  else await router.push(id ? `/chat/${encodeURIComponent(id)}` : '/chat')
}
async function onSend(content: string, images: ChatImage[] = []) {
  if (preparingSend.value || busy.value || missingConversation.value) return
  preparingSend.value = true
  try {
    privacyWarning.value = ''
    // The server applies the checksum-aware recognizers; this cheap screen avoids extra IO for ordinary turns.
    if (/@|1[3-9]\d{9}|\d{17}[0-9Xx]/.test(content)) {
      try {
        privacyWarning.value = (await checkPrivacyText(content)).warning ?? ''
      } catch {
        privacyWarning.value =
          '隐私风险检查暂不可用；模型端仍按你的脱敏设置处理。建议在设置中确认已开启。'
      }
    }
    const submittedMode = mode.value
    const rerankContext =
      submittedMode === 'CAREER_DISCOVERY' && route.query.careerAction === 'rerankCareers'
        ? {
            careerAction: 'rerankCareers',
            revisionId: route.query.revisionId,
            city: typeof route.query.city === 'string' ? route.query.city : '',
            ...(typeof route.query.minSalaryK === 'string' && route.query.minSalaryK
              ? { minSalaryK: Number(route.query.minSalaryK) }
              : {}),
            avoidOvertime: route.query.avoidOvertime === 'true',
            research: route.query.research === 'true',
          }
        : undefined
    const matchAction = matchingSelection.value
    const interviewAction =
      submittedMode === 'INTERVIEWER' && route.query.interviewAction === 'recallStarStories'
        ? 'recallStarStories'
        : undefined
    lastAttempt.value = content
    seed.value = ''
    let context: Record<string, string> = {}
    for (const key of [
      'jobId',
      'applicationId',
      'matchId',
      'jobMatchId',
      'resumeId',
      'resumeVersionId',
      'diagnosisReportId',
      'optimizationReportId',
      'interviewSessionId',
    ]) {
      if (typeof route.query[key] === 'string') context[key] = route.query[key]
    }
    context = optimizationContext(viewMessages.value, context)
    // Consume the selected action once; keep resource IDs for ordinary follow-up questions.
    if (route.query.jobAction === 'match') await clearMatchAction()
    if (rerankContext) {
      const query = { ...route.query }
      for (const key of [
        'careerAction',
        'revisionId',
        'city',
        'minSalaryK',
        'avoidOvertime',
        'research',
      ])
        delete query[key]
      await router.replace({ path: route.path, query })
    }
    if (interviewAction) {
      const query = { ...route.query }
      delete query.interviewAction
      await router.replace({ path: route.path, query })
    }
    if (current.value && context.optimizationReportId)
      await router.replace({
        path: `/chat/${encodeURIComponent(current.value.id)}`,
        query: { ...route.query, ...context, mode: submittedMode },
      })
    await chat.send(
      content,
      submittedMode,
      tier.value,
      (id) => {
        const query = Object.keys(context).length ? { ...context, mode: submittedMode } : {}
        void router.replace({ path: `/chat/${encodeURIComponent(id)}`, query })
      },
      rerankContext
        ? rerankContext
        : matchAction
          ? { ...context, jobAction: 'match' }
          : interviewAction
            ? { ...context, interviewAction }
            : Object.keys(context).length
              ? context
              : undefined,
      images,
    )
    await nextTick()
    if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
  } finally {
    preparingSend.value = false
  }
}
async function editLastQuestion() {
  const content = lastQuestion.value || ''
  seed.value = ''
  await nextTick()
  seed.value = content
}
async function adoptTarget(selection: CareerTargetSelection) {
  if (busy.value || missingConversation.value) return
  panelOpen.value = true
  const content = `请将「${selection.name}」设为目标职业`
  lastAttempt.value = content
  // Reuse the chat approval flow: this starts a WRITE step, never PUTs the profile from the card.
  await chat.send(
    content,
    'CAREER_DISCOVERY',
    tier.value,
    (id) => {
      void router.replace({ path: `/chat/${encodeURIComponent(id)}` })
    },
    { careerAction: 'setCareerTarget', ...selection },
  )
}
async function adoptGrowth(taskTitles: string[]) {
  if (busy.value || missingConversation.value || !taskTitles.length) return
  panelOpen.value = true
  const content = `同步成长任务：我确认已完成「${taskTitles.join('」、「')}」`
  lastAttempt.value = content
  await chat.send(
    content,
    'COACH',
    tier.value,
    (id) => {
      void router.replace({ path: `/chat/${encodeURIComponent(id)}` })
    },
    { coachAction: 'adoptGrowthPlan', taskTitles },
  )
}
async function applyPatches(decision: PatchDecision, payload: unknown) {
  if (busy.value || missingConversation.value) return
  const card = payload && typeof payload === 'object' ? (payload as Record<string, unknown>) : {}
  const selected: Record<string, string> = {}
  for (const key of ['resumeId', 'resumeVersionId']) {
    const value = card[key] ?? route.query[key]
    if (typeof value === 'string') selected[key] = value
  }
  const context = optimizationContext(viewMessages.value, selected)
  if (
    !['resumeId', 'resumeVersionId'].every((key) =>
      /^[A-Za-z0-9_-]{1,80}$/.test(context[key] || ''),
    ) ||
    typeof card.batchId !== 'string' ||
    card.batchId !== decision.batchId ||
    !/^[A-Za-z0-9_-]{1,100}$/.test(decision.batchId)
  ) {
    error.value = '无法关联这批建议的简历、版本或批次，请从对应简历重新打开改写建议。'
    return
  }
  panelOpen.value = true
  const content = '请采纳所选的简历改写建议'
  lastAttempt.value = content
  await chat.send(
    content,
    'RESUME',
    tier.value,
    (id) => {
      void router.replace({ path: `/chat/${encodeURIComponent(id)}`, query: route.query })
    },
    {
      resumeAction: 'applyPatches',
      resumeId: context.resumeId,
      resumeVersionId: context.resumeVersionId,
      ...decision,
    },
  )
}
async function older() {
  const el = scrollArea.value
  const height = el?.scrollHeight || 0
  await chat.older()
  await nextTick()
  if (el) el.scrollTop += el.scrollHeight - height
}
const rename = (id: string, title: string) => chat.update(id, { title })
const togglePin = (conversation: Conversation) =>
  chat.update(conversation.id, { pinned: !conversation.pinned })
async function remove(id: string) {
  const removed = await chat.remove(id)
  if (removed && current.value?.id === id) await select()
  return removed
}
</script>
<template>
  <section class="conversation-workspace">
    <ConversationHistory
      :conversations="conversations"
      :current-id="current?.id"
      :expanded="historyOpen"
      :sending="sending"
      :loading="listLoading"
      :error="listError"
      :has-more="hasMoreConversations"
      :mock="mock"
      :rename="rename"
      :toggle-pin="togglePin"
      :remove="remove"
      @select="select"
      @reload="chat.refreshList()"
      @more="chat.refreshList(true)"
    />
    <div class="chat-layout persistent-chat">
      <div class="chat-main">
        <ChatToolbar
          :title="current?.title || '新对话'"
          :status="mock ? 'Mock 演示' : '自动保存'"
          :history-open="historyOpen"
          :panel-open="panelOpen"
          @toggle-history="historyOpen = !historyOpen"
          @toggle-panel="panelOpen = !panelOpen"
        />
        <div ref="scrollArea" class="chat-scroll" aria-live="polite" aria-relevant="additions text">
          <div class="chat-column">
            <p v-if="privacyWarning" class="helper-note" role="status">
              {{ privacyWarning }} <RouterLink to="/settings/privacy">隐私设置</RouterLink>
            </p>
            <p v-if="typeof route.query.jobId === 'string'" class="helper-note">
              已关联选定岗位和简历版本。可发送匹配或定向改写请求，生成建议后仍需逐条核对采纳；搜索新岗位按本轮条件执行。
              <RouterLink :to="'/jobs/' + encodeURIComponent(route.query.jobId)"
                >返回岗位</RouterLink
              >
            </p>
            <p v-if="typeof route.query.applicationId === 'string'" class="helper-note">
              已关联这条求职记录。可只读查看进度，或请求更新下一步行动；聊天写入仍需你批准。
              <RouterLink
                :to="{ path: '/board', query: { application: route.query.applicationId } }"
                >返回机会详情</RouterLink
              >
            </p>
            <p v-if="route.query.optimizationReportId" class="helper-note">
              已关联这批改写建议。讨论会读取保存结果；明确要求重新生成时才调用改写模型，可在卡片选择建议并批准审批后采纳，也可到简历页确认。
              <RouterLink
                v-if="typeof route.query.resumeId === 'string'"
                :to="{
                  path: '/resumes/' + encodeURIComponent(route.query.resumeId),
                  query: {
                    tab: 'optimize',
                    resumeVersionId: route.query.resumeVersionId,
                    optimizationReportId: route.query.optimizationReportId,
                  },
                }"
                >查看改写建议</RouterLink
              >
            </p>
            <p v-if="route.query.diagnosisReportId" class="helper-note">
              已关联选定版本的简历诊断报告。发送消息后，AI 会读取报告继续讨论。
              <RouterLink
                v-if="typeof route.query.resumeId === 'string'"
                :to="'/resumes/' + encodeURIComponent(route.query.resumeId)"
                >返回简历</RouterLink
              >
            </p>
            <button
              v-if="hasMoreMessages"
              class="btn text older"
              :disabled="loading"
              @click="older"
            >
              加载更早消息
            </button>
            <p v-if="loading" class="subtle" role="status">正在加载记录…</p>
            <section
              v-if="replay.runId.value"
              class="panel form-stack mb-4"
              aria-label="运行回放"
              aria-live="polite"
            >
              <h3>历史运行回放</h3>
              <p v-if="replay.loading.value" role="status">正在读取事件…</p>
              <p v-if="replay.error.value" role="alert">{{ replay.error.value }}</p>
              <p v-if="replay.partial.value">旧运行未保存正文或卡片事件，只能回放已记录过程。</p>
              <p>{{ replay.position.value }} / {{ replay.events.value.length }} 个事件</p>
              <div class="flex flex-wrap gap-2">
                <button
                  class="btn"
                  :disabled="replay.loading.value || replay.complete.value"
                  @click="replay.play"
                >
                  播放
                </button>
                <button class="btn" :disabled="!replay.playing.value" @click="replay.pause">
                  暂停
                </button>
                <button
                  class="btn"
                  :disabled="replay.loading.value || replay.complete.value"
                  @click="replay.step"
                >
                  下一事件
                </button>
                <button class="btn" :disabled="replay.loading.value" @click="replay.seek(0)">
                  从头开始
                </button>
                <button
                  v-if="replay.error.value"
                  class="btn"
                  @click="replay.open(replay.runId.value)"
                >
                  重新读取
                </button>
                <label
                  >速度
                  <select v-model="replay.speed.value">
                    <option :value="0.5">0.5×</option>
                    <option :value="1">1×</option>
                    <option :value="2">2×</option>
                    <option :value="4">4×</option>
                  </select></label
                >
                <button class="btn" @click="replay.close">退出回放</button>
              </div>
              <label
                >事件进度
                <input
                  type="range"
                  min="0"
                  :max="replay.events.value.length"
                  :value="replay.position.value"
                  :disabled="replay.loading.value"
                  @input="replay.seek(Number(($event.target as HTMLInputElement).value))"
              /></label>
            </section>
            <MessageList
              v-if="viewMessages.length"
              :messages="displayedMessages"
              :replay-disabled="busy"
              @replay="openReplay"
              ><template #cards="{ cards }"
                ><slot
                  name="cards"
                  v-bind="{
                    cards,
                    adoptTarget,
                    adoptGrowth,
                    applyPatches,
                    disabled: busy || missingConversation || Boolean(replay.runId.value),
                  }" /></template
            ></MessageList>
            <ChatWelcome v-else-if="!loading && !missingConversation" @pick="seed = $event" />
          </div>
        </div>
        <div class="chat-column chat-dock">
          <div v-if="error" class="error-banner" role="alert">
            {{ error
            }}<button
              class="btn text"
              :disabled="sending"
              @click="
                chat.open(
                  typeof route.params.conversationId === 'string'
                    ? route.params.conversationId
                    : undefined,
                )
              "
            >
              重新加载
            </button>
          </div>
          <div v-if="remoteRunId && !sending" class="helper-note">
            这段对话仍在生成，记录会自动更新。<button class="btn text" @click="chat.stop()">
              停止生成
            </button>
          </div>
          <button
            v-if="
              lastQuestion &&
              !busy &&
              (error || ['FAILED', 'CANCELLED'].includes(chat.messages.value.at(-1)?.status || ''))
            "
            class="btn text"
            @click="editLastQuestion"
          >
            重新编辑上条问题
          </button>
          <div v-if="matchingSelection" class="helper-note">
            本次发送将匹配所选岗位与简历。<button
              class="btn text"
              :disabled="busy"
              @click="clearMatchAction"
            >
              改为普通提问
            </button>
          </div>
          <Composer
            :key="current?.id || 'new'"
            :disabled="
              preparingSend ||
              loading ||
              needsReload ||
              missingConversation ||
              Boolean(remoteRunId && !sending)
            "
            :streaming="sending"
            :initial-draft="seed"
            @send="onSend"
            @stop="chat.stop"
          >
            <template #tools>
              <select v-model="mode" class="tool-select" :disabled="busy" aria-label="对话模式">
                <option v-for="m in modes" :key="m.value" :value="m.value">{{ m.label }}</option>
              </select>
              <select v-model="tier" class="tool-select" :disabled="busy" aria-label="模型档位">
                <option value="FLASH" title="DeepSeek Flash 响应快">快速</option>
                <option value="PRO" title="DeepSeek Pro 推理更强">深度</option>
              </select>
            </template>
          </Composer>
          <p class="chat-footnote">
            {{
              mock
                ? 'Mock 脚本演示，内容只在当前页面可见。'
                : '历史保存在服务端，上下文最多参考最近 10 轮完整对话。图片只在发送的这一轮交给模型。'
            }}
          </p>
        </div>
      </div>
      <aside v-if="panelOpen" class="chat-panel visible">
        <slot
          name="agent"
          :run="replay.runId.value ? replay.run.value : activeRun"
          :readonly="Boolean(replay.runId.value)"
        />
      </aside>
    </div>
  </section>
</template>
<style scoped>
.conversation-workspace {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 28px;
  margin: -16px 0 -40px;
}
.persistent-chat {
  grid-template-columns: minmax(0, 1fr);
  min-width: 0;
}
.persistent-chat:has(.chat-panel) {
  grid-template-columns: minmax(0, 1fr) 300px;
}
.chat-panel {
  animation: rise 200ms var(--ease-out) both;
}
.chat-column {
  width: 100%;
  max-width: 760px;
  margin: 0 auto;
}
.chat-scroll .chat-column {
  padding: 16px 4px 24px;
}
.older {
  display: flex;
  margin: 0 auto 16px;
}
.chat-dock {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: 12px;
}
.chat-footnote {
  font-size: 12px;
  color: var(--muted-foreground);
  text-align: center;
}
@media (max-width: 1300px) {
  .persistent-chat:has(.chat-panel) {
    grid-template-columns: minmax(0, 1fr);
    height: auto;
  }
  .persistent-chat .chat-panel {
    max-height: 360px;
  }
}
@media (max-width: 1000px) {
  .conversation-workspace {
    grid-template-columns: minmax(0, 1fr);
    gap: 8px;
    margin: -8px 0 -32px;
  }
}
@media (max-width: 600px) {
  .chat-footnote {
    line-height: 1.7;
  }
}
</style>
