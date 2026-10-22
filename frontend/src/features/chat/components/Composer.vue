<script setup lang="ts">
import { formatDisplay, localizedText, translate as uiText } from '@/shared/utils/language'

import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import {
  CHAT_IMAGE_MAX_BYTES,
  CHAT_IMAGE_MAX_COUNT,
  CHAT_IMAGE_TYPES,
  uploadChatImage,
  type ChatImage,
} from '@/shared/api/attachments'
const props = defineProps<{ disabled?: boolean; streaming?: boolean; initialDraft?: string }>()
const emit = defineEmits<{ send: [content: string, images: ChatImage[]]; stop: [] }>()
/** AG-02：选图后立即上传，发送时只带 fileId；本地预览用 object URL */
interface Pending {
  key: string
  name: string
  preview: string
  image?: ChatImage
}
const pending = ref<Pending[]>([])
const uploading = ref(0)
const attachError = localizedText()
const picker = ref<HTMLInputElement | null>(null)
const ready = computed(() => pending.value.flatMap((p) => (p.image ? [p.image] : [])))
async function pick(event: Event) {
  const input = event.target as HTMLInputElement
  const files = [...(input.files ?? [])]
  input.value = ''
  attachError.value = ''
  for (const file of files) {
    if (pending.value.length >= CHAT_IMAGE_MAX_COUNT) {
      attachError.value = () => uiText('Up to {0} images per message', [CHAT_IMAGE_MAX_COUNT])
      break
    }
    if (!CHAT_IMAGE_TYPES.includes(file.type)) {
      attachError.value = () => uiText('Only PNG, JPG and WebP images are supported')
      continue
    }
    if (file.size > CHAT_IMAGE_MAX_BYTES) {
      attachError.value = () => uiText('Images must be 5 MB or smaller')
      continue
    }
    const item: Pending = {
      key: crypto.randomUUID(),
      name: file.name,
      preview: URL.createObjectURL(file),
    }
    pending.value.push(item)
    uploading.value++
    try {
      const image = await uploadChatImage(file)
      const live = pending.value.find((p) => p.key === item.key)
      if (live) live.image = image
    } catch (error) {
      attachError.value = () =>
        error instanceof Error ? error.message : uiText('Image upload failed')
      remove(item.key)
    } finally {
      uploading.value--
    }
  }
}
function remove(key: string) {
  const item = pending.value.find((p) => p.key === key)
  if (item) URL.revokeObjectURL(item.preview)
  pending.value = pending.value.filter((p) => p.key !== key)
}
function clearImages() {
  for (const item of pending.value) URL.revokeObjectURL(item.preview)
  pending.value = []
}
onBeforeUnmount(clearImages)
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
  if (!content || content.length > 4000 || props.disabled || props.streaming || uploading.value)
    return
  emit('send', content, ready.value)
  draft.value = ''
  clearImages()
  attachError.value = ''
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
      :aria-label="uiText('Message')"
      :placeholder="uiText('Share your goal or describe the problem you are facing')"
      :disabled="disabled"
      @keydown="keydown"
    />
    <div v-if="pending.length" class="composer-images" :aria-label="uiText('Images to send')">
      <figure v-for="item in pending" :key="item.key" :class="{ uploading: !item.image }">
        <img :src="item.preview" :alt="item.name" />
        <button
          type="button"
          :aria-label="uiText('Remove {0}', [item.name])"
          @click="remove(item.key)"
        >
          ×
        </button>
      </figure>
    </div>
    <p v-if="attachError" class="composer-error" role="alert">{{ attachError }}</p>
    <div class="composer-bar">
      <div class="composer-tools">
        <input
          ref="picker"
          type="file"
          accept="image/png,image/jpeg,image/webp"
          multiple
          hidden
          @change="pick"
        />
        <button
          type="button"
          class="composer-attach"
          :disabled="disabled || streaming || pending.length >= CHAT_IMAGE_MAX_COUNT"
          :title="uiText('Add images (PNG/JPG/WebP, up to 5 MB each, 3 maximum)')"
          @click="picker?.click()"
        >
          <AppIcon name="plus" :size="14" />{{ uiText('Image') }}
        </button>
        <slot name="tools" />
      </div>
      <span v-if="draft.length > 3500" class="composer-count num"
        >{{ formatDisplay(draft.length) }}/4000</span
      >
      <span class="composer-hint">{{ uiText('Enter to send, Shift + Enter for a new line') }}</span>
      <button v-if="streaming" type="button" class="btn composer-stop" @click="emit('stop')">
        <AppIcon name="stop" :size="14" />{{ uiText('Stop receiving') }}
      </button>
      <button
        v-else
        type="button"
        class="btn primary composer-send"
        :disabled="disabled || !draft.trim() || uploading > 0"
        :aria-label="uiText('Send')"
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
.composer-images {
  display: flex;
  gap: 8px;
  padding-top: 4px;
}
.composer-images figure {
  position: relative;
  margin: 0;
}
.composer-images img {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  border: 1px solid var(--border);
  object-fit: cover;
}
.composer-images figure.uploading img {
  opacity: 0.5;
}
.composer-images button {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  border: 1px solid var(--border);
  border-radius: 50%;
  background: var(--card);
  color: var(--foreground);
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}
.composer-error {
  margin: 0;
  font-size: 12px;
  color: var(--destructive);
}
.composer-attach {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0 10px;
  background-image: none;
  min-height: 30px;
  border: 1px solid var(--border);
  border-radius: 15px;
  background: var(--background);
  color: var(--muted-foreground);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
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
