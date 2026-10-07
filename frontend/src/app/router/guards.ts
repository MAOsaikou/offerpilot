import type { Router } from 'vue-router'
import { useAuthStore } from '@/shared/stores/auth'

const APP_NAME = import.meta.env.VITE_APP_NAME || 'OfferPilot'

export function registerGuards(router: Router): void {
  router.beforeEach((to) => {
    const auth = useAuthStore()
    // 未登录访问受保护页面 → 跳转登录页并记录来路
    if (!to.meta.public && !auth.isAuthenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    // 已登录访问登录页 → 直接回工作台
    if (to.name === 'login' && auth.isAuthenticated) {
      return { path: '/' }
    }
    return true
  })

  router.afterEach((to) => {
    document.title = to.meta.title ? `${to.meta.title} · ${APP_NAME}` : APP_NAME
  })
}
