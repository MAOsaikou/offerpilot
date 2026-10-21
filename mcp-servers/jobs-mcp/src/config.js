export const DEFAULT_LIVE_TIMEOUT_MS = 6500;
export const DEFAULT_SITE_TIMEOUT_MS = 6000;

export function positiveTimeout(value, fallback) {
  const timeout = Number(value);
  return Number.isFinite(timeout) && timeout > 0 ? timeout : fallback;
}
