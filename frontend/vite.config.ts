import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv, type ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const proxyTarget = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8081'
  // changeOrigin 只改写 Host；浏览器的 Origin 若原样转发，后端会把同源代理请求当作跨域，
  // 通过 127.0.0.1 或局域网地址打开开发服务器时被 CORS 拒绝（403）
  const sameOrigin: ProxyOptions = {
    target: proxyTarget,
    changeOrigin: true,
    configure: (proxy) => proxy.on('proxyReq', (request) => request.removeHeader('origin')),
  }

  return {
    plugins: [vue(), tailwindcss()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/actuator/health': sameOrigin,
        '/v3/api-docs': { target: proxyTarget, changeOrigin: true },
        '/swagger-ui': { target: proxyTarget, changeOrigin: true },
        // 本地开发把 /api 转发到后端，避免 CORS
        '/api': sameOrigin,
      },
    },
    build: {
      target: 'es2022',
      sourcemap: false,
    },
  }
})
