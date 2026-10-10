import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSearchHandler, resolveMode } from './source.js';

const args = { query: 'Java', site: 'bytedance', nature: 'campus', location: '上海' };
const silent = () => {};

test('AUTO falls back on thrown failures, all-sites-failed and empty-due-to-error', async () => {
  for (const [live, reason] of [
    [async () => { throw new Error('offline'); }, 'LIVE_UNAVAILABLE'],
    [async () => ({ jobs: [], skipped: [{ site: args.site }], degradedSites: [] }), 'ALL_SITES_FAILED'],
  ]) {
    const result = await createSearchHandler({ mode: 'AUTO', live, warn: silent })(args);
    assert.ok(result.jobs.length);
    assert.equal(result._meta.source, 'LOCAL_SNAPSHOT');
    assert.equal(result._meta.fallbackReason, reason);
  }
  const partial = async () => ({ jobs: [], skipped: [{ site: 'tencent' }], degradedSites: [] });
  const result = await createSearchHandler({ mode: 'AUTO', live: partial, warn: silent })({ query: 'Java', sites: ['tencent', 'bytedance'], nature: 'all' });
  assert.equal(result._meta.fallbackReason, 'EMPTY_DUE_TO_ERROR');
});

test('AUTO whole-query timeout returns a snapshot and cancels queued work', async () => {
  let signal;
  const handler = createSearchHandler({ mode: 'AUTO', timeoutMs: 10, warn: silent,
    live: (_targets, _options, controls) => { signal = controls.signal; return new Promise(() => {}); } });
  const result = await handler(args);
  assert.equal(result._meta.fallbackReason, 'LIVE_TIMEOUT');
  assert.equal(signal.aborted, true);
});

test('LIVE surfaces failures; healthy empty and partial success remain live in AUTO', async () => {
  await assert.rejects(createSearchHandler({ mode: 'LIVE', live: async () => { throw new Error('offline'); } })(args), /offline/);
  for (const jobs of [[], [{ id: 'live-1' }]]) {
    const result = await createSearchHandler({ mode: 'AUTO', live: async () => ({ jobs, skipped: [], degradedSites: [] }) })(args);
    assert.equal(result._meta.source, 'company-career-site');
    assert.equal(result._meta.fallbackReason, undefined);
    assert.equal(result.jobs.length, jobs.length);
  }
});

test('legacy ALL and unknown values warn and resolve to AUTO', () => {
  for (const mode of ['ALL', 'unknown', '']) {
    const warnings = [];
    assert.equal(resolveMode(mode, message => warnings.push(message)), 'AUTO');
    assert.equal(warnings.length, 1);
  }
});

test('MCP search_jobs actually routes LOCAL without fetch', async t => {
  process.env.JOBS_SOURCE_MODE = 'LOCAL';
  let calls = 0;
  t.mock.method(globalThis, 'fetch', () => { calls++; throw new Error('network prohibited'); });
  const { createMcpServer } = await import('./server.js');
  const { Client } = await import('@modelcontextprotocol/sdk/client/index.js');
  const { InMemoryTransport } = await import('@modelcontextprotocol/sdk/inMemory.js');
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const server = createMcpServer();
  const client = new Client({ name: 'snapshot-test', version: '1' });
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const toolResult = await client.callTool({ name: 'search_jobs', arguments: args });
    assert.equal(toolResult.isError, undefined);
    const result = JSON.parse(toolResult.content[0].text);
    assert.equal(result._meta.source, 'LOCAL_SNAPSHOT');
    assert.ok(result.jobs.length);
    assert.equal(result._meta.snapshotDate, '2026-10-10');
    assert.equal(result._meta.fallbackReason, undefined);
    for (const job of result.jobs) {
      assert.equal(job.site, args.site);
      assert.equal(job.nature, args.nature);
      assert.ok(job.locations.includes(args.location));
      assert.match(job.id, /^snapshot-/);
      assert.equal(job.url, '');
    }
    for (const filters of [{ ...args, location: '不存在城市' }, { ...args, query: '前端', category: '后端' }]) {
      const empty = await client.callTool({ name: 'search_jobs', arguments: filters });
      assert.equal(JSON.parse(empty.content[0].text).jobs.length, 0);
    }
    const limited = await client.callTool({ name: 'search_jobs', arguments: { query: 'Java', nature: 'all', limit: 1 } });
    const limit = JSON.parse(limited.content[0].text);
    assert.equal(limit.jobs.length, 1);
    assert.equal(limit._meta.truncated, true);
    assert.match(limit.jobs[0].title, /Java/);
    const detail = await client.callTool({ name: 'get_job_detail', arguments: { site: args.site, jobId: result.jobs[0].id } });
    assert.equal(JSON.parse(detail.content[0].text).job.source, 'LOCAL_SNAPSHOT');
    const directory = await client.callTool({ name: 'list_sites', arguments: {} });
    assert.equal(JSON.parse(directory.content[0].text)._meta.source, 'LOCAL_SNAPSHOT');
    assert.equal(calls, 0);
  } finally { await client.close(); await server.close(); delete process.env.JOBS_SOURCE_MODE; }
});
