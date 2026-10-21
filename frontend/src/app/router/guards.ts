import { watch } from 'vue'
import { language, translateLabel } from '@/shared/utils/language'
import type { Router } from 'vue-router'
import { useAuthStore } from '@/shared/stores/auth'

const APP_NAME = import.meta.env.VITE_APP_NAME || 'OfferPilot'

export function registerGuards(router: Router): void {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    await auth.refreshSession()
    if (to.meta.staff && !auth.isStaff) {
      return { name: 'staff-login', query: { redirect: to.fullPath } }
    }
    // 未登录访问受保护页面 → 跳转登录页并记录来路
    if (!to.meta.public && !auth.isAuthenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    // 已登录访问登录页 → 直接回工作台
    if (to.name === 'login' && auth.isLoggedIn) {
      return { path: '/' }
    }
    if (to.name === 'staff-login' && auth.isStaff) return { path: '/staff' }
    return true
  })

  watch(language, () => {
    const title = router.currentRoute.value.meta.title
    document.title = title ? `${translateLabel(title)} · ${APP_NAME}` : APP_NAME
  })
  router.afterEach((to) => {
    document.title = to.meta.title ? `${translateLabel(to.meta.title)} · ${APP_NAME}` : APP_NAME
  })
}
