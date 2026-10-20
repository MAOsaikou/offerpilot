import { readFileSync } from 'node:fs';
import { normalizeNature, DEFAULT_SITES, hasSite, searchAcrossSites } from './jobhunt.js';

// Single maintained source; the Docker image preserves this relative repository layout.
const dataset = JSON.parse(readFileSync(new URL('../../../backend/offerpilot-job/src/main/resources/jobs/snapshot.json', import.meta.url), 'utf8'));

export class SearchInputError extends Error {}

export function resolveMode(value = 'AUTO', warn = console.warn) {
  const mode = String(value).trim().toUpperCase();
  if (['LIVE', 'LOCAL', 'AUTO'].includes(mode)) return mode;
  warn('Unsupported JOBS_SOURCE_MODE (including legacy ALL); using AUTO');
  return 'AUTO';
}

const metadata = () => ({
  source: 'LOCAL_SNAPSHOT', snapshotDate: dataset.snapshotDate,
  sourceDescription: dataset.sourceDescription,
});

export function snapshotSites() {
  return { sites: structuredClone(dataset.sites), _meta: metadata() };
}

export function snapshotDetail(site, id) {
  const job = dataset.jobs.find(job => job.site === site && job.id === id);
  if (!job) throw new Error('快照中没有该岗位');
  return { job: structuredClone(job), _meta: metadata() };
}

export function localSearch({ query, targets, nature, location = '', category = '', limit = 20 }, reason) {
  const tokens = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const score = job => tokens.reduce((sum, token) => sum + (job.title.toLowerCase().includes(token) ? 10 : 1), 0);
  const matches = dataset.jobs.filter(job => targets.includes(job.site)
    && (nature === 'all' || job.nature === nature)
    && job.locations.includes(location.trim()) && job.category.includes(category.trim())
    && tokens.every(token => [job.title, job.company, job.category, job.description, job.requirement, ...job.skills]
      .join(' ').toLowerCase().includes(token)))
    .sort((a, b) => score(b) - score(a) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return {
    jobs: structuredClone(matches.slice(0, limit)),
    _meta: { ...metadata(), query, nature, sites: targets, total: Math.min(matches.length, limit),
      truncated: matches.length > limit, cached: false, skipped: [], degradedSites: [],
      ...(reason ? { fallbackReason: reason } : {}) },
  };
}

/** Whole-query deadline also covers queued sites, not just each site's timeout. */
export function createSearchHandler({ mode = process.env.JOBS_SOURCE_MODE ?? 'AUTO',
  live = searchAcrossSites, timeoutMs = Number(process.env.JOBS_LIVE_TIMEOUT_MS || 10000),
  siteTimeoutMs = Number(process.env.SITE_TIMEOUT_MS || 8000),
  concurrency = Number(process.env.MAX_PARALLEL_SITES || 6), warn = console.warn } = {}) {
  const resolvedMode = resolveMode(mode, warn);
  const budget = Number.isFinite(timeoutMs) && timeoutMs > 0 ? timeoutMs : 10000;
  return async ({ query = '', site, sites, nature, location = '', category = '', limit }) => {
    const keyword = String(query).trim();
    if (keyword.length > 100 || (!keyword && !site && !sites?.length && !location && !(nature && normalizeNature(nature) !== 'all'))) throw new SearchInputError('请提供关键词、企业或城市筛选条件');
    const targets = sites?.length ? sites : site ? [site] : resolvedMode === 'LOCAL' ? dataset.sites.map(s => s.id) : DEFAULT_SITES;
    if (targets.some(id => !hasSite(id))) throw new SearchInputError('未知站点，可用站点见 list_sites 工具');
    const options = { query: keyword, targets, nature: normalizeNature(nature), location, category,
      limit: Math.min(Math.max(Number(limit) || 20, 1), 50) };
    const fallback = reason => {
      if (reason) warn(`Jobs live search unavailable; using local snapshot (${reason})`);
      // No-site fallback searches the whole curated dataset (including aliyun).
      return localSearch({ ...options, targets: site || sites?.length ? targets : dataset.sites.map(s => s.id) }, reason);
    };
    if (resolvedMode === 'LOCAL') return fallback();
    let timer;
    const controller = new AbortController();
    try {
      const value = await Promise.race([
        live(targets, options, { concurrency, timeoutMs: siteTimeoutMs, signal: controller.signal }),
        new Promise((_, reject) => { timer = setTimeout(() => { controller.abort(); reject(new Error('LIVE_TIMEOUT')); }, budget); }),
      ]);
      if (resolvedMode === 'AUTO' && value.skipped.length === targets.length) return fallback('ALL_SITES_FAILED');
      if (resolvedMode === 'AUTO' && !value.jobs.length && value.skipped.length) return fallback('EMPTY_DUE_TO_ERROR');
      const jobs = value.jobs.slice(0, options.limit);
      return { jobs, _meta: { source: 'company-career-site', query: keyword, nature: options.nature,
        sites: targets, total: jobs.length, truncated: value.jobs.length > jobs.length,
        cached: value.cached ?? false, skipped: value.skipped, degradedSites: value.degradedSites } };
    } catch (error) {
      if (resolvedMode === 'LIVE') throw error;
      return fallback(error.message === 'LIVE_TIMEOUT' ? 'LIVE_TIMEOUT' : 'LIVE_UNAVAILABLE');
    } finally { clearTimeout(timer); }
  };
}
