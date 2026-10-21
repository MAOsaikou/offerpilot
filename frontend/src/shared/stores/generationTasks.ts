import { localizedText, translate as uiText } from '@/shared/utils/language'
import { computed, onScopeDispose, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { ApiError } from '@/shared/api/client'
import { queryClient } from '@/shared/api/queryClient'
import { identitySignal, sessionRevision } from '@/shared/utils/authSession'
import {
  cancelBackgroundTask,
  generationResult,
  getGenerationTask,
  isActiveTask,
  recentGenerationTasks,
  startGenerationTask,
  type BackgroundTask,
  type GenerationKind,
} from '@/shared/api/generationTasks'

export const useGenerationTasks = defineStore('generation-tasks', () => {
  const tasks = ref<BackgroundTask[]>([])
  const error = localizedText()
  const notice = localizedText()
  const startedTaskId = ref('')
  const active = computed(() => tasks.value.filter(isActiveTask))
  let timer: ReturnType<typeof setTimeout> | undefined
  let epoch = 0
  let delay = 2000
  let pending: Promise<void> | undefined
  function merge(rows: BackgroundTask[], notify = true) {
    const previous = new Map(tasks.value.map((t) => [t.id, t]))
    for (const task of rows) {
      const old = previous.get(task.id)
      // A slow restore response cannot resurrect an immutable terminal task.
      if (old && !isActiveTask(old) && isActiveTask(task)) continue
      if (notify && old && isActiveTask(old) && !isActiveTask(task)) {
        for (const key of [
          'job-matches',
          'diagnoses',
          'optimizations',
          'applications',
          'interviews',
          'interview-report',
        ])
          void queryClient.invalidateQueries({ queryKey: [key] })
        notice.value = () =>
          `${task.title}：${task.status === 'SUCCEEDED' ? uiText('Completed') : task.error || task.stage}`
      }
      previous.set(task.id, task)
    }
    tasks.value = [...previous.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  }
  function schedule() {
    clearTimeout(timer)
    timer = undefined
    if (active.value.length) timer = setTimeout(() => void refresh(), delay)
  }
  async function refresh() {
    if (pending) return pending
    const version = epoch
    pending = (async () => {
      try {
        const rows = await recentGenerationTasks()
        if (version !== epoch) return
        tasks.value = tasks.value.filter(
          (t) => !isActiveTask(t) || rows.some((row) => row.id === t.id),
        )
        merge(rows)
        error.value = ''
        delay = Math.min(10000, delay * 1.3)
      } catch (cause) {
        if (version !== epoch) return
        error.value = () => (cause instanceof Error ? cause.message : uiText('Could not load task'))
        delay = Math.min(30000, delay * 2)
      } finally {
        if (version === epoch) {
          pending = undefined
          schedule()
        }
      }
    })()
    return pending
  }
  async function restore(kind: GenerationKind, entity: string, selectedId?: string) {
    const version = epoch
    const rows = await recentGenerationTasks(kind, entity)
    if (selectedId && !rows.some((t) => t.id === selectedId)) {
      const selected = await getGenerationTask(selectedId)
      if (selected.kind === kind && selected.entityId === entity) rows.push(selected)
    }
    if (version !== epoch) return
    merge(rows)
    schedule()
  }
  function latest(kind: string, entity: string) {
    return tasks.value.find((t) => t.kind === kind && t.entityId === entity)
  }
  async function cancel(id: string) {
    await cancelBackgroundTask(id)
    await refresh()
  }
  async function run<T>(
    kind: GenerationKind,
    entity: string,
    input: object,
    requestId: string,
    signal?: AbortSignal,
    started?: (id: string) => void,
  ): Promise<T> {
    const version = epoch
    const session = identitySignal()
    session.throwIfAborted()
    signal?.throwIfAborted()
    const task = await startGenerationTask(kind, entity, input, requestId)
    session.throwIfAborted()
    if (version !== epoch) throw new Error(uiText('Identity changed'))
    merge([task], false)
    startedTaskId.value = task.id
    started?.(task.id)
    delay = 2000
    schedule()
    const done = await new Promise<BackgroundTask>((resolve, reject) => {
      const combined = signal ? AbortSignal.any([signal, session]) : session
      let stop = () => {}
      const cleanup = () => {
        stop()
        combined.removeEventListener('abort', abort)
      }
      const abort = () => {
        cleanup()
        reject(
          new DOMException(
            uiText('Observation ended; the background task continues'),
            'AbortError',
          ),
        )
      }
      stop = watch(
        () => tasks.value.find((t) => t.id === task.id),
        (current) => {
          if (!current) {
            cleanup()
            reject(new Error(uiText('Task deleted or access denied. Please reload.')))
          } else if (!isActiveTask(current)) {
            cleanup()
            resolve(current)
          }
        },
        { flush: 'sync' },
      )
      combined.addEventListener('abort', abort, { once: true })
      if (combined.aborted) abort()
      else if (!isActiveTask(task)) {
        cleanup()
        resolve(task)
      }
    })
    if (done.status !== 'SUCCEEDED')
      throw new ApiError(done.error || done.stage, {
        code: done.status === 'CANCELLED' ? 30205 : 50000,
      })
    return generationResult<T>(done.id)
  }
  function reset() {
    ++epoch
    clearTimeout(timer)
    timer = undefined
    pending = undefined
    tasks.value = []
    error.value = ''
    notice.value = ''
    startedTaskId.value = ''
    delay = 2000
  }
  watch(
    () => {
      void sessionRevision.value
      return identitySignal()
    },
    reset,
    { flush: 'sync' },
  )
  onScopeDispose(reset)
  return {
    tasks,
    active,
    error,
    notice,
    startedTaskId,
    refresh,
    restore,
    latest,
    cancel,
    run,
    reset,
  }
})
