<script setup lang="ts">
import { computed } from 'vue'
import CardFallback from './CardFallback.vue'
import { resolveCardComponent } from './registry'

const props = defineProps<{
  cardType: string
  payload: unknown
}>()

const component = computed(() => resolveCardComponent(props.cardType))
</script>

<template>
  <div class="card-reveal">
    <component :is="component" v-if="component" :payload="payload" />
    <CardFallback v-else :card-type="cardType" :payload="payload" />
  </div>
</template>
