<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { chatImageUrl } from '@/shared/api/attachments'

/** 已发送消息里的图片缩略图：按需带登录头取回，卸载时释放 object URL */
const props = defineProps<{ fileId: string; name: string }>()
const url = ref('')
const failed = ref(false)
let revoked = false
watch(
  () => props.fileId,
  async (fileId) => {
    failed.value = false
    try {
      const next = await chatImageUrl(fileId)
      if (revoked) URL.revokeObjectURL(next)
      else {
        if (url.value) URL.revokeObjectURL(url.value)
        url.value = next
      }
    } catch {
      failed.value = true
    }
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  revoked = true
  if (url.value) URL.revokeObjectURL(url.value)
})
</script>

<template>
  <img v-if="url" class="chat-image" :src="url" :alt="name" />
  <span v-else class="chat-image placeholder" :title="name">{{
    failed ? '图片不可用' : '加载中'
  }}</span>
</template>

<style scoped>
.chat-image {
  width: 96px;
  height: 96px;
  border-radius: 10px;
  border: 1px solid var(--border);
  object-fit: cover;
  background: var(--muted);
}
.placeholder {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--muted-foreground);
}
</style>
