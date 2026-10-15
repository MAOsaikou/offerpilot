<script setup lang="ts">
defineProps<{ source?: string; snapshotDate?: string; cached?: boolean }>()
</script>

<template>
  <span
    v-if="source === 'LOCAL_SNAPSHOT'"
    class="tag"
    :title="`快照日期：${snapshotDate || '未提供'}；人工整理的示例岗位快照，非实时数据`"
    >离线快照</span
  >
  <span
    v-else-if="source === 'STORED_SNAPSHOT'"
    class="tag"
    title="实时详情暂不可用，展示已保存的企业岗位；可能已调整或关闭"
    >已存岗位 · 实时更新不可用</span
  >
  <span v-else-if="source === 'demo'" class="tag" title="虚构体验岗位，不接受实际投递"
    >演示岗位</span
  >
  <span
    v-else-if="source === 'USER_IMPORT'"
    class="tag"
    title="你粘贴或从网址导入的岗位，只有你可见；内容以原招聘页为准"
    >我导入的岗位</span
  >
  <span v-else class="tag">企业官网 · {{ cached ? '近期缓存' : '本次查询' }}</span>
</template>
