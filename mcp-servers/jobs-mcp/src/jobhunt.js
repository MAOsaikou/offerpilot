/**
 * jobhunt-cli 适配器。
 *
 * 直接使用它的编程接口（package.json 的 main 导出），不起子进程：
 * 更快、不依赖 Windows 上的 .cmd 包装，也不需要解析 stdout 文本。
 */

import {
  EmptyResultError,
  getJobDetail as cliGetJobDetail,
  listSites as cliListSites,
  searchJobs as cliSearchJobs,
} from 'jobhunt-cli';

/** OfferPilot 面向校招/实习，默认查校招；允许传中文。 */
const NATURE_ALIASES = {
  social: 'social',
  社招: 'social',
  campus: 'campus',
  校招: 'campus',
  intern: 'intern',
  实习: 'intern',
  all: 'all',
  全部: 'all',
};

export const DEFAULT_NATURE = 'campus';

/** 不带 site 参数时并发查询的站点。选主流的这几家，兼顾覆盖面和响应速度。 */
export const DEFAULT_SITES = [
  'tencent',
  'bytedance',
  'meituan',
  'xiaomi',
  'baidu',
  'jd',
  'huawei',
  'netease',
];

export function normalizeNature(input) {
  if (input === undefined || input === null || String(input).trim() === '') return DEFAULT_NATURE;
  return NATURE_ALIASES[String(input).trim()] ?? DEFAULT_NATURE;
}

/** 站点清单，字段名统一成驼峰，方便工具层直接返回。 */
export function listSites() {
  return cliListSites().map((site) => ({
    id: site.id,
    name: site.name,
    description: site.description ?? '',
    supportedNatures: site.supportedNatures ?? site.supported_natures ?? [],
    defaultNature: site.defaultNature ?? site.default_nature ?? 'social',
    maxPageSize: site.maxPageSize ?? site.max_page_size ?? null,
    detailIdHint: site.detailIdHint ?? site.detail_id_hint ?? '',
  }));
}

export function hasSite(siteId) {
  return listSites().some((site) => site.id === siteId);
}

function siteDisplayName(siteId) {
  return listSites().find((site) => site.id === siteId)?.name ?? siteId;
}

/** 把 jobhunt-cli 的字段映射成项目约定的岗位卡片结构（见 docs/04 §5.1）。导出以便单测。 */
export function toJobCard(siteId, job) {
  return {
    id: String(job.id ?? job.detail_id ?? ''),
    site: siteId,
    company: siteDisplayName(siteId),
    title: job.name ?? '',
    category: job.category_name ?? '',
    nature: job.nature_code ?? '',
    locations: job.location_names ?? '',
    department: job.department_name ?? '',
    updatedAt: job.updated_at ?? '',
    url: job.url ?? '',
    ...(job.description ? { description: job.description } : {}),
    ...(job.requirement ? { requirement: job.requirement } : {}),
  };
}

/** 判断异常是否为「上游明确返回空结果」。导出以便单测。 */
export function isEmptyResult(error) {
  return error instanceof EmptyResultError
    || error?.name === 'EmptyResultError'
    || /EMPTY_RESULT|returned no data/i.test(error?.message ?? '');
}

function matchesQuery(job, needle) {
  const haystack = `${job.name ?? ''} ${job.department_name ?? ''} ${job.category_name ?? ''}`.toLowerCase();
  return haystack.includes(needle);
}

/**
 * 查询单个站点。
 *
 * 实测腾讯、美团的校招接口不支持关键词过滤（带关键词必返回空，不带则有数据），
 * 所以这里做一次降级：拉取该类型的岗位列表后在本地按关键词过滤。
 * 返回 degraded 标记，调用方可以据此在 _meta 里说明。
 */
export async function searchSite(siteId, options = {}) {
  const {
    query = '',
    nature = DEFAULT_NATURE,
    location = '',
    category = '',
    limit = 20,
  } = options;

  const resolvedNature = normalizeNature(nature);
  const keyword = String(query).trim();

  try {
    const rows = await cliSearchJobs(siteId, {
      query: keyword,
      nature: resolvedNature,
      location,
      category,
      limit,
    });
    return { jobs: rows.map((job) => toJobCard(siteId, job)), degraded: false };
  } catch (error) {
    if (!keyword || !isEmptyResult(error)) throw error;

    const rows = await cliSearchJobs(siteId, {
      nature: resolvedNature,
      location,
      category,
      limit: Math.max(limit * 5, 50),
    });
    const needle = keyword.toLowerCase();
    return {
      jobs: rows.filter((job) => matchesQuery(job, needle)).slice(0, limit).map((job) => toJobCard(siteId, job)),
      degraded: true,
    };
  }
}

export async function getJobDetail(siteId, jobId, nature = DEFAULT_NATURE) {
  const detail = await cliGetJobDetail(siteId, jobId, { nature: normalizeNature(nature) });
  return toJobCard(siteId, detail);
}

/** 给单个站点的查询套一层超时，避免一个慢站点拖住整批查询。 */
function withTimeout(promise, timeoutMs, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label} 超时（${timeoutMs}ms）`)), timeoutMs);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/**
 * 并发查询多个站点。
 *
 * 文档要求：并行不超过 6 个站点，单站超时 8s，失败的站点记录在 skipped 里，
 * 而不是让整次查询失败——这样 Agent 至少能拿到部分结果。
 */
export async function searchAcrossSites(siteIds, options = {}, { concurrency = 6, timeoutMs = 8000 } = {}) {
  const jobs = [];
  const skipped = [];
  const degradedSites = [];
  const queue = [...siteIds];

  async function worker() {
    while (queue.length > 0) {
      const siteId = queue.shift();
      try {
        const result = await withTimeout(searchSite(siteId, options), timeoutMs, `${siteId} 查询`);
        jobs.push(...result.jobs);
        if (result.degraded) degradedSites.push(siteId);
      } catch (error) {
        skipped.push({ site: siteId, reason: error?.message ?? String(error) });
      }
    }
  }

  const workers = Array.from({ length: Math.min(concurrency, siteIds.length) }, worker);
  await Promise.all(workers);

  return { jobs, skipped, degradedSites };
}
