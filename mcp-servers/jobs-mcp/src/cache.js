/**
 * 极简内存缓存，带过期时间。
 *
 * 用途：同一个关键词在短时间内会被 Agent 反复查询，直接打企业接口既慢又不礼貌。
 * 文档要求 10 分钟，这里通过 ttlMs 参数控制。
 */

const DEFAULT_TTL_MS = 10 * 60 * 1000;
const MAX_ENTRIES = 500;

const store = new Map();

function read(key) {
  const hit = store.get(key);
  if (!hit) return undefined;
  if (hit.expiresAt <= Date.now()) {
    store.delete(key);
    return undefined;
  }
  return hit.value;
}

function write(key, value, ttlMs) {
  // 超量时淘汰最早插入的一条（Map 保持插入顺序）
  if (store.size >= MAX_ENTRIES && !store.has(key)) {
    const oldest = store.keys().next();
    if (!oldest.done) store.delete(oldest.value);
  }
  store.set(key, { value, expiresAt: Date.now() + ttlMs });
}

/**
 * 命中缓存就直接返回，否则执行 loader 并缓存结果。
 * 返回 cached 标记，便于在 _meta 里告诉调用方数据是否来自缓存。
 */
export async function withCache(key, loader, ttlMs = DEFAULT_TTL_MS) {
  const hit = read(key);
  if (hit !== undefined) return { value: hit, cached: true };

  const value = await loader();
  write(key, value, ttlMs);
  return { value, cached: false };
}

export function cacheStats() {
  return { entries: store.size, maxEntries: MAX_ENTRIES };
}

export function clearCache() {
  store.clear();
}
