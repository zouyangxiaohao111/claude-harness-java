// @vitest-environment jsdom
// [stream-raw] 样式契约必须读**真计算值**，故本文件走 jsdom（同 `math.test.tsx` 的先例）；
// 其余断言只用到 `renderToStaticMarkup`，在 jsdom 下同样成立。
import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { MarkdownText } from '../MarkdownText.tsx'

/**
 * [stream-raw] 流式臂契约：**原样纯文本直出**。
 *
 * 为什么这条契约重要（而非只是「行为断言」）：流式臂此前走增量 mdast（`StreamingRenderer`），
 * 与收口臂（`renderSettled` → `repair` + `parseGfmWithMath`）是**两套解析器**，实测同一文本在
 * 流式期与收口给出不同结果（`##标题` 流式期字面裸露、收口才成形，且掉字 + 整体位移）。
 * 用户裁定「流式期间不做 markdown 解析，后端发什么就原样打字直出；这一轮结束后由收口臂
 * 做一次完整渲染」。因此本文件钉的是**最强形态**：逐字符相等 —— 一旦有人在流式臂里重新
 * 引入任何解析/改写/裁剪，这里必须红。
 */

const noop = () => {}

/** HTML 反转义（`react-dom/server` 对文本子节点的转义集：& < > " '）。 */
function unescapeHtml(s: string): string {
  return s
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#x27;/g, "'")
    .replace(/&amp;/g, '&')
}

/**
 * 流式臂输出的**结构契约**：恰好是
 * `<div class="{className}"><div class="md-stream-raw">{原文}</div></div>`。
 * 形状不匹配直接抛（而不是让下面的比较给出误导性的差异）——
 * 这条正则本身就钉住了「外层 div 保留」「内层用 div 而非 pre」（`<pre>` 会被
 * `.msg .content pre` 压过样式，见 globals.css 的 .md-stream-raw 注释）。
 */
function streamRawShell(text: string, className = 'content md'): { html: string; inner: string } {
  const html = renderToStaticMarkup(
    <MarkdownText text={text} streaming className={className} onRunHtml={noop} />,
  )
  const m = new RegExp(
    `^<div class="${className}"><div class="md-stream-raw">([\\s\\S]*)</div></div>$`,
  ).exec(html)
  if (m === null) {
    throw new Error(`流式臂输出形状不符（应为 <div class="${className}"><div class="md-stream-raw">…</div></div>）：${html.slice(0, 200)}`)
  }
  return { html, inner: unescapeHtml(m[1]!) }
}

/** 流式臂渲染出的原文（逐字符应与输入相等）。 */
function streamRawOf(text: string): string {
  return streamRawShell(text).inner
}

/** 不得出现的 markdown 结构标签（流式臂只输出纯文本，一个都不该有）。 */
const STRUCTURE_TAGS = [
  '<h1', '<h2', '<h3', '<h4', '<h5', '<h6',
  '<table', '<pre', '<code', '<ul', '<ol', '<blockquote', '<hr', '<strong', '<em',
  'class="katex',
]

const CORPUS: [string, string][] = [
  ['普通段落', '这是一段普通文字。\n第二行也一样。\n\n新段落。'],
  ['## 带空格标题', '## 带空格标题\n\n正文一句。'],
  ['##不带空格标题', '##不带空格标题\n\n正文一句。'],
  ['围栏代码块（闭合）', '```js\nconst a = 1\n```'],
  ['围栏代码块（未闭合）', '说明\n\n```html\n<div class="x">\n<p>部分代码'],
  ['GFM 表格', '| a | b |\n| --- | --- |\n| 1 | 2 |'],
  ['表格列数不齐', '| a | b |\n| --- | --- |\n| 1 |'],
  ['引用', '> 引用第一行\n> 第二行'],
  ['无序列表', '- 甲\n- 乙'],
  ['有序列表', '1. 甲\n2. 乙'],
  ['**粗体** 与 CJK 相邻', '**注意：**内容直接跟中文'],
  ['行内代码', '用 `a|b` 分隔'],
  ['|---|', '前文\n\n|---|\n\n后文'],
  ['---', '前文\n\n---\n\n后文'],
  ['连续空格与制表符', 'a   b\tc\t\t d'],
  ['HTML 敏感字符', '<script>alert("x") & \'y\'</script> > < & "'],
  ['数学 $x^2$', '行内公式 $x^2$ 结束'],
  ['数学 $$...$$', '$$\nx^2\n$$'],
  ['折行粘连（P0 形状）', '前文```\ncode\n```\n'],
  ['表格粘连标题（P1e 形状）', '## 参数 | 说明\n|---|---|\n|a|b|'],
]

describe('[stream-raw] 流式臂 = 原样纯文本直出（逐字符相等）', () => {
  for (const [name, text] of CORPUS) {
    it(`${name} → 输出逐字符等于原文，且无 markdown 结构标签`, () => {
      const { html, inner } = streamRawShell(text)
      // ① 逐字符相等（最强判据：掉字 / 改写 / 裁剪 / 插入都会红）
      expect(inner).toBe(text)
      // ② 否定断言：一个结构标签都不许出现
      for (const tag of STRUCTURE_TAGS) expect(html).not.toContain(tag)
    })
  }

  it('「标题粘连表格」一字不少（批次 B 之前，收口臂 rescue 会掉字的输入）', () => {
    // 这条输入曾同时踩两个坑：流式臂的增量解析与收口臂的 P1e 拆分给出不同的字面结果。
    // 批次 B 的闸门（`rescue.ts` 的 splitFormsTable）已把收口臂修好 —— 故这里两臂都钉「一字不少」：
    // 流式臂逐字符相等，收口臂该 heading 文本与后续行都完整（不成表，但**不掉字**）。
    const text = '## 参数 | 说明\n|---|---|\n|a|b|'
    expect(streamRawOf(text)).toBe(text)

    const settled = renderToStaticMarkup(<MarkdownText text={text} onRunHtml={noop} />)
    expect(settled).toContain('参数 | 说明')
    expect(settled).toContain('|---|---|')
    expect(settled).toContain('|a|b|')
  })

  it('类名是 md-stream-raw 且宿主是 div（不是 pre —— pre 会被 .msg .content pre 压过样式）', () => {
    const { html } = streamRawShell('正文')
    expect(html).toContain('<div class="content md">')
    expect(html).toContain('<div class="md-stream-raw">')
    expect(html).not.toContain('<pre')
    // 旧类名已随 [stream-raw] 退役
    expect(html).not.toContain('md-stream-degraded')
  })

  it('外层 className 由调用侧决定（content md / user-text md 同路，均不改写文本）', () => {
    expect(streamRawShell('正文', 'user-text md').inner).toBe('正文')
  })

  it('空文本 → 仍是同一外壳（不塌成 null/无节点）', () => {
    expect(streamRawOf('')).toBe('')
  })
})

describe('[stream-raw] 样式契约：注入真 CSS 后读**计算值**（DOM 层的 HTML 里换行还在，样式坏掉测不出）', () => {
  // 为什么单独立一条：本文件其余断言都在 DOM 层（`renderToStaticMarkup` 的 HTML 里换行原样存在），
  // 「原文逐字符相等」在**去掉 `white-space:pre-wrap` 后仍然为真** —— 但浏览器会把换行当普通空白
  // 折叠，整段挤成一行，用户看到的就不再是「原样直出」。故样式必须有自己的判据。
  //
  // 为什么是「计算值」而不是「在 CSS 文本上找子串」：子串判据不解析注释、不做选择器匹配、
  // 不评估层叠、不校验取值合法性。旧版两条断言（`/\.md-stream-raw\{([^}]*)\}/` + `toContain`
  // 与 `not.toContain('.md-stream-degraded{')`）是假牙，机制上说清两件事：
  // ① 把 `white-space:pre-wrap` 改成非法值 `pre-wrap-fake`（浏览器整条丢弃该声明 ⇒ 换行全部折叠，
  //    「原样直出」的视觉契约彻底毁掉）—— 旧断言恒真，因为 `'white-space:pre-wrap-fake'` **包含**
  //    子串 `'white-space:pre-wrap'`（子串判据对「加尾巴」型破坏结构性失明）；
  // ② 追加更特异的 `.msg .content .md-stream-raw{white-space:normal}`（0,3,0 压过 0,1,0）——
  //    CSS 文本一个字没变，任何文本级断言都看不见。
  //    （验证代理 2026-09-18 的真文件实验实测：①② 下 27 条断言仍全绿。）
  // 换成 jsdom 注入 globals.css + `getComputedStyle` 后，①② 都实测变红（见本文件下方两条断言
  // 的反向实验输出）；删掉 `.md-stream-degraded` 规则（旧 ③ 想守的那件事）同样实测变红。
  /**
   * globals.css 的真文本。两条踩过的坑写在这里，避免下一个人重走：
   * - **不能用 `fileURLToPath(new URL(…, import.meta.url))`**：本文件跑在 jsdom 下，
   *   `import.meta.url` 是 http 形式 ⇒ 抛 `TypeError: The URL must be of scheme file`（实测）。
   * - **不能用 vite 的 `?raw`**：对 `.css` 实测返回**空串**（CSS 插件先接管了该扩展名）——
   *   注入空样式表会让本条测试变成**假绿**。故下面显式断言文本长度（这条断言就是那次踩坑的产物）。
   * 路径按 vitest 的 cwd 解析（本仓测试命令为 `cd front && vitest run`；读不到会 loudly 抛 ENOENT）。
   */
  const cssText = readFileSync(path.resolve(process.cwd(), 'src/styles/globals.css'), 'utf8')

  /**
   * 注入 globals.css 并挂出**生产同形**的 DOM：`.msg > .content.md > .md-stream-raw`
   * （再加一个 D1 用过的 `<pre class="md-stream-degraded">`，回退线要一起守）。
   * 宿主 `.msg` 不可省：生产路径就在 `.msg` 内，少了它任何 `.msg …` 后代选择器都命中不了。
   */
  function mount(): { raw: Element; degraded: Element } {
    document.head.innerHTML = ''
    document.body.innerHTML = ''
    const style = document.createElement('style')
    style.textContent = cssText
    document.head.appendChild(style)
    const host = document.createElement('div')
    host.className = 'msg'
    host.innerHTML = renderToStaticMarkup(
      <MarkdownText text={'第一行\n第二行'} streaming className="content md" onRunHtml={noop} />,
    )
    host.querySelector('.content')!.insertAdjacentHTML('beforeend', '<pre class="md-stream-degraded">第三行</pre>')
    document.body.appendChild(host)
    const raw = host.querySelector('.md-stream-raw')
    const degraded = host.querySelector('.md-stream-degraded')
    if (raw === null || degraded === null) {
      throw new Error('挂载失败：DOM 里缺少 .md-stream-raw 或 .md-stream-degraded')
    }
    return { raw, degraded }
  }

  it('前提：注入的是 globals.css 的**真文本**（空文本会让下面两条测试假绿）', () => {
    expect(cssText.length).toBeGreaterThan(100_000)
  })

  it('.md-stream-raw 的计算值 white-space=pre-wrap（换行不被折叠）', () => {
    const { raw } = mount()
    const cs = window.getComputedStyle(raw)
    expect(cs.whiteSpace).toBe('pre-wrap')
    expect(cs.wordBreak).toBe('break-word')
  })

  it('回退线完整：D1 的 <pre class="md-stream-degraded"> 同样拿到 pre-wrap', () => {
    // 该规则曾被 [stream-raw] 的重命名连带删掉（D1 路径在生产已不可达），而 `MarkdownText` 的
    // `STREAM_DEGRADE_CLASS`／产出 `<pre className={STREAM_DEGRADE_CLASS}>` 一直在（为支持一行回退）
    // ⇒ 回退后一旦命中 D1 降级，`<pre>` 会落到 `.msg .content pre`（0,2,1，**不设 white-space**）
    // 手上：长行不折行 + 横向滚动 + 丢 `word-break`。故「回退是一条线」必须包含本规则，
    // 且须以**计算值**证明它真生效（文本里存在 ≠ 生效）。
    const { degraded } = mount()
    const cs = window.getComputedStyle(degraded)
    expect(cs.whiteSpace).toBe('pre-wrap')
    expect(cs.wordBreak).toBe('break-word')
  })
})

describe('[stream-raw] 两臂分工：流式 = 原文，收口 = markdown 渲染', () => {
  const text = '## 标题\n\n一段有 **加粗** 的文本。\n\n| x | y |\n| --- | --- |\n| 1 | 2 |'

  it('流式臂不做任何解释（与收口臂结果不同正是设计意图）', () => {
    expect(streamRawOf(text)).toBe(text)
    const settled = renderToStaticMarkup(<MarkdownText text={text} onRunHtml={noop} />)
    expect(settled).toContain('<h2>标题</h2>')
    expect(settled).toContain('<strong>加粗</strong>')
    expect(settled).toContain('<table>')
  })
})
