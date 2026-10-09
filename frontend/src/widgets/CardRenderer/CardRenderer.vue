<script setup lang="ts">
import { computed } from 'vue'
import CardFallback from './CardFallback.vue'
import { resolveCardComponent } from './registry'
import type { PatchDecision } from '@/shared/types/optimization'
import type { CareerTargetSelection } from '@/shared/types/cards'

const props = defineProps<{
  cardType: string
  payload: unknown
  applyLabel?: string
  interactive?: boolean
  disabled?: boolean
  appliedVersionId?: string | null
}>()

const emit = defineEmits<{
  apply: [decision: PatchDecision]
  adoptGrowth: [taskTitles: string[]]
  adoptTarget: [selection: CareerTargetSelection]
}>()

const component = computed(() => resolveCardComponent(props.cardType))
</script>

<template>
  <div class="card-reveal">
    <component
      :is="component"
      v-if="component"
      :payload="payload"
      v-bind="
        cardType === 'resume_diff'
          ? { interactive, disabled, appliedVersionId, applyLabel }
          : cardType === 'career_recommendation' || cardType === 'roadmap'
            ? { interactive, disabled }
            : {}
      "
      @apply="emit('apply', $event)"
      @adopt-growth="emit('adoptGrowth', $event)"
      @adopt-target="emit('adoptTarget', $event)"
    />
    <CardFallback v-else :card-type="cardType" :payload="payload" />
  </div>
</template>
