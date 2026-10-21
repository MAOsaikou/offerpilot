<script setup lang="ts">
import { formatDisplay, localizedText, translate as uiText } from '@/shared/utils/language'

import { computed, onMounted, ref } from 'vue'
import {
  addMemory,
  listMemoryCandidates,
  confirmMemoryCandidate,
  deleteMemoryCandidate,
  type MemoryCandidate,
  clearMemories,
  deleteMemory,
  editMemory,
  listMemories,
  memoryKinds,
  setMemoryEnabled,
  type MemoryDraft,
  type MemoryInventory,
  type UserMemory,
} from '@/shared/api/memories'

const inventory = ref<MemoryInventory>({ items: [], memoryEnabled: true, limit: 50 })
const candidates = ref<MemoryCandidate[]>([])
const loaded = ref(false)
const busy = ref(false)
const error = localizedText()
const status = ref('')
const editingId = ref<string | null>(null)
const formOpen = ref(false)
const draft = ref<MemoryDraft>({ kind: 'OTHER', fact: '' })
const confirming = ref<string | null>(null)
const sourceLabels = computed(() => ({
  EXPLICIT: uiText('Confirmed in chat'),
  SUGGESTED_ACCEPTED: uiText('Suggestion confirmed'),
  MANUAL: uiText('Manually managed'),
}))
const points = computed(() => Array.from(draft.value.fact).length)
const valid = computed(() => draft.value.fact.trim().length > 0 && points.value <= 200)
async function load() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const [saved, pending] = await Promise.all([listMemories(), listMemoryCandidates()])
    inventory.value = saved
    candidates.value = pending
    loaded.value = true
  } catch {
    error.value = () => uiText('Could not load memories. Please retry.')
  } finally {
    busy.value = false
  }
}
async function changeEnabled(event: Event) {
  const input = event.target as HTMLInputElement
  const next = input.checked
  await perform(async () => {
    inventory.value.memoryEnabled = (await setMemoryEnabled(next)).memoryEnabled
  }, uiText('Memory settings saved.'))
  input.checked = inventory.value.memoryEnabled
}
async function perform(action: () => Promise<void>, message: string) {
  if (!loaded.value || busy.value) return
  busy.value = true
  error.value = ''
  status.value = ''
  try {
    await action()
    status.value = message
  } catch (cause) {
    error.value = () =>
      cause instanceof Error
        ? uiText('Operation failed: {0}. Please retry.', [cause.message])
        : uiText('Operation failed. Please retry.')
  } finally {
    busy.value = false
  }
}
function open(memory?: UserMemory) {
  editingId.value = memory?.id ?? null
  draft.value = { kind: memory?.kind ?? 'OTHER', fact: memory?.fact ?? '' }
  formOpen.value = true
  confirming.value = null
}
async function save() {
  if (!valid.value) return
  await perform(async () => {
    const saved = editingId.value
      ? await editMemory(editingId.value, draft.value)
      : await addMemory(draft.value)
    inventory.value.items = [saved, ...inventory.value.items.filter((item) => item.id !== saved.id)]
    formOpen.value = false
  }, uiText('Memory saved.'))
}
function askDelete(id: string) {
  confirming.value = id
  formOpen.value = false
}
async function remove() {
  const id = confirming.value
  if (!id) return
  await perform(
    async () => {
      if (id === 'all') {
        await clearMemories()
        inventory.value.items = []
        candidates.value = []
      } else {
        await deleteMemory(id)
        inventory.value.items = inventory.value.items.filter((item) => item.id !== id)
      }
      confirming.value = null
    },
    id === 'all' ? uiText('All memories cleared.') : uiText('Memory deleted.'),
  )
}
async function decideCandidate(candidate: MemoryCandidate, accept: boolean) {
  await perform(
    async () => {
      if (accept) {
        const saved = await confirmMemoryCandidate(candidate.id)
        inventory.value.items = [
          saved,
          ...inventory.value.items.filter((item) => item.id !== saved.id),
        ]
      } else await deleteMemoryCandidate(candidate.id)
      candidates.value = candidates.value.filter((item) => item.id !== candidate.id)
    },
    accept
      ? uiText('Candidate confirmed. Future conversations may use it.')
      : uiText('Candidate deleted. This fact will not be suggested again.'),
  )
}
onMounted(load)
</script>

<template>
  <section class="form-stack" aria-labelledby="memory-heading">
    <h3 id="memory-heading">{{ uiText('Long-term memory') }}</h3>
    <p class="subtle">
      {{
        uiText(
          'Saves your confirmed target cities, roles, skills and constraints for new conversations. After successful exchanges, candidates are extracted from your words and used only after confirmation. Nothing is automatically extracted from resumes, job descriptions or notes. Up to 50 entries, 200 characters each.',
        )
      }}
    </p>
    <label>
      <input
        type="checkbox"
        :checked="inventory.memoryEnabled"
        :disabled="busy || !loaded"
        @change="changeEnabled"
      />
      {{ uiText('Use long-term memory (on by default)') }}
    </label>
    <p v-if="loaded && !inventory.memoryEnabled" class="helper-note">
      {{
        uiText(
          'Off: new runs do not read memory or suggest saving it. Existing entries remain and can be edited or deleted. Responses started before disabling may still contain previously read context.',
        )
      }}
    </p>
    <p class="subtle">
      {{
        uiText(
          'Memories are stored on the server and follow text masking settings before model use. Deleting a memory does not delete the original conversation, whose context may still refer to it. Deleting account data also clears memories.',
        )
      }}
    </p>
    <p v-if="busy" role="status">{{ uiText('Syncing memories…') }}</p>
    <p v-if="status" role="status">{{ status }}</p>
    <p v-if="error" class="error-banner" role="alert">
      {{ error }}
      <button class="btn" :disabled="busy" @click="load">{{ uiText('Reload #2') }}</button>
    </p>
    <template v-if="loaded">
      <div v-if="candidates.length" class="form-stack" :aria-label="uiText('Memory candidates')">
        <h4>{{ uiText('Extracted from conversations · Please review') }}</h4>
        <article v-for="candidate in candidates" :key="candidate.id" class="rounded-lg border p-3">
          <p class="break-words">{{ candidate.fact }}</p>
          <p class="subtle">
            {{
              uiText('Confidence {0}% · {1}', [
                Math.round(candidate.confidence * 100),
                candidate.extractor === 'rule'
                  ? uiText('Rule-based extraction')
                  : uiText('FLASH extraction'),
              ])
            }}
          </p>
          <a
            :href="`/chat/${encodeURIComponent(candidate.sourceConversationId)}?messageId=${encodeURIComponent(candidate.sourceMessageId)}`"
            >{{ uiText('View source conversation') }}</a
          >
          <div class="flex flex-wrap gap-2">
            <button
              class="btn"
              :disabled="busy || !inventory.memoryEnabled"
              @click="decideCandidate(candidate, true)"
            >
              {{ uiText('Confirm candidate') }}
            </button>
            <button class="btn" :disabled="busy" @click="decideCandidate(candidate, false)">
              {{ uiText('Delete candidate') }}
            </button>
          </div>
        </article>
      </div>
      <div class="flex flex-wrap gap-2">
        <button
          class="btn"
          :disabled="busy || inventory.items.length >= inventory.limit"
          @click="open()"
        >
          {{ uiText('Add memory') }}
        </button>
        <button
          class="btn"
          :disabled="busy || inventory.items.length === 0"
          @click="askDelete('all')"
        >
          {{ uiText('Clear all memories') }}
        </button>
        <span class="subtle"
          >{{ formatDisplay(inventory.items.length) }} / {{ inventory.limit }}</span
        >
      </div>
      <p v-if="inventory.items.length === 0" class="subtle">
        {{
          uiText(
            'No long-term memories yet. Add one manually or ask in chat to remember your target city, then confirm.',
          )
        }}
      </p>
      <ul v-else class="space-y-3">
        <li v-for="memory in inventory.items" :key="memory.id" class="rounded-lg border p-3">
          <p class="break-words">{{ memory.fact }}</p>
          <p class="subtle">{{ memoryKinds[memory.kind] }} · {{ sourceLabels[memory.source] }}</p>
          <div class="mt-2 flex flex-wrap gap-2">
            <button
              class="btn"
              :disabled="busy"
              :aria-label="uiText('Edit memory: {0}', [memory.fact])"
              @click="open(memory)"
            >
              {{ uiText('Edit #2') }}
            </button>
            <button
              class="btn"
              :disabled="busy"
              :aria-label="uiText('Delete memory: {0}', [memory.fact])"
              @click="askDelete(memory.id)"
            >
              {{ uiText('Delete') }}
            </button>
          </div>
        </li>
      </ul>
      <div
        v-if="confirming"
        class="rounded-lg border p-3"
        role="group"
        :aria-label="uiText('Confirm memory deletion')"
      >
        <p>
          {{
            confirming === 'all'
              ? uiText('Clear all memories? This cannot be undone.')
              : uiText('Delete this memory? This cannot be undone.')
          }}
        </p>
        <button class="btn" :disabled="busy" @click="remove">
          {{ uiText('Confirm deletion') }}
        </button>
        <button class="btn" :disabled="busy" @click="confirming = null">
          {{ uiText('Cancel') }}
        </button>
      </div>
      <form v-if="formOpen" class="form-stack" @submit.prevent="save">
        <fieldset :disabled="busy" class="form-stack min-w-0">
          <legend>{{ editingId ? uiText('Edit memory') : uiText('Add memory') }}</legend>
          <label class="field"
            >{{ uiText('Type') }}
            <select v-model="draft.kind">
              <option v-for="(label, kind) in memoryKinds" :key="kind" :value="kind">
                {{ label }}
              </option>
            </select>
          </label>
          <label class="field"
            >{{ uiText('Fact (one line, up to 200 characters)')
            }}<input v-model="draft.fact" type="text"
          /></label>
          <p class="subtle">{{ points }} / 200</p>
          <div class="flex flex-wrap gap-2">
            <button type="submit" class="btn primary" :disabled="!valid">
              {{ uiText('Save memory') }}
            </button>
            <button type="button" class="btn" @click="formOpen = false">
              {{ uiText('Cancel') }}
            </button>
          </div>
        </fieldset>
      </form>
    </template>
  </section>
</template>
