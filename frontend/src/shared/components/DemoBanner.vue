<script setup lang="ts">
import { language, translate as uiText } from '@/shared/utils/language'

import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useAuthStore } from '@/shared/stores/auth'
import { getDemoSandboxStatus } from '@/shared/api/auth'

const auth = useAuthStore()
const sandbox = computed(() => Boolean(auth.user?.isDemo && auth.user?.demoExpiresAt))
const query = useQuery({
  queryKey: computed(() => ['demo-sandbox', auth.identityKey]),
  queryFn: ({ signal }) => getDemoSandboxStatus(signal),
  enabled: sandbox,
  refetchInterval: 30_000,
  retry: false,
})
const remainingMinutes = computed(() => {
  const expiry = query.data.value?.expiresAt || auth.user?.demoExpiresAt
  // Query updates every 30s; use its server duration to avoid browser clock skew.
  return Math.ceil(
    (query.data.value?.remainingSeconds ??
      Math.max(0, (Date.parse(expiry || '') - Date.now()) / 1000)) / 60,
  )
})
</script>
<template>
  <aside v-if="auth.user?.isDemo" class="demo-banner" :aria-label="uiText('Demo account information')">
    <template v-if="sandbox">
      <strong>{{ uiText("Private demo sandbox") }}</strong> {{ uiText("· {0} minutes remaining", [remainingMinutes]) }} <span v-if="query.data.value"> {{ uiText("· {0} / {1} tokens left today (resets at midnight UTC)", [query.data.value.remainingTokens.toLocaleString(language), query.data.value.dailyTokenLimit.toLocaleString(language)]) }} </span>
      <span v-else-if="query.isError.value"> {{ uiText("· Could not load quota") }}</span>
      <span v-else> {{ uiText("· Loading quota…") }}</span>
      <p>{{ uiText("Automatically deleted on expiry. Do not enter personal information.") }}</p>
    </template>
    <template v-else>
      <strong>{{ uiText("Shared demo account") }}</strong> {{ uiText("· Fictional data resets periodically. Do not enter personal information.") }} </template>
  </aside>
</template>
<style scoped>
.demo-banner {
  padding: 12px 16px;
  margin-bottom: 16px;
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow-wrap: anywhere;
  font-size: 13px;
}
.demo-banner p {
  margin: 4px 0 0;
}
</style>
