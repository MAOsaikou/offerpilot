import { computed, ref } from 'vue'
import { identitySignal } from '@/shared/utils/authSession'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { favoriteJob, listApplications, updateApplication } from '@/shared/api/applications'
import type { ApplicationPatch, ServerApplication } from '@/shared/types/board'

export function useBoard() {
  const client = useQueryClient()
  const query = useQuery({
    queryKey: ['applications'],
    queryFn: ({ signal }) => listApplications(signal),
    retry: false,
  })
  const applications = computed(() => query.data.value ?? [])
  const busy = ref(false)
  const error = ref('')
  async function write(action: () => Promise<ServerApplication>) {
    if (busy.value) return false
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
  return {
    query,
    applications,
    busy,
    error,
    saved: (jobId: string) => applications.value.some((item) => item.jobId === jobId),
    favorite: (jobId: string) => write(() => favoriteJob(jobId)),
    update: (id: string, patch: ApplicationPatch) => write(() => updateApplication(id, patch)),
  }
}
