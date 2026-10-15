#!/usr/bin/env node
import { createHash, createHmac, randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const hash = (value) => createHash('sha256').update(value).digest('hex')
const hmac = (key, value) => createHmac('sha256', key).update(value).digest()
const encode = (value) => encodeURIComponent(value).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`)

// Minimal AWS Signature V4 for the path-style S3 operations exercised here.
function signedUrl(base, method, path, body, accessKey, secretKey, presigned = false) {
  const url = new URL(base)
  if (url.pathname !== '/' || url.search || url.username || url.password) throw new Error('Storage URLs must be plain HTTP(S) origins')
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Storage URLs must use HTTP(S)')
  url.pathname = path.split('/').map(encode).join('/')
  const timestamp = new Date().toISOString().replace(/[:-]|\.\d{3}/g, '')
  const date = timestamp.slice(0, 8)
  const scope = `${date}/us-east-1/s3/aws4_request`
  const payload = presigned ? 'UNSIGNED-PAYLOAD' : hash(body)
  const signedHeaders = presigned ? 'host' : 'host;x-amz-content-sha256;x-amz-date'
  const headers = presigned ? `host:${url.host}\n` : `host:${url.host}\nx-amz-content-sha256:${payload}\nx-amz-date:${timestamp}\n`
  if (presigned) for (const [key, value] of Object.entries({
    'X-Amz-Algorithm': 'AWS4-HMAC-SHA256', 'X-Amz-Credential': `${accessKey}/${scope}`,
    'X-Amz-Date': timestamp, 'X-Amz-Expires': '60', 'X-Amz-SignedHeaders': signedHeaders,
  })) url.searchParams.set(key, value)
  const query = [...url.searchParams].sort(([a], [b]) => a.localeCompare(b))
    .map(([key, value]) => `${encode(key)}=${encode(value)}`).join('&')
  const canonical = [method, url.pathname, query, headers, signedHeaders, payload].join('\n')
  const signingKey = hmac(hmac(hmac(hmac(`AWS4${secretKey}`, date), 'us-east-1'), 's3'), 'aws4_request')
  const signature = createHmac('sha256', signingKey).update(`AWS4-HMAC-SHA256\n${timestamp}\n${scope}\n${hash(canonical)}`).digest('hex')
  if (presigned) {
    url.search = `${query}&X-Amz-Signature=${signature}`
    return { url, headers: {} }
  }
  return { url, headers: { 'x-amz-date': timestamp, 'x-amz-content-sha256': payload,
    Authorization: `AWS4-HMAC-SHA256 Credential=${accessKey}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}` } }
}

export async function smoke(env = process.env) {
  const internal = env.STORAGE_INTERNAL_URL
  const publicUrl = env.STORAGE_PUBLIC_URL
  const access = env.MINIO_ROOT_USER
  const secret = env.MINIO_ROOT_PASSWORD
  if (!internal || !publicUrl || !access || !secret) throw new Error('Set STORAGE_INTERNAL_URL, STORAGE_PUBLIC_URL, MINIO_ROOT_USER and MINIO_ROOT_PASSWORD')
  const bucket = env.MINIO_BUCKET || `offerpilot-smoke-${randomUUID()}`
  if (!/^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/.test(bucket)) throw new Error('MINIO_BUCKET must be a valid path-style bucket name')
  const path = `/${bucket}/offerpilot-smoke/${randomUUID()}.txt`
  const content = Buffer.from('OfferPilot isolated storage verification / 虚构资料', 'utf8')
  const request = async (base, method, objectPath, body = Buffer.alloc(0), password = secret) => {
    const signed = signedUrl(base, method, objectPath, body, access, password)
    const response = await fetch(signed.url, { method, headers: signed.headers,
      ...(method === 'GET' || method === 'HEAD' ? {} : { body }), signal: AbortSignal.timeout(30000), redirect: 'error' })
    const bytes = Buffer.from(await response.arrayBuffer())
    return { status: response.status, bytes }
  }
  const expect = (condition, label) => { if (!condition) throw new Error(`Storage check failed: ${label}`) }
  const bucketStatus = await request(internal, 'HEAD', `/${bucket}`)
  if (bucketStatus.status === 404) expect((await request(internal, 'PUT', `/${bucket}`)).status === 200, 'create isolated bucket')
  else expect(bucketStatus.status === 200, 'authenticated bucket access')
  let uploaded = false
  try {
    expect((await request(internal, 'PUT', path, content)).status === 200, 'authenticated upload')
    uploaded = true
    const direct = await request(internal, 'GET', path)
    expect(direct.status === 200 && direct.bytes.equals(content), 'authenticated read')
    const wrong = await request(internal, 'GET', path, Buffer.alloc(0), `${secret}-incorrect`)
    expect(wrong.status === 403, 'incorrect credential rejection')
    const unsigned = await fetch(new URL(path, publicUrl), { signal: AbortSignal.timeout(30000), redirect: 'error' })
    expect(unsigned.status === 403, 'anonymous download rejection')
    await unsigned.arrayBuffer()
    const signed = signedUrl(publicUrl, 'GET', path, Buffer.alloc(0), access, secret, true)
    const download = await fetch(signed.url, { signal: AbortSignal.timeout(30000), redirect: 'error' })
    expect(download.status === 200 && Buffer.from(await download.arrayBuffer()).equals(content), 'signed proxy download and signature host preservation')
    for (const method of ['PUT', 'DELETE']) {
      const denied = await request(publicUrl, method, path, method === 'PUT' ? content : Buffer.alloc(0))
      expect(denied.status === 405, `${method} blocked at the public proxy`)
    }
  } finally {
    // One explicitly identified test object only; never delete a bucket or other files.
    if (uploaded) expect((await request(internal, 'DELETE', path)).status === 204, 'single test object cleanup')
  }
  console.log('PASS: authenticated upload/read, wrong-key and anonymous rejection, signed proxy download, public PUT/DELETE blocked; test object cleaned up')
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  smoke().catch((error) => { console.error(error.message); process.exitCode = 1 })
}
