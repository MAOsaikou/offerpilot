import test from 'node:test';
import assert from 'node:assert/strict';

import { cacheStats, clearCache, withCache } from './cache.js';

test.beforeEach(() => clearCache());

test('全站失败不缓存，恢复后的合法空结果可以缓存', async () => {
  let calls = 0;
  const loader = async () => ({ items: [], failed: ++calls === 1 });
  const cacheable = (result) => !result.failed;
  const failed = await withCache('outage', loader, 60000, cacheable);
  assert.equal(failed.value.failed, true);
  assert.equal(cacheStats().entries, 0);
  const recovered = await withCache('outage', loader, 60000, cacheable);
  assert.equal(recovered.cached, false);
  assert.deepEqual(recovered.value.items, []);
  assert.equal((await withCache('outage', loader, 60000, cacheable)).cached, true);
  assert.equal(calls, 2);
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
