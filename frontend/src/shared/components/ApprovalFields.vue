<script setup lang="ts">
import { computed } from 'vue'

import { translate as uiText } from '@/shared/utils/language'

import { watch, ref } from 'vue'
const props = defineProps<{ fields: string[]; args: unknown; disabled?: boolean }>()
const emit = defineEmits<{ change: [Record<string, unknown>, string] }>()
const values = ref<Record<string, string>>({})
const labels = computed<Record<string, string>>(() => ({
  name: uiText('Target career name'),
  taskTitles: uiText('Completed tasks (JSON array)'),
  accepted: uiText('Accepted patches (JSON array)'),
  edited: uiText('Edited patches (JSON object)'),
  confirmed: uiText('Verified patches (JSON array)'),
  questionIndex: uiText('Question index (starts at 0)'),
  role: uiText('Interview role'),
  kind: uiText('Interview type'),
  answer: uiText('Interview answer'),
  nextQuestion: uiText('Next question (true / false)'),
  fact: uiText('Memory content (original quotes only)'),
  status: uiText('Status'),
  note: uiText('Notes'),
  nextAction: uiText('Next action'),
  nextActionAt: uiText('Action date'),
}))
const jsonFields = new Set([
  'taskTitles',
  'accepted',
  'edited',
  'confirmed',
  'questionIndex',
  'nextQuestion',
])
watch(
  () => props.args,
  (args) => {
    const data = (args ?? {}) as Record<string, unknown>
    values.value = Object.fromEntries(
      props.fields.map((field) => [
        field,
        jsonFields.has(field)
          ? JSON.stringify(data[field] ?? (field === 'edited' ? {} : []))
          : String(data[field] ?? ''),
      ]),
    )
  },
  { immediate: true },
)
function change() {
  try {
    const original = (props.args ?? {}) as Record<string, unknown>
    const args: Record<string, unknown> = {}
    for (const field of props.fields) {
      const value = jsonFields.has(field)
        ? JSON.parse(values.value[field] ?? '')
        : values.value[field]
      if (JSON.stringify(value) !== JSON.stringify(original[field])) args[field] = value
    }
    emit('change', args, '')
  } catch {
    emit('change', {}, uiText('Enter valid JSON parameters'))
  }
}
</script>
<template>
  <div class="min-w-0 space-y-2">
    <label v-for="field in fields" :key="field" class="block text-xs">
      {{ labels[field] ?? field }}
      <textarea
        v-model="values[field]"
        :disabled="disabled"
        rows="2"
        class="mt-1 block w-full min-w-0 rounded border bg-background p-2"
        @input="change"
      />
    </label>
  </div>
</template>
