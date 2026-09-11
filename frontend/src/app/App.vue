<script setup lang="ts">
import { RouterView } from 'vue-router'
import AuroraBackground from '@/shared/components/AuroraBackground.vue'
import StageCurtain from '@/shared/components/StageCurtain.vue'
</script>

<template>
  <div class="relative min-h-screen">
    <AuroraBackground />

    <RouterView v-slot="{ Component }">
      <Transition name="page" mode="out-in">
        <component :is="Component" />
      </Transition>
    </RouterView>

    <!-- 全局幕布：覆盖在布局之上，路由切换时落下再抽走 -->
    <StageCurtain />
  </div>
</template>
