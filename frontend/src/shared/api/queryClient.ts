import { QueryClient } from '@tanstack/vue-query'

/** 应用和身份边界共用此实例；切换身份时取消并清除服务端缓存。 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      retry: (count, error) => count < 1 && !('status' in error && error.status === 401),
      refetchOnWindowFocus: false,
    },
  },
})
