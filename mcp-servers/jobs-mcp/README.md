# jobs-mcp

把企业官网公开招聘岗位包装成 MCP 工具，供 Agent（Spring AI、Cursor 等）调用。

基于开源工具 [`jobhunt-cli`](https://github.com/Enzoding/JobHunt-CLI)，覆盖 36 家企业官网的
校招 / 实习 / 社招岗位。只访问企业公开接口，不做登录态操作。

## 一、提供哪些工具

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `list_sites` | 无 | 列出支持的企业站点及各自支持的招聘类型 |
| `search_jobs` | `query`（必填）、`site`、`sites`、`nature`、`location`、`category`、`limit` | 按关键词搜索岗位；不指定站点时并发查询多家 |
| `get_job_detail` | `site`、`jobId`、`nature` | 按 id 取岗位完整描述与任职要求 |

`nature` 取值：`campus`（校招，**默认**）/ `intern`（实习）/ `social`（社招）/ `all`。
也接受中文「校招」「实习」「社招」「全部」。

返回结构：

```json
{
  "jobs": [
    {
      "id": "7668351902195009845",
      "site": "bytedance",
      "company": "ByteDance",
      "title": "Agent后端开发工程师 - Aime",
      "category": "后端",
      "nature": "campus",
      "locations": "北京,上海,杭州",
      "department": "",
      "updatedAt": "2026-07-30",
      "url": "https://jobs.bytedance.com/campus/position/.../detail",
      "description": "……",
      "requirement": "……"
    }
  ],
  "_meta": {
    "source": "company-career-site",
    "query": "后端",
    "nature": "campus",
    "sites": ["bytedance"],
    "total": 2,
    "truncated": false,
    "cached": false,
    "skipped": [],
    "degradedSites": []
  }
}
```

`_meta` 的用途：`source` 供前端标注「企业官网」；`cached` 说明数据是否来自缓存；
`skipped` 列出查询失败的站点（含原因）；`degradedSites` 列出走了降级路径的站点（见下文）。

## 二、快速验证（不用装 MCP 客户端）

服务跑起来后，用 curl 检查 `_meta.source`：company-career-site 为官网结果，LOCAL_SNAPSHOT 为非实时示例；需要仅验证官网时设 JOBS_SOURCE_MODE=LIVE。

```bash
# 健康检查
curl http://localhost:7001/health

# 站点清单
curl http://localhost:7001/list_sites

# 查岗位（注意：Windows PowerShell 下建议把 JSON 写进文件再 --data-binary）
curl -X POST http://localhost:7001/search_jobs \
  -H 'Content-Type: application/json' \
  -d '{"query":"后端","nature":"campus","limit":3}'
```

### 单元测试

用 Node 内置的 `node:test`，不引入任何额外依赖：

```bash
npm test
```

覆盖范围：缓存（命中 / 过期 / 淘汰）、招聘类型归一化（含中文别名）、站点清单与默认站点有效性、
字段映射（含 `detail_id` 兜底与可选字段）、空结果判定、跨站点并发下的失败隔离。

其中一条测试专门锁住一个**容易踩的坑**：`jobhunt-cli` 的 `wrapNatureError` 只原样保留
`ApiError` / `ArgumentError`，`EmptyResultError` 会被包装成 `ApiError`，导致
`instanceof EmptyResultError` 判断失效——降级逻辑实际依赖的是消息里的 `returned no data`。
改动这段代码前请先看懂那条测试。

## 三、架构

```
AI 助手
  │  POST /mcp  {"method":"tools/call","params":{"name":"search_jobs",...}}
  ▼
server.js ── 解析 MCP 协议，路由到对应工具
  │
  ▼
handleSearch() ── 决定查哪些站点 → 查缓存 → 未命中则并发查询
  │
  ▼
searchAcrossSites() ── 工作池：最多 6 个并发，每站 8 秒超时，失败只记录
  │
  ▼
searchSite() ── 调用 jobhunt-cli；空结果且带关键词时降级重试
  │
  ▼
toJobCard() ── 字段归一化（snake_case → 项目约定的 camelCase）
  │
  ▼
组装 { jobs, _meta } → MCP 文本响应
```

| 文件 | 职责 |
| --- | --- |
| `src/server.js` | MCP 协议、工具注册、站点选择、缓存调度、调试路由 |
| `src/jobhunt.js` | 数据脏活：调用上游、降级、并发、超时、字段归一化 |
| `src/cache.js` | 带过期时间的内存缓存 |

## 四、几个设计决定（为什么这么写）

### 1. 用编程接口，不起子进程

`jobhunt-cli` 除了命令行，还导出了 `searchJobs` / `listSites` / `getJobDetail` 等函数。
直接 `import` 调用，比 `spawn` 一个子进程更好：

- 不用解析 stdout 文本（脆弱）
- 不用等待进程启动（慢）
- 不用处理 Windows 上 `.cmd` 包装的转义问题

### 2. 校招关键词的降级路径

实测发现：**腾讯、美团、网易、华为的校招接口不支持关键词过滤**——带关键词一定返回空，
不带关键词却能拿到数据。

所以 `searchSite()` 里做了降级：捕获 `EmptyResultError` 后，改为「拉取该类型岗位列表 +
本地按关键词过滤」，并在返回值里标记 `degraded: true`，最终透传到 `_meta.degradedSites`。

两个要点：

- **降级判断要窄**：只有「空结果」才降级，超时、网络错误、参数错误照常抛出，
  否则会把真实故障伪装成"查不到"。
- **降级要透明**：调用方有权知道这次匹配是站点原生支持的，还是我们本地做的。

### 3. 失败不中断整批查询

跨站点并发时，单个站点超时或报错**不会**让整次查询失败，只会在 `_meta.skipped` 里记一笔。
Agent 拿到的是「部分结果 + 明确的缺失说明」，比整次失败有用得多。

### 4. 并发用工作池，不是分批

开 `MAX_PARALLEL_SITES` 个循环 worker 共抢一个站点队列：快站点不会被慢站点拖累，
总耗时接近最慢的那个站点，而不是所有站点耗时之和。

### 5. 缓存 10 分钟

同一关键词会被 Agent 反复查询，直接打企业接口既慢又不礼貌。默认 TTL 600 秒，
且把 `cached` 标记返回给调用方，便于排查"数据怎么没更新"。

### 6. 字段归一化是一层防腐层

上游字段是 `snake_case`（`category_name`、`location_names`），项目约定是 `camelCase`
（`category`、`locations`），统一在 `toJobCard()` 里翻译。

好处：将来若更换数据源（比如上游挂了，改成直接调企业接口），**只改这一个函数**，
上层逻辑一行都不用动。

## 五、环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `7001` | 监听端口 |
| `CACHE_TTL_SECONDS` | `600` | 缓存有效期 |
| `MAX_PARALLEL_SITES` | `6` | 跨站点并发上限 |
| `SITE_TIMEOUT_MS` | `8000` | 单站点超时 |
| `JOBS_SOURCE_MODE` | `AUTO` | LIVE / LOCAL / AUTO，旧 ALL/未知值 WARN 后按 AUTO |
| `JOBS_LIVE_TIMEOUT_MS` | `10000` | 整次多站查询预算，含排队 |

`deploy/docker-compose.yml` 向后端和本服务同时透传模式与整次查询预算。

### 离线快照与构建

默认 AUTO 优先官网，抛错/超时/全部失败/空结果伴随失败站点时回退；健康空结果不回退，有岗位的部分成功保留官网结果。LIVE 保留原行为（失败站点在 skipped，全部失败仍返回元数据供后端报错）；LOCAL 在 search_jobs/list_sites/get_job_detail 路径不调用网络。预算到期停止后续排队站点，jobhunt-cli 公共接口不能取消已发出的底层 fetch，调用方仍按预算返回。仅官网成功且无 skipped 的查询进入10分钟缓存，避免缓存故障阻止恢复。

唯一数据文件：`backend/offerpilot-job/src/main/resources/jobs/snapshot.json`（相对仓库根），2026-10-10，40 条/8 企业，人工整理的示例岗位快照，非实时数据。ID=snapshot-*，url 为空。`_meta.source=LOCAL_SNAPSHOT`，含 snapshotDate/sourceDescription；AUTO 另有 fallbackReason。关键词按空白分词后要求每个词命中标题/企业/类别/职责/要求/skills，标题权重较高，稳定 ID 次序；site/sites、nature、location、category 和 limit 生效。快照默认搜索全部8家；显式 site/sites 严格保留。limit 最多50，不新增分页。

刷新唯一 JSON 并同步顶层/条目 snapshotDate，保留声明与稳定 ID，运行 `npm test` 和后端 JobSourceTest/JobsSnapshotHttpIntegrationTest，重建两个服务镜像。Docker 从仓库根构建，复制同一资源，无第二份维护数据：

```bash
# 仓库根
docker build -f mcp-servers/jobs-mcp/Dockerfile -t offerpilot/jobs-mcp .
docker compose -f deploy/docker-compose.yml build jobs-mcp
```

## 六、JS 语法速查（给 Java 背景的读者）

本服务的 JS 语法很有限，认完下表就足够读代码。

| JS 写法 | 本仓库中的例子 | Java 对应 |
| --- | --- | --- |
| `import { a } from 'x'` | `import { searchJobs as cliSearchJobs } from 'jobhunt-cli'` | `import`（`as` = 导入时改名） |
| `export function f(){}` | `export function listSites(){}` | `public static` 方法 |
| `const x = 1` | `const PORT = Number(process.env.PORT \|\| 7001)` | `final int PORT = ...` |
| 箭头函数 `(a) => b` | `rows.filter((job) => matchesQuery(job, needle))` | Lambda |
| 对象字面量 `{a: 1}` | `{ id: ..., title: job.name }` | 临时 DTO / Map |
| 模板串 `` `${a}` `` | `` `${siteId} 查询` `` | `String.format` |
| 解构 `const {a} = o` | `const { query = '', nature = 'campus' } = options` | 无直接对应（拆字段 + 默认值） |
| 展开 `...arr` | `jobs.push(...result.jobs)` | `list.addAll(...)` |
| `??` | `job.name ?? ''` | `Objects.requireNonNullElse` |
| `?.` | `error?.message` | `Optional.ofNullable(...).map(...)` |
| `async` / `await` | `await cliSearchJobs(...)` | `CompletableFuture.join()` |
| `Promise.race` | `Promise.race([promise, timeout])` | `CompletableFuture.anyOf(...)` |
| `try/catch` | — | 与 Java 相同 |
| `Map` / `Set` | `const store = new Map()` | `HashMap` / `HashSet` |
| `.map` / `.filter` | `rows.map(...).filter(...)` | Stream 的 `map` / `filter` |

与 Java 相比，本质差异只有三点：

1. **变量不写类型**——类型在运行时决定，读代码时靠上下文推断。
2. **对象随手可写**——不需要先定义 class 就能 `{ id: 1, name: 'x' }`。
3. **异步写起来像同步**——`async/await` 把 `Promise` 包装成顺序代码的样子。

## 七、数据来源与合规

- LIVE 数据来自**企业官网公开招聘接口**，经 `jobhunt-cli` 适配器访问；LOCAL/AUTO 回退为人工整理示例快照，非实时数据。
- 不做登录态操作，不抓取需要授权或明确禁止抓取的内容。
- 跨站点并发上限 6、单站超时 8 秒、结果缓存 10 分钟，均为减少对目标站点的压力。

## 八、已知边界

- 腾讯 / 美团 / 网易 / 华为的校招关键词匹配为**本地过滤**（见设计决定 2）。
- 单元测试覆盖纯逻辑与错误判定；**真实站点查询未纳入自动化**（依赖外部接口，不适合放进测试套件）。
- 后端通过专用 JobsMcpClient 调用 /mcp，通用 Spring AI MCP 自动装配仍关闭。
- 上游 `jobhunt-cli` 或企业接口变更可能导致部分站点失效，此时会在 `_meta.skipped` 里体现。
