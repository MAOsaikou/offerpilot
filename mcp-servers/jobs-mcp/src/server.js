/**
 * jobs-mcp：把企业官网公开招聘岗位包装成 MCP 工具。
 *
 * 对外提供：
 *   POST /mcp            标准 MCP 端点（Streamable HTTP），供 Spring AI / Cursor 等客户端调用
 *   GET  /health         健康检查
 *   GET  /list_sites     调试用：直接看站点清单
 *   POST /search_jobs    调试用：不装 MCP 客户端也能验证返回真实岗位
 *
 * 工具：list_sites / search_jobs / get_job_detail
 */

import express from 'express';
import { z } from 'zod';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';

import { cacheStats, withCache } from './cache.js';
import {
  DEFAULT_SITES,
  getJobDetail,
  hasSite,
  listSites,
  normalizeNature,
  searchAcrossSites,
} from './jobhunt.js';

const PORT = Number(process.env.PORT || 7001);
const CACHE_TTL_MS = Number(process.env.CACHE_TTL_SECONDS || 600) * 1000;
const MAX_PARALLEL_SITES = Number(process.env.MAX_PARALLEL_SITES || 6);
const SITE_TIMEOUT_MS = Number(process.env.SITE_TIMEOUT_MS || 8000);

/** 告诉下游数据来自企业官网，前端据此标注来源。 */
const SOURCE = 'company-career-site';

const SEARCH_INPUT = {
  query: z.string().min(1).describe('搜索关键词，例如「后端」「Java」「算法」「产品经理」'),
  site: z.string().optional().describe('只查单个站点，如 tencent、bytedance。不传则并发查询多个站点'),
  sites: z.array(z.string()).optional().describe('查询多个指定站点；优先级高于 site'),
  nature: z.string().optional().describe('招聘类型：campus（校招，默认）/ intern（实习）/ social（社招）/ all（全部）'),
  location: z.string().optional().describe('城市名或该站点内的地区代码，例如「深圳」'),
  category: z.string().optional().describe('岗位类别，例如「技术」「产品」「设计」'),
  limit: z.number().int().min(1).max(50).optional().describe('返回条数上限，默认 20，最大 50'),
};

const DETAIL_INPUT = {
  site: z.string().min(1).describe('岗位所属站点，例如 tencent'),
  jobId: z.string().min(1).describe('search_jobs 返回的岗位 id'),
  nature: z.string().optional().describe('招聘类型：campus / intern / social，需与搜索时一致'),
};

function json(payload) {
  return { content: [{ type: 'text', text: JSON.stringify(payload, null, 2) }] };
}

function failure(message) {
  return { isError: true, content: [{ type: 'text', text: message }] };
}

async function handleSearch({ query, site, sites, nature, location, category, limit }) {
  const resolvedNature = normalizeNature(nature);
  const max = Math.min(Number(limit) || 20, 50);
  const keyword = String(query).trim();

  const targets = sites?.length ? sites : (site ? [site] : DEFAULT_SITES);
  const unknown = targets.filter((id) => !hasSite(id));
  if (unknown.length) {
    return failure(`未知站点：${unknown.join(', ')}。可用站点见 list_sites 工具。`);
  }

  const cacheKey = JSON.stringify({ targets, keyword, resolvedNature, location, category, max });
  const { value, cached } = await withCache(cacheKey, async () => {
    return searchAcrossSites(targets, { query: keyword, nature: resolvedNature, location, category, limit: max }, {
      concurrency: MAX_PARALLEL_SITES,
      timeoutMs: SITE_TIMEOUT_MS,
    });
  }, CACHE_TTL_MS, (result) => result.skipped.length < targets.length);

  const jobs = value.jobs.slice(0, max);
  return json({
    jobs,
    _meta: {
      source: SOURCE,
      query: keyword,
      nature: resolvedNature,
      sites: targets,
      total: jobs.length,
      truncated: value.jobs.length > jobs.length,
      cached,
      skipped: value.skipped,
      degradedSites: value.degradedSites,
    },
  });
}

async function handleDetail({ site, jobId, nature }) {
  if (!hasSite(site)) {
    return failure(`未知站点：${site}。可用站点见 list_sites 工具。`);
  }
  const cacheKey = JSON.stringify({ detail: site, jobId, nature: normalizeNature(nature) });
  const { value, cached } = await withCache(
    cacheKey,
    () => getJobDetail(site, jobId, nature),
    CACHE_TTL_MS,
  );
  return json({ job: value, _meta: { source: SOURCE, cached } });
}

function createMcpServer() {
  const server = new McpServer({ name: 'offerpilot-jobs', version: '0.2.0' });

  server.registerTool('list_sites', {
    title: '列出招聘站点',
    description: '列出支持查询的企业招聘站点，以及每个站点支持的招聘类型（校招/实习/社招）。'
      + '调用 search_jobs 前如果不确定站点 id，先用这个工具。',
    inputSchema: {},
  }, async () => {
    const sites = listSites();
    return json({ sites, _meta: { source: SOURCE, total: sites.length } });
  });

  server.registerTool('search_jobs', {
    title: '搜索企业官网岗位',
    description: '按关键词搜索企业官网公开招聘岗位（默认校招）。'
      + '不指定 site 时会并发查询多个主流站点，返回结果附带来源与失败站点说明。'
      + '注意：不要声称你搜索了未返回的站点，也不要编造岗位。',
    inputSchema: SEARCH_INPUT,
  }, async (args) => {
    try {
      return await handleSearch(args);
    } catch (error) {
      return failure(`岗位查询失败：${error?.message ?? error}`);
    }
  });

  server.registerTool('get_job_detail', {
    title: '获取岗位详情',
    description: '根据 search_jobs 返回的岗位 id 获取该岗位的完整描述与任职要求。',
    inputSchema: DETAIL_INPUT,
  }, async (args) => {
    try {
      return await handleDetail(args);
    } catch (error) {
      return failure(`获取岗位详情失败：${error?.message ?? error}`);
    }
  });

  return server;
}

const app = express();
app.use(express.json({ limit: '1mb' }));

app.get('/health', (req, res) => {
  res.json({ status: 'UP', tools: ['list_sites', 'search_jobs', 'get_job_detail'], sites: listSites().length, cache: cacheStats() });
});

// 调试入口：不装 MCP 客户端也能验证服务是否返回真实岗位
app.get('/list_sites', (req, res) => {
  res.json({ sites: listSites(), _meta: { source: SOURCE } });
});

app.post('/search_jobs', async (req, res) => {
  try {
    const result = await handleSearch(req.body ?? {});
    if (result.isError) return res.status(400).json({ message: result.content[0].text });
    res.json(JSON.parse(result.content[0].text));
  } catch (error) {
    res.status(500).json({ message: error?.message ?? String(error) });
  }
});

// MCP 端点：无状态模式，每个请求新建 server + transport
app.post('/mcp', async (req, res) => {
  const server = createMcpServer();
  const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
  res.on('close', () => {
    transport.close();
    server.close();
  });
  try {
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    if (!res.headersSent) {
      res.status(500).json({ jsonrpc: '2.0', error: { code: -32603, message: error?.message ?? 'Internal error' }, id: null });
    }
  }
});

app.get('/mcp', (req, res) => {
  res.status(405).json({ jsonrpc: '2.0', error: { code: -32000, message: 'Method not allowed. POST to /mcp.' }, id: null });
});

app.listen(PORT, process.env.HOST || '0.0.0.0', () => {
  const sites = listSites();
  console.log(`jobs-mcp listening on :${PORT} — ${sites.length} sites, tools: list_sites / search_jobs / get_job_detail`);
});
