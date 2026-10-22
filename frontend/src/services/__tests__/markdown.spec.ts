import { describe, expect, it } from 'vitest'

import { looksLikeMarkdown, renderMarkdown } from '../markdown'

/**
 * 把渲染结果按浏览器的口吻解析一遍，再找出所有「真的会被执行」的东西。
 *
 * 为什么不用 `expect(html).not.toContain('onerror')` 这种字符串断言：
 * 转义之后的无害文本里**也会**出现 `onerror`、`javascript:` 这些字样
 * （`html: false` 会把整段标签转义成 `&lt;img ... onerror="..."&gt;`），
 * 裸字符串匹配会把它误判成漏洞。真正要守住的性质是「没有生成活的节点」，
 * 所以这里直接问 DOM：有没有脚本类元素？有没有 on* 事件属性？有没有危险协议？
 */
function collectExecutableNodes(html: string): string[] {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const found: string[] = []

  for (const el of Array.from(doc.body.querySelectorAll('*'))) {
    if (['SCRIPT', 'IFRAME', 'OBJECT', 'EMBED', 'FORM'].includes(el.tagName)) {
      found.push(`<${el.tagName.toLowerCase()}>`)
    }
    for (const attr of Array.from(el.attributes)) {
      if (attr.name.toLowerCase().startsWith('on')) {
        found.push(`${el.tagName.toLowerCase()}[${attr.name}]`)
      }
      if (/^\s*(?:javascript|data|vbscript):/i.test(attr.value)) {
        found.push(`${el.tagName.toLowerCase()}[${attr.name}=${attr.value.slice(0, 32)}]`)
      }
    }
  }

  return found
}

/**
 * `renderMarkdown` 的输入是**不可信内容**：它来自模型，而模型的上下文里混着
 * 用户上传的文档。所以这一组用例的重点不是「Markdown 渲染得好不好看」，
 * 而是**两条 XSS 防线有没有真的生效** —— 它守的是 `v-html` 这个执行点。
 *
 * 第一道：markdown-it 的 `html: false`（把原始 HTML 整段转义）
 * 第二道：DOMPurify 的白名单清洗（标签/属性/协议三重限制）
 */
describe('renderMarkdown 的 XSS 防线', () => {
  it('裸 <script> 被转义成文本，而不是变成标签', () => {
    const html = renderMarkdown('<script>alert(1)</script>')

    expect(collectExecutableNodes(html)).toEqual([])
    // 内容还在，只是作为文字显示 —— 这比直接删掉更可诊断
    expect(html).toContain('alert(1)')
  })

  it('事件属性注入不会产生带 on* 的节点', () => {
    const html = renderMarkdown('<img src=x onerror="alert(1)">')

    expect(collectExecutableNodes(html)).toEqual([])
    // 转义成文字后浏览器不会加载它，因此连 img 元素都不该存在
    expect(new DOMParser().parseFromString(html, 'text/html').querySelector('img')).toBeNull()
  })

  it('javascript: 协议的链接被挡掉', () => {
    const html = renderMarkdown('[点我](javascript:alert(1))')

    expect(collectExecutableNodes(html)).toEqual([])
    expect(renderMarkdown('[点我](javascript:alert(1))')).not.toContain('href="javascript:')
  })

  it('data: 协议的链接也被挡掉（只放行 http/https/mailto）', () => {
    const html = renderMarkdown('[点我](data:text/html;base64,PHNjcmlwdD4=)')

    expect(collectExecutableNodes(html)).toEqual([])
  })

  it('iframe / form 这类可执行结构被禁止', () => {
    const html = renderMarkdown('<iframe src="https://evil.example"></iframe>\n\n<form action="/x"></form>')

    expect(collectExecutableNodes(html)).toEqual([])
  })

  it('上面几条合起来守住的性质：任何恶意输入都产不出可执行节点', () => {
    const payloads = [
      '<img src=x onerror=alert(1)>',
      '<svg/onload=alert(1)>',
      '<a href="javascript:alert(1)">x</a>',
      '<body onload=alert(1)>',
      '[x](JaVaScRiPt:alert(1))',
      '<iframe srcdoc="<script>alert(1)</script>"></iframe>',
      '<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>',
    ]

    for (const payload of payloads) {
      expect(collectExecutableNodes(renderMarkdown(payload)), `payload: ${payload}`).toEqual([])
    }
  })
})

describe('renderMarkdown 的正常渲染', () => {
  it('空输入返回空串', () => {
    expect(renderMarkdown('')).toBe('')
  })

  it('基础语法正常渲染', () => {
    const html = renderMarkdown('# 标题\n\n**粗体** 与 *斜体*')

    expect(html).toContain('<h1>标题</h1>')
    expect(html).toContain('<strong>粗体</strong>')
    expect(html).toContain('<em>斜体</em>')
  })

  it('外部链接被加上 target=_blank 与 rel=noopener（防 tabnabbing）', () => {
    const html = renderMarkdown('[文档](https://example.com/doc)')

    expect(html).toContain('href="https://example.com/doc"')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer nofollow"')
  })

  it('表格正常渲染（回答里常用来列字段）', () => {
    const html = renderMarkdown('| 字段 | 要求 |\n| --- | --- |\n| 设备编号 | 必填 |')

    expect(html).toContain('<table>')
    expect(html).toContain('<th>字段</th>')
    expect(html).toContain('<td>必填</td>')
  })
})

describe('renderMarkdown 的代码块', () => {
  it('带 data-lang，供 CSS 显示语言标签', () => {
    const html = renderMarkdown('```python\nprint(1)\n```')

    expect(html).toContain('class="md-code-block"')
    expect(html).toContain('data-lang="python"')
    expect(html).toContain('class="hljs language-python"')
  })

  it('语言别名可用（模型常写 py / js / sh）', () => {
    expect(renderMarkdown('```py\nprint(1)\n```')).toContain('data-lang="py"')
    expect(renderMarkdown('```js\nconst a = 1\n```')).toContain('data-lang="js"')
  })

  it('未知语言降级为转义纯文本，且不抛异常', () => {
    const html = renderMarkdown('```definitely-not-a-language\n<x>\n```')

    expect(html).toContain('data-lang="definitely-not-a-language"')
    expect(html).not.toContain('<x>')
  })

  it('代码里的 HTML 被转义，不会被浏览器当标签解析', () => {
    const html = renderMarkdown('```html\n<script>alert(1)</script>\n```')

    expect(html).not.toContain('<script>alert(1)</script>')
  })
})

describe('looksLikeMarkdown', () => {
  it('识别得出常见的 Markdown 结构', () => {
    expect(looksLikeMarkdown('```js\n1\n```')).toBe(true)
    expect(looksLikeMarkdown('## 小节标题')).toBe(true)
    expect(looksLikeMarkdown('- 一\n- 二')).toBe(true)
    expect(looksLikeMarkdown('1. 一')).toBe(true)
    expect(looksLikeMarkdown('这是 **重点**')).toBe(true)
    expect(looksLikeMarkdown('见 [文档](https://example.com)')).toBe(true)
    expect(looksLikeMarkdown('> 注意')).toBe(true)
  })

  it('普通的一句话不算 Markdown（走纯文本渲染更快也更省）', () => {
    expect(looksLikeMarkdown('电量低于 30% 必须回桩充电。')).toBe(false)
  })

  it('空值与 null 安全', () => {
    expect(looksLikeMarkdown('')).toBe(false)
    expect(looksLikeMarkdown(null as unknown as string)).toBe(false)
  })
})
