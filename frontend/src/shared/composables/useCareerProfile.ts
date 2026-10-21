import { localizedText, translate as uiText } from '@/shared/utils/language'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  getCareerProfile,
  saveCareerProfile,
  saveCareerTasks,
  type CareerProfile,
  type AssessmentConfirmed,
} from '@/shared/api/career'
import { useAuthStore } from '@/shared/stores/auth'
import { identitySignal } from '@/shared/utils/authSession'

// Both views use API state only; legacy workspace fields remain for other pages.
// Reads after route changes wait for the previous view's queued writes.
let writes: Promise<void> = Promise.resolve()
const emptyProfile = (): CareerProfile => ({
  target: '',
  city: '',
  skills: '',
  interests: [],
  major: '',
  courses: '',
  valuesText: '',
  assessment: null,
  completedTasks: [],
  updatedAt: null,
})

export function useCareerProfile() {
  const auth = useAuthStore()
  const profile = ref(emptyProfile())
  const loading = ref(true)
  const ready = ref(false)
  const error = localizedText()
  const dirty = { profile: ref(false), tasks: ref(false) }
  const revisions = { profile: 0, tasks: 0 }
  const pending = computed(() => dirty.profile.value || dirty.tasks.value)
  let timer: ReturnType<typeof setTimeout> | undefined
  let generation = 0
  let disposed = false
  let reader: AbortController | undefined
  let cityBeforeAssessment = ''

  async function load() {
    const current = ++generation
    reader?.abort()
    reader = new AbortController()
    const signal = reader.signal
    loading.value = true
    ready.value = false
    error.value = ''
    try {
      await writes
      signal.throwIfAborted()
      const result = await getCareerProfile(signal)
      if (current !== generation || disposed) return
      profile.value = result
      ready.value = true
    } catch (cause) {
      if (current === generation && !disposed)
        error.value = () => (cause instanceof Error ? cause.message : uiText("Could not load career profile. Please retry."))
    } finally {
      if (current === generation && !disposed) loading.value = false
    }
  }

  function enqueue(kind: 'profile' | 'tasks') {
    if (!ready.value) return
    const current = generation
    const revision = revisions[kind]
    const session = identitySignal()
    const { target, city, skills, interests, major, courses, valuesText, completedTasks } =
      profile.value
    const fields = { target, city, skills, interests: [...interests], major, courses, valuesText }
    const completed = [...completedTasks]
    writes = writes.then(async () => {
      try {
        // A pending old-owner edit must never acquire the new identity's token.
        session.throwIfAborted()
        const result = await (kind === 'profile'
          ? saveCareerProfile(fields)
          : saveCareerTasks(completed))
        if (current !== generation || disposed || revision !== revisions[kind]) return
        profile.value.updatedAt = result.updatedAt
        dirty[kind].value = false
        if (!pending.value) error.value = ''
      } catch (cause) {
        if (current === generation && !disposed && revision === revisions[kind])
          error.value = () => (cause instanceof Error ? cause.message : uiText("Could not save. Please retry."))
      }
    })
  }

  function saveProfile(delay = 400) {
    if (!ready.value) return
    dirty.profile.value = true
    revisions.profile++
    clearTimeout(timer)
    timer = undefined
    if (delay === 0) enqueue('profile')
    else
      timer = setTimeout(() => {
        timer = undefined
        enqueue('profile')
      }, delay)
  }

  function saveTasks() {
    if (!ready.value) return
    dirty.tasks.value = true
    revisions.tasks++
    enqueue('tasks')
  }

  async function flushProfile() {
    clearTimeout(timer)
    timer = undefined
    if (dirty.profile.value) enqueue('profile')
    await writes
    if (dirty.profile.value) throw new Error(error.value || uiText("Retry saving your career profile first"))
    cityBeforeAssessment = profile.value.city
  }

  function applyAssessment(assessment: AssessmentConfirmed | null) {
    profile.value.assessment = assessment
    // Preserve any edits made while assessment confirmation was in flight.
    if (assessment?.constraints.city && profile.value.city === cityBeforeAssessment) {
      profile.value.city = assessment.constraints.city
      // Requeue other edits made during confirmation with the confirmed city.
      if (dirty.profile.value) saveProfile(0)
    }
  }

  function retry() {
    if (!ready.value) return void load()
    clearTimeout(timer)
    timer = undefined
    if (dirty.profile.value) enqueue('profile')
    if (dirty.tasks.value) enqueue('tasks')
  }

  watch(
    () => auth.identityKey,
    () => {
      clearTimeout(timer)
      timer = undefined
      dirty.profile.value = dirty.tasks.value = false
      profile.value = emptyProfile()
      void load()
    },
    { immediate: true, flush: 'sync' },
  )

  onBeforeUnmount(() => {
    // Flush the debounce on SPA navigation; writes still use their captured identity.
    if (timer !== undefined) {
      clearTimeout(timer)
      enqueue('profile')
    }
    disposed = true
    reader?.abort()
  })

  return {
    profile,
    loading,
    ready,
    error,
    pending,
    saveProfile,
    saveTasks,
    retry,
    flushProfile,
    applyAssessment,
  }
}
