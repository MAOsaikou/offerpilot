<script setup lang="ts">
import { RouterLink } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import { stages, useWorkspace } from '@/shared/stores/workspace'
const workspace = useWorkspace()
const jobFor = workspace.jobFor
function changed(title: string) {
  workspace.notify('更新了岗位进展：' + title)
}
</script>
<template>
  <section class="page-stack">
    <PageHeading title="每一个机会，都有迹可循" description="用清晰的进度，管理你的求职节奏。"
      ><RouterLink to="/jobs" class="btn primary">＋ 发现岗位</RouterLink></PageHeading
    >
    <div class="helper-note">
      共 {{ workspace.state.applications.length }} 个机会 ·
      状态和备注仅保存在本机；更新状态不会发送真实投递。使用卡片下拉框移动阶段。
    </div>
    <div v-if="!workspace.state.applications.length" class="panel empty-state">
      <h2>把第一个机会放进看板</h2>
      <p>在岗位页点击收藏，随后就能在这里记录投递、面试和 Offer 进度。</p>
      <RouterLink to="/jobs" class="btn primary">去发现岗位</RouterLink>
    </div>
    <div v-else class="kanban">
      <section v-for="stage in stages" :key="stage" class="kanban-column">
        <h2>
          {{ stage }}
          <span>{{ workspace.state.applications.filter((a) => a.stage === stage).length }}</span>
        </h2>
        <article
          v-for="app in workspace.state.applications.filter((a) => a.stage === stage)"
          :key="app.id"
          class="application-card"
        >
          <p>{{ jobFor(app.jobId)?.company }}</p>
          <RouterLink :to="'/jobs/' + app.jobId"
            ><h3>{{ jobFor(app.jobId)?.title }}</h3></RouterLink
          >
          <p>{{ jobFor(app.jobId)?.city }} · {{ app.date }}</p>
          <label class="field"
            >当前阶段<select v-model="app.stage" @change="changed(jobFor(app.jobId)?.title || '')">
              <option v-for="s in stages" :key="s">{{ s }}</option>
            </select></label
          ><label class="field"
            >跟进备注<textarea
              v-model="app.note"
              rows="2"
              maxlength="2000"
              placeholder="记录下一步、面试时间…"
            />
          </label>
        </article>
        <p v-if="!workspace.state.applications.some((a) => a.stage === stage)" class="subtle">
          暂无机会
        </p>
      </section>
    </div>
  </section>
</template>
