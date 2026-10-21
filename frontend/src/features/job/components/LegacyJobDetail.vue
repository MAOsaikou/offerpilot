<script setup lang="ts">
import { translate as uiText } from '@/shared/utils/language'

import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import PageHeading from '@/shared/components/PageHeading.vue'
import { jobs } from '@/shared/stores/workspace'
const route = useRoute()
const job = computed(() => jobs.find((j) => j.id === route.params.id))
</script>
<template>
  <section v-if="job" class="page-stack">
    <PageHeading :title="job.title" :description="job.company + ' · ' + job.city"
      ><RouterLink to="/jobs" class="btn">{{ uiText('← Back to jobs') }}</RouterLink></PageHeading
    >
    <div class="helper-note">
      {{
        uiText(
          'This is a fictional sample job. Search for real jobs from company websites and save them instead.',
        )
      }}
    </div>
    <div class="split-content">
      <article class="panel form-stack">
        <h2>{{ uiText('About the role') }}</h2>
        <p class="prose-content">{{ job.description }}</p>
        <h2>{{ uiText('Skill keywords') }}</h2>
        <div class="chips">
          <span v-for="tag in job.tags" :key="tag" class="chip">{{ tag }}</span>
        </div>
        <h2>{{ uiText('Preparation tips') }}</h2>
        <p class="subtle">
          {{
            uiText(
              'Prepare relevant project experience covering your responsibilities, concrete actions and verifiable results. Check official recruitment information before applying.',
            )
          }}
        </p>
        <RouterLink
          :to="{
            path: '/chat',
            query: {
              mode: 'JOB',
              prompt:
                uiText('Help me analyze the skills needed for this role:') +
                job.title +
                '。' +
                job.description,
            },
          }"
          class="btn"
          >{{ uiText('Discuss this direction with AI') }}</RouterLink
        >
      </article>
      <aside class="panel form-stack">
        <span class="company-logo" :style="{ background: job.color }">{{
          job.company.slice(0, 1)
        }}</span>
        <h2>{{ job.company }}</h2>
        <p class="large-number" style="font-size: 1.5rem">{{ job.salary }}</p>
        <p class="subtle">
          {{ uiText('Work city: {0}', [job.city]) }}<br />{{
            uiText('Job direction: {0}', [job.category])
          }}<br />{{ uiText('Source: local sample') }}
        </p>
        <RouterLink to="/resumes" class="btn primary">{{ uiText('Prepare my resume') }}</RouterLink
        ><RouterLink to="/board" class="btn">{{ uiText('Track application progress') }}</RouterLink>
      </aside>
    </div>
  </section>
  <div v-else class="panel empty-state">
    <h2>{{ uiText('Job not found') }}</h2>
    <RouterLink to="/jobs" class="btn">{{ uiText('Back to job list') }}</RouterLink>
  </div>
</template>
