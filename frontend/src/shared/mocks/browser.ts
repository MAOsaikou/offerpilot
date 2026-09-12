import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'

/** 开发期拦截后端请求的 Service Worker，仅在 VITE_ENABLE_MOCK 打开时启动 */
export const worker = setupWorker(...handlers)
