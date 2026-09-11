<script setup lang="ts">
import { RouterView } from 'vue-router'
import AuroraBackground from '@/shared/components/AuroraBackground.vue'
</script>

<template>
  <div class="relative min-h-screen">
    <AuroraBackground />

    <RouterView v-slot="{ Component }">
      <Transition name="page" mode="out-in">
        <component :is="Component" />
      </Transition>
    </RouterView>
  </div>
</template>
