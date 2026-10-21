<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { ref } from 'vue'
import MarkdownView from '@/shared/components/MarkdownView.vue'
import MessageUsage from '@/shared/components/MessageUsage.vue'
import CitationPanel from '@/shared/components/CitationPanel.vue'
import ChatImageThumb from './ChatImageThumb.vue'
import type { ChatMessageView } from '../types'

defineProps<{ message: ChatMessageView; replayDisabled?: boolean }>()
const emit = defineEmits<{ replay: [runId: string] }>()
const root = ref<HTMLElement | null>(null)
const missingSource = ref('')
/** AG-05: a [KB-…] reference in the reply jumps to the matching note in this turn's source card. */
function locate(reference: string) {
  if (!/^(KB-[0-9a-f]{12}|[0-9]+)$/.test(reference)) return
  const selector = reference.startsWith('KB-')
    ? `[data-kb-reference="${reference}"]:not(.md-citation)`
    : `article[data-citation-index="${reference}"]`
  const source = root.value?.querySelector<HTMLElement>(selector)
  missingSource.value = source ? '' : reference
  if (!source) return
  source.scrollIntoView({
    behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
    block: 'center',
  })
  source.classList.remove('cite-flash')
  void source.offsetWidth
  source.classList.add('cite-flash')
  source.focus({ preventScroll: true })
}
</script>

<template>
  <div v-if="message.role === 'user'" class="message-in flex justify-end">
    <div class="user-turn">
      <div
        v-if="message.images?.length"
        class="user-images"
        :aria-label="uiText('Image sent with this message')"
      >
        <ChatImageThumb
          v-for="image in message.images"
          :key="image.fileId"
          :file-id="image.fileId"
          :name="image.name"
        />
      </div>
      <div class="user-bubble">{{ message.text }}</div>
    </div>
  </div>

  <div v-else ref="root" class="message-in assistant" :class="{ 'is-live': message.streaming }">
    <details v-if="message.reasoning" class="reasoning">
      <summary>{{ uiText('Reasoning summary') }}</summary>
      <p>{{ message.reasoning }}</p>
    </details>

    <MarkdownView
      v-if="message.text"
      :text="message.text"
      :citation-indexes="message.citations?.map((source) => source.index)"
      :streaming="message.streaming === true"
      @cite="locate"
    />
    <p v-else class="pending">
      <span v-if="!message.status || message.status === 'RUNNING'" class="live-dot pending-dot" />
      {{
        message.status === 'FAILED'
          ? uiText('This response could not be completed')
          : message.status === 'CANCELLED'
            ? uiText('This response was stopped')
            : uiText('Thinking')
      }}
    </p>
    <p v-if="missingSource" class="run-note" role="status">
      {{
        uiText(
          'No source was found for citation {0} in this response. The model may have used an incorrect reference number.',
          [missingSource],
        )
      }}
    </p>
    <p v-if="message.status === 'FAILED' || message.status === 'CANCELLED'" class="run-note">
      {{
        message.status === 'FAILED'
          ? uiText(
              'Check model settings or your connection, then resend. This exchange is excluded from future context.',
            )
          : uiText(
              'Generation stopped. Received content is retained. This exchange is excluded from future context.',
            )
      }}
    </p>

    <CitationPanel :sources="message.citations ?? []" />
    <MessageUsage :usage="message.usage" :model="message.model" :duration-ms="message.durationMs" />
    <button
      v-if="message.runId && message.status !== 'RUNNING'"
      class="btn mt-2"
      type="button"
      :disabled="replayDisabled"
      @click="emit('replay', message.runId)"
    >
      {{ uiText('Replay this run') }}
    </button>
    <div v-if="message.cards?.length" class="assistant-cards">
      <slot name="cards" :cards="message.cards ?? []" />
    </div>
  </div>
</template>

<style scoped>
.user-turn {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  max-width: min(80%, 36em);
}
.user-images {
  display: flex;
  gap: 6px;
}
.user-bubble {
  max-width: 100%;
  padding: 10px 16px;
  border-radius: 16px 16px 4px 16px;
  background: color-mix(in srgb, var(--foreground) 7%, var(--card));
  font-size: 15px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.assistant {
  min-width: 0;
  padding-left: 16px;
  border-left: 2px solid var(--border);
  transition: border-color 400ms ease;
}
.assistant.is-live {
  border-left-color: var(--primary);
}
.reasoning {
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--muted-foreground);
}
.reasoning summary {
  cursor: pointer;
  user-select: none;
  width: fit-content;
}
.reasoning summary:hover {
  color: var(--foreground);
}
.reasoning p {
  margin-top: 6px;
  line-height: 1.7;
}
.pending {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: var(--muted-foreground);
}
.pending-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.run-note {
  margin-top: 8px;
  font-size: 13px;
  color: var(--muted-foreground);
}
:deep(.cite-flash) {
  animation: cite-flash 1.6s ease;
}
@keyframes cite-flash {
  0%,
  40% {
    box-shadow: 0 0 0 3px color-mix(in oklab, var(--primary) 45%, transparent);
  }
  100% {
    box-shadow: 0 0 0 0 transparent;
  }
}
.assistant-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 14px;
}
</style>
