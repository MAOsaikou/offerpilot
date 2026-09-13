<script setup lang="ts">
import { ref, watch } from 'vue'
const props = defineProps<{ disabled?: boolean; streaming?: boolean; initialDraft?: string }>()
const emit = defineEmits<{ send: [content: string]; stop: [] }>()
const draft = ref(props.initialDraft || '')
watch(
  () => props.initialDraft,
  (value) => {
    draft.value = value || ''
  },
)
function submit() {
  const content = draft.value.trim()
  if (!content || content.length > 4000 || props.disabled || props.streaming) return
  emit('send', content)
  draft.value = ''
}
function keydown(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229 || event.key !== 'Enter' || event.shiftKey) return
  event.preventDefault()
  submit()
}
</script>
<template>
  <div class="glass-panel rounded-2xl p-4">
    <textarea
      v-model="draft"
      rows="3"
      maxlength="4000"
      aria-label="对话内容"
      placeholder="说说你的目标，或写下现在遇到的问题…"
      class="w-full resize-none bg-transparent text-base leading-relaxed outline-none placeholder:text-muted-foreground"
      :disabled="disabled"
      @keydown="keydown"
    />
    <div class="mt-2 flex items-center justify-between gap-3">
      <p class="text-xs text-muted-foreground">
        Enter 发送 · Shift+Enter 换行
        <span v-if="draft.length > 3500">{{ draft.length }}/4000</span>
      </p>
      <button v-if="streaming" type="button" class="btn" @click="emit('stop')">停止接收</button
      ><button
        v-else
        type="button"
        class="btn primary"
        :disabled="disabled || !draft.trim()"
        @click="submit"
      >
        发送 ↑
      </button>
    </div>
  </div>
</template>
