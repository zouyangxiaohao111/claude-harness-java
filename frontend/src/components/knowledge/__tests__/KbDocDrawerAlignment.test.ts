import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 文档抽屉的三处「跟源站不一致」（2026-11-10 用户实测指出）。
 *
 * <h2>用户原话</h2>
 * 「点开文档 点击文件 右边抽屉展现出来后 有几个问题：
 *  1. 下载 / 查看 trace / 问 weknora 助手（这个要换成助手不要加 weknora）以及 x 图标
 *     不和原测代码匹配 你自行造的；
 *  2. 查看分块后 点击辅助召回问题 无法点击；另外分块中的布局没有和原测一致」
 *
 * <h2>三条各自的真因与判据</h2>
 * <ol>
 *   <li><b>文案</b>：源侧 i18n 是 `askAboutDocument: '问 {name}'`，
 *       `name` = 助手显示名（回退 `assistant.title`）。「WeKnora」是源站品牌名，
 *       本仓助手不叫这个名字 ⇒ 用户可见文案里不该出现它。</li>
 *   <li><b>图标</b>：源侧用 `<t-icon name="download|chart-line|chat">`，
 *       本仓原来用字符（`⤓` / `⇄` / `✧` / `✕`）—— 字形与描边风格都和 tdesign 不一致。</li>
 *   <li><b>分块正文</b>：源侧 `doc-content.vue:2139` 是
 *       `<div class="md-content" v-html="chunk.processedContent">`（渲染后的 markdown）。
 *       本仓原来 `{c.content}` 直出原始串 ⇒ `#` / `##` 字面露出、表格与列表不走版式，
 *       这就是用户说的「布局没有和原测一致」。</li>
 * </ol>
 */

const DRAWER = fileURLToPath(new URL('../KbDocDrawer.tsx', import.meta.url))
const SPRITE = fileURLToPath(new URL('../IconSprite.tsx', import.meta.url))

function drawer(): string {
  return readFileSync(DRAWER, 'utf8').replace(/\r\n?/g, '\n')
}

function sprite(): string {
  return readFileSync(SPRITE, 'utf8').replace(/\r\n?/g, '\n')
}

describe('抽屉头部：文案与图标对齐源站', () => {
  it('⭐ 用户可见文案不得出现外部产品名 "WeKnora"', () => {
    const s = drawer()
    // 只查「用户可见」的 title / aria-label / 正文文案里的出现；注释里说明来源是允许的
    const visible = s
      .split('\n')
      .filter((l) => !l.trim().startsWith('*') && !l.trim().startsWith('//'))
      .join('\n')
    expect(visible, 'title/aria-label 写死了 WeKnora')
      .not.toMatch(/title="[^"]*WeKnora[^"]*"/)
    expect(visible).not.toMatch(/aria-label="[^"]*WeKnora[^"]*"/)
  })

  it('⭐⭐ 「问助手」按源站是 assistant-button + resource-icon（⛔ 不是 t-icon chat）', () => {
    const s = drawer()
    expect(s).toContain('title="问助手"')
    // 源站 AssistantButton.vue：`<button class="assistant-button is-icon-only">`
    //   + `<span class="resource-icon" mask-image=星形>`
    // 源站根本没有 chat 图标 —— 那是本仓上一版臆造的。
    expect(s).toContain('assistant-button is-icon-only')
    expect(s).toContain('resource-icon')
    expect(s, '仍在用臆造的 chat 图标').not.toContain('#t-icon-chat')
  })

  it('⭐⭐ 三个头部动作按源站：两个 t-icon svg + 一个 resource-icon', () => {
    const s = drawer()
    for (const id of ['#t-icon-download', '#t-icon-chart-line']) {
      expect(s, `缺少 ${id}`).toContain(id)
    }
    // 源站每个图标 svg 都带 `class="t-icon t-icon-xxx"` 与 `font-size`
    // —— 只写 width/height 会让 `.t-icon{width:1em;height:1em}` 不生效。
    expect(s).toContain('className="t-icon t-icon-download"')
    expect(s).toContain('className="t-icon t-icon-chart-line"')
    // Trace 按钮在源站是 theme-success + trace-entry-btn
    expect(s).toContain('t-button--theme-success')
    expect(s).toContain('trace-entry-btn')
    // ⚠️ 每个元素单独查：全文 contains 只要有一个带就过，另两个漏了照样绿（实测过）。
    //    下载 / Trace 两个 t-button 各自必须带 `t-button--icon-only`；
    //    助手是 assistant-button，用 `is-icon-only`。
    // ⚠️ 锚点必须取 **className="..." 这一行**：注释里也写了 header-action-btn /
    //    trace-entry-btn，用裸词定位会命中注释（实测过，正反两个方向都红）。
    for (const cls of ['t-button--icon-only header-action-btn',
                       't-button--icon-only header-action-btn trace-entry-btn']) {
      expect(s, `找不到 className 含「${cls}」的按钮`).toContain(cls)
    }
    expect(s, '助手按钮缺 is-icon-only').toContain('assistant-button is-icon-only')
    // 旧的字符占位不许再当图标
    expect(s, '仍用 ⤓ 当下载图标').not.toContain('<span className="t-button__text">⤓</span>')
    expect(s, '仍用 ⇄ 当 Trace 图标').not.toContain('<span className="t-button__text">⇄</span>')
    expect(s, '仍用 ✧ 当助手图标').not.toContain('<span className="t-button__text">✧</span>')
  })

  it('⭐ 关闭按钮也是 svg（⛔ 不是字符 ✕），并按源站加 t-icon 类', () => {
    const s = drawer()
    expect(s).toContain('#t-icon-close')
    expect(s, '关闭 svg 缺 class="t-icon t-icon-close"').toContain('className="t-icon t-icon-close"')
    // ⚠️ 只在**关闭抽屉那个 button 标签**里找 ✕：
    //   ① 注释里也提到 `.t-drawer__close-btn` ⇒ 必须先定位到 `<button`
    //   ② 页面别处还有字符 ✕（弹层关闭）⇒ 全文件匹配会假红。
    const i = s.indexOf('className="t-drawer__close-btn"')
    expect(i).toBeGreaterThan(0)
    const tag = s.slice(i, s.indexOf('</button>', i))
    expect(tag, '抽屉关闭按钮仍是字符 ✕').not.toContain('✕')
    expect(tag).toContain('#t-icon-close')
  })
})

describe('IconSprite：新增的图标 path 必须真的存在', () => {
  it('⭐⭐ 五个新图标都在 sprite 里（缺一个就渲染成空白）', () => {
    const s = sprite()
    for (const id of ['chart-line', 'close', 'git-branch', 'help-circle', 'history']) {
      expect(s, `IconSprite 缺 t-icon-${id}`).toContain(`id="t-icon-${id}"`)
    }
  })

  it('⭐ 每个 symbol 都要有非空 path（只挂 symbol 不挂 path = 看不见）', () => {
    const s = sprite()
    const symbols = s.match(/<symbol[^>]*id="t-icon-[a-z-]+"[\s\S]*?<\/symbol>/g) ?? []
    expect(symbols.length).toBeGreaterThan(10)
    const empty = symbols.filter((b) => !/<path[\s>]/.test(b))
    expect(empty, `这些 symbol 没有 path：${empty.length} 个`).toHaveLength(0)
  })
})

describe('分块正文：markdown 必须渲染（对齐源站 v-html=processedContent）', () => {
  it('⭐⭐ 分块正文走 MarkdownTextV2，⛔ 不是原样输出字符串', () => {
    const s = drawer()
    // 2026-11-11：全仓对话/预览渲染器统一从 V1（@/markdown/MarkdownText）
    // 切到 V2（@/markdown/vendor/renderer，源站流水线移植版）。
    // 本条测试的**意图不变**：分块正文必须走 markdown 渲染而不是裸字符串。
    expect(s).toContain("import { MarkdownTextV2 } from '@/markdown/vendor/renderer'")
    // 渲染点：text 来自 c.content，class 仍是 md-content
    const i = s.indexOf('<MarkdownTextV2')
    expect(i, '找不到 MarkdownTextV2 渲染点').toBeGreaterThan(0)
    const block = s.slice(i, i + 400)
    expect(block).toContain('text={c.content')
    expect(block).toContain('md-content')
  })

  it('⭐⭐ 不许再把分块正文裸输出（那就是「布局不一致」的成因）', () => {
    const s = drawer()
    expect(s, '分块正文仍在裸输出 c.content')
      .not.toMatch(/<div className=\{`md-content[^`]*`\}>\{c\.content\}<\/div>/)
  })
})

describe('分块头部操作按钮：active 态对齐源站', () => {
  it('⭐ 四个操作按钮都要有 is-active（源站 :class="{ is-active: ... }"）', () => {
    const s = drawer()
    const total = (s.match(/icon-action-btn[^`]*is-active/g) ?? []).length
    expect(total, 'active 态数量不足（源站 4 个操作按钮都有）').toBeGreaterThanOrEqual(4)
  })
})
