import { fileURLToPath, URL } from 'node:url'
import { configDefaults, defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// Protocol and pure-function tests need no DOM or jsdom startup.
const nodeTests = [
  'src/shared/api/sse.spec.ts',
  'src/widgets/CardRenderer/registry.spec.ts',
  'src/shared/api/diagnoses.spec.ts',
  'src/shared/api/optimizations.spec.ts',
  'src/shared/api/resume-model-settings.spec.ts',
  'src/shared/types/model-tier.spec.ts',
  'src/features/chat/optimizationContext.spec.ts',
]

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    globals: true,
    projects: [
      {
        extends: true,
        test: { name: 'node', environment: 'node', include: nodeTests },
      },
      {
        extends: true,
        test: {
          name: 'dom',
          environment: 'jsdom',
          include: ['src/**/*.spec.ts'],
          exclude: [...configDefaults.exclude, ...nodeTests],
        },
      },
    ],
  },
})
