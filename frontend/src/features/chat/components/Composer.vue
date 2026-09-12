<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{
  disabled?: boolean
  streaming?: boolean
}>()

const emit = defineEmits<{
  send: [content: string]
  stop: []
}>()

const draft = ref('')

function submit(): void {
  const content = draft.value.trim()
  if (!content || props.disabled || props.streaming) return
  emit('send', content)
  draft.value = ''
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey) return
  event.preventDefault()
  submit()
}
</script>

<template>
  <div class="glass-panel rounded-2xl p-3">
    <textarea
      v-model="draft"
      rows="3"
      placeholder="说一句话，比如「帮我找字节和美团的后端校招岗，再按字节的 JD 改我的简历」"
      class="w-full resize-none bg-transparent text-sm leading-relaxed outline-none placeholder:text-muted-foreground"
      :disabled="disabled"
      @keydown="onKeydown"
    />

    <div class="mt-2 flex items-center justify-between">
      <p class="text-xs text-muted-foreground">
        <kbd class="font-mono">Enter</kbd> 发送 · <kbd class="font-mono">Shift+Enter</kbd> 换行
      </p>

      <button
        v-if="streaming"
        type="button"
        class="rounded-xl border border-border px-4 py-1.5 text-sm transition-colors hover:bg-foreground/5"
        @click="emit('stop')"
      >
        停止
      </button>
      <button
        v-else
        type="button"
        class="rounded-xl bg-primary px-4 py-1.5 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-50"
        :disabled="disabled || draft.trim().length === 0"
        @click="submit"
      >
        发送
      </button>
    </div>
  </div>
</template>
