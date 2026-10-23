<script setup lang="ts">
import { computed } from 'vue'
import { translate as uiText } from '@/shared/utils/language'
import type { PresenceStatus, PresenceValues } from '@/shared/types/presence'
defineProps<{
  status: PresenceStatus
  values?: Partial<PresenceValues> | null
  starting?: boolean
  uploadFailed?: boolean
  note?: string
}>()
const gauges = computed(
  () =>
    [
      { key: 'eyeContact', label: uiText('Eye contact estimate') },
      { key: 'smile', label: uiText('Smile estimate') },
      { key: 'headStability', label: uiText('Head stability') },
      { key: 'postureStability', label: uiText('Posture stability') },
    ] as const,
)
</script>
<template>
  <section class="panel form-stack presence-gauges" :aria-label="uiText('On-device analysis')">
    <h3>{{ uiText('On-device analysis') }}</h3>
    <p class="helper-note">{{ uiText('Video is never uploaded') }}</p>
    <p v-if="starting" role="status">{{ uiText('Starting camera analysis…') }}</p>
    <p v-else-if="status !== 'running'" role="status">{{ uiText('On-device analysis skipped') }}</p>
    <template v-else>
      <p v-if="!values" role="status">{{ uiText('Waiting for face and shoulders in view') }}</p>
      <div class="presence-grid">
        <div v-for="gauge in gauges" :key="gauge.key">
          <div>
            {{ gauge.label }} ·
            {{
              values?.[gauge.key] === undefined ? '—' : `${Math.round(values[gauge.key]! * 100)}%`
            }}
          </div>
          <progress
            v-if="values?.[gauge.key] !== undefined"
            :value="values[gauge.key]"
            max="1"
            :aria-label="gauge.label"
          />
        </div>
      </div>
    </template>
    <p v-if="uploadFailed" class="helper-note" role="status">
      {{ uiText('Analysis upload failed; retrying with the next window') }}
    </p>
    <p class="helper-note">{{ note || uiText('Browser estimates are for reference only') }}</p>
  </section>
</template>
<style scoped>
.presence-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 180px), 1fr));
  gap: 12px;
}
progress {
  width: 100%;
  accent-color: var(--primary);
}
.presence-gauges {
  overflow-wrap: anywhere;
}
</style>
