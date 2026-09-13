<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace, questions } from '@/shared/stores/workspace'
import { downloadText } from '@/shared/utils/download'
const workspace = useWorkspace()
const route = useRoute()
const session = computed(() => workspace.state.interviews.find((i) => i.id === route.params.id))
const answer = ref('')
watch(
  () => session.value?.question,
  () => {
    answer.value = session.value?.answers[session.value.question] || ''
  },
  { immediate: true },
)
function saveDraft() {
  if (session.value) session.value.answers[session.value.question] = answer.value
}
function submit() {
  if (!session.value || !answer.value.trim()) return
  saveDraft()
  if (session.value.question === questions.length - 1) {
    session.value.completed = true
    workspace.notify('完成了文字面试练习：' + session.value.role)
  } else session.value.question++
}
function exportAnswers() {
  if (!session.value) return
  downloadText(
    session.value.role + '-面试复盘.md',
    questions
      .map((q, i) => '## ' + q + '\n\n' + (session.value?.answers[i] || '未作答'))
      .join('\n\n'),
  )
}
</script>
<template>
  <section v-if="session" class="page-stack">
    <PageHeading
      :title="session.completed ? '一次练习，一份积累' : session.role + ' · 文字练习'"
      eyebrow="PRACTICE ROOM"
      :description="session.kind + ' · 固定题库自练'"
      ><RouterLink to="/interviews" class="btn">返回练习列表</RouterLink
      ><button v-if="session.completed" class="btn primary" @click="exportAnswers">
        导出复盘
      </button></PageHeading
    ><template v-if="!session.completed"
      ><section class="panel interview-stage">
        <span class="tag">第 {{ session.question + 1 }} / {{ questions.length }} 题</span>
        <div class="interview-avatar"><AppIcon name="interviews" :size="38" /></div>
        <h2>{{ questions[session.question] }}</h2>
        <p class="subtle">不急着给出完美答案。先把你的真实经历讲清楚。</p>
      </section>
      <form class="panel form-stack" @submit.prevent="submit">
        <label class="field"
          >你的回答<textarea
            v-model="answer"
            rows="6"
            required
            maxlength="12000"
            placeholder="可以从背景、任务、行动、结果四个角度展开…"
            @input="saveDraft"
          />
        </label>
        <div class="card-footer">
          <span class="subtle">{{ answer.length }} 字 · 草稿保存在本机</span
          ><button
            type="button"
            class="btn"
            :disabled="session.question === 0"
            @click="session.question--"
          >
            上一题</button
          ><button class="btn primary" :disabled="!answer.trim()">
            {{ session.question === questions.length - 1 ? '完成并复盘' : '保存，下一题 →' }}
          </button>
        </div>
      </form></template
    ><template v-else
      ><div class="success-banner">已完成全部 5 题。下面是你的原始回答，不含 AI 评分。</div>
      <div class="split-content">
        <section class="panel">
          <article v-for="(question, index) in questions" :key="question" class="answer-card">
            <h3>{{ index + 1 }}. {{ question }}</h3>
            <p class="prose-content">{{ session.answers[index] || '未作答' }}</p>
          </article>
        </section>
        <aside class="panel form-stack" style="align-self: start">
          <h2>复盘时，问自己三个问题</h2>
          <p class="subtle">
            ① 我是否直接回答了问题？<br />② 我是否说清自己的贡献？<br />③ 我的结果是否具体、可验证？
          </p>
          <RouterLink
            :to="{
              path: '/chat',
              query: {
                mode: 'INTERVIEWER',
                prompt: '我正在准备' + session.role + '的面试，请帮我练习回答项目经历类问题。',
              },
            }"
            class="btn primary"
            >继续和 AI 练习 ↗</RouterLink
          ><RouterLink to="/career" class="btn">更新成长行动</RouterLink>
        </aside>
      </div></template
    >
  </section>
  <div v-else class="panel empty-state">
    <h2>未找到这场练习</h2>
    <RouterLink to="/interviews" class="btn">返回练习列表</RouterLink>
  </div>
</template>
