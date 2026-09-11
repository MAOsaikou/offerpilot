import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { VueQueryPlugin } from '@tanstack/vue-query'
import App from './App.vue'
import { router } from './router'
import { queryClient } from './providers/query'
import { initTheme } from '@/shared/utils/theme'
import '@/shared/styles/index.css'
import '@/shared/styles/transition.css'

/**
 * 后端就绪前用 MSW 顶替接口。
 * 开发环境默认开启，`VITE_ENABLE_MOCK=false` 可关掉。
 * 整段被 import.meta.env.DEV 包住，生产构建会被摇掉，msw 不会进产物。
 */
async function enableMocking(): Promise<void> {
  if (!import.meta.env.DEV) return
  if (import.meta.env.VITE_ENABLE_MOCK === 'false') return
  const { worker } = await import('@/shared/mocks/browser')
  await worker.start({ onUnhandledRequest: 'bypass' })
}

initTheme()

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(VueQueryPlugin, { queryClient })

enableMocking()
  .catch((error: unknown) => {
    console.error('[mock] 启动失败，将以真实后端模式运行', error)
  })
  .finally(() => {
    app.mount('#app')
  })
