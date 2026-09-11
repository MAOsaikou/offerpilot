export {}

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题，用于 document.title 与顶栏 */
    title?: string
    /** 是否无需登录即可访问 */
    public?: boolean
  }
}
