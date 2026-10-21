<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { asPayload } from '@/shared/utils/payload'
import type { InterviewerStatePayload } from '@/shared/types/cards'
const props = defineProps<{ payload: unknown }>()
const data = computed(() => asPayload<InterviewerStatePayload>(props.payload, 'state'))
</script>
<template>
  <article v-if="data" class="surface space-y-2 rounded-[14px] p-4 text-sm">
    <h3 class="font-semibold">
      {{ uiText('Text mock interview · {0} / {1}', [data.questionIndex + 1, data.total]) }}
    </h3>
    <p class="break-words">{{ data.question }}</p>
    <p class="text-xs text-muted-foreground">{{ data.note }}</p>
    <RouterLink v-if="data.sessionId" :to="'/interviews/' + data.sessionId" class="btn">{{
      uiText('Open interview room')
    }}</RouterLink>
    <p class="text-xs text-muted-foreground">
      {{
        uiText(
          'Answer in chat. You can ask for the next question, interview feedback or to end the interview. If you have multiple interviews, enter chat from the corresponding interview room.',
        )
      }}
    </p>
  </article>
  <p v-else class="text-sm text-muted-foreground">
    {{ uiText('Interview questions are unavailable. Please retry.') }}
  </p>
</template>
