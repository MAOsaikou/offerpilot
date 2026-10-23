<script setup lang="ts">
import {
  formatNumber,
  formatDateTime,
  localizedText,
  translate as uiText,
} from '@/shared/utils/language'

import { onBeforeUnmount, ref } from 'vue'
import {
  getOpsStatus,
  getCleanupReceipts,
  type OpsStatus,
  type CleanupReceipt,
} from '@/shared/api/ops'
import { identitySignal } from '@/shared/utils/authSession'

const status = ref<OpsStatus>()
const receipts = ref<CleanupReceipt[]>([])
const loading = ref(false)
const error = localizedText()
const session = identitySignal()
let mounted = true
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const [next, cleanup] = await Promise.all([getOpsStatus(), getCleanupReceipts()])
    if (!mounted || session.aborted) return
    status.value = next
    receipts.value = cleanup
  } catch (ex) {
    if (mounted && !session.aborted)
      error.value = () =>
        ex instanceof Error ? ex.message : uiText('Could not load status. Please retry.')
  } finally {
    loading.value = false
  }
}
onBeforeUnmount(() => {
  mounted = false
})
</script>

<template>
  <section class="ops" :aria-label="uiText('Service status')" :aria-busy="loading">
    <h2>{{ uiText('Service status') }}</h2>
    <button class="btn" :disabled="loading" @click="load">
      {{ loading ? uiText('Loading…') : uiText('Refresh service status') }}
    </button>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <template v-if="status">
      <p role="status">
        {{ uiText('{0} · Uptime: {1} seconds', [status.state, status.uptimeSeconds]) }}
      </p>
      <p>
        {{
          uiText('Last 5 minutes: {0} requests, {1} server errors ({2}%)', [
            status.http.requests,
            status.http.errors,
            formatNumber(status.http.errorRate * 100, {
              minimumFractionDigits: 1,
              maximumFractionDigits: 1,
            }),
          ])
        }}
      </p>
      <p v-if="status.usage">
        {{
          uiText(
            'Last hour: {0} model calls, {1} tokens, {2} failures, {3} calls without usage data',
            [
              status.usage.calls,
              status.usage.promptTokens + status.usage.completionTokens,
              status.usage.failed,
              status.usage.unreported,
            ],
          )
        }}
      </p>
      <p v-else>{{ uiText('Model usage is currently unavailable') }}</p>
      <p>
        {{
          uiText('Alerts: {0} · External notifications: {1}', [
            status.alerts.length ? status.alerts.join('、') : uiText('None'),
            status.externalPushEnabled ? uiText('On') : uiText('Off'),
          ])
        }}
      </p>
      <p class="subtle">
        {{
          uiText("Checked: {0}. Status follows the server's check schedule.", [
            formatDateTime(status.checkedAt),
          ])
        }}
      </p>
      <p>
        {{
          uiText('Output moderation matches: {0}', [
            Object.entries(status.moderationHits ?? {})
              .map(([category, count]) => `${category} ${count}`)
              .join('、') || '0',
          ])
        }}
      </p>
      <h3>{{ uiText('Background cleanup receipts') }}</h3>
      <p v-if="!receipts.length">{{ uiText('No pending cleanup receipts.') }}</p>
      <ul v-else>
        <li v-for="receipt in receipts" :key="receipt.id">
          {{
            uiText('{0} · {1} · {2} retries · Next: {3}', [
              receipt.id,
              receipt.kind,
              receipt.attempts,
              receipt.next_attempt_at,
            ])
          }}
        </li>
      </ul>
    </template>
    <p v-else-if="!loading && !error" class="subtle">
      {{ uiText('Refresh to view service status and background cleanup progress.') }}
    </p>
  </section>
</template>

<style scoped>
.ops {
  min-width: 0;
  background: var(--card);
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow-wrap: anywhere;
}
.ops p {
  margin-block: 12px;
}
</style>
