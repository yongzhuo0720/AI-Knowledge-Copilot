import DOMPurify from 'dompurify'
// 注意：这里必须走 highlight.js/lib/core 而不是直接 import 'highlight.js'。
// 后者会把 190+ 种语言全部打进产物，实测让 ChatView chunk 从 ~130KB 膨胀到 1.1MB。
// 用 core + 手动注册常用语言，只保留真正会出现的十几种。
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import cpp from 'highlight.js/lib/languages/cpp'
import csharp from 'highlight.js/lib/languages/csharp'
import css from 'highlight.js/lib/languages/css'
import go from 'highlight.js/lib/languages/go'
import ini from 'highlight.js/lib/languages/ini'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import kotlin from 'highlight.js/lib/languages/kotlin'
import markdown from 'highlight.js/lib/languages/markdown'
import plaintext from 'highlight.js/lib/languages/plaintext'
import python from 'highlight.js/lib/languages/python'
import rust from 'highlight.js/lib/languages/rust'
import scss from 'highlight.js/lib/languages/scss'
import shell from 'highlight.js/lib/languages/shell'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import MarkdownIt from 'markdown-it'

// 语言别名：模型常输出 js/py/sh/yaml 这类缩写
const LANGUAGES: Record<string, unknown> = {
  bash, cpp, csharp, css, go, ini, java, javascript, json, kotlin,
  markdown, plaintext, python, rust, scss, shell, sql, typescript, xml, yaml,
}
const ALIASES: Record<string, string> = {
  js: 'javascript', ts: 'typescript', py: 'python', sh: 'shell',
  yml: 'yaml', html: 'xml', vue: 'xml', c: 'cpp', 'c++': 'cpp',
  golang: 'go', rs: 'rust', kt: 'kotlin', cs: 'csharp', text: 'plaintext',
}
for (const [name, definition] of Object.entries(LANGUAGES)) {
  hljs.registerLanguage(name, definition as Parameters<typeof hljs.registerLanguage>[1])
}
for (const [alias, target] of Object.entries(ALIASES)) {
  hljs.registerAliases(alias, { languageName: target })
}

/**
 * 把模型输出的 Markdown 渲染成安全的 HTML。
 *
 * ## 为什么必须做 XSS 防护
 * 模型输出的内容有两条「不可信」来源：
 * 1. 用户上传的文档里可能夹带 `<script>`、`onerror=` 之类的攻击串，
 *    被 RAG 检索出来后原样进入回答；
 * 2. 模型本身可能被提示词注入（prompt injection）诱导输出恶意 HTML。
 *
 * 只要用了 `v-html` 渲染这些内容，就等价于把攻击者写的代码放进自己的页面执行。
 * 所以这里做**两道防线**：
 * - 第一道：markdown-it 关闭 `html`，原始 HTML 标签一律转义成文本；
 * - 第二道：DOMPurify 白名单过滤，即使第一道被绕过也不会执行脚本。
 *
 * 两道都用，是因为它们过滤的维度不同 —— 前者处理"Markdown 里的裸 HTML"，
 * 后者处理"渲染产物里的危险节点/属性"。任何一道单独用都有绕过姿势。
 */

// 允许的语言白名单之外的代码，降级为纯文本，避免 highlight.js 抛异常
const md = new MarkdownIt({
  html: false, // 第一道防线：不解析裸 HTML
  linkify: true,
  breaks: true, // 单换行也换行，符合聊天场景直觉
  typographer: false,
  highlight(code: string, language: string): string {
    const lang = language?.trim().toLowerCase()
    const canHighlight = lang && hljs.getLanguage(lang)
    let body: string
    try {
      body = canHighlight
        ? hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
        : md.utils.escapeHtml(code)
    } catch {
      body = md.utils.escapeHtml(code)
    }
    // 带上 data-lang，方便 CSS 右上角显示语言标签
    return `<pre class="md-code-block" data-lang="${md.utils.escapeHtml(lang || 'text')}"><code class="hljs language-${md.utils.escapeHtml(lang || 'text')}">${body}</code></pre>`
  },
})

// 让链接新窗口打开，并切断 window.opener（防 tabnabbing 反向劫持）
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A' && node.hasAttribute('href')) {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer nofollow')
  }
})

export function renderMarkdown(source: string): string {
  if (!source) return ''
  const rawHtml = md.render(source)
  // 第二道防线：白名单过滤
  return DOMPurify.sanitize(rawHtml, {
    ALLOWED_TAGS: [
      'p', 'br', 'hr', 'strong', 'em', 'del', 's', 'blockquote', 'code', 'pre',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'ul', 'ol', 'li', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
      'a', 'span', 'div', 'sup', 'sub',
    ],
    ALLOWED_ATTR: ['href', 'title', 'class', 'data-lang', 'target', 'rel', 'colspan', 'rowspan'],
    // 只要 http/https/mailto，挡掉 javascript: / data: 协议
    ALLOWED_URI_REGEXP: /^(?:https?|mailto):/i,
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form', 'input'],
  })
}

/** 判断内容是否值得走 Markdown 渲染（纯文本走渲染也安全，只是没必要） */
export function looksLikeMarkdown(source: string): boolean {
  return /```|^\s{0,3}#{1,6}\s|^\s*[-*+]\s|^\s*\d+\.\s|\*\*|\[.+\]\(.+\)|^\s*>\s/m.test(source ?? '')
}
