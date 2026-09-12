import { QueryClient } from '@tanstack/vue-query'

/**
 * 全局 QueryClient。
 * 服务端数据（会话、简历、岗位、看板、报告）统一由 TanStack Query 管理，
 * 客户端会话状态放 Pinia —— 见 docs/07 §5.1。
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
})
