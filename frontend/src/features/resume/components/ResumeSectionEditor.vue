<script setup lang="ts">
import type { ResumeEntry } from '@/shared/types/resume'
export interface Field {
  key: string
  label: string
  array?: boolean
  multiline?: boolean
}
const props = defineProps<{ title: string; fields: Field[]; disabled?: boolean }>()
const entries = defineModel<ResumeEntry[]>({ required: true })
function add() {
  entries.value = [
    ...entries.value,
    Object.fromEntries(props.fields.map((f) => [f.key, f.array ? [] : ''])),
  ]
}
function update(index: number, field: Field, event: Event) {
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
      <button type="button" class="btn" :disabled="disabled || entries.length >= 50" @click="add">
        ＋ 添加{{ title }}
      </button>
    </div>
    <p v-if="!entries.length" class="subtle">尚未填写{{ title }}，可按实际情况补充。</p>
    <fieldset
      v-for="(entry, index) in entries"
      :key="index"
      :disabled="disabled"
      class="panel form-stack"
    >
      <legend>{{ title }} {{ index + 1 }}</legend>
      <label v-for="field in fields" :key="field.key" class="field"
        >{{ field.label }}{{ field.array ? '（每行一项）' : '' }}
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
      <button type="button" class="btn" @click="entries = entries.filter((_, i) => i !== index)">
        移除此条{{ title }}
      </button>
    </fieldset>
  </section>
</template>
