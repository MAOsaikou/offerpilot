<script setup lang="ts">
import { RouterView } from 'vue-router'
import { useAuthStore } from '@/shared/stores/auth'
const auth = useAuthStore()
</script>

<template>
  <RouterView :key="auth.identityKey" v-slot="{ Component }">
    <component :is="Component" v-if="Component" :key="auth.identityKey" />
  </RouterView>
</template>
