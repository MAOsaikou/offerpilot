<script setup lang="ts">
import { formatDisplay, translate as uiText } from '@/shared/utils/language'

import { computed } from 'vue'
import CareerCardFacts from '@/shared/components/CareerCardFacts.vue'
import { RouterLink } from 'vue-router'
import type { CareerRecommendationPayload, CareerTargetSelection } from '@/shared/types/cards'

const props = defineProps<{
  payload: CareerRecommendationPayload
  interactive?: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ adoptTarget: [selection: CareerTargetSelection] }>()
const valid = computed(
  () =>
    Array.isArray(props.payload?.recommendations) && Array.isArray(props.payload?.interestsUsed),
)
</script>

<template>
  <section
    v-if="valid"
    class="min-w-0 rounded-xl border border-border bg-card p-4"
    :aria-label="uiText('Career recommendations')"
  >
    <h3 class="font-semibold">
      {{ uiText('Career interest recommendations · {0}', [payload.hollandCode]) }}
    </h3>
    <p class="mt-1 text-sm text-muted-foreground">
      {{
        uiText(
          '{0}. Recommendations are for exploration. Setting a goal still requires approval.',
          [
            payload.interestsUsed.includes(uiText('Conversational assessment'))
              ? uiText('Based on your confirmed assessment profile (interest code:') +
                payload.hollandCode +
                '）'
              : uiText('Based on saved interests:') + payload.interestsUsed.join('、'),
          ],
        )
      }}
    </p>
    <p v-if="!payload.recommendations.length" class="mt-3 text-sm" role="status">
      {{ uiText('No matching careers. Adjust your interests on the discovery page and retry.') }}
    </p>
    <RouterLink
      v-if="payload.revisionId"
      :to="{ path: '/career', query: { revision: payload.revisionId } }"
      class="btn"
      >{{ uiText('Open these recommendations and comparison') }}</RouterLink
    >
    <p
      v-if="payload.recommendations.length && payload.ordering === 'interest'"
      class="mt-2 text-sm text-muted-foreground"
    >
      {{
        uiText(
          'Candidates combine occupation text and interest recall, ordered by interest match (descending). Interest match = (Pearson r + 1) / 2.',
        )
      }}
    </p>
    <ol v-if="payload.recommendations.length" class="mt-3 space-y-3">
      <li
        v-for="(item, index) in payload.recommendations"
        :key="item.occupationId"
        class="min-w-0 rounded-lg border border-border p-3"
      >
        <div class="flex flex-wrap items-center gap-2">
          <h4 class="break-words font-medium">{{ formatDisplay(index + 1) }}. {{ item.name }}</h4>
          <span class="text-xs text-muted-foreground"
            >{{ item.family }} · {{ item.hollandCode }}</span
          >
        </div>
        <p v-if="item.rankingScore != null" class="mt-2 text-sm">
          {{
            uiText(
              'Combined ranking score: {0} (initial rank score + approved research/salary constraint adjustments; not a probability)',
              [item.rankingScore.toFixed(4)],
            )
          }}
        </p>
        <p v-if="item.rankDelta != null" class="mt-2 text-sm">
          {{
            item.rankDelta > 0
              ? uiText('↑ Moved up') + item.rankDelta
              : item.rankDelta < 0
                ? uiText('↓ Moved down') + Math.abs(item.rankDelta)
                : uiText('Rank unchanged')
          }}
        </p>
        <ul class="mt-2 space-y-1 text-sm text-muted-foreground">
          <li v-for="reason in item.reasons" :key="reason" class="break-words">{{ reason }}</li>
        </ul>
        <p
          v-for="citation in item.citations ?? []"
          :key="citation.occupationId"
          class="mt-2 break-words text-xs text-muted-foreground"
        >
          {{ uiText('Source: {0} · {1}', [citation.title, citation.excerpt]) }}
        </p>
        <CareerCardFacts :details="item.details" :match="item.similarity" />
        <button
          v-if="interactive"
          type="button"
          class="mt-3 rounded border border-primary px-3 py-2 text-sm text-primary disabled:opacity-50"
          :disabled="disabled"
          :aria-label="uiText('Set {0} as goal', [item.name])"
          @click="emit('adoptTarget', { occupationId: item.occupationId, name: item.name })"
        >
          {{ uiText('Set as goal') }}
        </button>
      </li>
    </ol>
  </section>
  <p v-else role="alert">
    {{ uiText('Career recommendation data is unavailable. Please search again.') }}
  </p>
</template>
