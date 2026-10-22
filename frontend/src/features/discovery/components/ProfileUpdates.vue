<script setup lang="ts">
import { computed } from 'vue'

import { localizedText, language, translate as uiText } from '@/shared/utils/language'

import { onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '@/shared/stores/auth'
import {
  getProfileSuggestions,
  decideProfileSuggestion,
  type ProfileSuggestion,
} from '@/shared/api/careerUpdates'
import type { CareerProfile } from '@/shared/api/career'
const props = defineProps<{ beforeConfirm: () => Promise<void> }>()
const emit = defineEmits<{ confirmed: [profile: CareerProfile]; busy: [value: boolean] }>()
const auth = useAuthStore()
const items = ref<ProfileSuggestion[]>([]),
  selected = ref<Record<string, string[]>>({})
const loading = ref(false),
  busy = ref(false),
  error = localizedText(),
  online = ref(navigator.onLine)
const labels = computed(() => ({
  RESUME: uiText('Resume skills'),
  GITHUB: uiText('GitHub evidence'),
  INTERVIEW: uiText('Interview report'),
}))
const statuses = computed(() => ({
  PENDING: uiText('Pending confirmation'),
  CONFIRMED: uiText('Confirmed'),
  REJECTED: uiText('Ignored'),
  STALE: uiText('Source changed'),
}))
let generation = 0
async function load() {
  const current = generation
  loading.value = true
  error.value = ''
  try {
    const data = await getProfileSuggestions()
    if (current !== generation) return
    items.value = data
    selected.value = Object.fromEntries(data.map((item) => [item.id, []]))
  } catch (cause) {
    if (current === generation)
      error.value = () =>
        cause instanceof Error ? cause.message : uiText('Could not load profile suggestions')
  } finally {
    if (current === generation) loading.value = false
  }
}
async function decide(item: ProfileSuggestion, accept: boolean) {
  if (busy.value) return
  const current = generation
  busy.value = true
  emit('busy', true)
  error.value = ''
  try {
    await props.beforeConfirm()
    if (current !== generation) return
    const profile = await decideProfileSuggestion(item.id, accept, selected.value[item.id] ?? [])
    if (current !== generation) return
    if (accept) emit('confirmed', profile)
    await load()
  } catch (cause) {
    if (current === generation)
      error.value = () =>
        cause instanceof Error ? cause.message : uiText('Confirmation failed. Please retry.')
  } finally {
    if (current === generation) {
      busy.value = false
      emit('busy', false)
    }
  }
}
function connection() {
  online.value = navigator.onLine
}
window.addEventListener('online', connection)
window.addEventListener('offline', connection)
watch(
  () => auth.identityKey,
  () => {
    generation++
    items.value = []
    selected.value = {}
    busy.value = false
    emit('busy', false)
    void load()
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  generation++
  window.removeEventListener('online', connection)
  window.removeEventListener('offline', connection)
})
</script>
<template>
  <section
    id="profile-updates"
    class="panel form-stack"
    :aria-label="uiText('Profile update suggestions')"
  >
    <h2>{{ uiText('Profile suggestions from your materials') }}</h2>
    <p>
      {{
        uiText(
          'Existing materials are organized automatically but enter your profile only after confirmation. Select skills you agree with. Repository languages and interview feedback do not establish proficiency.',
        )
      }}
    </p>
    <p v-if="!online" role="status">
      {{ uiText('You are offline. Load and confirm suggestions when connected.') }}
    </p>
    <button class="btn" :disabled="loading || busy || !online" @click="load">
      {{ uiText('Refresh material suggestions') }}
    </button>
    <p v-if="loading" role="status">{{ uiText('Loading material suggestions…') }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!loading && !error && !items.length">
      {{
        uiText(
          'No suggestions yet. Save a resume, complete an interview report or refresh GitHub evidence, then check again.',
        )
      }}
    </p>
    <article v-for="item in items" :key="item.id" class="form-stack profile-suggestion">
      <h3>{{ labels[item.sourceType] }} · {{ statuses[item.status] }}</h3>
      <p class="subtle">
        {{
          uiText('Source: {0} · {1} #2', [
            item.sourceId,
            new Date(item.createdAt).toLocaleString(language),
          ])
        }}
      </p>
      <ul>
        <li v-for="fact in item.facts" :key="fact">{{ fact }}</li>
      </ul>
      <template v-if="item.status === 'PENDING'">
        <label v-for="skill in item.skills" :key="skill"
          ><input
            v-model="selected[item.id]"
            type="checkbox"
            :value="skill"
            :disabled="busy || !online"
          />
          {{ uiText('Add profile skill: {0}', [skill]) }}</label
        >
        <div class="actions">
          <button class="btn primary" :disabled="busy || !online" @click="decide(item, true)">
            {{ uiText('Confirm evidence and selected skills') }}</button
          ><button class="btn" :disabled="busy || !online" @click="decide(item, false)">
            {{ uiText('Ignore this suggestion') }}
          </button>
        </div>
      </template>
    </article>
  </section>
</template>
<style scoped>
.profile-suggestion {
  border-top: 1px solid var(--color-border);
  padding-top: 16px;
  overflow-wrap: anywhere;
  min-width: 0;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
input[type='checkbox'] {
  width: 18px;
  height: 18px;
  min-height: 18px;
}
</style>
