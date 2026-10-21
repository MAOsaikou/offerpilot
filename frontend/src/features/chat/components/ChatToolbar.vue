<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import AppIcon from '@/shared/components/AppIcon.vue'

defineProps<{ title: string; status: string; historyOpen: boolean; panelOpen: boolean }>()
const emit = defineEmits<{ 'toggle-history': []; 'toggle-panel': [] }>()
</script>
<template>
  <div class="chat-toolbar">
    <button
      class="icon-button history-toggle"
      :aria-expanded="historyOpen"
      :aria-label="uiText('Conversation history')"
      @click="emit('toggle-history')"
    >
      <AppIcon name="history" :size="18" />
    </button>
    <h1 class="chat-title">{{ title }}</h1>
    <span class="chat-status">{{ status }}</span>
    <button
      class="icon-button"
      :class="{ 'is-on': panelOpen }"
      :aria-expanded="panelOpen"
      :aria-label="uiText('Execution details')"
      :title="uiText('Execution details')"
      @click="emit('toggle-panel')"
    >
      <AppIcon name="panel" :size="18" />
    </button>
  </div>
</template>
<style scoped>
.chat-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 17px;
  font-weight: 650;
}
.chat-status {
  font-size: 12px;
  color: var(--muted-foreground);
}
.icon-button.is-on {
  background: var(--card);
  color: var(--primary);
  box-shadow: 0 0 0 1px var(--border);
}
.history-toggle {
  display: none;
}
@media (max-width: 1000px) {
  .history-toggle {
    display: inline-flex;
  }
}
@media (max-width: 600px) {
  .chat-status {
    display: none;
  }
}
</style>
