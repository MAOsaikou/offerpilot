<script setup lang="ts">
import { computed } from 'vue'

import { translateLabel, sourceText, translate as uiText } from '@/shared/utils/language'

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
const interests = [
  sourceText('Technology and engineering'),
  sourceText('Product planning'),
  sourceText('Data analysis'),
  sourceText('Creative design'),
  sourceText('Communication and collaboration'),
  sourceText('Research and exploration'),
]
const directions = computed(() => [
  {
    title: uiText('Technology and engineering'),
    role: uiText('Software engineer'),
    icon: 'jobs',
    desc: uiText('Enjoy breaking down problems and building useful products with code.'),
    skills: uiText('Programming fundamentals · Engineering practice · Systems thinking'),
  },
  {
    title: uiText('Product and design'),
    role: uiText('Product manager'),
    icon: 'discover',
    desc: uiText("Understand people's needs and turn vague ideas into practical solutions."),
    skills: uiText('User research · Requirements analysis · Communication'),
  },
  {
    title: uiText('Data and insights'),
    role: uiText('Data analyst'),
    icon: 'career',
    desc: uiText('Explore patterns in numbers and answer questions with evidence.'),
    skills: uiText('SQL · Statistics fundamentals · Visualization'),
  },
])
function choose(role: string) {
  profile.value.target = role
  saveProfile(0)
}
</script>
<template>
  <section class="page-stack">
    <PageHeading
      :title="uiText('My career profile')"
      :description="
        uiText(
          'Explore your education, interests and personality, then turn direction into action.',
        )
      "
      ><RouterLink
        :to="{
          path: '/chat',
          query: {
            mode: 'CAREER_DISCOVERY',
            prompt: uiText('Ask me questions to help explore my career interests and abilities.'),
          },
        }"
        class="btn primary"
        >{{ uiText('Explore in conversation') }}</RouterLink
      ></PageHeading
    >
    <p v-if="loading" role="status" class="subtle">{{ uiText('Loading career profile…') }}</p>
    <div v-if="error" role="alert" class="error-banner">
      {{ error }} <button class="btn text" @click="retry">{{ uiText('Retry') }}</button>
    </div>
    <p v-if="ready" role="status" class="subtle">
      {{
        pending
          ? uiText('Career profile is not yet saved')
          : uiText('Career profile synced with the server')
      }}
    </p>
    <TargetPlanPanel :enabled="ready && !pending" :target="profile.target" :city="profile.city" />
    <ProfileUpdates
      :before-confirm="flushProfile"
      @confirmed="evidenceConfirmed"
      @busy="evidenceBusy = $event"
    />
    <div class="split-content">
      <section class="panel form-stack">
        <h2>{{ uiText('Career direction') }}</h2>
        <div class="form-grid">
          <label class="field"
            >{{ uiText('Target career')
            }}<input
              v-model="profile.target"
              :disabled="!ready || evidenceBusy"
              maxlength="100"
              :placeholder="uiText('For example: frontend engineer')"
              @input="saveProfile()" /></label
          ><label class="field"
            >{{ uiText('Preferred city')
            }}<input
              v-model="profile.city"
              :disabled="!ready || evidenceBusy"
              maxlength="100"
              :placeholder="uiText('For example: Hangzhou, Shanghai')"
              @input="saveProfile()"
          /></label>
        </div>
        <p class="subtle">
          {{
            uiText(
              'Text edits save automatically. If saving fails, your draft stays on this page. Click retry.',
            )
          }}
        </p>
      </section>
      <aside class="panel form-stack">
        <span class="stat-icon"><AppIcon name="discover" /></span>
        <h2>{{ uiText('There is no single right answer') }}</h2>
        <p class="subtle">
          {{
            uiText(
              'Recall something that absorbed your attention. What were you doing? What felt rewarding? Specific experiences say more than a simple label.',
            )
          }}
        </p>
        <div class="helper-note">
          {{
            uiText(
              'When long-term memory is enabled, your goal, city, major and interest code are synced to memory. Existing memories only fill empty fields. Manage or disable memory in settings.',
            )
          }}
        </div>
        <RouterLink to="/career" class="btn">{{ uiText('Turn direction into action') }}</RouterLink>
      </aside>
    </div>
    <div class="profile-panels">
      <section class="panel form-stack" aria-labelledby="education-title">
        <span class="tag">{{ uiText('01 · Education') }}</span>
        <h2 id="education-title">{{ uiText('Major and courses') }}</h2>
        <label class="field"
          >{{ uiText('Major')
          }}<input
            v-model="profile.major"
            name="major"
            :disabled="!ready || evidenceBusy"
            maxlength="100"
            :placeholder="uiText('For example: software engineering')"
            @input="saveProfile()"
        /></label>
        <label class="field"
          >{{ uiText('Courses (one per line)')
          }}<textarea
            v-model="profile.courses"
            name="courses"
            :disabled="!ready || evidenceBusy"
            rows="5"
            maxlength="3000"
            :placeholder="uiText('Data Structures and Algorithms / Database Fundamentals')"
            @input="saveProfile()"
          />
        </label>
        <p class="subtle">
          {{ uiText('Add relevant courses, labs or electives. You can leave this blank for now.') }}
        </p>
      </section>
      <section class="panel form-stack" aria-labelledby="interests-title">
        <span class="tag">{{ uiText('02 · Interests and strengths') }}</span>
        <h2 id="interests-title">{{ uiText('Interests and skills') }}</h2>
        <p v-if="profile.assessment" class="helper-note">
          {{ uiText('Confirmed Holland interest code:')
          }}<strong>{{ profile.assessment.code || uiText('Undifferentiated') }}</strong>
        </p>
        <label class="field"
          >{{ uiText('Current skills')
          }}<textarea
            v-model="profile.skills"
            name="skills"
            :disabled="!ready || evidenceBusy"
            rows="4"
            maxlength="3000"
            :placeholder="uiText('What can you do? What have you practiced?')"
            @input="saveProfile()"
          />
        </label>
        <div class="field">
          {{ uiText('Areas of interest') }}
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
              />{{ translateLabel(interest) }}</label
            >
          </div>
        </div>
      </section>
      <section class="panel form-stack personality-panel" aria-labelledby="personality-title">
        <span class="tag">{{ uiText('03 · Work style and choices') }}</span>
        <h2 id="personality-title">{{ uiText('Personality and values') }}</h2>
        <label class="field"
          >{{ uiText('Values (one per line)')
          }}<textarea
            v-model="profile.valuesText"
            name="valuesText"
            :disabled="!ready || evidenceBusy"
            rows="3"
            maxlength="1000"
            :placeholder="uiText('Continuous growth / Work-life balance')"
            @input="saveProfile()"
          />
        </label>
        <ProfilePersonality
          v-if="ready"
          :assessment="profile.assessment"
          :before-confirm="flushProfile"
          @confirmed="personalityConfirmed"
        />
        <p v-else class="subtle">
          {{
            loading
              ? uiText('Loading personality profile…')
              : uiText('Retry loading your career profile first.')
          }}
        </p>
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
      <h2>{{ uiText('Start exploring these directions') }}</h2>
      <span class="subtle">{{ uiText('General career references') }}</span>
    </div>
    <div class="card-grid">
      <article v-for="direction in directions" :key="direction.title" class="panel job-card">
        <span class="stat-icon"><AppIcon :name="direction.icon" /></span>
        <h2>{{ direction.title }}</h2>
        <p class="subtle">{{ direction.desc }}</p>
        <p class="tag">{{ direction.skills }}</p>
        <button class="btn" :disabled="!ready || evidenceBusy" @click="choose(direction.role)">
          {{
            profile.target === direction.role
              ? uiText('Current goal')
              : uiText('Set as exploration goal')
          }}
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
