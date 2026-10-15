#!/usr/bin/env node
import { spawnSync } from 'node:child_process'
import { isIP } from 'node:net'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const placeholder = (value) => typeof value !== 'string' || !value.trim()
  || /replace-with|change-me|sk-not-configured|placeholder|your-key|^</i.test(value)
const hostname = (value) => typeof value === 'string' && /^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$/i.test(value)
  && value.length <= 253 && value.split('.').every((label) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label))
  && value.includes('.') && !isIP(value) && !/(^|\.)(localhost|local|test|invalid|example)$|(^|\.)example\.(com|net|org)$/i.test(value)
function durationSeconds(value) {
  const simple = /^(\d+)(ms|s|m|h|d)$/.exec(value)
  if (simple) return Number(simple[1]) * { ms: 0.001, s: 1, m: 60, h: 3600, d: 86400 }[simple[2]]
  const iso = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(value)
  return iso ? Number(iso[1] ?? 0) * 3600 + Number(iso[2] ?? 0) * 60 + Number(iso[3] ?? 0) : NaN
}

// Validate the rendered configuration, so process-environment overrides cannot bypass checks.
// Values, including credentials, never form part of the returned diagnostics.
export function validateDeployment(config) {
  const errors = [], warnings = []
  const require = (condition, message) => { if (!condition) errors.push(message) }
  const services = config?.services ?? {}
  for (const name of ['postgres', 'redis', 'minio', 'jobs-mcp', 'backend', 'frontend', 'caddy']) {
    require(!!services[name], `Missing service: ${name}`)
  }
  const backend = services.backend?.environment ?? {}
  const proxy = services.caddy?.environment ?? {}
  require(hostname(proxy.DOMAIN), 'DOMAIN must be a non-placeholder DNS hostname, without a scheme or port')
  require(hostname(proxy.STORAGE_DOMAIN) && proxy.STORAGE_DOMAIN !== proxy.DOMAIN,
    'STORAGE_DOMAIN must be a separate non-placeholder DNS hostname')
  require(backend.MINIO_PUBLIC_ENDPOINT === `https://${proxy.STORAGE_DOMAIN}`,
    'MINIO_PUBLIC_ENDPOINT must match https://STORAGE_DOMAIN')
  require(backend.MINIO_ENDPOINT === 'http://minio:9000', 'MINIO_ENDPOINT must point to the authenticated minio service')
  require((backend.CORS_ORIGINS ?? '').split(',').map((s) => s.trim()).includes(`https://${proxy.DOMAIN}`),
    'CORS_ORIGINS must include the HTTPS app origin')
  require(!placeholder(backend.JWT_SECRET) && Buffer.byteLength(backend.JWT_SECRET ?? '') >= 64,
    'JWT_SECRET must contain at least 64 bytes and no placeholder')
  const encryption = backend.DATA_ENCRYPTION_KEY ?? ''
  require(/^[A-Za-z0-9+/]{43}=$/.test(encryption) && Buffer.from(encryption, 'base64').length === 32,
    'DATA_ENCRYPTION_KEY must be base64 of 32 bytes')
  for (const [name, value] of [
    ['POSTGRES_PASSWORD', services.postgres?.environment?.POSTGRES_PASSWORD],
    ['REDIS_PASSWORD', services.redis?.environment?.REDIS_PASSWORD],
    ['MINIO_ROOT_PASSWORD', services.minio?.environment?.MINIO_ROOT_PASSWORD],
  ]) require(!placeholder(value) && Buffer.byteLength(value ?? '') >= 16, `${name} must be at least 16 bytes and no placeholder`)
  require(backend.REDIS_PASSWORD === services.redis?.environment?.REDIS_PASSWORD,
    'Backend and Redis passwords must match')
  require(backend.POSTGRES_PASSWORD === services.postgres?.environment?.POSTGRES_PASSWORD,
    'Backend and PostgreSQL passwords must match')
  const storage = services.minio ?? {}
  require(/@sha256:[a-f0-9]{64}$/i.test(storage.image ?? '') && !/s3mock|registry\.invalid|replace-with/i.test(storage.image ?? ''),
    'Storage must use an authenticated image pinned to a verified digest; S3Mock is rejected')
  require(!placeholder(storage.environment?.MINIO_ROOT_USER), 'MINIO_ROOT_USER must be configured')
  require(backend.MINIO_ROOT_USER === storage.environment?.MINIO_ROOT_USER
    && backend.MINIO_ROOT_PASSWORD === storage.environment?.MINIO_ROOT_PASSWORD,
    'Backend and authenticated storage credentials must match')
  require((storage.volumes ?? []).some((volume) => volume.source !== 'minio_data' && volume.target === '/data'),
    'Authenticated storage must use a separate data volume')
  require((services.caddy?.volumes ?? []).some((volume) => volume.target === '/etc/caddy/optional/storage-auth.caddy' && volume.read_only),
    'Caddy must mount the read-only storage proxy configuration')
  for (const name of ['postgres', 'redis', 'minio', 'jobs-mcp']) {
    require((services[name]?.ports ?? []).every((port) => ['127.0.0.1', '::1'].includes(port.host_ip)),
      `${name} published ports must bind to loopback`)
  }
  require(!(services.backend?.ports?.length), 'Backend must be reachable through Caddy only')
  require(backend.SPRING_PROFILES_ACTIVE === 'prod', 'Backend must use the prod profile')
  require(backend.DEMO_ACCOUNT_ENABLED === 'true' && backend.DEMO_SANDBOX_ENABLED === 'true',
    'Public demo must enable isolated visitor sandboxes')
  const ttl = durationSeconds(backend.DEMO_SANDBOX_TTL ?? '24h')
  require(ttl >= 1 && ttl <= 86400, 'DEMO_SANDBOX_TTL must be between 1s and 24h, with an explicit unit or ISO duration')
  for (const name of ['DEMO_SANDBOX_DAILY_TOKEN_LIMIT', 'DEMO_SANDBOX_MAX_ACTIVE', 'DEMO_SANDBOX_MAX_PER_DAY', 'AUTH_DEMO_LOGIN_PER_MINUTE']) {
    require(/^[1-9]\d*$/.test(backend[name] ?? ''), `${name} must be an explicit positive integer`)
  }
  require(backend.LLM_MOCK !== 'true' && backend.VITE_ENABLE_MOCK !== 'true', 'Mock providers must be disabled')
  for (const provider of new Set([backend.LLM_PROVIDER_FLASH ?? 'deepseek', backend.LLM_PROVIDER_PRO ?? 'deepseek', backend.LLM_VISION_PROVIDER ?? 'deepseek'])) {
    const variable = { deepseek: 'DEEPSEEK_API_KEY', bailian: 'BAILIAN_API_KEY', custom: 'CUSTOM_LLM_API_KEY' }[provider]
    require(!!variable && !placeholder(backend[variable]), 'Every selected model provider must have a non-placeholder API key')
  }
  if (backend.MCP_SERVER_ENABLED === 'true') require(!placeholder(backend.MCP_SERVER_API_KEY), 'Enabled MCP Server requires a non-placeholder API key')
  if (placeholder(backend.BAILIAN_API_KEY)) warnings.push('Embedding is unavailable; only rules/full-text fallback can be accepted')
  return { errors, warnings }
}

export function main(args = process.argv.slice(2)) {
  if (args.length !== 2 || args[0] !== '--env') {
    console.error('Usage: node deploy/scripts/preflight.mjs --env <ignored-env-file>')
    return 2
  }
  const envFile = resolve(args[1])
  const rendered = spawnSync('docker', ['compose', '--env-file', envFile,
    '-f', resolve(root, 'deploy/docker-compose.yml'),
    '-f', resolve(root, 'deploy/docker-compose.storage-auth.example.yml'),
    '--profile', 'full', 'config', '--format', 'json'], {
    cwd: root, env: { ...process.env, OFFERPILOT_ENV_FILE: envFile }, encoding: 'utf8', timeout: 30000, maxBuffer: 16 * 1024 * 1024,
  })
  if (rendered.error || rendered.status !== 0) {
    // Compose errors can contain interpolated secret values, so do not print stderr.
    console.error('FAIL: Compose could not render the env file and authenticated storage override. Check the file, required variables and Compose >= 2.24.4.')
    return 1
  }
  let config
  try { config = JSON.parse(rendered.stdout) } catch {
    console.error('FAIL: Compose did not return valid JSON')
    return 1
  }
  const { errors, warnings } = validateDeployment(config)
  for (const error of errors) console.error(`FAIL: ${error}`)
  for (const warning of warnings) console.log(`NOTE: ${warning}`)
  if (errors.length) return 1
  console.log('PASS: rendered public deployment configuration; credentials were not printed')
  console.log('Still required: image pull/security review, DNS/TLS, storage authentication, full journey and paired backup/restore verification')
  return 0
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) process.exitCode = main()
