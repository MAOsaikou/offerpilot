import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

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
  return html.replace(
    /\[(KB-[0-9a-f]{12})\]/g,
    '<button type="button" class="md-citation" data-kb-reference="$1" title="查看引用的笔记">$1</button>',
  )
}

export function renderMarkdown(source: string, options: { streaming?: boolean } = {}): string {
  const input = options.streaming ? closeOpenFences(source) : source
  return DOMPurify.sanitize(renderCitations(md.render(input)))
}
