import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiError, EmptyResultError } from 'jobhunt-cli';

import {
  DEFAULT_NATURE,
  DEFAULT_SITES,
  hasSite,
  isEmptyResult,
  listSites,
  normalizeNature,
  searchAcrossSites,
  toJobCard,
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

test('listSites 返回站点清单且字段完整', () => {
  const sites = listSites();
  assert.ok(sites.length >= 30, `期望至少 30 个站点，实际 ${sites.length}`);

  for (const id of ['tencent', 'bytedance', 'meituan', 'xiaomi']) {
    assert.ok(sites.some((site) => site.id === id), `清单里缺少 ${id}`);
  }
  for (const site of sites) {
    assert.equal(typeof site.id, 'string');
    assert.ok(site.id.length > 0);
    assert.equal(typeof site.name, 'string');
    assert.ok(site.name.length > 0);
    assert.ok(Array.isArray(site.supportedNatures));
    assert.ok(site.supportedNatures.includes('social'), `${site.id} 应支持社招`);
  }
});

test('DEFAULT_SITES 都在清单里', () => {
  for (const id of DEFAULT_SITES) {
    assert.equal(hasSite(id), true, `默认站点 ${id} 不在清单里`);
  }
});

test('hasSite 只认已知站点', () => {
  assert.equal(hasSite('tencent'), true);
  assert.equal(hasSite('not-a-real-site'), false);
});

test('toJobCard 把上游字段映射成项目约定结构', () => {
  const card = toJobCard('bytedance', {
    id: '7668351902195009845',
    name: 'Agent后端开发工程师 - Aime',
    category_name: '后端',
    nature_code: 'campus',
    location_names: '北京,上海,杭州',
    department_name: 'Aime',
    updated_at: '2026-07-30',
    url: 'https://jobs.bytedance.com/campus/position/7668351902195009845/detail',
  });

  assert.deepEqual(card, {
    id: '7668351902195009845',
    site: 'bytedance',
    company: 'ByteDance',
    title: 'Agent后端开发工程师 - Aime',
    category: '后端',
    nature: 'campus',
    locations: '北京,上海,杭州',
    department: 'Aime',
    updatedAt: '2026-07-30',
    url: 'https://jobs.bytedance.com/campus/position/7668351902195009845/detail',
  });
});

test('toJobCard 用 detail_id 兜底，字段缺失时不产生 undefined', () => {
  const card = toJobCard('tencent', { detail_id: '9' });
  assert.equal(card.id, '9');
  assert.equal(card.title, '');
  assert.equal(card.locations, '');
  assert.equal('description' in card, false, '没有描述时不应出现该键');
  assert.equal('requirement' in card, false, '没有任职要求时不应出现该键');
});

test('toJobCard 透传详情字段', () => {
  const card = toJobCard('tencent', { id: '1', description: '职责', requirement: '要求' });
  assert.equal(card.description, '职责');
  assert.equal(card.requirement, '要求');
});

test('isEmptyResult 识别空结果，不误伤其他错误', () => {
  assert.equal(isEmptyResult(new EmptyResultError('tencent search')), true);
  assert.equal(isEmptyResult(new Error('EMPTY_RESULT')), true);
  assert.equal(isEmptyResult(new Error('tencent search returned no data')), true);

  const named = new Error('随便什么消息');
  named.name = 'EmptyResultError';
  assert.equal(isEmptyResult(named), true);

  assert.equal(isEmptyResult(new Error('request timeout')), false);
  assert.equal(isEmptyResult(new ApiError('HTTP_500', 'upstream failed')), false);
  assert.equal(isEmptyResult(undefined), false);
  assert.equal(isEmptyResult(null), false);
});

test('isEmptyResult 能识别被上游包装过的空结果错误', () => {
  // jobhunt-cli 的 wrapNatureError 只原样保留 ApiError / ArgumentError，
  // EmptyResultError 会被包装成 ApiError，因此 instanceof 判断失效，
  // 只能靠消息里的 "returned no data" 兜底。这条测试锁住该行为。
  const wrapped = new ApiError('EMPTY_RESULT', '[tencent/campus] tencent search returned no data');
  assert.equal(wrapped instanceof EmptyResultError, false, '前提：包装后不再是 EmptyResultError');
  assert.equal(isEmptyResult(wrapped), true, '必须仍被识别为空结果，否则降级逻辑不会触发');
});

test('searchAcrossSites 把失败站点记入 skipped 而不中断整批', async () => {
  const result = await searchAcrossSites(['not-a-real-site'], { query: '后端' });

  assert.deepEqual(result.jobs, []);
  assert.equal(result.skipped.length, 1);
  assert.equal(result.skipped[0].site, 'not-a-real-site');
  assert.ok(result.skipped[0].reason.length > 0, 'skip 原因不应为空');
});

test('searchAcrossSites 对空站点列表安全返回', async () => {
  const result = await searchAcrossSites([], { query: '后端' });
  assert.deepEqual(result, { jobs: [], skipped: [], degradedSites: [] });
});
