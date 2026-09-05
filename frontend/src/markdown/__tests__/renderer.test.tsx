import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MarkdownText } from '../MarkdownText.tsx'

const noop = () => {}
function render(text: string, streaming = false) {
  return renderToStaticMarkup(<MarkdownText text={text} streaming={streaming} onRunHtml={noop} />)
}

describe('breaks 软换行（对齐旧 marked breaks:true）', () => {
  it('段落内单个换行 → <br>，不塌成空格', () => {
    const html = render('第一行\n第二行\n\n新段')
    expect(html).toContain('<p>第一行<br/>第二行</p>')
    expect(html).toContain('<p>新段</p>')
  })
  it('list item 内软换行出 <br>', () => {
    const html = render('- 甲\n  乙')
    expect(html).toContain('<li>甲<br/>乙</li>')
  })
  it('硬换行（行尾两空格）也出 <br>', () => {
    const html = render('a  \nb')
    expect(html).toContain('<br/>')
  })
})

describe('GFM 结构', () => {
  it('表格 → .md-table-scroll + table/th/td', () => {
    const html = render('| a | b |\n| --- | --- |\n| 1 | 2 |')
    expect(html).toContain('md-table-scroll')
    expect(html).toContain('<table>')
    expect(html).toContain('<th>')
    expect(html).toContain('<td>')
  })
  it('任务列表 → contains-task-list + checked checkbox', () => {
    const html = render('- [x] 完成\n- [ ] 待办')
    expect(html).toContain('contains-task-list')
    expect(html).toContain('task-list-item')
    expect(html).toContain('type="checkbox"')
    expect(html).toContain('checked=""')
  })
  it('中文紧贴强调（cjkFriendlyStrong）→ 能闭合 <strong>', () => {
    const html = render('**注意：**内容直接跟中文')
    expect(html).toContain('<strong>注意：</strong>')
  })
  it('heading/ul/blockquote/hr 基础节点', () => {
    const html = render('## 标题\n\n> 引用\n\n- 项\n\n---')
    expect(html).toContain('<h2>标题</h2>')
    expect(html).toContain('<blockquote>')
    expect(html).toContain('<ul>')
    expect(html).toContain('<hr/>')
  })
})

describe('代码块（CodeBlock banner + 复制/运行）', () => {
  it('闭合 ```html → 有 md-code-block/banner/运行/复制，代码被转义不产裸标签', () => {
    const html = render('```html\n<b>1</b>\n```')
    expect(html).toContain('md-code-block')
    expect(html).toContain('md-code-banner')
    expect(html).toContain('md-code-run')
    expect(html).toContain('运行')
    expect(html).toContain('复制')
    expect(html).toContain('&lt;b&gt;1&lt;/b&gt;')
    expect(html).not.toContain('<b>1</b>')
  })
  it('```js → 只有复制没有运行', () => {
    const html = render('```js\nconst a = 1\n```')
    expect(html).toContain('md-code-copy')
    expect(html).toContain('复制')
    expect(html).not.toContain('md-code-run')
    expect(html).not.toContain('运行')
  })
  it('未闭合 ```html（流式中）→ 仍产 CodeBlock、lang=html、partial 值、有运行', () => {
    const text = '说明\n\n```html\n<div class="x">\n<p>部分代码'
    const html = render(text, true)
    expect(html).toContain('md-code-block')
    expect(html).toContain('html')
    expect(html).toContain('md-code-run')
    expect(html).toContain('部分代码')
    expect(html).toContain('&lt;div class=&quot;x&quot;&gt;')
  })
})

describe('XSS 自持（替代 DOMPurify）', () => {
  it('raw HTML 一律字面量文本，不建 <script> 元素', () => {
    const html = render('<script>alert(1)</script>')
    expect(html).toContain('&lt;script&gt;')
    expect(html).not.toContain('<script>')
  })
  it('javascript: 链接协议不放行（退化为纯文本）', () => {
    const html = render('[x](javascript:alert(1))')
    expect(html).not.toContain('href="javascript')
    expect(html).not.toContain('<a')
    expect(html).toContain('x')
  })
  it('http 外链带 target/rel', () => {
    const html = render('[x](https://e.com/a)')
    expect(html).toContain('href="https://e.com/a"')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer"')
  })
  it('相对路径图片不放行（渲染 alt 占位），绝对 http(s) 图片放行', () => {
    const rel = render('![alt](/rel.png)')
    expect(rel).not.toContain('<img')
    expect(rel).toContain('md-image-alt')
    const abs = render('![a](https://e.com/i.png)')
    expect(abs).toContain('<img')
    expect(abs).toContain('src="https://e.com/i.png"')
  })
})

describe('streaming 与 settled 一致性（无需 patch 的 GFM 文本应逐字节一致）', () => {
  it('heading/段落/表格/列表/强调/链接 两种形态渲染一致', () => {
    const text = '## 标题\n\n一段有 **加粗** 与 [链接](https://e.com) 的文本。\n\n| x | y |\n| --- | --- |\n| 1 | 2 |\n\n- 甲\n- 乙'
    expect(render(text, true)).toBe(render(text, false))
  })
  it('脚注 settled 出 <sup> + data-footnotes', () => {
    const html = render('正文[^1]\n\n[^1]: 注释内容')
    expect(html).toContain('<sup>1</sup>')
    expect(html).toContain('data-footnotes')
  })
})

describe('settled-only patch（有意终跳：流式与结束之间允许一次修正）', () => {
  it('`##核心` 无空格标题：settled 出 <h2>，streaming 按字面段落', () => {
    const text = '##核心\n\n正文'
    const settled = render(text, false)
    const streaming = render(text, true)
    expect(settled).toContain('<h2>核心</h2>')
    expect(streaming).toContain('<p>')
    expect(streaming).toContain('##核心')
    expect(streaming).not.toContain('<h2>')
  })
})
