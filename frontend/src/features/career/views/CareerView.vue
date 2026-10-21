<script setup lang="ts">
import { formatDisplay, translateLabel, translate as uiText } from '@/shared/utils/language'

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
    <PageHeading
      :title="uiText('Turn your goals into steps')"
      :description="uiText('Set a direction, keep practicing and build a record of your progress.')"
      ><RouterLink to="/discover" class="btn">{{
        uiText('Adjust career goal')
      }}</RouterLink></PageHeading
    >
    <p v-if="loading" role="status" class="subtle">{{ uiText('Loading career profile…') }}</p>
    <div v-if="error" role="alert" class="error-banner">
      {{ error }} <button class="btn text" @click="retry">{{ uiText('Retry') }}</button>
    </div>
    <p v-if="ready" role="status" class="subtle">
      {{
        pending
          ? uiText('Growth actions are not yet saved')
          : uiText('Growth actions synced with the server')
      }}
    </p>
    <div class="split-content">
      <section class="panel">
        <div class="section-heading">
          <h2>{{ uiText('This round of growth actions') }}</h2>
          <span class="tag">{{ uiText('General action checklist') }}</span>
        </div>
        <label
          v-for="(task, index) in learningTasks"
          :key="task"
          class="check-row"
          :class="{ done: profile.completedTasks.includes(task) }"
          ><span class="step-number">{{ formatDisplay(String(index + 1).padStart(2, '0')) }}</span
          ><input
            v-model="profile.completedTasks"
            type="checkbox"
            :value="task"
            :disabled="!ready"
            @change="saveTasks"
          /><span>{{ translateLabel(task) }}</span></label
        >
      </section>
      <aside class="form-stack">
        <section class="panel form-stack">
          <p class="eyebrow">{{ uiText('Target career') }}</p>
          <h2>{{ profile.target || uiText('Choose a goal first') }}</h2>
          <p class="subtle">{{ uiText('Preferred city: {0}', [profile.city || uiText('Any')]) }}</p>
          <div class="large-number">
            {{ formatDisplay(progress) }}<span style="font-size: 1rem"> %</span>
          </div>
          <div class="focus-progress"><div :style="{ width: progress + '%' }" /></div>
          <p class="subtle">
            {{
              uiText('{0} of {1} actions completed', [
                profile.completedTasks.length,
                learningTasks.length,
              ])
            }}
          </p>
          <RouterLink
            :to="{
              path: '/chat',
              query: {
                mode: 'COACH',
                prompt: uiText(
                  'My career goal is {0}. My current skills are {1}. Please help me create an actionable learning plan.',
                  [profile.target, profile.skills],
                ),
              },
            }"
            class="btn primary"
            >{{ uiText('Discuss next steps with AI') }}</RouterLink
          >
        </section>
        <section class="panel form-stack">
          <h2>{{ uiText('Make learning count') }}</h2>
          <p class="subtle">
            {{
              uiText(
                'Choose a small problem and build a project you can show. Reflect each week: what you did, where you got stuck and what to change next time.',
              )
            }}
          </p>
          <RouterLink to="/kb" class="btn text">{{ uiText('Write a reflection') }}</RouterLink>
        </section>
      </aside>
    </div>
    <TargetPlanPanel :enabled="ready && !pending" :target="profile.target" :city="profile.city" />
    <CareerRecommendations />
  </section>
</template>
