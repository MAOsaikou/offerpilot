<script setup lang="ts">
import {
  formatDisplay,
  formatDateTime,
  language,
  translateLabel,
  sourceText,
  translate as uiText,
} from '@/shared/utils/language'

import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRouter, useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import InterviewJobContext from '@/shared/components/InterviewJobContext.vue'
import PageHeading from '@/shared/components/PageHeading.vue'
import AppIcon from '@/shared/components/AppIcon.vue'
import { useWorkspace } from '@/shared/stores/workspace'
import { listResumes, listResumeVersions } from '@/shared/api/resumes'
import { listApplications } from '@/shared/api/applications'
import { useSpeech } from '@/shared/composables/useSpeech'
import { questionTypeLabels, type QuestionType } from '@/shared/types/interview'
import type { InterviewOptions } from '@/shared/types/interview'
import { createInterview, listInterviews } from '@/shared/api/interviews'
import { deleteStarStory, listStarStories } from '@/shared/api/starStories'
import type { InterviewKind } from '@/shared/types/interview'
import type { Interview } from '@/shared/types/workspace'
import { questions } from '@/shared/utils/interviewQuestions'
import { exportInterview } from '@/shared/utils/interviewExport'

const workspace = useWorkspace()
const router = useRouter()
const route = useRoute()
const cache = useQueryClient()
const query = useQuery({
  queryKey: ['interviews'],
  queryFn: ({ signal }) => listInterviews(signal),
  retry: false,
})
const stories = useQuery({
  queryKey: ['star-stories'],
  queryFn: ({ signal }) => listStarStories(signal),
  retry: false,
})
const storyBusy = ref('')
const storyError = ref('')
async function removeStory(id: string) {
  if (storyBusy.value) return
  storyBusy.value = id
  storyError.value = ''
  try {
    await deleteStarStory(id, controller.signal)
    if (controller.signal.aborted) return
    void cache.invalidateQueries({ queryKey: ['star-stories'] })
  } catch (e) {
    if (!controller.signal.aborted) storyError.value = (e as Error).message
  } finally {
    storyBusy.value = ''
  }
}
const role = ref(typeof route.query.role === 'string' ? route.query.role : '')
const kind = ref<InterviewKind>(sourceText('General'))
const difficulty = ref<InterviewOptions['difficulty']>('STANDARD')
const mode = ref<InterviewOptions['mode']>('TEXT')
const questionCount = ref(5)
const industry = ref('')
const feedbackMode = ref<'IMMEDIATE' | 'END'>('IMMEDIATE')
const customMix = ref(false)
const typeMix = ref<Record<QuestionType, number>>({
  BEHAVIORAL: 2,
  SITUATIONAL: 1,
  KNOWLEDGE: 1,
  CASE: 1,
})
const mixTotal = computed(() => Object.values(typeMix.value).reduce((sum, n) => sum + Number(n), 0))
const mixInvalid = computed(() => customMix.value && mixTotal.value !== questionCount.value)
const durationMinutes = ref(30)
const resumeId = ref('')
const versionId = ref('')
const jobId = ref(typeof route.query.jobId === 'string' ? route.query.jobId : '')
const applications = useQuery({
  queryKey: ['applications'],
  queryFn: ({ signal }) => listApplications(signal),
  retry: false,
})
const resumes = useQuery({
  queryKey: ['resumes', 'interview'],
  queryFn: ({ signal }) => listResumes(1, signal),
  retry: false,
})
const versions = useQuery({
  queryKey: computed(() => ['resume-versions', resumeId.value]),
  queryFn: ({ signal }) => listResumeVersions(resumeId.value, signal),
  enabled: computed(() => !!resumeId.value),
  retry: false,
})
watch(resumeId, () => {
  versionId.value = ''
})
watch(jobId, (id) => {
  const selected = applications.data.value?.find((item) => item.jobId === id)
  if (selected?.job) role.value = selected.job.title
})
const speech = useSpeech()
const voiceAvailable = computed(
  () => speech.recognitionSupported.value && speech.synthesisSupported.value,
)
const invalidSelection = computed(
  () =>
    !!resumeId.value && (!versionId.value || versions.isPending.value || versions.isError.value),
)
const busy = ref(false)
const error = ref('')
const controller = new AbortController()
onBeforeUnmount(() => controller.abort())

async function start() {
  if (!role.value.trim() || busy.value || invalidSelection.value || mixInvalid.value) return
  busy.value = true
  error.value = ''
  try {
    const session = await createInterview(
      {
        role: role.value.trim(),
        kind: kind.value,
        jobId: jobId.value || undefined,
        resumeId: resumeId.value || undefined,
        versionId: versionId.value || undefined,
        difficulty: difficulty.value,
        mode: mode.value,
        durationMinutes: durationMinutes.value,
        questionCount: questionCount.value,
        industry: industry.value,
        feedbackMode: feedbackMode.value,
        typeMix: customMix.value ? typeMix.value : {},
      },
      controller.signal,
    )
    if (controller.signal.aborted) return
    void cache.invalidateQueries({ queryKey: ['interviews'] })
    await router.push('/interviews/' + session.id)
  } catch (e) {
    if (!controller.signal.aborted) error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

function exportLegacy(session: Interview) {
  exportInterview(
    session.role,
    questions.map((questionText, questionIndex) => ({
      questionIndex,
      questionText,
      answerText: session.answers[questionIndex] || '',
    })),
  )
}
</script>
<template>
  <section class="page-stack overflow-safe">
    <PageHeading
      :title="uiText('Be ready to tell your story')"
      :description="uiText('Practice explaining your experience clearly and build confidence.')"
    />
    <div class="split-content">
      <form class="panel form-stack" @submit.prevent="start">
        <h2>{{ uiText('Create a mock interview') }}</h2>
        <p v-if="typeof route.query.jobId === 'string'" class="helper-note">
          {{
            uiText(
              "This practice session will be linked to the selected job's application workspace.",
            )
          }}<RouterLink :to="'/jobs/' + route.query.jobId">{{ uiText('View job') }}</RouterLink>
        </p>
        <div class="form-grid">
          <label class="field"
            >{{ uiText('Linked job (optional)')
            }}<select v-model="jobId" :disabled="busy">
              <option value="">{{ uiText('No linked job') }}</option>
              <option
                v-if="jobId && !applications.data.value?.some((item) => item.jobId === jobId)"
                :value="jobId"
              >
                {{ role }}
              </option>
              <option v-for="item in applications.data.value" :key="item.id" :value="item.jobId">
                {{ item.job?.title || uiText('Job details unavailable') }}
              </option>
            </select></label
          >
          <p v-if="applications.isPending.value" role="status">
            {{ uiText('Loading saved jobs…') }}
          </p>
          <p v-if="applications.isError.value" role="alert">
            {{ formatDisplay(applications.error.value?.message) }}
            <button type="button" class="btn" @click="applications.refetch()">
              {{ uiText('Retry jobs') }}
            </button>
          </p>
          <label class="field"
            >{{ uiText('Resume')
            }}<select v-model="resumeId" :disabled="busy || resumes.isPending.value">
              <option value="">{{ uiText('No resume (general practice)') }}</option>
              <option
                v-for="item in resumes.data.value?.items.filter(
                  (item) => item.parseStatus === 'READY',
                )"
                :key="item.id"
                :value="item.id"
              >
                {{ item.title }}
              </option>
            </select></label
          >
          <p v-if="resumes.isPending.value" role="status">{{ uiText('Loading resumes…') }}</p>
          <p v-if="resumes.isError.value" role="alert">
            {{ formatDisplay(resumes.error.value?.message) }}
            <button type="button" class="btn" @click="resumes.refetch()">
              {{ uiText('Retry resumes') }}
            </button>
          </p>
          <label v-if="resumeId" class="field"
            >{{ uiText('Resume version')
            }}<select v-model="versionId" required :disabled="busy || versions.isPending.value">
              <option value="">{{ uiText('Select a saved version') }}</option>
              <option v-for="item in versions.data.value" :key="item.id" :value="item.id">
                {{
                  uiText('Version {0} · {1}', [
                    item.versionNo,
                    new Date(item.createdAt).toLocaleDateString(language),
                  ])
                }}
              </option>
            </select></label
          >
          <p v-if="resumeId && versions.isPending.value" role="status">
            {{ uiText('Loading versions…') }}
          </p>
          <p v-if="resumeId && versions.isError.value" role="alert">
            {{ formatDisplay(versions.error.value?.message) }}
            <button type="button" class="btn" @click="versions.refetch()">
              {{ uiText('Retry versions') }}
            </button>
          </p>
          <label class="field"
            >{{ uiText('Target job')
            }}<input
              v-model="role"
              :disabled="busy"
              required
              maxlength="100"
              :placeholder="uiText('Enter the role you want to practice for')" /></label
          ><label class="field"
            >{{ uiText('Interview type')
            }}<select v-model="kind" :disabled="busy">
              <option :value="sourceText('Technical')">{{ uiText('Technical') }}</option>
              <option :value="sourceText('Behavioral')">{{ uiText('Behavioral') }}</option>
              <option>HR</option>
              <option :value="sourceText('General')">{{ uiText('General') }}</option>
            </select></label
          >
        </div>
        <div class="form-grid">
          <label class="field"
            >{{ uiText('Difficulty')
            }}<select v-model="difficulty" :disabled="busy">
              <option value="EASY">{{ uiText('Beginner') }}</option>
              <option value="STANDARD">{{ uiText('Intermediate') }}</option>
              <option value="HARD">{{ uiText('Advanced') }}</option>
            </select></label
          >
          <label class="field"
            >{{ uiText('Mode')
            }}<select v-model="mode" :disabled="busy">
              <option value="TEXT">{{ uiText('Text') }}</option>
              <option value="VOICE" :disabled="!voiceAvailable">
                {{
                  voiceAvailable
                    ? uiText('Voice (browser recognition and speech)')
                    : uiText('Voice (unavailable)')
                }}
              </option>
            </select></label
          >
          <label class="field"
            >{{ uiText('Question count')
            }}<input
              v-model.number="questionCount"
              type="number"
              min="3"
              max="15"
              required
              :disabled="busy"
          /></label>
          <label class="field"
            >{{ uiText('Industry (optional)')
            }}<input
              v-model="industry"
              maxlength="100"
              :placeholder="uiText('For example: healthcare, education, consumer goods')"
              :disabled="busy"
          /></label>
          <label class="field"
            >{{ uiText('Feedback timing')
            }}<select v-model="feedbackMode" :disabled="busy">
              <option value="IMMEDIATE">{{ uiText('After each answer') }}</option>
              <option value="END">{{ uiText('At the end') }}</option>
            </select></label
          >
          <label class="field"
            >{{ uiText('Duration')
            }}<select v-model="durationMinutes" :disabled="busy">
              <option :value="15">{{ uiText('15 minutes') }}</option>
              <option :value="30">{{ uiText('30 minutes') }}</option>
              <option :value="45">{{ uiText('45 minutes') }}</option>
              <option :value="60">{{ uiText('60 minutes') }}</option>
            </select></label
          >
        </div>
        <fieldset class="form-stack" :disabled="busy">
          <legend>{{ uiText('Question mix') }}</legend>
          <label
            ><input v-model="customMix" type="checkbox" />{{
              uiText('Customize counts by question type (automatic selection by role by default)')
            }}</label
          >
          <div v-if="customMix" class="form-grid">
            <label v-for="(label, type) in questionTypeLabels" :key="type" class="field"
              >{{ label
              }}<input v-model.number="typeMix[type]" type="number" min="0" max="15" required
            /></label>
          </div>
          <p v-if="mixInvalid" role="alert">
            {{ uiText('Question counts total {0}; expected {1}.', [mixTotal, questionCount]) }}
          </p>
        </fieldset>
        <p class="helper-note">
          {{
            uiText(
              'Any role is supported. Enter the room to generate questions. Rule-based fallback questions are labeled when the model is unavailable, and unavailable scores remain unscored. Improved answers use only real answers and resume facts.',
            )
          }}
        </p>
        <p v-if="error" role="alert">{{ error }}</p>
        <button
          class="btn primary"
          type="submit"
          :disabled="busy || !role.trim() || invalidSelection || mixInvalid"
        >
          {{ busy ? uiText('Creating…') : uiText('Start practice') }}
        </button>
      </form>
      <aside class="panel form-stack">
        <span class="stat-icon"><AppIcon name="interviews" /></span>
        <h2>{{ uiText('Clarity comes first') }}</h2>
        <p class="subtle">
          {{
            uiText(
              'Structure project stories with STAR: situation, task, action and result. Include concrete details wherever possible.',
            )
          }}
        </p>
        <span class="tag">{{
          uiText('Question-by-question practice · Adjustable count and feedback timing')
        }}</span>
      </aside>
    </div>
    <section class="panel">
      <div class="section-heading">
        <h2>{{ uiText('My practice history') }}</h2>
        <span class="subtle">{{
          uiText('{0} practice sessions', [query.data.value?.length ?? 0])
        }}</span>
      </div>
      <p v-if="query.isPending.value" role="status">{{ uiText('Loading practice history…') }}</p>
      <div v-else-if="query.isError.value" class="empty-state" role="alert">
        <p>{{ formatDisplay(query.error.value?.message) }}</p>
        <button class="btn" @click="query.refetch()">{{ uiText('Reload #2') }}</button>
      </div>
      <div v-else-if="!query.data.value?.length" class="empty-state">
        <h2>{{ uiText('Start your first practice with an introduction') }}</h2>
        <p>{{ uiText('Completed answers stay here for repeated review.') }}</p>
      </div>
      <article
        v-for="session in query.data.value"
        :key="session.id"
        class="list-row interview-record"
      >
        <span class="stat-icon"><AppIcon name="interviews" /></span>
        <div>
          <h3>
            <RouterLink :to="'/interviews/' + session.id">{{ session.role }}</RouterLink>
          </h3>
          <InterviewJobContext :job-id="session.jobId" :fallback-title="session.role" />
          <small
            >{{ translateLabel(session.kind) }} ·
            {{ new Date(session.createdAt).toLocaleString(language) }}</small
          >
        </div>
        <div class="interview-record-actions">
          <span class="tag">{{
            session.completed ? uiText('Completed') : uiText('In progress')
          }}</span>
          <RouterLink :to="'/interviews/' + session.id">{{
            session.completed ? uiText('View report') : uiText('Continue practice')
          }}</RouterLink>
        </div>
      </article>
    </section>
    <section v-if="workspace.state.interviews.length" class="panel">
      <div class="section-heading">
        <h2>{{ uiText('Older local practice') }}</h2>
      </div>
      <p class="helper-note">
        {{
          uiText(
            'These records stay in this browser. You can export the original answers. New sessions and drafts are saved on the server.',
          )
        }}
      </p>
      <article v-for="session in workspace.state.interviews" :key="session.id" class="list-row">
        <div>
          <h3>{{ session.role }}</h3>
          <small>{{ translateLabel(session.kind) }} · {{ formatDateTime(session.date) }}</small>
        </div>
        <button class="btn" @click="exportLegacy(session)">
          {{ uiText('Export older practice') }}
        </button>
      </article>
    </section>

    <section class="panel">
      <div class="section-heading">
        <h2>{{ uiText('STAR story library') }}</h2>
        <span class="subtle"
          >{{ formatDisplay(stories.data.value?.items.length ?? 0) }} /
          {{ formatDisplay(stories.data.value?.limit ?? 30) }}</span
        >
      </div>
      <p class="helper-note">
        {{
          uiText(
            'STAR answers you confirm from completed practice can be cited as evidence in future interview conversations.',
          )
        }}
      </p>
      <p v-if="storyError" role="alert">{{ storyError }}</p>
      <p v-if="stories.isPending.value" role="status">{{ uiText('Loading story library…') }}</p>
      <div v-else-if="stories.isError.value" class="empty-state" role="alert">
        <p>{{ formatDisplay(stories.error.value?.message) }}</p>
        <button class="btn" @click="stories.refetch()">{{ uiText('Reload #2') }}</button>
      </div>
      <div v-else-if="!stories.data.value?.items.length" class="empty-state">
        <h2>{{ uiText('No saved stories yet') }}</h2>
        <p>
          {{
            uiText(
              'After practice, save answers with sufficient STAR details from the review page.',
            )
          }}
        </p>
      </div>
      <article
        v-for="story in stories.data.value?.items"
        :key="story.id"
        class="answer-card"
        style="margin-top: 12px"
      >
        <h3>{{ story.title }}</h3>
        <p class="subtle">
          {{ story.role || uiText('Role not specified') }} · {{ formatDisplay(story.starCount) }}/4
          STAR
        </p>
        <p class="prose-content">
          {{ story.body.slice(0, 200) }}{{ formatDisplay(story.body.length > 200 ? '…' : '') }}
        </p>
        <button
          type="button"
          class="btn"
          :disabled="Boolean(storyBusy)"
          @click="removeStory(story.id)"
        >
          {{ storyBusy === story.id ? uiText('Deleting…') : uiText('Delete') }}
        </button>
      </article>
    </section>
  </section>
</template>

<style scoped>
.interview-record > .interview-record-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  min-width: 0;
  max-width: 100%;
}
@media (max-width: 600px) {
  .interview-record {
    display: grid;
    grid-template-columns: 40px minmax(0, 1fr);
    align-items: start;
  }
  .interview-record-actions {
    grid-column: 2;
  }
}
</style>
