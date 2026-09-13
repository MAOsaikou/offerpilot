<script setup lang="ts">
import { RouterLink } from 'vue-router'
import AppIcon from '@/shared/components/AppIcon.vue'
</script>
<template>
  <section class="auth-local">
    <span class="brand-mark" style="margin: auto"><AppIcon /></span>
    <h1>欢迎来到 OfferPilot</h1>
    <p class="subtle">从了解自己，到找到下一站。<br />进入本地工作区，开始整理你的求职旅程。</p>
    <RouterLink to="/" class="btn primary">进入本地工作区 →</RouterLink>
    <p class="subtle" style="margin-top: 22px">
      当前未接入账号认证。业务内容只保存在此浏览器，AI 对话连接配置的后端。
    </p>
  </section>
</template>
