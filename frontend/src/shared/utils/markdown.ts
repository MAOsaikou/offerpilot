import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'
import { highlightCode, normalizeLanguage } from './codeHighlight'

/**
 * Markdown 渲染（docs/07 §8）。
 * - 单例 markdown-it，关闭 html 直通，输出一律过 DOMPurify 消毒
 * - 流式阶段先做「临时闭合」，避免未写完的代码块把后半段全吞进去
 * - 公式不在目标场景内，不引入 KaTeX
 */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  breaks: true,
})

/** 代码块：语言标签 + 复制按钮；语法就绪时用 shiki 高亮，否则沿用默认的转义输出 */
const defaultFence = md.renderer.rules.fence!
md.renderer.rules.fence = (tokens, idx, options, env, self) => {
  const token = tokens[idx]!
  const language = normalizeLanguage(token.info)
  const body =
    highlightCode(token.content, language) ?? defaultFence(tokens, idx, options, env, self)
  const raw = token.info.trim().split(/\s+/)[0] ?? ''
  const label = raw ? md.utils.escapeHtml(raw) : '代码'
  return `<div class="code-block"><div class="code-head"><span class="code-lang">${label}</span><button type="button" class="code-copy" data-copy-code>复制</button></div>${body}</div>`
}

/** 统计未闭合的围栏代码块，缺一个就补一个 */
export function closeOpenFences(source: string): string {
  const fences = source.match(/^```/gm)
  if (fences && fences.length % 2 === 1) return `${source}\n\`\`\``
  return source
}

/**
 * 把正文里的知识笔记引用 [KB-xxxxxxxxxxxx]（由程序检索生成，见 CardNarrationDigest）渲染成可点击的小标签，
 * 点击后定位到本轮的「知识笔记来源」卡片。没有来源的纯数字 [1] 保持原样，不渲染成无效按钮。
 */
function renderCitations(html: string): string {
  // 代码块里的文字原样保留，不做引用替换
  return html
    .split(/(<pre[\s\S]*?<\/pre>)/)
    .map((part, index) =>
      index % 2
        ? part
        : part.replace(
            /\[(KB-[0-9a-f]{12})\]/g,
            '<button type="button" class="md-citation" data-kb-reference="$1" title="查看引用的笔记">$1</button>',
          ),
    )
    .join('')
}

const textRenderer = md.renderer.rules.text!
md.renderer.rules.text = (tokens, idx, options, env, self) => {
  const text = textRenderer(tokens, idx, options, env, self)
  let inLink = false
  for (let i = 0; i < idx; i++) {
    if (tokens[i]?.type === 'link_open') inLink = true
    if (tokens[i]?.type === 'link_close') inLink = false
  }
  if (inLink) return text
  return text.replace(/\[(\d+)\]/g, (whole, index) =>
    Array.isArray(env?.citationIndexes) && env.citationIndexes.includes(Number(index))
      ? `<button type="button" class="md-citation" data-citation-index="${index}">[${index}]</button>`
      : whole,
  )
}
export function renderMarkdown(
  source: string,
  options: { streaming?: boolean; citationIndexes?: number[] } = {},
): string {
  const input = options.streaming ? closeOpenFences(source) : source
  return DOMPurify.sanitize(
    renderCitations(md.render(input, { citationIndexes: options.citationIndexes })),
  )
}
