<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { debugRetrieval, type RetrievalDebugResult } from '@/shared/api/retrieval-debug'
import { identitySignal } from '@/shared/utils/authSession'
const session = identitySignal()
const query = ref('')
const limit = ref(10)
const busy = ref(false)
const error = ref('')
const result = ref<RetrievalDebugResult | null>(null)
const score = (value: number | null) => (value === null ? '未命中' : value.toFixed(6))
async function search() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  result.value = null
  try {
    const value = await debugRetrieval(query.value, limit.value)
    session.throwIfAborted()
    result.value = value
  } catch {
    if (!session.aborted) error.value = '检索失败，请确认管理员权限、后端连接及查询范围后重试。'
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main class="page-stack mx-auto max-w-5xl p-4 sm:p-8">
    <RouterLink to="/staff" class="text-primary">返回管理工作台</RouterLink>
    <h1>管理员检索调试</h1>
    <p class="subtle">
      仅检索公开职业库。向量只使用本地 bge-m3；召回分数为
      RRF，重排分数为查询词覆盖率，不是模型置信度。
    </p>
    <form class="panel form-stack" @submit.prevent="search">
      <label class="field"
        >查询文字<input v-model="query" required maxlength="2000" :disabled="busy"
      /></label>
      <label class="field"
        >返回条数<input
          v-model.number="limit"
          type="number"
          min="1"
          max="50"
          required
          :disabled="busy"
      /></label>
      <button class="btn primary" :disabled="busy">{{ busy ? '检索中…' : '检索' }}</button>
    </form>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <template v-if="result">
      <p role="status">{{ result.notice }}</p>
      <p class="subtle">
        Embedding：{{ result.embedding }} · 向量{{ result.vectorAvailable ? '可用' : '不可用' }} ·
        重排：{{ result.reranker }}
      </p>
      <p v-if="!result.items.length">没有召回结果，请调整查询。</p>
      <article v-for="hit in result.items" :key="hit.id" class="panel form-stack min-w-0">
        <h2>{{ hit.rank }}. {{ hit.name }}</h2>
        <dl class="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
          <div>
            <dt>召回分数（RRF）</dt>
            <dd>{{ score(hit.recallScore) }} · 原排名 {{ hit.recallRank }}</dd>
          </div>
          <div>
            <dt>重排分数（词覆盖率）</dt>
            <dd>{{ score(hit.rerankScore) }}</dd>
          </div>
          <div>
            <dt>全文分数 / 排名</dt>
            <dd>{{ score(hit.lexicalScore) }} / {{ hit.lexicalRank ?? '—' }}</dd>
          </div>
          <div>
            <dt>向量余弦相似度 / 排名</dt>
            <dd>{{ score(hit.vectorScore) }} / {{ hit.vectorRank ?? '—' }}</dd>
          </div>
        </dl>
        <p class="whitespace-pre-wrap break-words">{{ hit.excerpt }}</p>
      </article>
    </template>
  </main>
</template>
