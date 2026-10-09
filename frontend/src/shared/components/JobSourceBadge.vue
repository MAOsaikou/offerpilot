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
  <span v-else class="tag">企业官网 · {{ cached ? '近期缓存' : '本次查询' }}</span>
</template>
