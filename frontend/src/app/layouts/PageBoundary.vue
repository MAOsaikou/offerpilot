<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { onErrorCaptured, ref, watch } from 'vue'

const props = defineProps<{ resetKey: string }>()
const failed = ref(false)

onErrorCaptured(() => {
  failed.value = true
  return false
})
watch(
  () => props.resetKey,
  () => {
    failed.value = false
  },
)
</script>

<template>
  <div class="route-page">
    <section v-if="failed" class="panel page-stack" role="alert">
      <h1>{{ uiText('This page could not be displayed') }}</h1>
      <p>{{ uiText('Try again or open another page using the navigation.') }}</p>
      <div>
        <button class="btn" type="button" @click="failed = false">
          {{ uiText('Reload page') }}
        </button>
      </div>
    </section>
    <slot v-else />
  </div>
</template>
