/**
 * 把 O*NET 职业条目批量翻译成中文。
 *
 * 用法：node translate.mjs candidates.json out.json
 * 环境变量：DEEPSEEK_API_KEY（必填）、BATCH_SIZE（默认 20）、CONCURRENCY（默认 4）、DEEPSEEK_MODEL（默认 deepseek-flash）
 *
 * 容错设计（模型做批量翻译时偶发丢条目）：
 * - 结果按 code 匹配，不依赖返回顺序
 * - 缺失的条目用更小的批次补翻，最多 3 轮
 * - 每批完成即落盘；重跑时自动跳过已翻译的 code
 */
import fs from 'node:fs';

const API = 'https://api.deepseek.com/chat/completions';
const KEY = process.env.DEEPSEEK_API_KEY;
const MODEL = process.env.DEEPSEEK_MODEL || 'deepseek-flash';
const BATCH_SIZE = Number(process.env.BATCH_SIZE || 20);
const CONCURRENCY = Number(process.env.CONCURRENCY || 4);

if (!KEY) {
  console.error('缺少 DEEPSEEK_API_KEY');
  process.exit(1);
}

const [, , inPath, outPath] = process.argv;
const candidates = JSON.parse(fs.readFileSync(inPath, 'utf8'));

const SYSTEM = '你是中文本地化专家，负责把美国劳工部 O*NET 职业数据库的条目翻译给中国大学生看，用于职业探索。';

function buildPrompt(items) {
  return `下面是 O*NET 职业条目，请翻译成简体中文。

要求：
1. name：职业名称。使用中国大陆求职市场上通行的叫法，不要生硬直译。例如 "Software Developers" → "软件开发工程师"，"Web Developers" → "Web 前端开发工程师"，"Marketing Managers" → "市场经理"，"Graphic Designers" → "平面设计师"。
2. description：一句话说明这个职业主要做什么，60-100 字，用学生能看懂的语言。只转述原文信息，不要添加原文没有的内容（不要编造薪资、行业前景、招聘公司，也不要补充原文未提到的具体例子、课程或工作内容）。
3. 输入有多少条，输出就必须有多少条，每条都要有，不要省略、不要合并。输出 JSON 对象，形如 {"items":[{"code":"...","name":"...","description":"..."}]}，不要输出 markdown 代码块或任何其他文字。

输入：
${JSON.stringify(items.map((i) => ({ code: i.code, title: i.title, description: i.description })))}`;
}

function readDone() {
  if (!fs.existsSync(outPath)) return new Map();
  const rows = JSON.parse(fs.readFileSync(outPath, 'utf8'));
  return new Map(rows.map((row) => [row.code, { name: row.name, descriptionZh: row.descriptionZh }]));
}

/** 调用一次模型，返回 Map<code, {name, descriptionZh}>，并累计 token 用量。 */
async function callModel(items, usage) {
  const response = await fetch(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
    body: JSON.stringify({
      model: MODEL,
      temperature: 0.2,
      response_format: { type: 'json_object' },
      messages: [
        { role: 'system', content: SYSTEM },
        { role: 'user', content: buildPrompt(items) },
      ],
    }),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${(await response.text()).slice(0, 200)}`);

  const payload = await response.json();
  usage.prompt += payload.usage?.prompt_tokens ?? 0;
  usage.completion += payload.usage?.completion_tokens ?? 0;

  const parsed = JSON.parse(payload.choices?.[0]?.message?.content ?? '{}');
  const rows = Array.isArray(parsed.items) ? parsed.items : [];
  const wanted = new Set(items.map((i) => i.code));
  const found = new Map();
  for (const row of rows) {
    if (wanted.has(row.code) && typeof row.name === 'string' && row.name.trim()) {
      found.set(row.code, { name: row.name.trim(), descriptionZh: String(row.description ?? '').trim() });
    }
  }
  return found;
}

/** 递归补翻：只重试本轮缺失的条目，批次随缺失数量自然变小。 */
async function translateAll(items, usage, attempt = 1) {
  const found = await callModel(items, usage);
  const missing = items.filter((i) => !found.has(i.code));
  if (missing.length === 0) return found;
  if (attempt >= 3) {
    throw new Error(`重试 3 轮后仍缺 ${missing.length} 条：${missing.slice(0, 3).map((m) => m.code).join(', ')}`);
  }
  console.log(`  ↻ 第 ${attempt} 轮缺 ${missing.length} 条，缩小批次重试`);
  const retried = await translateAll(missing, usage, attempt + 1);
  for (const [code, value] of retried) found.set(code, value);
  return found;
}

const done = readDone();
const pending = candidates.filter((c) => !done.has(c.code));
console.log(`待翻译 ${pending.length} 条（已完成 ${done.size} 条）`);

const usage = { prompt: 0, completion: 0 };
const batches = [];
for (let i = 0; i < pending.length; i += BATCH_SIZE) {
  batches.push(pending.slice(i, i + BATCH_SIZE));
}

const started = Date.now();
let completed = 0;

function save() {
  // 按候选原顺序输出，便于人工校对
  const ordered = candidates
    .map((c) => {
      const hit = done.get(c.code);
      return hit ? { ...c, name: hit.name, descriptionZh: hit.descriptionZh } : null;
    })
    .filter(Boolean);
  fs.writeFileSync(outPath, JSON.stringify(ordered, null, 2), 'utf8');
}

async function worker() {
  while (batches.length > 0) {
    const batch = batches.shift();
    const found = await translateAll(batch, usage);
    for (const [code, value] of found) done.set(code, value);
    completed += batch.length;
    save();
    console.log(`进度 ${completed}/${pending.length}`);
  }
}

await Promise.all(Array.from({ length: Math.min(CONCURRENCY, batches.length) }, worker));
save();

const seconds = ((Date.now() - started) / 1000).toFixed(1);
console.log(`\n完成 ${done.size}/${candidates.length} 条，耗时 ${seconds}s，输入 ${usage.prompt} tokens，输出 ${usage.completion} tokens`);
