<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { computed } from 'vue'
import type { InterviewTurnScorePayload } from '@/shared/types/cards'
const props = defineProps<{ payload: InterviewTurnScorePayload }>()
const valid = computed(
  () =>
    props.payload &&
    Number.isInteger(props.payload.turnIndex) &&
    typeof props.payload.brief === 'string' &&
    (props.payload.score === null ||
      (Number.isFinite(props.payload.score) &&
        props.payload.score >= 0 &&
        props.payload.score <= 100)),
)
</script>
<template>
  <section
    class="panel form-stack min-w-0 break-words"
    :aria-label="uiText('Single-question interview score')"
  >
    <h3>{{ uiText('Single-question interview score') }}</h3>
    <p v-if="!valid" role="alert">
      {{ uiText('Score data is unavailable. Please request scoring again.') }}
    </p>
    <template v-else
      ><p>
        {{
          uiText('Question {0} · {1} / 100', [
            payload.turnIndex + 1,
            payload.score ?? uiText('Not scored'),
          ])
        }}
      </p>
      <p>{{ payload.brief || uiText('No scoring explanation yet') }}</p>
    </template>
  </section>
</template>
