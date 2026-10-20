<script setup lang="ts">
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
const error = ref('')
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
      error.value = ex instanceof Error ? ex.message : '状态读取失败，请重试'
  } finally {
    loading.value = false
  }
}
onBeforeUnmount(() => {
  mounted = false
})
</script>

<template>
  <section class="ops" aria-label="运行状态" :aria-busy="loading">
    <h2>运行状态</h2>
    <button class="btn" :disabled="loading" @click="load">
      {{ loading ? '读取中…' : '刷新运行状态' }}
    </button>
    <p v-if="error" role="alert">{{ error }}</p>
    <template v-if="status">
      <p role="status">{{ status.state }} · 已运行 {{ status.uptimeSeconds }} 秒</p>
      <p>
        最近 5 分钟：{{ status.http.requests }} 次请求，{{ status.http.errors }} 次服务错误（{{
          (status.http.errorRate * 100).toFixed(1)
        }}%）
      </p>
      <p v-if="status.usage">
        最近 1 小时：{{ status.usage.calls }} 次模型调用，{{
          status.usage.promptTokens + status.usage.completionTokens
        }}
        tokens，{{ status.usage.failed }} 次失败，{{ status.usage.unreported }} 次用量未上报
      </p>
      <p v-else>模型用量暂不可用</p>
      <p>
        告警：{{ status.alerts.length ? status.alerts.join('、') : '无' }} · 外部推送{{
          status.externalPushEnabled ? '开启' : '关闭'
        }}
      </p>
      <p class="subtle">检查时间：{{ status.checkedAt }}。状态按服务端检查周期更新。</p>
      <p>
        输出审核命中：{{
          Object.entries(status.moderationHits ?? {})
            .map(([category, count]) => `${category} ${count}`)
            .join('、') || '0'
        }}
      </p>
      <h3>后台清理回执</h3>
      <p v-if="!receipts.length">没有待处理清理回执。</p>
      <ul v-else>
        <li v-for="receipt in receipts" :key="receipt.id">
          {{ receipt.id }} · {{ receipt.kind }} · 已重试 {{ receipt.attempts }} 次 · 下次
          {{ receipt.next_attempt_at }}
        </li>
      </ul>
    </template>
    <p v-else-if="!loading && !error" class="subtle">点击刷新查看运行状态与后台清理进度。</p>
  </section>
</template>

<style scoped>
.ops {
  margin-block: 24px;
  padding: 16px;
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow-wrap: anywhere;
}
.ops p {
  margin-block: 12px;
}
</style>
