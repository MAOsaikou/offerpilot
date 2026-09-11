import type { Router } from 'vue-router'

const APP_NAME = import.meta.env.VITE_APP_NAME || 'OfferPilot'

export function registerGuards(router: Router): void {
  router.beforeEach(() => {
    // TODO(auth): 接入 useAuthStore 后启用登录守卫
    // const auth = useAuthStore()
    // if (!to.meta.public && !auth.isAuthenticated) {
    //   return { name: 'login', query: { redirect: to.fullPath } }
    // }
    return true
  })

  router.afterEach((to) => {
    document.title = to.meta.title ? `${to.meta.title} · ${APP_NAME}` : APP_NAME
  })
}
