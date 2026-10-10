<script setup lang="ts">
import { onErrorCaptured, ref, watch } from 'vue'

const props = defineProps<{ resetKey: string }>()
const failed = ref(false)

onErrorCaptured(() => {
  failed.value = true
  return false
})
watch(
  () => props.resetKey,
  () => {
    failed.value = false
  },
)
</script>

<template>
  <div class="route-page">
    <section v-if="failed" class="panel page-stack" role="alert">
      <h1>页面暂时无法显示</h1>
      <p>请重试，或通过导航打开其他页面。</p>
      <div><button class="btn" type="button" @click="failed = false">重试页面</button></div>
    </section>
    <slot v-else />
  </div>
</template>
