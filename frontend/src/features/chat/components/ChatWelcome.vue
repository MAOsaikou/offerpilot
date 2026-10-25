<script setup lang="ts">
import { computed } from 'vue'

import { translate as uiText } from '@/shared/utils/language'
import AppIcon from '@/shared/components/AppIcon.vue'
import BrandMark from '@/shared/components/BrandMark.vue'

const emit = defineEmits<{ pick: [text: string] }>()
const suggestions = computed(() => [
  { icon: 'discover', text: uiText('Help me explore my career interests and direction') },
  {
    icon: 'resumes',
    text: uiText('How can I improve project experience descriptions on my resume?'),
  },
  { icon: 'interviews', text: uiText('Help me prepare an introduction for a technical interview') },
  { icon: 'board', text: uiText('How should I plan a week of job searching?') },
])
</script>
<template>
  <div class="chat-welcome">
    <span class="welcome-mark stagger-in" aria-hidden="true"><BrandMark :size="28" /></span>
    <h2 class="stagger-in">{{ uiText("Let's figure out your next step.") }}</h2>
    <p class="subtle stagger-in" style="--i: 1">
      {{ uiText('Describe a specific challenge and start with one small goal.') }}
    </p>
    <div class="suggestion-grid">
      <button
        v-for="(item, index) in suggestions"
        :key="item.text"
        class="stagger-in"
        :style="{ '--i': index + 2 }"
        @click="emit('pick', item.text)"
      >
        <span class="suggestion-icon" aria-hidden="true"
          ><AppIcon :name="item.icon" :size="16"
        /></span>
        <span class="suggestion-text">{{ item.text }}</span>
      </button>
    </div>
    <p class="subtle">
      {{
        uiText(
          'Ask follow-up questions freely. This conversation uses recently completed exchanges; a new conversation starts independently.',
        )
      }}
    </p>
  </div>
</template>
<style scoped>
.welcome-mark {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  margin-bottom: 4px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--card);
  color: var(--foreground);
}
.welcome-mark :deep(path) {
  stroke-dasharray: 32;
  animation: welcome-draw 700ms var(--ease-out) 160ms backwards;
}
@keyframes welcome-draw {
  from {
    stroke-dashoffset: 32;
  }
}
.suggestion-icon {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  border-radius: var(--radius-control);
  background: var(--muted);
  color: var(--muted-foreground);
  transition:
    background-color var(--dur-mid) var(--ease-out),
    color var(--dur-mid) var(--ease-out);
}
.suggestion-text {
  flex: 1;
}
.suggestion-grid button:hover .suggestion-icon {
  background: var(--primary-tint);
  color: var(--primary);
}
</style>
