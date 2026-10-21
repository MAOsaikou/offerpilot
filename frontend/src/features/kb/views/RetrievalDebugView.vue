<script setup lang="ts">
import { formatNumber, formatDisplay, localizedText, translate as uiText } from '@/shared/utils/language'

import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { debugRetrieval, type RetrievalDebugResult } from '@/shared/api/retrieval-debug'
import { identitySignal } from '@/shared/utils/authSession'
const session = identitySignal()
const query = ref('')
const limit = ref(10)
const busy = ref(false)
const error = localizedText()
const result = ref<RetrievalDebugResult | null>(null)
const score = (value: number | null) => (value === null ? uiText("No match") : formatNumber(value, { minimumFractionDigits: 6, maximumFractionDigits: 6 }))
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
    if (!session.aborted) error.value = () => (uiText("Search failed. Check administrator access, backend connection and query scope, then retry."))
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <main class="page-stack mx-auto max-w-5xl p-4 sm:p-8">
    <RouterLink to="/staff" class="text-primary">{{ uiText("Back to staff console") }}</RouterLink>
    <h1>{{ uiText("Administrator retrieval diagnostics") }}</h1>
    <p class="subtle"> {{ uiText("Searches only the public career catalog. Vectors use local bge-m3. Retrieval scores use RRF; reranking measures keyword coverage, not model confidence.") }} </p>
    <form class="panel form-stack" @submit.prevent="search">
      <label class="field"
        >{{ uiText("Query text") }}<input v-model="query" required maxlength="2000" :disabled="busy"
      /></label>
      <label class="field"
        >{{ uiText("Result limit") }}<input
          v-model.number="limit"
          type="number"
          min="1"
          max="50"
          required
          :disabled="busy"
      /></label>
      <button class="btn primary" :disabled="busy">{{ busy ? uiText("Searching…") : uiText("Search #2") }}</button>
    </form>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
    <template v-if="result">
      <p role="status">{{ result.notice }}</p>
      <p class="subtle"> {{ uiText("Embedding: {0} · Vectors: {1} · Reranking: {2}", [result.embedding, result.vectorAvailable ? uiText("Available") : uiText("Unavailable"), result.reranker]) }}
      </p>
      <p v-if="!result.items.length">{{ uiText("No retrieved results. Adjust your query.") }}</p>
      <article v-for="hit in result.items" :key="hit.id" class="panel form-stack min-w-0">
        <h2>{{ formatDisplay(hit.rank) }}. {{ hit.name }}</h2>
        <dl class="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
          <div>
            <dt>{{ uiText("Retrieval score (RRF)") }}</dt>
            <dd>{{ uiText("{0} · Original rank {1}", [score(hit.recallScore), hit.recallRank]) }}</dd>
          </div>
          <div>
            <dt>{{ uiText("Reranking score (keyword coverage)") }}</dt>
            <dd>{{ score(hit.rerankScore) }}</dd>
          </div>
          <div>
            <dt>{{ uiText("Full-text score / rank") }}</dt>
            <dd>{{ score(hit.lexicalScore) }} / {{ hit.lexicalRank ?? '—' }}</dd>
          </div>
          <div>
            <dt>{{ uiText("Vector cosine similarity / rank") }}</dt>
            <dd>{{ score(hit.vectorScore) }} / {{ hit.vectorRank ?? '—' }}</dd>
          </div>
        </dl>
        <p class="whitespace-pre-wrap break-words">{{ hit.excerpt }}</p>
      </article>
    </template>
  </main>
</template>
