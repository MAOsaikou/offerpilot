import { computed, ref } from 'vue'
import { identitySignal } from '@/shared/utils/authSession'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { favoriteJob, listApplications, updateApplication } from '@/shared/api/applications'
import type { ApplicationPatch, ApplicationStatus, ServerApplication } from '@/shared/types/board'

/**
 * Stage failures outlive the card instance: an optimistic move remounts the card in the new column,
 * and a rejected save remounts it back. The notice has to be readable by that new instance.
 */
const stageNotice = ref('')

/**
 * `cardScope` is for components rendered per card: they read the list the board already loaded, so a
 * card that remounts in a new column (drag-and-drop) must not refetch and overwrite an optimistic move.
 */
export function useBoard(options: { cardScope?: boolean } = {}) {
  const client = useQueryClient()
  const query = useQuery({
    queryKey: ['applications'],
    queryFn: ({ signal }) => listApplications(signal),
    retry: false,
    refetchOnMount: !options.cardScope,
  })
  const applications = computed(() => query.data.value ?? [])
  const busy = ref(false)
  const error = ref('')
  async function write(action: () => Promise<ServerApplication>) {
    if (busy.value) return false
    stageNotice.value = ''
    busy.value = true
    error.value = ''
    const session = identitySignal()
    try {
      // Cancel older reads so a late response cannot undo the saved state.
      await client.cancelQueries({ queryKey: ['applications'] })
      const saved = await action()
      session.throwIfAborted()
      client.setQueryData<ServerApplication[]>(['applications'], (items = []) => [
        saved,
        ...items.filter((item) => item.id !== saved.id),
      ])
      void client.invalidateQueries({ queryKey: ['applications'] })
      return true
    } catch (e) {
      error.value = (e as Error).message
      return false
    } finally {
      busy.value = false
    }
  }
  /** Drag-and-drop stage change: show the new column at once, roll back if the server refuses. */
  async function move(id: string, status: ApplicationStatus) {
    const current = applications.value.find((item) => item.id === id)
    if (!current || current.status === status || busy.value) return false
    const previous = client.getQueryData<ServerApplication[]>(['applications'])
    const place = (items: ServerApplication[] = []) =>
      items.map((item) => (item.id === id ? { ...item, status } : item))
    stageNotice.value = ''
    // Paint the new column before any await. Re-apply after cancel so a late read cannot put it back.
    client.setQueryData<ServerApplication[]>(['applications'], place)
    await client.cancelQueries({ queryKey: ['applications'] })
    client.setQueryData<ServerApplication[]>(['applications'], place)
    const ok = await write(() => updateApplication(id, { status }))
    if (!ok && previous) {
      client.setQueryData(['applications'], previous)
      stageNotice.value = error.value
    }
    return ok
  }
  return {
    query,
    move,
    applications,
    busy,
    error,
    stageNotice,
    saved: (jobId: string) => applications.value.some((item) => item.jobId === jobId),
    favorite: (jobId: string) => write(() => favoriteJob(jobId)),
    update: (id: string, patch: ApplicationPatch) => write(() => updateApplication(id, patch)),
  }
}
