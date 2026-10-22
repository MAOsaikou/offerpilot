import { fileURLToPath, URL } from 'node:url'
import { configDefaults, defineConfig } from 'vitest/config'
import { parseCLI } from 'vitest/node'
import vue from '@vitejs/plugin-vue'

// Protocol and pure-function tests need no DOM or jsdom startup.
const nodeTests = [
  'src/shared/api/sse.spec.ts',
  'src/shared/api/diagnoses.spec.ts',
  'src/shared/api/optimizations.spec.ts',
  'src/shared/stores/agentRun.spec.ts',
  'src/shared/types/model-tier.spec.ts',
  'src/features/chat/optimizationContext.spec.ts',
]

// Keep identity, transport, approval and recovery boundaries in the everyday suite.
const essentialTests = [
  'src/app/router/spa-nav.spec.ts',
  'src/features/chat/composables/useConversationChat.spec.ts',
  'src/features/settings/components/DeleteMyData.spec.ts',
  'src/features/settings/components/MemorySettings.spec.ts',
  'src/features/settings/components/PrivacySettings.spec.ts',
  'src/shared/api/agent.spec.ts',
  'src/shared/api/auth.spec.ts',
  'src/shared/api/client.spec.ts',
  'src/shared/api/sse.spec.ts',
  'src/shared/components/PendingAgentWrites.spec.ts',
  'src/shared/composables/useAgentStream.spec.ts',
  'src/shared/stores/agentRun.spec.ts',
  'src/shared/stores/auth.spec.ts',
  'src/shared/stores/generationTasks.spec.ts',
  'src/shared/stores/workspace.spec.ts',
  'src/shared/types/model-tier.spec.ts',
  'src/shared/utils/markdown.spec.ts',
  'src/widgets/AgentPanel/ApprovalCard.spec.ts',
]

export default defineConfig(({ mode }) => {
  const { filter, options } = parseCLI(['vitest', ...process.argv.slice(2)])
  // Explicit file/name/changed-file selection must also reach non-default tests.
  const full =
    mode === 'full' ||
    filter.length > 0 ||
    Boolean(options.testNamePattern || options.changed || options.related)

  return {
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
          test: {
            name: 'node',
            environment: 'node',
            include: full ? nodeTests : nodeTests.filter((path) => essentialTests.includes(path)),
          },
        },
        {
          extends: true,
          test: {
            name: 'dom',
            environment: 'jsdom',
            include: full ? ['src/**/*.spec.ts'] : essentialTests,
            exclude: [...configDefaults.exclude, ...nodeTests],
          },
        },
      ],
    },
  }
})
