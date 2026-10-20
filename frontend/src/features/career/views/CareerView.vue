<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import CareerRecommendations from '../components/CareerRecommendations.vue'
import TargetPlanPanel from '@/shared/components/TargetPlanPanel.vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import { learningTasks } from '@/shared/stores/workspace'
import { useCareerProfile } from '@/shared/composables/useCareerProfile'
const { profile, loading, ready, error, pending, saveTasks, retry } = useCareerProfile()
const progress = computed(() =>
  Math.round((profile.value.completedTasks.length / learningTasks.length) * 100),
)
</script>
<template>
  <section class="page-stack">
    <PageHeading title="把远方，拆成每一步" description="明确目标，持续练习，留下可回顾的成长轨迹。"
      ><RouterLink to="/discover" class="btn">调整职业目标</RouterLink></PageHeading
    >
    <p v-if="loading" role="status" class="subtle">正在读取职业档案…</p>
    <div v-if="error" role="alert" class="error-banner">
      {{ error }} <button class="btn text" @click="retry">重试</button>
    </div>
    <p v-if="ready" role="status" class="subtle">
      {{ pending ? '成长行动尚未保存' : '成长行动已与服务器同步' }}
    </p>
    <div class="split-content">
      <section class="panel">
        <div class="section-heading">
          <h2>本轮成长行动</h2>
          <span class="tag">通用行动清单</span>
        </div>
        <label
          v-for="(task, index) in learningTasks"
          :key="task"
          class="check-row"
          :class="{ done: profile.completedTasks.includes(task) }"
          ><span class="step-number">{{ String(index + 1).padStart(2, '0') }}</span
          ><input
            v-model="profile.completedTasks"
            type="checkbox"
            :value="task"
            :disabled="!ready"
            @change="saveTasks"
          /><span>{{ task }}</span></label
        >
      </section>
      <aside class="form-stack">
        <section class="panel form-stack">
          <p class="eyebrow">目标方向</p>
          <h2>{{ profile.target || '先确定一个目标' }}</h2>
          <p class="subtle">意向城市：{{ profile.city || '不限' }}</p>
          <div class="large-number">{{ progress }}<span style="font-size: 1rem"> %</span></div>
          <div class="focus-progress"><div :style="{ width: progress + '%' }" /></div>
          <p class="subtle">
            {{ profile.completedTasks.length }} / {{ learningTasks.length }} 个行动已完成
          </p>
          <RouterLink
            :to="{
              path: '/chat',
              query: {
                mode: 'COACH',
                prompt:
                  '我的目标是' +
                  profile.target +
                  '，已有技能：' +
                  profile.skills +
                  '。请帮我制定可执行的学习计划。',
              },
            }"
            class="btn primary"
            >和 AI 讨论下一步</RouterLink
          >
        </section>
        <section class="panel form-stack">
          <h2>让学习有真实产出</h2>
          <p class="subtle">
            选一个小问题，做出一个能展示的项目。每周留下一段复盘：做了什么、卡在哪里、下次怎么改。
          </p>
          <RouterLink to="/kb" class="btn text">记录一次复盘</RouterLink>
        </section>
      </aside>
    </div>
    <TargetPlanPanel :enabled="ready && !pending" :target="profile.target" :city="profile.city" />
    <CareerRecommendations />
  </section>
</template>
