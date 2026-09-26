<script setup lang="ts">
import { RouterLink } from 'vue-router'
import BrandMark from '@/shared/components/BrandMark.vue'
const stops = [
  { x: 40, y: 250, label: '了解自己' },
  { x: 170, y: 190, label: '打磨简历' },
  { x: 300, y: 150, label: '投递岗位' },
  { x: 430, y: 60, label: '拿到 Offer' },
]
</script>
<template>
  <section class="login">
    <div class="auth-local">
      <BrandMark :size="44" />
      <h1>欢迎来到 OfferPilot</h1>
      <p class="subtle">从了解自己，到找到下一站。进入本地工作区，开始整理你的求职旅程。</p>
      <RouterLink to="/" class="btn primary self-start">进入本地工作区</RouterLink>
      <p class="subtle text-sm">
        当前未接入账号认证。业务内容只保存在这个浏览器，AI 对话连接配置的后端。
      </p>
    </div>
    <svg class="login-route" viewBox="0 0 470 300" fill="none" aria-hidden="true">
      <path
        class="login-route-line"
        d="M40 250 C 110 250, 120 190, 170 190 S 250 150, 300 150 S 390 60, 430 60"
        pathLength="1"
      />
      <g
        v-for="(stop, index) in stops"
        :key="stop.label"
        class="login-stop"
        :style="{ '--i': index }"
      >
        <circle
          :cx="stop.x"
          :cy="stop.y"
          :r="index === stops.length - 1 ? 7 : 5"
          :class="index === stops.length - 1 ? 'is-end' : ''"
        />
        <text :x="stop.x" :y="stop.y + 28" text-anchor="middle">{{ stop.label }}</text>
      </g>
    </svg>
  </section>
</template>
<style scoped>
.login {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr);
  align-items: center;
  gap: 48px;
}
.auth-local {
  max-width: 26rem;
}
.login-route {
  width: 100%;
  height: auto;
  overflow: visible;
}
.login-route-line {
  stroke: var(--primary);
  stroke-width: 2.5;
  stroke-linecap: round;
  stroke-dasharray: 1;
  stroke-dashoffset: 0;
  animation: draw 900ms var(--ease-out) 150ms both;
}
@keyframes draw {
  from {
    stroke-dashoffset: 1;
  }
}
.login-stop {
  animation: rise 240ms var(--ease-out) both;
  animation-delay: calc(200ms + var(--i) * 180ms);
}
.login-stop circle {
  fill: var(--background);
  stroke: var(--foreground);
  stroke-width: 2;
}
.login-stop circle.is-end {
  fill: var(--foreground);
}
.login-stop text {
  fill: var(--muted-foreground);
  font-size: 13px;
  font-family: var(--app-font-sans);
}
@media (max-width: 760px) {
  .login {
    grid-template-columns: 1fr;
  }
  .login-route {
    max-width: 420px;
  }
}
</style>
