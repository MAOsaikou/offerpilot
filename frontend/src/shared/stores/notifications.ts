import { localizedText, translate as uiText } from '@/shared/utils/language'
import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { getUnreadCount } from '@/shared/api/notifications'
import { useAuthStore } from './auth'

export const useNotifications = defineStore('notifications', () => {
  const auth = useAuthStore()
  const unread = ref(0)
  const error = localizedText()
  let generation = 0
  let request = 0
  async function refresh() {
    const current = generation
    const revision = ++request
    try {
      const count = await getUnreadCount()
      if (current === generation && revision === request) {
        unread.value = count
        error.value = ''
      }
    } catch (cause) {
      if (current === generation && revision === request)
        error.value = () =>
          cause instanceof Error ? cause.message : uiText('Notifications unavailable')
    }
  }
  function setCount(count: number) {
    request++
    unread.value = count
    error.value = ''
  }
  watch(
    () => auth.identityKey,
    () => {
      generation++
      request++
      unread.value = 0
      error.value = ''
    },
    { flush: 'sync' },
  )
  return { unread, error, refresh, setCount }
})
