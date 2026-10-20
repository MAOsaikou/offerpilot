<script setup lang="ts">
import { ref } from 'vue'
import { sessionRevision } from '@/shared/utils/authSession'
import { RouterLink } from 'vue-router'
import type { AssessmentConfirmed, CareerProfile } from '@/shared/api/career'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useCareerProfile } from '@/shared/composables/useCareerProfile'
import CareerAssessment from '../components/CareerAssessment.vue'
import CareerQuestionnaire from '../components/CareerQuestionnaire.vue'
import ProfilePersonality from '../components/ProfilePersonality.vue'
import ProfileUpdates from '../components/ProfileUpdates.vue'
import TargetPlanPanel from '@/shared/components/TargetPlanPanel.vue'
const {
  profile,
  loading,
  ready,
  error,
  pending,
  saveProfile,
  retry,
  flushProfile,
  applyAssessment,
} = useCareerProfile()
const assessmentRevision = ref(0)
const questionnaireRevision = ref(0)
const evidenceBusy = ref(false)
function evidenceConfirmed(saved: CareerProfile) {
  profile.value.skills = saved.skills
  profile.value.evidence = saved.evidence
  profile.value.updatedAt = saved.updatedAt
}
function personalityConfirmed(assessment: AssessmentConfirmed) {
  applyAssessment(assessment)
  assessmentRevision.value++
  questionnaireRevision.value++
}
function assessmentChanged(assessment: AssessmentConfirmed | null) {
  applyAssessment(assessment)
  questionnaireRevision.value++
}
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
  profile.value.target = role
  saveProfile(0)
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      title="我的职业画像"
      description="从专业、兴趣和性格三个维度认识自己，把方向变成下一步行动。"
      ><RouterLink
        :to="{
          path: '/chat',
          query: { mode: 'CAREER_DISCOVERY', prompt: '请通过提问帮助我梳理职业兴趣和能力。' },
        }"
        class="btn primary"
        >开始对话探索</RouterLink
      ></PageHeading
    >
    <p v-if="loading" role="status" class="subtle">正在读取职业档案…</p>
    <div v-if="error" role="alert" class="error-banner">
      {{ error }} <button class="btn text" @click="retry">重试</button>
    </div>
    <p v-if="ready" role="status" class="subtle">
      {{ pending ? '职业档案尚未保存' : '职业档案已与服务器同步' }}
    </p>
    <TargetPlanPanel :enabled="ready && !pending" :target="profile.target" :city="profile.city" />
    <ProfileUpdates
      :before-confirm="flushProfile"
      @confirmed="evidenceConfirmed"
      @busy="evidenceBusy = $event"
    />
    <div class="split-content">
      <section class="panel form-stack">
        <h2>职业方向</h2>
        <div class="form-grid">
          <label class="field"
            >目标方向<input
              v-model="profile.target"
              :disabled="!ready || evidenceBusy"
              maxlength="100"
              placeholder="例如：前端开发工程师"
              @input="saveProfile()" /></label
          ><label class="field"
            >意向城市<input
              v-model="profile.city"
              :disabled="!ready || evidenceBusy"
              maxlength="100"
              placeholder="例如：杭州、上海"
              @input="saveProfile()"
          /></label>
        </div>
        <p class="subtle">文字编辑会自动保存。保存失败时保留本页草稿，请点击重试。</p>
      </section>
      <aside class="panel form-stack">
        <span class="stat-icon"><AppIcon name="discover" /></span>
        <h2>探索没有标准答案</h2>
        <p class="subtle">
          回想一件让你投入的事：你在做什么？其中哪一部分让你有成就感？这些具体经历，比一个简单的标签更有价值。
        </p>
        <div class="helper-note">
          开启长期记忆时，目标、城市、专业和兴趣码会同步到记忆；仅用已有记忆补充空白项。可在设置中管理或关闭。
        </div>
        <RouterLink to="/career" class="btn">把方向变成行动</RouterLink>
      </aside>
    </div>
    <div class="profile-panels">
      <section class="panel form-stack" aria-labelledby="education-title">
        <span class="tag">01 · 学习积累</span>
        <h2 id="education-title">专业与课程</h2>
        <label class="field"
          >专业<input
            v-model="profile.major"
            name="major"
            :disabled="!ready || evidenceBusy"
            maxlength="100"
            placeholder="例如：软件工程"
            @input="saveProfile()"
        /></label>
        <label class="field"
          >课程（每行一门）<textarea
            v-model="profile.courses"
            name="courses"
            :disabled="!ready || evidenceBusy"
            rows="5"
            maxlength="3000"
            placeholder="数据结构与算法&#10;数据库原理"
            @input="saveProfile()"
          />
        </label>
        <p class="subtle">写下与你的方向有关的课程、实验或选修经历，没想好可以先留空。</p>
      </section>
      <section class="panel form-stack" aria-labelledby="interests-title">
        <span class="tag">02 · 喜欢与擅长</span>
        <h2 id="interests-title">兴趣与技能</h2>
        <p v-if="profile.assessment" class="helper-note">
          已确认的 Holland 兴趣码：<strong>{{ profile.assessment.code || '未分化' }}</strong>
        </p>
        <label class="field"
          >已有技能<textarea
            v-model="profile.skills"
            name="skills"
            :disabled="!ready || evidenceBusy"
            rows="4"
            maxlength="3000"
            placeholder="你会什么？有哪些实践经历？"
            @input="saveProfile()"
          />
        </label>
        <div class="field">
          感兴趣的领域
          <div class="chips">
            <label
              v-for="interest in interests"
              :key="interest"
              class="chip"
              :class="{ selected: profile.interests.includes(interest) }"
              ><input
                v-model="profile.interests"
                type="checkbox"
                :value="interest"
                :disabled="!ready || evidenceBusy"
                @change="saveProfile(0)"
              />{{ interest }}</label
            >
          </div>
        </div>
      </section>
      <section class="panel form-stack personality-panel" aria-labelledby="personality-title">
        <span class="tag">03 · 做事方式与选择</span>
        <h2 id="personality-title">性格与价值观</h2>
        <label class="field"
          >价值观（每行一项）<textarea
            v-model="profile.valuesText"
            name="valuesText"
            :disabled="!ready || evidenceBusy"
            rows="3"
            maxlength="1000"
            placeholder="持续成长&#10;工作生活平衡"
            @input="saveProfile()"
          />
        </label>
        <ProfilePersonality
          v-if="ready"
          :assessment="profile.assessment"
          :before-confirm="flushProfile"
          @confirmed="personalityConfirmed"
        />
        <p v-else class="subtle">{{ loading ? '正在读取性格画像…' : '请先重试读取职业画像。' }}</p>
      </section>
    </div>
    <CareerQuestionnaire
      :key="`${sessionRevision}:${questionnaireRevision}`"
      :before-confirm="flushProfile"
      @confirmed="personalityConfirmed"
    />
    <div id="career-assessment" tabindex="-1">
      <CareerAssessment
        :key="`${sessionRevision}:${assessmentRevision}`"
        :before-confirm="flushProfile"
        @changed="assessmentChanged"
      />
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
        <button class="btn" :disabled="!ready || evidenceBusy" @click="choose(direction.role)">
          {{ profile.target === direction.role ? '当前目标' : '设为探索目标' }}
        </button>
      </article>
    </div>
  </section>
</template>

<style scoped>
.profile-panels {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}
.profile-panels > * {
  min-width: 0;
  overflow-wrap: anywhere;
}
.chips input[type='checkbox'] {
  width: 16px;
  height: 16px;
  min-height: 16px;
  margin: 0;
}
.personality-panel {
  grid-column: 1 / -1;
}
#career-assessment {
  scroll-margin-top: 24px;
}
@media (max-width: 700px) {
  .profile-panels {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
