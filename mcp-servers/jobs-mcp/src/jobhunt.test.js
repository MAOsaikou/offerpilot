import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiError, EmptyResultError } from 'jobhunt-cli';

import {
  toJobCard,
  DEFAULT_NATURE,
  isEmptyResult,
  normalizeNature,
  searchAcrossSites,
} from './jobhunt.js';

test('normalizeNature 支持中文别名，其他输入回落到校招', () => {
  assert.equal(normalizeNature('campus'), 'campus');
  assert.equal(normalizeNature('校招'), 'campus');
  assert.equal(normalizeNature('intern'), 'intern');
  assert.equal(normalizeNature('实习'), 'intern');
  assert.equal(normalizeNature('social'), 'social');
  assert.equal(normalizeNature('社招'), 'social');
  assert.equal(normalizeNature('all'), 'all');
  assert.equal(normalizeNature('全部'), 'all');

  assert.equal(normalizeNature(undefined), DEFAULT_NATURE);
  assert.equal(normalizeNature(null), DEFAULT_NATURE);
  assert.equal(normalizeNature('   '), DEFAULT_NATURE);
  assert.equal(normalizeNature('不存在的类型'), DEFAULT_NATURE);
  assert.equal(DEFAULT_NATURE, 'campus', '面向校招场景，默认应为 campus');
});

test('isEmptyResult 能识别被上游包装过的空结果错误', () => {
  // jobhunt-cli 的 wrapNatureError 只原样保留 ApiError / ArgumentError，
  // EmptyResultError 会被包装成 ApiError，因此 instanceof 判断失效，
  // 只能靠消息里的 "returned no data" 兜底。这条测试锁住该行为。
  const wrapped = new ApiError('EMPTY_RESULT', '[tencent/campus] tencent search returned no data');
  assert.equal(wrapped instanceof EmptyResultError, false, '前提：包装后不再是 EmptyResultError');
  assert.equal(isEmptyResult(wrapped), true, '必须仍被识别为空结果，否则降级逻辑不会触发');
  assert.equal(isEmptyResult(new EmptyResultError('tencent search')), true);
  assert.equal(isEmptyResult(new ApiError('HTTP_500', 'upstream failed')), false);
  assert.equal(isEmptyResult(new Error('request timeout')), false);
});

test('searchAcrossSites 把失败站点记入 skipped 而不中断整批', async () => {
  const result = await searchAcrossSites(['not-a-real-site'], { query: '后端' });

  assert.deepEqual(result.jobs, []);
  assert.equal(result.skipped.length, 1);
  assert.equal(result.skipped[0].site, 'not-a-real-site');
  assert.ok(result.skipped[0].reason.length > 0, 'skip 原因不应为空');
});

test('default per-site timeout is six seconds even when the upstream never settles', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let settled = false;
  const pending = searchAcrossSites(['tencent'], {}, { search: () => new Promise(() => {}) })
    .then(value => { settled = true; return value; });
  t.mock.timers.tick(5999);
  await Promise.resolve();
  assert.equal(settled, false);
  t.mock.timers.tick(1);
  const result = await pending;
  assert.deepEqual(result.jobs, []);
  assert.equal(result.skipped[0].site, 'tencent');
  assert.match(result.skipped[0].reason, /6000ms/);
});

test('toJobCard preserves provided deadline and leaves unknown deadline empty', () => {
  assert.equal(toJobCard('fixture', { id: '1', deadline: '2026-12-31' }).deadline, '2026-12-31');
  assert.equal(toJobCard('fixture', { id: '1' }).deadline, '');
});
