<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { computed, onErrorCaptured, ref, watch } from 'vue'
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

const failed = ref(false)
const revision = ref(0)
onErrorCaptured(() => {
  failed.value = true
  return false
})
function retry() {
  revision.value++
  failed.value = false
}
watch(() => props.cardType, retry)
const component = computed(() => resolveCardComponent(props.cardType))
</script>

<template>
  <div class="card-reveal">
    <section
      v-if="failed"
      class="panel form-stack"
      role="alert"
      :aria-label="uiText('Could not load card')"
    >
      <p>
        {{
          uiText(
            'This card could not be displayed. You can still read the response and other cards.',
          )
        }}
      </p>
      <button type="button" class="btn" @click="retry">{{ uiText('Reload card') }}</button>
    </section>
    <component
      :is="component"
      v-else-if="component"
      :key="revision"
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
