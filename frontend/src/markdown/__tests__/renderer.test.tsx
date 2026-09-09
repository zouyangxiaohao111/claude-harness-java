import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MarkdownText, acquireStreamingRenderer } from '../MarkdownText.tsx'

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

describe('streaming 与 settled 一致性（[markdown-fix] 去 patches 后同源文本逐字节一致）', () => {
  it('heading/段落/表格/列表/强调/链接 两种形态渲染一致', () => {
    const text = '## 标题\n\n一段有 **加粗** 与 [链接](https://e.com) 的文本。\n\n| x | y |\n| --- | --- |\n| 1 | 2 |\n\n- 甲\n- 乙'
    expect(render(text, true)).toBe(render(text, false))
  })
  it('脚注 settled 出 <sup> + data-footnotes', () => {
    const html = render('正文[^1]\n\n[^1]: 注释内容')
    expect(html).toContain('<sup>1</sup>')
    expect(html).toContain('data-footnotes')
  })
  it('`##核心`（无空格 ATX）两态一致按 CommonMark 段落渲染（原 settled-only patch 已移除 · 对齐 dsh）', () => {
    const text = '##核心\n\n正文'
    expect(render(text, true)).toBe(render(text, false))
    const settled = render(text, false)
    expect(settled).toContain('##核心')
    expect(settled).not.toContain('<h2>')
  })
})

describe('[markdown-fix] 围栏代码零改写（原 patches 整文本正则的 RED 回归）', () => {
  it('C 代码 #include/#define 终稿不被插空格（原 fixHeadings 会改成 # include/# define）', () => {
    const text = '```c\n#include <stdio.h>\n#define N 5\nint main() { return 0; }\n```'
    const html = render(text, false)
    expect(html).toContain('#include')
    expect(html).not.toContain('# include')
    expect(html).toContain('#define N 5')
    expect(html).not.toContain('# define')
  })
  it('bash shebang 行不被改动', () => {
    const html = render('```bash\n#!/usr/bin/env bash\necho hi\n```', false)
    expect(html).toContain('#!')
  })
})

// 超过 STREAM_DEGRADE_CHARS(8000) 的单块长度：必然触发 D1 纯文本降级
const LONG_SINGLE_BLOCK = 'x'.repeat(9000)

describe('[chat-switch-stream-align] D1 · streaming 超长单块 → 纯文本降级（零 mdast parse，治「字不吐」）', () => {
  it('超长单段 prose（不可冻结单块）→ <pre md-stream-degraded> 直出原文，不产 md 元素', () => {
    const html = render(LONG_SINGLE_BLOCK, true)
    expect(html).toContain('md-stream-degraded')
    expect(html).toContain(LONG_SINGLE_BLOCK.slice(0, 40))
    expect(html).not.toContain('<p>')
  })
  it('降级不丢字：settled 精排仍含全文（一次全量自愈）', () => {
    const html = render(LONG_SINGLE_BLOCK, false)
    expect(html).toContain(LONG_SINGLE_BLOCK.slice(0, 40))
  })
  it('正常多段短文本不触发降级（冻结有效，维持 markdown）', () => {
    const text = '短段第一行。\n\n' + 'y'.repeat(300)
    const html = render(text, true)
    expect(html).not.toContain('md-stream-degraded')
    expect(html).toContain('<p>')
  })
})

describe('[chat-switch-stream-align] D2 · 流式渲染器按 streamKey 复用（切会话不重建增量状态）', () => {
  it('同 key 同回调 → 返回同一实例（跨 remount 复用）', () => {
    const a = acquireStreamingRenderer('sess-a:blk-1', noop)
    const b = acquireStreamingRenderer('sess-a:blk-1', noop)
    expect(b).toBe(a)
  })
  it('onRunHtml 引用变化 → 重建（冻结元素烘焙回调，引用须稳定）', () => {
    const a = acquireStreamingRenderer('sess-a:blk-2', noop)
    const cb = () => {}
    const c = acquireStreamingRenderer('sess-a:blk-2', cb)
    expect(c).not.toBe(a)
    expect(acquireStreamingRenderer('sess-a:blk-2', cb)).toBe(c)
  })
})

describe('[markdown-fix] settled LRU 缓存键含 onRunHtml（同 text 不同回调不串）', () => {
  it('先渲染带运行按钮、再同 text 无回调 → 不被旧缓存污染', () => {
    const text = '```html\n<b>x</b>\n```'
    const withFn = renderToStaticMarkup(<MarkdownText text={text} onRunHtml={noop} />)
    expect(withFn).toContain('运行')
    // 同 text 第二次无 onRunHtml：若缓存键漏回调会命中「带按钮旧树」→ 必须重渲（RED: 原纯 text 键必绿失败）
    const withoutFn = renderToStaticMarkup(<MarkdownText text={text} />)
    expect(withoutFn).not.toContain('运行')
  })
})
