<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
const workspace = useWorkspace()
const message = ref('')
const interests = ['技术研发', '产品策划', '数据分析', '创意设计', '沟通协作', '研究探索']
const directions = [
  {
    title: '技术研发',
    role: '软件开发工程师',
    icon: 'jobs',
    desc: '享受拆解问题，用代码搭建可用的产品。',
    skills: '编程基础 · 工程实践 · 系统思维',
  },
  {
    title: '产品与设计',
    role: '产品经理',
    icon: 'discover',
    desc: '关注人的需求，乐于把模糊的想法变成方案。',
    skills: '用户研究 · 需求分析 · 表达沟通',
  },
  {
    title: '数据与洞察',
    role: '数据分析师',
    icon: 'career',
    desc: '对数字背后的规律保持好奇，用证据回答问题。',
    skills: 'SQL · 统计基础 · 可视化',
  },
]
function choose(role: string) {
  workspace.state.target = role
  message.value = '已设为目标：' + role
  workspace.notify(message.value)
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="从了解自己，到找到方向"
      eyebrow="DISCOVER YOUR PATH"
      description="先写下你的偏好，再探索值得投入的职业方向。"
      ><RouterLink
        :to="{
          path: '/chat',
          query: { mode: 'CAREER_DISCOVERY', prompt: '请通过提问帮助我梳理职业兴趣和能力。' },
        }"
        class="btn primary"
        >开始对话探索 ↗</RouterLink
      ></PageHeading
    >
    <p v-if="message" role="status" class="success-banner">{{ message }}</p>
    <div class="split-content">
      <section class="panel form-stack">
        <h2>我的职业画像</h2>
        <div class="form-grid">
          <label class="field"
            >目标方向<input
              v-model="workspace.state.target"
              maxlength="100"
              placeholder="例如：前端开发工程师" /></label
          ><label class="field"
            >意向城市<input
              v-model="workspace.state.city"
              maxlength="100"
              placeholder="例如：杭州、上海"
          /></label>
        </div>
        <label class="field"
          >已有技能<textarea
            v-model="workspace.state.skills"
            rows="3"
            maxlength="3000"
            placeholder="你会什么？有哪些实践经历？"
          />
        </label>
        <div class="field">
          感兴趣的领域
          <div class="chips">
            <label
              v-for="interest in interests"
              :key="interest"
              class="chip"
              :class="{ selected: workspace.state.interests.includes(interest) }"
              ><input v-model="workspace.state.interests" type="checkbox" :value="interest" />
              {{ interest }}</label
            >
          </div>
        </div>
        <p class="subtle">你的选择会自动保存在本机，并显示在工作台与成长路线中。</p>
      </section>
      <aside class="panel form-stack">
        <span class="stat-icon"><AppIcon name="discover" /></span>
        <h2>探索没有标准答案</h2>
        <p class="subtle">
          回想一件让你投入的事：你在做什么？其中哪一部分让你有成就感？这些具体经历，比一个简单的标签更有价值。
        </p>
        <div class="helper-note">此处是自填偏好与通用方向介绍，不是心理测评或 AI 匹配报告。</div>
        <RouterLink to="/career" class="btn">把方向变成行动 →</RouterLink>
      </aside>
    </div>
    <div class="section-heading">
      <h2>从这些方向开始了解</h2>
      <span class="subtle">通用职业参考</span>
    </div>
    <div class="card-grid">
      <article v-for="direction in directions" :key="direction.title" class="panel job-card">
        <span class="stat-icon"><AppIcon :name="direction.icon" /></span>
        <h2>{{ direction.title }}</h2>
        <p class="subtle">{{ direction.desc }}</p>
        <p class="tag">{{ direction.skills }}</p>
        <button class="btn" @click="choose(direction.role)">
          {{ workspace.state.target === direction.role ? '当前目标' : '设为探索目标 →' }}
        </button>
      </article>
    </div>
  </section>
</template>
