<script setup lang="ts">
import { formatDisplay } from '@/shared/utils/language'
import { useCountUp } from '@/shared/composables/useCountUp'

const props = withDefaults(defineProps<{ value: number; duration?: number; delay?: number }>(), {
  duration: 700,
  delay: 0,
})
const shown = useCountUp(() => props.value, props.duration, props.delay)
</script>
<template>
  <span>{{ formatDisplay(shown ?? value) }}</span>
</template>
