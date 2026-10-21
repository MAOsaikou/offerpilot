<script setup lang="ts">
import { formatDisplay, localizedText, translate as uiText } from '@/shared/utils/language'

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
const privacyWarning = localizedText()
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
const modes = computed<{ value: AgentMode; label: string }[]>(() => [
  { value: 'AUTO', label: uiText("AI assistant") },
  { value: 'CAREER_DISCOVERY', label: uiText("Career discovery") },
  { value: 'RESUME', label: uiText("Resume coach") },
  { value: 'JOB', label: uiText("Job analysis") },
  { value: 'INTERVIEWER', label: uiText("Interview practice") },
  { value: 'COACH', label: uiText("Growth coach") },
])
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
      !modes.value.some((m) => m.value === route.query.mode)
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
      modes.value.find((m) => m.value === route.query.mode)?.value ||
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
          () => (uiText("The privacy risk check is unavailable. Your masking preferences still apply when sending to the model. Check that masking is enabled in settings."))
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
  const content = uiText("Set “{0}” as my target career", [selection.name])
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
  const content = uiText("Sync growth tasks: I confirm that I have completed “{0}”", [taskTitles.join('」、「')])
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
    error.value = uiText("The resume, version or batch for these suggestions could not be identified. Reopen the rewrite suggestions from the resume.")
    return
  }
  panelOpen.value = true
  const content = uiText("Accept the selected resume rewrite suggestions")
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
          :title="current?.title || uiText('New chat')"
          :status="mock ? uiText('Mock demo') : uiText('Autosave')"
          :history-open="historyOpen"
          :panel-open="panelOpen"
          @toggle-history="historyOpen = !historyOpen"
          @toggle-panel="panelOpen = !panelOpen"
        />
        <div ref="scrollArea" class="chat-scroll" aria-live="polite" aria-relevant="additions text">
          <div class="chat-column">
            <p v-if="privacyWarning" class="helper-note" role="status">
              {{ privacyWarning }} <RouterLink to="/settings/privacy">{{ uiText("Privacy settings") }}</RouterLink>
            </p>
            <p v-if="typeof route.query.jobId === 'string'" class="helper-note"> {{ uiText("The selected job and resume version are linked. Request matching or a targeted rewrite, then review each suggestion before accepting it. New job searches use the current request's conditions.") }} <RouterLink :to="'/jobs/' + encodeURIComponent(route.query.jobId)"
                >{{ uiText("Back to job") }}</RouterLink
              >
            </p>
            <p v-if="typeof route.query.applicationId === 'string'" class="helper-note"> {{ uiText("This application is linked. View progress or request a next-action update. Chat changes require your approval.") }} <RouterLink
                :to="{ path: '/board', query: { application: route.query.applicationId } }"
                >{{ uiText("Back to application") }}</RouterLink
              >
            </p>
            <p v-if="route.query.optimizationReportId" class="helper-note"> {{ uiText("These rewrite suggestions are linked. Discussion reads saved results. A rewrite model runs only when you explicitly request regeneration. Select suggestions and approve them in chat, or confirm them on the resume page.") }} <RouterLink
                v-if="typeof route.query.resumeId === 'string'"
                :to="{
                  path: '/resumes/' + encodeURIComponent(route.query.resumeId),
                  query: {
                    tab: 'optimize',
                    resumeVersionId: route.query.resumeVersionId,
                    optimizationReportId: route.query.optimizationReportId,
                  },
                }"
                >{{ uiText("View rewrite suggestions") }}</RouterLink
              >
            </p>
            <p v-if="route.query.diagnosisReportId" class="helper-note"> {{ uiText("The diagnosis for the selected resume version is linked. Send a message to discuss it with AI.") }} <RouterLink
                v-if="typeof route.query.resumeId === 'string'"
                :to="'/resumes/' + encodeURIComponent(route.query.resumeId)"
                >{{ uiText("Back to resume") }}</RouterLink
              >
            </p>
            <button
              v-if="hasMoreMessages"
              class="btn text older"
              :disabled="loading"
              @click="older"
            > {{ uiText("Load earlier messages") }} </button>
            <p v-if="loading" class="subtle" role="status">{{ uiText("Loading history…") }}</p>
            <section
              v-if="replay.runId.value"
              class="panel form-stack mb-4"
              :aria-label="uiText('Run replay')"
              aria-live="polite"
            >
              <h3>{{ uiText("Historical run replay") }}</h3>
              <p v-if="replay.loading.value" role="status">{{ uiText("Loading events…") }}</p>
              <p v-if="replay.error.value" role="alert">{{ formatDisplay(replay.error.value) }}</p>
              <p v-if="replay.partial.value">{{ uiText("This older run has no saved text or card events. Only recorded steps can be replayed.") }}</p>
              <p>{{ uiText("{0} of {1} events", [replay.position.value, replay.events.value.length]) }}</p>
              <div class="flex flex-wrap gap-2">
                <button
                  class="btn"
                  :disabled="replay.loading.value || replay.complete.value"
                  @click="replay.play"
                > {{ uiText("Play") }} </button>
                <button class="btn" :disabled="!replay.playing.value" @click="replay.pause"> {{ uiText("Pause") }} </button>
                <button
                  class="btn"
                  :disabled="replay.loading.value || replay.complete.value"
                  @click="replay.step"
                > {{ uiText("Next event") }} </button>
                <button class="btn" :disabled="replay.loading.value" @click="replay.seek(0)"> {{ uiText("Restart") }} </button>
                <button
                  v-if="replay.error.value"
                  class="btn"
                  @click="replay.open(replay.runId.value)"
                > {{ uiText("Reload") }} </button>
                <label
                  >{{ uiText("Speed") }} <select v-model="replay.speed.value">
                    <option :value="0.5">0.5×</option>
                    <option :value="1">1×</option>
                    <option :value="2">2×</option>
                    <option :value="4">4×</option>
                  </select></label
                >
                <button class="btn" @click="replay.close">{{ uiText("Exit replay") }}</button>
              </div>
              <label
                >{{ uiText("Event progress") }} <input
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
            > {{ uiText("Reload #2") }} </button>
          </div>
          <div v-if="remoteRunId && !sending" class="helper-note"> {{ uiText("This conversation is still generating. Records update automatically.") }}<button class="btn text" @click="chat.stop()"> {{ uiText("Stop generation") }} </button>
          </div>
          <button
            v-if="
              lastQuestion &&
              !busy &&
              (error || ['FAILED', 'CANCELLED'].includes(chat.messages.value.at(-1)?.status || ''))
            "
            class="btn text"
            @click="editLastQuestion"
          > {{ uiText("Edit previous question") }} </button>
          <div v-if="matchingSelection" class="helper-note"> {{ uiText("This message will match the selected job and resume.") }}<button
              class="btn text"
              :disabled="busy"
              @click="clearMatchAction"
            > {{ uiText("Ask a general question instead") }} </button>
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
              <select v-model="mode" class="tool-select" :disabled="busy" :aria-label="uiText('Chat mode')">
                <option v-for="m in modes" :key="m.value" :value="m.value">{{ m.label }}</option>
              </select>
              <select v-model="tier" class="tool-select" :disabled="busy" :aria-label="uiText('Model tier')">
                <option value="FLASH" :title="uiText('DeepSeek Flash responds quickly')">{{ uiText("Fast") }}</option>
                <option value="PRO" :title="uiText('DeepSeek Pro offers stronger reasoning')">{{ uiText("Deep") }}</option>
              </select>
            </template>
          </Composer>
          <p class="chat-footnote">
            {{
              mock
                ? uiText("Mock scripted demo. Content is only visible on this page.")
                : uiText("History is saved on the server. Context includes up to 10 recent completed exchanges. Images are sent to the model for the current exchange only.")
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
