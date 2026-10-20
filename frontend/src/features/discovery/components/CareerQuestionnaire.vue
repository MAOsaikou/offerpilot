<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import {
  getQuestionnaire,
  saveQuestionnaireAnswer,
  completeQuestionnaire,
  getCareerProfile,
  type CareerQuestionnaire,
  type AssessmentConfirmed,
} from '@/shared/api/career'
const props = defineProps<{ beforeConfirm?: () => Promise<void> }>()
const emit = defineEmits<{ confirmed: [assessment: AssessmentConfirmed] }>()
const state = ref<CareerQuestionnaire>()
const step = ref(0)
const busy = ref(false)
const error = ref('')
const online = ref(navigator.onLine)
const updateOnline = () => {
  online.value = navigator.onLine
}
window.addEventListener('online', updateOnline)
window.addEventListener('offline', updateOnline)
let disposed = false
onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('online', updateOnline)
  window.removeEventListener('offline', updateOnline)
})
const count = computed(() => Object.keys(state.value?.answers ?? {}).length)
const question = computed(() => state.value?.questions[step.value])
const choices = ['很不喜欢', '不太喜欢', '不确定', '比较喜欢', '非常喜欢']
async function load() {
  busy.value = true
  error.value = ''
  try {
    const next = await getQuestionnaire()
    if (disposed) return
    state.value = next
    const missing = next.questions.findIndex((q) => next.answers[q.id] == null)
    step.value = missing < 0 ? next.questions.length : missing
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '读取失败，请重试'
  } finally {
    if (!disposed) busy.value = false
  }
}
async function answer(value: number) {
  if (busy.value || !question.value || !online.value) return
  busy.value = true
  error.value = ''
  try {
    const next = await saveQuestionnaireAnswer(question.value.id, value)
    if (disposed) return
    state.value = next
    step.value++
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
  } finally {
    if (!disposed) busy.value = false
  }
}
async function complete() {
  if (!state.value || busy.value || !online.value) return
  busy.value = true
  error.value = ''
  try {
    await props.beforeConfirm?.()
    const next = await completeQuestionnaire(state.value.revision)
    if (disposed) return
    state.value = next
    const profile = await getCareerProfile()
    if (!disposed && profile.assessment) emit('confirmed', profile.assessment)
  } catch (cause) {
    if (!disposed) error.value = cause instanceof Error ? cause.message : '提交失败，请重试'
  } finally {
    if (!disposed) busy.value = false
  }
}
void load()
</script>
<template>
  <section class="panel form-stack" aria-labelledby="questionnaire-title">
    <h2 id="questionnaire-title">快速兴趣问卷 · 30 题</h2>
    <p class="subtle">
      按 O*NET 使用的 RIASEC 兴趣框架自写中文活动题，每类 5
      题；不是官方译本或经常模验证的心理量表。按喜欢程度作答，不是能力考试，约需 5 分钟。
    </p>
    <p class="helper-note">
      每题保存到账号，可离开后续答。确认写入时只替换画像的六维兴趣与兴趣码，保留已确认的特质和现实条件；之后确认对话测评会再次更新兴趣，以最近一次明确确认为准。
    </p>
    <p v-if="!online" role="status">当前离线，已保存回答会保留，恢复网络后可继续。</p>
    <p v-if="busy" role="status">正在同步问卷…</p>
    <p v-if="error" role="alert">
      {{ error }} <button class="btn" :disabled="busy" @click="load">重新读取</button>
    </p>
    <template v-if="state">
      <p role="status">已保存 {{ count }} / 30 题</p>
      <article v-if="question" class="form-stack" aria-label="问卷答题卡">
        <h3>{{ step + 1 }}. {{ question.text }}</h3>
        <div class="flex flex-wrap gap-2" role="group" aria-label="喜欢程度">
          <button
            v-for="(label, index) in choices"
            :key="label"
            class="btn"
            :aria-pressed="state.answers[question.id] === index + 1"
            :disabled="busy || !online"
            @click="answer(index + 1)"
          >
            {{ label }}
          </button>
        </div>
      </article>
      <p v-else-if="!state.completed">
        30 题已答完，可以确认写入画像；分数完全相同时会保存为未分化，不强行给出兴趣码。
      </p>
      <p v-if="state.completed" role="status">
        已写入画像，兴趣码 {{ state.code || '未分化' }}。并列分数按 R、I、A、S、E、C
        顺序展示，不代表显著差异。
      </p>
      <div class="flex flex-wrap gap-2">
        <button class="btn" :disabled="busy || step === 0" @click="step--">上一题</button>
        <button
          class="btn"
          :disabled="
            busy ||
            step >= state.questions.length ||
            !question ||
            state.answers[question.id] == null
          "
          @click="step++"
        >
          下一题
        </button>
        <button
          v-if="count === 30"
          class="btn primary"
          :disabled="busy || !online || state.completed"
          @click="complete"
        >
          确认兴趣并写入画像
        </button>
      </div>
    </template>
  </section>
</template>
