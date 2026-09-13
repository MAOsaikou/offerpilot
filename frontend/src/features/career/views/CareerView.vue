<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import { useWorkspace, learningTasks } from '@/shared/stores/workspace'
const workspace = useWorkspace()
const progress = computed(() =>
  Math.round((workspace.state.tasks.length / learningTasks.length) * 100),
)
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="把远方，拆成每一步"
      eyebrow="YOUR GROWTH ROADMAP"
      description="明确目标，持续练习，留下可回顾的成长轨迹。"
      ><RouterLink to="/discover" class="btn">调整职业目标</RouterLink></PageHeading
    >
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
          :class="{ done: workspace.state.tasks.includes(task) }"
          ><span class="step-number">{{ String(index + 1).padStart(2, '0') }}</span
          ><input v-model="workspace.state.tasks" type="checkbox" :value="task" /><span>{{
            task
          }}</span></label
        >
      </section>
      <aside class="form-stack">
        <section class="panel form-stack">
          <p class="eyebrow">YOUR DESTINATION</p>
          <h2>{{ workspace.state.target || '先确定一个目标' }}</h2>
          <p class="subtle">意向城市：{{ workspace.state.city || '不限' }}</p>
          <div class="large-number">{{ progress }}<span style="font-size: 1rem"> %</span></div>
          <div class="focus-progress"><div :style="{ width: progress + '%' }" /></div>
          <p class="subtle">
            {{ workspace.state.tasks.length }} / {{ learningTasks.length }} 个行动已完成
          </p>
          <RouterLink
            :to="{
              path: '/chat',
              query: {
                mode: 'COACH',
                prompt:
                  '我的目标是' +
                  workspace.state.target +
                  '，已有技能：' +
                  workspace.state.skills +
                  '。请帮我制定可执行的学习计划。',
              },
            }"
            class="btn primary"
            >和 AI 讨论下一步 ↗</RouterLink
          >
        </section>
        <section class="panel form-stack">
          <h2>让学习有真实产出</h2>
          <p class="subtle">
            选一个小问题，做出一个能展示的项目。每周留下一段复盘：做了什么、卡在哪里、下次怎么改。
          </p>
          <RouterLink to="/kb" class="btn text">记录一次复盘 →</RouterLink>
        </section>
      </aside>
    </div>
  </section>
</template>
