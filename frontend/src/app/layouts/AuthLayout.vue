<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <div class="flex min-h-screen items-center justify-center px-4">
    <div class="glass-panel rise-in w-full max-w-sm rounded-2xl p-8 shadow-2xl">
      <RouterView />
    </div>
  </div>
</template>
