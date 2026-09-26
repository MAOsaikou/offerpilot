<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import AppIcon from '@/shared/components/AppIcon.vue'
const props = defineProps<{ disabled?: boolean; streaming?: boolean; initialDraft?: string }>()
const emit = defineEmits<{ send: [content: string]; stop: [] }>()
const draft = ref(props.initialDraft || '')
const field = ref<HTMLTextAreaElement | null>(null)
watch(
  () => props.initialDraft,
  async (value) => {
    draft.value = value || ''
    if (value) {
      await nextTick()
      field.value?.focus()
    }
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
  <div class="composer" :class="{ 'is-streaming': streaming }">
    <textarea
      ref="field"
      v-model="draft"
      rows="2"
      maxlength="4000"
      aria-label="对话内容"
      placeholder="说说你的目标，或写下现在遇到的问题"
      :disabled="disabled"
      @keydown="keydown"
    />
    <div class="composer-bar">
      <div class="composer-tools"><slot name="tools" /></div>
      <span v-if="draft.length > 3500" class="composer-count num">{{ draft.length }}/4000</span>
      <span class="composer-hint">Enter 发送，Shift + Enter 换行</span>
      <button v-if="streaming" type="button" class="btn composer-stop" @click="emit('stop')">
        <AppIcon name="stop" :size="14" />停止接收
      </button>
      <button
        v-else
        type="button"
        class="btn primary composer-send"
        :disabled="disabled || !draft.trim()"
        aria-label="发送"
        @click="submit"
      >
        <AppIcon name="send" :size="18" />
      </button>
    </div>
  </div>
</template>
<style scoped>
.composer {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 12px 10px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
  box-shadow: var(--shadow-float);
  transition:
    border-color var(--dur-fast),
    box-shadow var(--dur-mid);
}
.composer:focus-within {
  border-color: color-mix(in srgb, var(--primary) 60%, var(--border));
}
.composer.is-streaming {
  border-color: color-mix(in srgb, var(--primary) 40%, var(--border));
}
textarea {
  width: 100%;
  min-height: 48px;
  max-height: 240px;
  field-sizing: content;
  resize: none;
  border: 0;
  background: transparent;
  color: var(--foreground);
  font: inherit;
  font-size: 15px;
  line-height: 1.65;
  outline: none;
}
textarea::placeholder {
  color: var(--muted-foreground);
}
.composer-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.composer-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.composer-hint,
.composer-count {
  font-size: 12px;
  color: var(--muted-foreground);
  white-space: nowrap;
}
:slotted(.tool-select) {
  min-height: 30px;
  padding: 0 26px 0 10px;
  border: 1px solid var(--border);
  border-radius: 15px;
  background-color: var(--background);
  background-image:
    linear-gradient(45deg, transparent 50%, currentColor 50%),
    linear-gradient(135deg, currentColor 50%, transparent 50%);
  background-position:
    calc(100% - 13px) 50%,
    calc(100% - 9px) 50%;
  background-size: 4px 4px;
  background-repeat: no-repeat;
  color: var(--muted-foreground);
  font: inherit;
  font-size: 13px;
  appearance: none;
  cursor: pointer;
  transition:
    color var(--dur-fast),
    border-color var(--dur-fast);
}
:slotted(.tool-select:hover:not(:disabled)) {
  color: var(--foreground);
  border-color: var(--input);
}
.composer-send {
  width: 38px;
  padding: 0;
  border-radius: 50%;
}
.composer-stop {
  border-color: var(--primary);
  color: var(--primary);
}
@media (max-width: 640px) {
  .composer-hint {
    display: none;
  }
}
</style>
