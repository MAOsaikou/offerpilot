import type { Router } from 'vue-router'
import { coverCurtain, revealCurtain } from '@/shared/composables/usePageCurtain'

const APP_NAME = import.meta.env.VITE_APP_NAME || 'OfferPilot'

export function registerGuards(router: Router): void {
  router.beforeEach(async (to, from) => {
    // TODO(auth): 接入 useAuthStore 后启用登录守卫
    // const auth = useAuthStore()
    // if (!to.meta.public && !auth.isAuthenticated) {
    //   return { name: 'login', query: { redirect: to.fullPath } }
    // }

    // 首次进入不做幕布，只有真正的页面切换才落下来
    if (from.name && to.fullPath !== from.fullPath) {
      await coverCurtain()
    }

    return true
  })

  router.afterEach((to) => {
    document.title = to.meta.title ? `${to.meta.title} · ${APP_NAME}` : APP_NAME

    // 等新页面完成一次绘制再拉幕，避免幕布抽走后露出空档
    requestAnimationFrame(() => {
      requestAnimationFrame(() => revealCurtain())
    })
  })
}
