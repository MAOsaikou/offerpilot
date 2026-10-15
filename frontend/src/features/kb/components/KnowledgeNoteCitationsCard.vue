<script setup lang="ts">
import { ref, watch } from 'vue'
import { getKnowledgeNote, type KnowledgeNote } from '@/shared/api/knowledge'
import { sessionRevision } from '@/shared/utils/authSession'
import type { KnowledgeNoteCitationsPayload } from '@/shared/types/cards'

const props = defineProps<{ payload: KnowledgeNoteCitationsPayload }>()
const original = ref<KnowledgeNote | null>(null)
const loading = ref<string | null>(null)
const error = ref('')
let request = 0
watch([sessionRevision, () => props.payload], () => {
  request++
  original.value = null
  loading.value = null
  error.value = ''
})
async function open(id: string) {
  const current = ++request
  original.value = null
  error.value = ''
  loading.value = id
  try {
    const note = await getKnowledgeNote(id)
    if (current === request) original.value = note
  } catch (cause) {
    if (current === request)
      error.value = cause instanceof Error ? cause.message : '无法读取当前原文，请重试。'
  } finally {
    if (current === request) loading.value = null
  }
}
</script>
<template>
  <section class="panel form-stack min-w-0 break-words" aria-label="知识笔记来源">
    <h3>知识笔记来源 · {{ payload.count }} 条</h3>
    <p class="helper-note">
      本轮检索到的个人笔记，仅供参考。摘录随回复保存，原文可能已修改或删除。
    </p>
    <article
      v-for="item in payload.items"
      :key="item.noteId"
      class="form-stack rounded-lg border p-3"
      :data-kb-reference="item.reference"
      tabindex="-1"
    >
      <h4>{{ item.title }}</h4>
      <p class="subtle">[{{ item.reference }}] · {{ item.category }}</p>
      <p class="whitespace-pre-wrap">{{ item.excerpt }}</p>
      <button type="button" class="btn" :disabled="!!loading" @click="open(item.noteId)">
        {{ loading === item.noteId ? '正在读取…' : '查看当前原文' }}
      </button>
    </article>
    <p v-if="error" class="error-banner" role="alert">{{ error }} 本轮引用摘录仍可查看。</p>
    <section v-if="original" class="form-stack" aria-label="笔记当前原文">
      <h4>{{ original.title }} · 当前原文</h4>
      <p class="whitespace-pre-wrap">{{ original.content }}</p>
      <button type="button" class="btn" @click="original = null">收起原文</button>
    </section>
  </section>
</template>
