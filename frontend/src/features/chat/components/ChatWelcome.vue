<script setup lang="ts">
import { computed } from 'vue'

import { translate as uiText } from '@/shared/utils/language'

const emit = defineEmits<{ pick: [text: string] }>()
const suggestions = computed(() => [
  uiText('Help me explore my career interests and direction'),
  uiText('How can I improve project experience descriptions on my resume?'),
  uiText('Help me prepare an introduction for a technical interview'),
  uiText('How should I plan a week of job searching?'),
])
</script>
<template>
  <div class="chat-welcome">
    <h2>{{ uiText("Let's figure out your next step.") }}</h2>
    <p class="subtle">
      {{ uiText('Describe a specific challenge and start with one small goal.') }}
    </p>
    <div class="suggestion-grid">
      <button
        v-for="(text, index) in suggestions"
        :key="text"
        class="stagger-in"
        :style="{ '--i': index + 1 }"
        @click="emit('pick', text)"
      >
        {{ text }}
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
