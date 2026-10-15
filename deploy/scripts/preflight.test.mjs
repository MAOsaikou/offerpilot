import test from 'node:test'
import assert from 'node:assert/strict'
import { validateDeployment } from './preflight.mjs'

function fixture() {
  const password = 'fictional-fixture-password-only'
  const key = 'fictional-fixture-provider-key'
  return { services: {
    postgres: { environment: { POSTGRES_PASSWORD: password }, ports: [{ host_ip: '127.0.0.1' }] },
    redis: { environment: { REDIS_PASSWORD: password }, ports: [{ host_ip: '127.0.0.1' }] },
    minio: { image: `quay.io/minio/minio@sha256:${'a'.repeat(64)}`,
      environment: { MINIO_ROOT_USER: 'fixture-user', MINIO_ROOT_PASSWORD: password },
      volumes: [{ source: 'minio_auth_data', target: '/data' }], ports: [{ host_ip: '127.0.0.1' }] },
    'jobs-mcp': { ports: [{ host_ip: '127.0.0.1' }] },
    frontend: {},
    caddy: { environment: { DOMAIN: 'app.offerpilot-preflight.io', STORAGE_DOMAIN: 'storage.offerpilot-preflight.io' },
      volumes: [{ target: '/etc/caddy/optional/storage-auth.caddy', read_only: true }] },
    backend: { environment: { SPRING_PROFILES_ACTIVE: 'prod',
      JWT_SECRET: 'fictional-fixture-jwt-only-'.repeat(4), DATA_ENCRYPTION_KEY: Buffer.alloc(32, 7).toString('base64'),
      POSTGRES_PASSWORD: password, REDIS_PASSWORD: password, MINIO_ROOT_USER: 'fixture-user', MINIO_ROOT_PASSWORD: password,
      MINIO_PUBLIC_ENDPOINT: 'https://storage.offerpilot-preflight.io', CORS_ORIGINS: 'https://app.offerpilot-preflight.io',
      MINIO_ENDPOINT: 'http://minio:9000',
      DEMO_ACCOUNT_ENABLED: 'true', DEMO_SANDBOX_ENABLED: 'true', DEMO_SANDBOX_DAILY_TOKEN_LIMIT: '200000',
      DEMO_SANDBOX_MAX_ACTIVE: '10', DEMO_SANDBOX_MAX_PER_DAY: '30', AUTH_DEMO_LOGIN_PER_MINUTE: '5',
      DEEPSEEK_API_KEY: key, BAILIAN_API_KEY: '', MCP_SERVER_ENABLED: 'false',
    } },
  } }
}

test('accepts separate authenticated storage and isolated visitors, while reporting embedding scope', () => {
  const result = validateDeployment(fixture())
  assert.deepEqual(result.errors, [])
  assert.match(result.warnings.join('\n'), /Embedding is unavailable/)
})

test('rejects public S3Mock even when its published port binds to loopback', () => {
  const config = fixture()
  config.services.minio.image = `adobe/s3mock@sha256:${'a'.repeat(64)}`
  assert.match(validateDeployment(config).errors.join('\n'), /S3Mock is rejected/)
})

test('rejects public data ports and direct backend access', () => {
  const config = fixture()
  config.services.postgres.ports[0].host_ip = '0.0.0.0'
  config.services.backend.ports = [{ host_ip: '127.0.0.1' }]
  const errors = validateDeployment(config).errors.join('\n')
  assert.match(errors, /postgres published ports/)
  assert.match(errors, /through Caddy only/)
})

test('rejects shared visitor mode, missing quotas and a mismatched public signature host', () => {
  const config = fixture()
  config.services.backend.environment.DEMO_SANDBOX_ENABLED = 'false'
  config.services.backend.environment.DEMO_SANDBOX_MAX_ACTIVE = '0'
  config.services.backend.environment.MINIO_PUBLIC_ENDPOINT = 'http://minio:9000'
  config.services.backend.environment.DEMO_SANDBOX_TTL = '48h'
  const errors = validateDeployment(config).errors.join('\n')
  assert.match(errors, /isolated visitor/)
  assert.match(errors, /MAX_ACTIVE/)
  assert.match(errors, /MINIO_PUBLIC_ENDPOINT/)
  assert.match(errors, /DEMO_SANDBOX_TTL/)
})

test('rejects placeholder domains, credentials and reuse of the S3Mock volume without echoing values', () => {
  const config = fixture()
  const secret = 'secret-value-must-never-be-printed'
  config.services.backend.environment.POSTGRES_PASSWORD = secret
  config.services.backend.environment.JWT_SECRET = 'change-me'
  config.services.backend.environment.DATA_ENCRYPTION_KEY = 'bad'
  config.services.backend.environment.DEEPSEEK_API_KEY = 'replace-with-key'
  config.services.caddy.environment.DOMAIN = 'demo.example.com'
  config.services.minio.volumes[0].source = 'minio_data'
  const errors = validateDeployment(config).errors.join('\n')
  for (const marker of ['DOMAIN', 'JWT_SECRET', 'DATA_ENCRYPTION_KEY', 'model provider', 'separate data volume']) assert.match(errors, new RegExp(marker))
  assert.ok(!errors.includes(secret))
  assert.ok(!errors.includes('replace-with-key'))
})
