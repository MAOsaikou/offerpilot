<script setup lang="ts">
import { computed } from 'vue'
import CardFallback from './CardFallback.vue'
import { resolveCardComponent } from './registry'
import type { PatchDecision } from '@/shared/types/optimization'

const props = defineProps<{
  cardType: string
  payload: unknown
  interactive?: boolean
  disabled?: boolean
  appliedVersionId?: string | null
}>()

const emit = defineEmits<{ apply: [decision: PatchDecision] }>()

const component = computed(() => resolveCardComponent(props.cardType))
</script>

<template>
  <div class="card-reveal">
    <component
      :is="component"
      v-if="component"
      :payload="payload"
      v-bind="cardType === 'resume_diff' ? { interactive, disabled, appliedVersionId } : {}"
      @apply="emit('apply', $event)"
    />
    <CardFallback v-else :card-type="cardType" :payload="payload" />
  </div>
</template>
