<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/shared/utils/markdown'

const props = defineProps<{
  text: string
  /** 流式输出中：补全未闭合代码块并显示光标 */
  streaming?: boolean
}>()

const html = computed(() => renderMarkdown(props.text, { streaming: props.streaming }))
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -- 内容已经过 DOMPurify 消毒 -->
  <div class="markdown-body" :class="{ streaming }" v-html="html" />
</template>
