<script setup lang="ts">
const emit = defineEmits<{ pick: [text: string] }>()
const suggestions = [
  '帮我梳理职业兴趣和方向',
  '如何写好简历中的项目经历？',
  '帮我准备技术面试的自我介绍',
  '如何安排一周的求职计划？',
]
</script>
<template>
  <div class="chat-welcome">
    <h2>下一步，我们一起想。</h2>
    <p class="subtle">描述你眼前的问题，从一个具体的小目标开始。</p>
    <div class="suggestion-grid">
      <button
        v-for="(text, index) in suggestions"
        :key="text"
        class="stagger-in"
        :style="{ '--i': index + 1 }"
        @click="emit('pick', text)"
      >
        {{ text }}
      </button>
    </div>
    <p class="subtle">支持连续追问。同一对话会参考最近完成的内容，新对话独立开始。</p>
  </div>
</template>
