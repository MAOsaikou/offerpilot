<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <div class="flex min-h-screen items-center justify-center bg-muted px-4">
    <div class="w-full max-w-sm rounded-xl border border-border bg-card p-8 shadow-sm">
      <RouterView />
    </div>
  </div>
</template>
