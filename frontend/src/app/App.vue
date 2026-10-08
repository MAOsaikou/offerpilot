<script setup lang="ts">
import { RouterView } from 'vue-router'
import { useAuthStore } from '@/shared/stores/auth'
const auth = useAuthStore()
</script>

<template>
  <RouterView :key="auth.identityKey" v-slot="{ Component }">
    <Transition name="page" mode="out-in">
      <component :is="Component" />
    </Transition>
  </RouterView>
</template>
