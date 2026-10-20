<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/shared/utils/markdown'
import { highlightRevision } from '@/shared/utils/codeHighlight'

const props = defineProps<{
  text: string
  /** 流式输出中：补全未闭合代码块并显示光标 */
  streaming?: boolean
  citationIndexes?: number[]
}>()

const emit = defineEmits<{ cite: [reference: string] }>()

const html = computed(() => {
  // 语法高亮异步就绪后重新渲染
  void highlightRevision.value
  return renderMarkdown(props.text, {
    streaming: props.streaming,
    citationIndexes: props.citationIndexes,
  })
})
async function copyCode(button: HTMLElement) {
  const code = button.closest('.code-block')?.querySelector('pre')?.textContent ?? ''
  try {
    await navigator.clipboard.writeText(code)
    button.textContent = '已复制'
  } catch {
    button.textContent = '复制失败'
  }
  window.setTimeout(() => {
    button.textContent = '复制'
  }, 1500)
}
function onClick(event: MouseEvent) {
  const element = event.target as HTMLElement | null
  const copy = element?.closest<HTMLElement>('[data-copy-code]')
  if (copy) {
    void copyCode(copy)
    return
  }
  const numeric = element?.closest<HTMLElement>('button[data-citation-index]')
  if (numeric?.dataset.citationIndex) emit('cite', numeric.dataset.citationIndex)
  const target = element?.closest<HTMLElement>('[data-kb-reference]')
  if (target?.dataset.kbReference) emit('cite', target.dataset.kbReference)
}
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -- 内容已经过 DOMPurify 消毒 -->
  <div class="markdown-body" :class="{ streaming }" @click="onClick" v-html="html" />
</template>
