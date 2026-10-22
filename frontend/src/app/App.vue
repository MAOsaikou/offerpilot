<script setup lang="ts">
import { watch } from 'vue'
import { RouterView } from 'vue-router'
import { useAuthStore } from '@/shared/stores/auth'
import { getPreferences } from '@/shared/api/preferences'
import { getStoredGuest, getStoredToken } from '@/shared/utils/authSession'
import { setLanguage } from '@/shared/utils/language'
import { setTheme } from '@/shared/utils/theme'
const auth = useAuthStore()
// AC-03: a theme saved on the account follows the user to this browser.
watch(
  () => auth.identityKey,
  (identity) => {
    setLanguage('zh-CN')
    if (!getStoredToken() && !getStoredGuest()) return
    getPreferences()
      .then((preferences) => {
        if (auth.identityKey !== identity) return
        setLanguage(preferences.language)
        if (preferences.saved) setTheme(preferences.theme)
      })
      .catch(() => undefined)
  },
  { immediate: true },
)
</script>

<template>
  <RouterView :key="auth.identityKey" v-slot="{ Component }">
    <component :is="Component" v-if="Component" :key="auth.identityKey" />
  </RouterView>
</template>
