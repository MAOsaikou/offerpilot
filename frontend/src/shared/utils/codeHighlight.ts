import { ref } from 'vue'
import type { HighlighterCore, LanguageRegistration } from 'shiki/core'

/**
 * 回复中代码块的语法高亮（AG-02）。
 * - shiki 核心 + JS 正则引擎，不加载 wasm；只有出现代码块时才按需加载对应语言，常见语言之外保持纯文本
 * - 高亮器异步就绪，就绪后递增 highlightRevision，MarkdownView 据此重新渲染
 * - 输出双主题 CSS 变量（--shiki-light / --shiki-dark），由 markdown.css 跟随 html.dark 切换
 */
type Grammar = () => Promise<{ default: LanguageRegistration[] }>
const GRAMMARS: Record<string, Grammar> = {
  java: () => import('shiki/langs/java.mjs'),
  python: () => import('shiki/langs/python.mjs'),
  javascript: () => import('shiki/langs/javascript.mjs'),
  typescript: () => import('shiki/langs/typescript.mjs'),
  tsx: () => import('shiki/langs/tsx.mjs'),
  vue: () => import('shiki/langs/vue.mjs'),
  html: () => import('shiki/langs/html.mjs'),
  css: () => import('shiki/langs/css.mjs'),
  json: () => import('shiki/langs/json.mjs'),
  yaml: () => import('shiki/langs/yaml.mjs'),
  xml: () => import('shiki/langs/xml.mjs'),
  sql: () => import('shiki/langs/sql.mjs'),
  bash: () => import('shiki/langs/bash.mjs'),
  go: () => import('shiki/langs/go.mjs'),
  c: () => import('shiki/langs/c.mjs'),
  kotlin: () => import('shiki/langs/kotlin.mjs'),
  rust: () => import('shiki/langs/rust.mjs'),
}
const ALIASES: Record<string, string> = {
  js: 'javascript',
  jsx: 'javascript',
  mjs: 'javascript',
  ts: 'typescript',
  py: 'python',
  sh: 'bash',
  shell: 'bash',
  zsh: 'bash',
  console: 'bash',
  yml: 'yaml',
  golang: 'go',
  // C++ 语法包约 800KB，按 C 语法近似高亮
  cpp: 'c',
  'c++': 'c',
  kt: 'kotlin',
  rs: 'rust',
  mysql: 'sql',
  postgresql: 'sql',
}
/** 超长代码块不在主线程反复高亮，流式时每个分片都会重新渲染 */
const MAX_HIGHLIGHT_CHARS = 20_000

export const highlightRevision = ref(0)
let highlighter: HighlighterCore | null = null
let creating: Promise<HighlighterCore> | null = null
const loaded = new Set<string>()
const loading = new Map<string, Promise<void>>()

export function normalizeLanguage(info: string): string {
  const raw = (info.trim().split(/\s+/)[0] ?? '').toLowerCase()
  return ALIASES[raw] ?? raw
}

function create(): Promise<HighlighterCore> {
  creating ??= Promise.all([
    import('shiki/core'),
    import('shiki/engine/javascript'),
    import('shiki/themes/github-light.mjs'),
    import('shiki/themes/github-dark.mjs'),
  ]).then(async ([core, engine, light, dark]) => {
    highlighter = await core.createHighlighterCore({
      themes: [light.default, dark.default],
      langs: [],
      engine: engine.createJavaScriptRegexEngine({ forgiving: true }),
    })
    return highlighter
  })
  return creating
}

/** 加载语言语法；已加载或不支持时立即返回。失败后保持纯文本，不重试。 */
export function ensureLanguage(language: string): Promise<void> {
  if (!GRAMMARS[language] || loaded.has(language)) return Promise.resolve()
  let pending = loading.get(language)
  if (!pending) {
    pending = Promise.all([create(), GRAMMARS[language]!()])
      .then(async ([instance, grammar]) => {
        await instance.loadLanguage(grammar.default)
        loaded.add(language)
        highlightRevision.value++
      })
      .catch(() => undefined)
    loading.set(language, pending)
  }
  return pending
}

/** 同步高亮：语法已就绪时返回 <pre class="shiki">…；否则触发加载并返回 null（先显示纯文本）。 */
export function highlightCode(code: string, language: string): string | null {
  if (!GRAMMARS[language] || code.length > MAX_HIGHLIGHT_CHARS) return null
  if (!highlighter || !loaded.has(language)) {
    void ensureLanguage(language)
    return null
  }
  try {
    return highlighter.codeToHtml(code, {
      lang: language,
      themes: { light: 'github-light', dark: 'github-dark' },
      defaultColor: false,
    })
  } catch {
    return null
  }
}
