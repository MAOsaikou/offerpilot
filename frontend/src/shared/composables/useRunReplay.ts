import { localizedText, translate as uiText } from '@/shared/utils/language'
import { computed, onScopeDispose, ref } from 'vue'
import { createPinia } from 'pinia'
import { getAgentWorkbench } from '@/shared/api/agent'
import { useAgentRunStore } from '@/shared/stores/agentRun'
import type { AgentEvent } from '@/shared/types/agent-events'

/** A separate store instance protects live runs; both paths use exactly the same apply reducer. */
export function useRunReplay() {
  const store = useAgentRunStore(createPinia())
  const events = ref<AgentEvent[]>([])
  const runId = ref('')
  const position = ref(0)
  const loading = ref(false)
  const playing = ref(false)
  const error = localizedText()
  const speed = ref(1)
  let generation = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  const run = computed(() => store.activeRun)
  const complete = computed(() => position.value >= events.value.length)
  const partial = computed(
    () =>
      events.value.length > 0 &&
      !events.value.some((e) => e.type === 'text.delta' || e.type === 'ui.card'),
  )
  function pause() {
    clearTimeout(timer)
    playing.value = false
  }
  function step() {
    pause()
    const event = events.value[position.value]
    if (!event) return
    store.apply(event)
    position.value++
  }
  function tick() {
    step()
    if (complete.value) return
    playing.value = true
    timer = setTimeout(tick, 500 / speed.value)
  }
  function play() {
    pause()
    if (!complete.value) tick()
  }
  function seek(value: number) {
    pause()
    store.reset()
    position.value = 0
    const target = Math.min(Math.max(0, value), events.value.length)
    while (position.value < target) step()
  }
  function close() {
    ++generation
    pause()
    store.reset()
    events.value = []
    runId.value = ''
    position.value = 0
    loading.value = false
    error.value = ''
  }
  async function open(id: string) {
    close()
    const version = generation
    runId.value = id
    loading.value = true
    try {
      const history = await getAgentWorkbench(id)
      if (version !== generation) return
      events.value = [...history].sort((a, b) => a.id - b.id)
      if (!history.length) error.value = () => (uiText("No replayable events in this run."))
    } catch (failure) {
      if (version === generation)
        error.value = () => (failure instanceof Error ? failure.message : uiText("Could not load replay. Please retry."))
    } finally {
      if (version === generation) loading.value = false
    }
  }
  onScopeDispose(close)
  return {
    runId,
    run,
    events,
    position,
    loading,
    playing,
    error,
    speed,
    complete,
    partial,
    open,
    close,
    play,
    pause,
    step,
    seek,
  }
}
