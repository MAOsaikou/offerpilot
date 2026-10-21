<script setup lang="ts" generic="T">
import { formatDisplay, localizedText, translate as uiText } from '@/shared/utils/language'

import { nextTick, ref, useId, watch } from 'vue'
import { moveEntry } from '../reorder'

const props = defineProps<{ title: string; disabled?: boolean }>()
const entries = defineModel<T[]>({ required: true })
const root = ref<HTMLElement>()
const hintId = useId()
const dragging = ref<number>()
const target = ref<number>()
const announcement = localizedText()

function cancelDrag() {
  dragging.value = target.value = undefined
}
// An edit, reload or save invalidates the drag's source position.
watch([entries, () => props.disabled], cancelDrag, { flush: 'sync' })

async function move(from: number, to: number, action: 'up' | 'down' | 'drag') {
  if (props.disabled) return
  const updated = moveEntry(entries.value, from, to)
  if (updated === entries.value) return
  cancelDrag()
  entries.value = updated
  announcement.value = () => (uiText("{0} item {1} moved to position {2} of {3}.", [props.title, from + 1, to + 1, updated.length]))
  await nextTick()
  const row = root.value?.querySelectorAll('fieldset')[to]
  const preferred = row?.querySelector<HTMLButtonElement>(`[data-action="${action}"]`)
  // At either end, keep focus on the moved entry's next available control.
  const fallback = row?.querySelector<HTMLButtonElement>(
    `[data-action="${action === 'up' ? 'down' : 'up'}"]:not(:disabled)`,
  )
  ;(preferred && !preferred.disabled ? preferred : fallback)?.focus()
}
function startDrag(index: number, event: DragEvent) {
  if (props.disabled || entries.value.length < 2 || !event.dataTransfer) {
    event.preventDefault()
    return
  }
  dragging.value = index
  event.dataTransfer.effectAllowed = 'move'
  event.dataTransfer.setData('application/x-offerpilot-resume-entry', String(index))
}
function dragOver(index: number, event: DragEvent) {
  if (props.disabled || dragging.value === undefined) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  target.value = index
}
function drop(index: number, event: DragEvent) {
  if (props.disabled || dragging.value === undefined) return
  event.preventDefault()
  const from = dragging.value
  cancelDrag()
  void move(from, index, 'drag')
}
</script>
<template>
  <div ref="root" class="form-stack reorder-list">
    <p v-if="entries.length > 1" :id="hintId" class="subtle"> {{ uiText("Drag the handle or use Move up and Move down to reorder. Saving creates a new version.") }} </p>
    <fieldset
      v-for="(entry, index) in entries"
      :key="index"
      :disabled="disabled"
      class="panel form-stack reorder-entry"
      :class="{ 'drop-target': target === index && dragging !== index }"
      @dragover="dragOver(index, $event)"
      @dragleave="target === index && (target = undefined)"
      @drop="drop(index, $event)"
    >
      <legend>{{ title }} {{ formatDisplay(index + 1) }}</legend>
      <div class="reorder-controls" role="group" :aria-label="uiText('Order of {0} item {1}', [title, index + 1])">
        <button
          type="button"
          class="btn drag-handle"
          data-action="drag"
          :draggable="!disabled && entries.length > 1"
          :disabled="disabled || entries.length < 2"
          :aria-label="uiText('Drag to reorder {0} item {1}', [title, index + 1])"
          :aria-describedby="entries.length > 1 ? hintId : undefined"
          @dragstart="startDrag(index, $event)"
          @dragend="cancelDrag"
        >
          <span aria-hidden="true">⠿</span> {{ uiText("Drag") }} </button>
        <button
          type="button"
          class="btn"
          data-action="up"
          :disabled="disabled || index === 0"
          :aria-label="uiText('Move {0} item {1} up', [title, index + 1])"
          @click="move(index, index - 1, 'up')"
        > {{ uiText("Move up") }} </button>
        <button
          type="button"
          class="btn"
          data-action="down"
          :disabled="disabled || index === entries.length - 1"
          :aria-label="uiText('Move {0} item {1} down', [title, index + 1])"
          @click="move(index, index + 1, 'down')"
        > {{ uiText("Move down") }} </button>
      </div>
      <slot :entry="entry" :index="index" />
    </fieldset>
    <p class="reorder-announcement" role="status" aria-live="polite" aria-atomic="true">
      {{ announcement }}
    </p>
  </div>
</template>
<style scoped>
.reorder-list,
.reorder-entry {
  min-width: 0;
}
.reorder-entry {
  margin: 0;
}
.reorder-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.reorder-controls .btn {
  min-width: 44px;
  min-height: 44px;
}
.drag-handle:not(:disabled) {
  cursor: grab;
}
.drag-handle:active:not(:disabled) {
  cursor: grabbing;
}
.drop-target {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
  background: color-mix(in srgb, var(--primary) 8%, var(--card));
}
.reorder-announcement {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
@media (max-width: 640px) {
  .reorder-entry {
    padding: 12px;
  }
}
</style>
