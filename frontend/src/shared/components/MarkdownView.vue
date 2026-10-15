<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/shared/utils/markdown'

const props = defineProps<{
  text: string
  /** 流式输出中：补全未闭合代码块并显示光标 */
  streaming?: boolean
}>()

const emit = defineEmits<{ cite: [reference: string] }>()

const html = computed(() => renderMarkdown(props.text, { streaming: props.streaming }))
function onClick(event: MouseEvent) {
  const target = (event.target as HTMLElement | null)?.closest<HTMLElement>('[data-kb-reference]')
  if (target?.dataset.kbReference) emit('cite', target.dataset.kbReference)
}
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -- 内容已经过 DOMPurify 消毒 -->
  <div class="markdown-body" :class="{ streaming }" @click="onClick" v-html="html" />
</template>
