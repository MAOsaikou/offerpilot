<script setup lang="ts">
import { localizedText, translate as uiText } from '@/shared/utils/language'

import { ref, watch } from 'vue'
import { getKnowledgeNote, type KnowledgeNote } from '@/shared/api/knowledge'
import { sessionRevision } from '@/shared/utils/authSession'
import type { KnowledgeNoteCitationsPayload } from '@/shared/types/cards'

const props = defineProps<{ payload: KnowledgeNoteCitationsPayload }>()
const original = ref<KnowledgeNote | null>(null)
const loading = ref<string | null>(null)
const error = localizedText()
let request = 0
watch([sessionRevision, () => props.payload], () => {
  request++
  original.value = null
  loading.value = null
  error.value = ''
})
async function open(id: string) {
  const current = ++request
  original.value = null
  error.value = ''
  loading.value = id
  try {
    const note = await getKnowledgeNote(id)
    if (current === request) original.value = note
  } catch (cause) {
    if (current === request)
      error.value = () =>
        cause instanceof Error
          ? cause.message
          : uiText('Could not read the current source. Please retry.')
  } finally {
    if (current === request) loading.value = null
  }
}
</script>
<template>
  <section
    class="panel form-stack min-w-0 break-words"
    :aria-label="uiText('Knowledge note sources')"
  >
    <h3>{{ uiText('Knowledge note sources · {0}', [payload.count]) }}</h3>
    <p class="helper-note">
      {{
        uiText(
          'Personal notes retrieved for this exchange, for reference only. Excerpts are saved with the response; the original may have changed or been deleted.',
        )
      }}
    </p>
    <article
      v-for="item in payload.items"
      :key="item.noteId"
      class="form-stack rounded-lg border p-3"
      :data-kb-reference="item.reference"
      tabindex="-1"
    >
      <h4>{{ item.title }}</h4>
      <p class="subtle">[{{ item.reference }}] · {{ item.category }}</p>
      <p class="whitespace-pre-wrap">{{ item.excerpt }}</p>
      <button type="button" class="btn" :disabled="!!loading" @click="open(item.noteId)">
        {{ loading === item.noteId ? uiText('Loading… #3') : uiText('View current source') }}
      </button>
    </article>
    <p v-if="error" class="error-banner" role="alert">
      {{ uiText('{0} The excerpt cited in this exchange remains available.', [error]) }}
    </p>
    <section v-if="original" class="form-stack" :aria-label="uiText('Current note source')">
      <h4>{{ uiText('{0} · Current source', [original.title]) }}</h4>
      <p class="whitespace-pre-wrap">{{ original.content }}</p>
      <button type="button" class="btn" @click="original = null">
        {{ uiText('Hide original text') }}
      </button>
    </section>
  </section>
</template>
