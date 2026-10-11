<script setup lang="ts">
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
  <aside v-if="auth.user?.isDemo" class="demo-banner" aria-label="体验账号信息">
    <template v-if="sandbox">
      <strong>独立体验沙箱</strong> · 剩余 {{ remainingMinutes }} 分钟
      <span v-if="query.data.value">
        · 今日剩余 {{ query.data.value.remainingTokens.toLocaleString() }} /
        {{ query.data.value.dailyTokenLimit.toLocaleString() }} tokens（UTC 零点恢复）
      </span>
      <span v-else-if="query.isError.value"> · 额度暂时无法读取</span>
      <span v-else> · 正在读取额度…</span>
      <p>到期自动删除。请勿填写个人信息。</p>
    </template>
    <template v-else>
      <strong>共享体验账号</strong> · 虚构数据会定期重置，请勿填写个人信息。
    </template>
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
