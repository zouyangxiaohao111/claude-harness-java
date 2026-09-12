import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { MarkdownText, acquireStreamingRenderer } from '../MarkdownText.tsx'

const noop = () => {}
function render(text: string, streaming = false, className?: string) {
  return renderToStaticMarkup(<MarkdownText text={text} streaming={streaming} className={className} onRunHtml={noop} />)
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

describe('streaming 与 settled 一致性（[markdown-fix] 去 patches 后同源；[rescue] 粘连标记是唯一例外）', () => {
  it('heading/段落/表格/列表/强调/链接 两种形态渲染一致', () => {
    const text = '## 标题\n\n一段有 **加粗** 与 [链接](https://e.com) 的文本。\n\n| x | y |\n| --- | --- |\n| 1 | 2 |\n\n- 甲\n- 乙'
    expect(render(text, true)).toBe(render(text, false))
  })
  it('脚注 settled 出 <sup> + data-footnotes', () => {
    const html = render('正文[^1]\n\n[^1]: 注释内容')
    expect(html).toContain('<sup>1</sup>')
    expect(html).toContain('data-footnotes')
  })
  it('无空格 ATX 标题被抢救为真标题（有意偏离 dsh）', () => {
    // 此处有意偏离 deepseek-harness：dsh 明文选择「不修复格式错误的模型输出」
    // （其 parse.ts 注释自述 "not a regex rewrite or malformed-model-output repair"），
    // 并把该降级行为钉死为测试契约。本项目选择抢救 —— 依据：全库 1143 条真实 assistant
    // 消息实测，中文模型输出里 `##标题`（# 后无空格）是系统性习惯，不修则整块裸露。
    // 详见 docs/zjkycode/specs/2026-09-12-markdown-dirty-input-rescue-design.md §8。
    const settled = render('##核心\n正文\n')
    expect(settled).toContain('<h2>核心</h2>')
    expect(settled).not.toContain('##核心')
    // 两态不再逐字节相等：流式臂不抢救（方案 B）。差异只允许是「裸露减少」——
    // 写成对照形态（两臂各有正向 + 镜像反向），「仍然裸露」与「没有变成标题」都要钉住。
    const streaming = render('##核心\n正文\n', true)
    expect(streaming).toContain('<p>##核心')
    expect(streaming).not.toContain('<h2>')
  })
})

// —— 以下两条 [rescue] describe 按设计文档 §7 的层序排列（层 3 两态 → 层 4 回滚哨兵）；
//    历史回归钉（[markdown-fix] / [chat-switch-stream-align]）统一排在其后。
describe('[rescue] 两态差异只允许是「裸露减少」', () => {
  it('流式臂不抢救：含粘连标记的文本在 streaming 下仍裸露', () => {
    const t = '##一句话结论\n'
    expect(render(t, true)).toContain('##一句话结论')      // 流式仍裸露（预期）
    expect(render(t, false)).toContain('<h2>一句话结论</h2>')  // 定稿已抢救
  })
  it('P1 路径的两态：定稿救出表格、流式仍裸露', () => {
    const t = '##已压缩|批次 |内容 |\n|---|---|\n|1 |a |\n'
    expect(render(t, true)).toContain('##已压缩')
    expect(render(t)).toContain('<table>')
  })
  it('P0 放弃闸下 P2 仍生效（两态差异只是裸露减少）', () => {
    const t = '##标题\n正文```\n'
    const settled = render(t)
    expect(settled).toContain('<h2>标题</h2>')   // P2 生效
    expect(settled).toContain('正文```')          // P0 被平衡闸挡住，围栏原样
    expect(render(t, true)).toContain('##标题')   // 流式仍裸露
  })
  it('抢救与 className 无关（用户气泡 user-text md 同路）', () => {
    // 用户气泡的真接线在 MessageList.tsx（ContentGuard className="user-text md" → MarkdownText settled）；
    // 本测试钉的是 renderSettled 不读 className，故两类气泡同路。有 @引用 的用户消息走另一条路，不抢救。
    const html = render('##一句话结论', false, 'user-text md')
    expect(html).toContain('<h2>一句话结论</h2>')
  })
})

describe('[rescue] 层 4 · 回滚哨兵（抢救不破坏合法语法）', () => {
  it('合法 ATX 一级标题仍出 <h1>', () => {
    const html = render('# 标题\n')
    expect(html).toContain('<h1>标题</h1>')
  })
  it('合法无序列表仍渲染为 <ul><li>（回滚守卫）', () => {
    const html = render('- 项一\n- 项二\n')
    expect(html).toContain('<ul>')
    expect(html.match(/<li>/g)).toHaveLength(2)
  })
  it('链接引用定义仍被解析（`[foo]: /url` + `[foo]`）', () => {
    // 注：不断言 href —— '/url' 是相对 URL，会 render.tsx 的 sanitizeUrl 协议白名单
    // （仅 http/https/mailto）判空而降级为纯文本。本测试钉的是「definition 仍被解析」：
    // 引用被解析 ⇒ 渲染为 label 文本；对照：无定义时方括号会保留（见下条）。
    // 本用例在 rescue 关闭时同样绿 —— 它钉的是回归边界，不是 rescue 行为。
    const html = render('[foo]: /url\n\n见 [foo]\n')
    expect(html).toContain('见 foo')
    expect(html).not.toContain('[foo]')
  })
  it('对照：无定义的引用保留方括号字面量', () => {
    expect(render('见 [foo]\n')).toContain('[foo]')
  })
  it('围栏内的表格分隔行与无空格 ATX 形状一律不被改动', () => {
    // 最危险的雷：`|---|` / `##x` 恰好是 P1/P2 的判据形状，围栏态一判错就会被改写
    const html = render('```\n|---|\n##不是标题\n```\n')
    expect(html).toContain('|---|')
    expect(html).toContain('##不是标题')
    expect(html).not.toContain('<h2>')
    expect(html).not.toContain('<table>')
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
