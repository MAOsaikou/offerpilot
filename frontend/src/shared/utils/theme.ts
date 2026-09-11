export type ThemeMode = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'op-theme'

export function resolveTheme(mode: ThemeMode): 'light' | 'dark' {
  if (mode !== 'system') return mode
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function getStoredTheme(): ThemeMode {
  const raw = localStorage.getItem(STORAGE_KEY)
  return raw === 'light' || raw === 'dark' || raw === 'system' ? raw : 'system'
}

export function applyTheme(mode: ThemeMode): void {
  document.documentElement.classList.toggle('dark', resolveTheme(mode) === 'dark')
}

export function setTheme(mode: ThemeMode): void {
  if (mode === 'system') localStorage.removeItem(STORAGE_KEY)
  else localStorage.setItem(STORAGE_KEY, mode)
  applyTheme(mode)
}

/** 跟随系统偏好，用户手动切换后以本地存储为准 */
export function initTheme(): void {
  applyTheme(getStoredTheme())
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (getStoredTheme() === 'system') applyTheme('system')
  })
}
