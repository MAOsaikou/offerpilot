<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import type { ResumeEntry } from '@/shared/types/resume'
import ResumeReorderList from './ResumeReorderList.vue'
export interface Field {
  key: string
  label: string
  array?: boolean
  multiline?: boolean
}
const props = defineProps<{ title: string; fields: Field[]; disabled?: boolean }>()
const entries = defineModel<ResumeEntry[]>({ required: true })
function add() {
  if (props.disabled || entries.value.length >= 50) return
  entries.value = [
    ...entries.value,
    Object.fromEntries(props.fields.map((f) => [f.key, f.array ? [] : ''])),
  ]
}
function update(index: number, field: Field, event: Event) {
  if (props.disabled) return
  const value = (event.target as HTMLInputElement).value
  entries.value = entries.value.map((entry, i) =>
    i === index ? { ...entry, [field.key]: field.array ? value.split('\n') : value } : entry,
  )
}
function text(entry: ResumeEntry, field: Field) {
  const value = entry[field.key]
  return Array.isArray(value) ? value.join('\n') : (value ?? '')
}
</script>
<template>
  <section class="form-stack resume-section">
    <div class="section-heading">
      <h2>{{ title }}</h2>
      <button type="button" class="btn" :disabled="disabled || entries.length >= 50" @click="add"> {{ uiText("＋ Add {0}", [title]) }}
      </button>
    </div>
    <p v-if="!entries.length" class="subtle">{{ uiText("No {0} entries yet. Add your real experience.", [title]) }}</p>
    <ResumeReorderList v-model="entries" :title="title" :disabled="disabled">
      <template #default="{ entry, index }">
        <label v-for="field in fields" :key="field.key" class="field"
          >{{ field.label }}{{ field.array ? uiText("(one per line)") : '' }}
          <textarea
            v-if="field.array || field.multiline"
            :value="text(entry, field)"
            rows="3"
            maxlength="5000"
            @input="update(index, field, $event)"
          />
          <input
            v-else
            :value="text(entry, field)"
            maxlength="5000"
            @input="update(index, field, $event)"
          />
        </label>
        <button type="button" class="btn" @click="entries = entries.filter((_, i) => i !== index)"> {{ uiText("Remove this {0} entry", [title]) }}
        </button>
      </template>
    </ResumeReorderList>
  </section>
</template>
<style scoped>
.resume-section {
  min-width: 0;
}
.section-heading {
  flex-wrap: wrap;
}
</style>
