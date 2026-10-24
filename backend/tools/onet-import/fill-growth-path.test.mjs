import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fillGrowthPaths } from './fill-growth-path.mjs';

test('join by SOC, retain zone distinctions, and preserve salaries and editorial paths', () => {
  const seed = { _meta: { version: 'fixture' }, occupations: [
    { id: 'five', onetCode: '11-1011.00', growthPath: null, salaryRange: null },
    { id: 'four', onetCode: '11-1021.00', growthPath: '  ', salaryRange: null },
    { id: 'curated', onetCode: 'unmapped', growthPath: '已有编辑路径', growthPathSource: 'curated', salaryRange: null },
    { id: 'blank-curated', onetCode: '11-1021.00', growthPathSource: 'curated', salaryRange: null },
  ] };
  const before = structuredClone(seed);
  const zones = new Map([['11-1021.00', 4], ['11-1011.00', 5]]);
  const result = fillGrowthPaths(seed, zones);
  assert.match(result.occupations[0].growthPath, /Job Zone 5.*研究生.*五年以上.*在岗培训/);
  assert.match(result.occupations[1].growthPath, /Job Zone 4.*本科.*数年.*在岗培训/);
  assert.deepEqual(result.occupations[2], before.occupations[2]);
  for (const index of [0, 1, 3]) {
    const { growthPath, growthPathSource, ...rest } = result.occupations[index];
    const { growthPath: oldPath, growthPathSource: oldSource, ...oldRest } = before.occupations[index];
    assert.ok(growthPath.length <= 120);
    assert.equal(growthPathSource, 'onet-job-zone');
    assert.deepEqual(rest, oldRest);
  }
  assert.deepEqual(seed, before);
  assert.deepEqual(fillGrowthPaths(result, zones), result);
});

test('missing SOC and unsupported zones fail instead of guessing a path', () => {
  const seed = { occupations: [{ id: 'unknown', onetCode: 'unknown', salaryRange: null }] };
  for (const zones of [new Map(), new Map([['unknown', 3]])]) {
    assert.throws(() => fillGrowthPaths(seed, zones), /missing or unsupported Job Zone/);
  }
  assert.equal(seed.occupations[0].growthPath, undefined);
});

test('generated paths are validated on rerun so drift cannot pass a check', () => {
  const seed = { occupations: [{ id: 'drift', onetCode: 'known', growthPath: '错误路径', growthPathSource: 'onet-job-zone' }] };
  assert.throws(() => fillGrowthPaths(seed, new Map([['known', 4]])), /generated path differs/);
});
