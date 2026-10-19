<script setup lang="ts">
import { watch, ref } from 'vue'
const props = defineProps<{ fields: string[]; args: unknown; disabled?: boolean }>()
const emit = defineEmits<{ change: [Record<string, unknown>, string] }>()
const values = ref<Record<string, string>>({})
const labels: Record<string, string> = {
  name: '目标职业名称',
  taskTitles: '已完成任务（JSON 数组）',
  accepted: '采纳补丁（JSON 数组）',
  edited: '编辑补丁（JSON 对象）',
  confirmed: '已核实补丁（JSON 数组）',
  questionIndex: '面试题号（从 0 开始）',
  role: '面试岗位',
  kind: '面试类型',
  answer: '面试答案',
  nextQuestion: '进入下一题（true / false）',
  fact: '记忆内容（仅可截取原话）',
  status: '状态',
  note: '备注',
  nextAction: '下一步行动',
  nextActionAt: '行动日期',
}
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
    emit('change', {}, '请填写有效的 JSON 参数')
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
