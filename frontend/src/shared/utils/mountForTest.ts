import { createApp, h, type Component } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
export function mountForTest(component: Component, props: Record<string, unknown> = {}) {
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp({ render: () => h(component, props) })
  app.use(
    createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: {} }],
    }),
  )
  app.mount(root)
  return {
    root,
    unmount: () => {
      app.unmount()
      root.remove()
    },
  }
}
