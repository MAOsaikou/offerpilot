<script setup lang="ts">
import { computed } from 'vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
const workspace = useWorkspace()
const unread = computed(() => workspace.state.notices.filter((n) => !n.read).length)
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="你的每一步，都有回响"
      eyebrow="ACTIVITY & NOTIFICATIONS"
      :description="unread + ' 条未读 · 本地操作记录'"
      ><button
        class="btn"
        :disabled="!unread"
        @click="workspace.state.notices.forEach((n) => (n.read = true))"
      >
        全部标为已读
      </button></PageHeading
    >
    <section class="panel">
      <div v-if="!workspace.state.notices.length" class="empty-state">
        <AppIcon name="bell" :size="40" />
        <h2>现在一切安静</h2>
        <p>收藏岗位、保存简历和完成练习后，操作记录会出现在这里。</p>
      </div>
      <article
        v-for="notice in workspace.state.notices"
        :key="notice.id"
        class="notice-item"
        :class="{ 'is-read': notice.read }"
      >
        <span class="stat-icon"><AppIcon name="check" /></span>
        <div>
          <p>{{ notice.text }}</p>
          <p class="subtle">{{ notice.date }}</p>
        </div>
        <button v-if="!notice.read" class="btn text" @click="notice.read = true">标为已读</button
        ><span v-else class="tag">已读</span>
      </article>
    </section>
  </section>
</template>
