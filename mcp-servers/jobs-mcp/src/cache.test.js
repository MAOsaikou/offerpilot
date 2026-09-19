import test from 'node:test';
import assert from 'node:assert/strict';

import { cacheStats, clearCache, withCache } from './cache.js';

test.beforeEach(() => clearCache());

test('首次未命中会执行 loader，第二次命中不再执行', async () => {
  let calls = 0;
  const loader = async () => {
    calls += 1;
    return { value: 42 };
  };

  const first = await withCache('k', loader);
  assert.equal(first.cached, false);
  assert.deepEqual(first.value, { value: 42 });

  const second = await withCache('k', loader);
  assert.equal(second.cached, true);
  assert.deepEqual(second.value, { value: 42 });

  assert.equal(calls, 1, 'loader 只应执行一次');
});

test('不同 key 互不影响', async () => {
  await withCache('a', async () => 'A');
  const b = await withCache('b', async () => 'B');
  assert.equal(b.cached, false);
  assert.equal(b.value, 'B');
  assert.equal((await withCache('a', async () => 'X')).value, 'A');
});

test('超过 ttl 后重新执行 loader', async () => {
  let calls = 0;
  const loader = async () => {
    calls += 1;
    return calls;
  };

  await withCache('k', loader, 30);
  await withCache('k', loader, 30);
  assert.equal(calls, 1, '未过期时应命中缓存');

  await new Promise((resolve) => setTimeout(resolve, 80));

  const after = await withCache('k', loader, 30);
  assert.equal(after.cached, false, '过期后应重新执行');
  assert.equal(calls, 2);
});

test('条目数不超过上限，超出时淘汰最早写入的一条', async () => {
  const { maxEntries } = cacheStats();
  for (let i = 0; i < maxEntries; i += 1) {
    await withCache(`k${i}`, async () => i);
  }
  assert.equal(cacheStats().entries, maxEntries);

  await withCache(`k${maxEntries}`, async () => maxEntries);
  assert.equal(cacheStats().entries, maxEntries, '写入新条目后仍不应超过上限');

  const evicted = await withCache('k0', async () => 'refetched');
  assert.equal(evicted.cached, false, '最早写入的条目应已被淘汰');
});
