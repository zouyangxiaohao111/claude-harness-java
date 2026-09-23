// @vitest-environment jsdom
//
// fix/browser-text-channels · 浏览器「取网页正文」通道回归
//
// WHY（规则九 · 测意图不测形式）: 用户实测四条取正文通道全断 —— read_page 只回可交互元素清单、
// 每节点文本被 slice(0,200) 砍死（回答正文根本不在返回里）；get_page_text 只拿到片段；
// 「点页面的复制按钮 + 读剪贴板」结构上不存在（manifest 无 clipboardRead）；只剩截图一屏一取。
// 本文件锁定改后必须成立的三件事：
//   1) filter:interactive 与改前**逐字节相同**（回归锚：防「修 A 砸 B」，computer/find 共用同一份 ref 索引）；
//   2) filter:all / depth / ref_id / max_chars 真正生效（改前是空头支票：扩展侧 readPage() 不收参数，
//      四参数全被静默忽略）；超出 max_chars 必须**显式报错**而不是静默截断；
//   3) get_page_text 默认仍是 innerText（零回归），mode:full 能拿到 innerText 漏掉的正文。
//
// 做法：读 front/extension/content.js 源码 → 在带 chrome/CSS 桩的 jsdom 窗口里执行 →
// 通过**捕获到的 onMessage handler** 发 {tool,args} —— 走的是真实分派路径。
// ⛔ 不把逻辑复制进测试里另测一份（那样测的是抄件，不是线上件）。
// 对照组 = fixtures/content.baseline.master.js（改前版本的**固化快照**）在同一 fixture 上跑同参数。
//   ⛔ 不再在运行期 `git show master:...`：本分支合入 master 后它会取到新文件 ⇒ 基线变成「自己比自己」，
//     断言 `not.toContain('READ_PAGE_TEXT_CAP')` 必红、回归锚失去判别力（复核批问题 3）。
// [browser-text-channels 复核批] 另钉两条：
//   4) filter:"interactive" 档**不再静默吞参数**：depth / ref_id 显式报错，max_chars 与 all 档同一套预算报错；
//   5) buildSelector() 的 `#${CSS.escape(el.id)}` 分支真被覆盖（带 id 的 fixture + CSS 桩）。

import { describe, expect, it } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'

// jsdom 不自带类型声明（无 @types/jsdom）⇒ 用 createRequire 取运行时导出，避免为一个测试塞 d.ts
const requireCjs = createRequire(import.meta.url)
const { JSDOM } = requireCjs('jsdom') as {
  JSDOM: new (html: string, options?: Record<string, unknown>) => { window: any }
}

// ⚠️ 本测试跑在 jsdom 环境（文件头）⇒ import.meta.url 是 http(s) 不是 file:，
// fileURLToPath 会抛「The URL must be of scheme file」。改用 vitest 的 root（= front/，
// 见 vite.config.ts 所在目录）作基准；路径不存在则显式抛错，绝不静默错读到别的文件。
const FRONT_ROOT = process.cwd()
const CONTENT_JS = path.resolve(FRONT_ROOT, 'extension/content.js')
if (!existsSync(CONTENT_JS)) {
  throw new Error(`找不到扩展源码 ${CONTENT_JS}（vitest 的 cwd 不是 front/？请从 front/ 下运行 vitest）`)
}
const SOURCE = readFileSync(CONTENT_JS, 'utf8')

/* 改前基线 = **固化快照**（fixture），⛔ 不再运行期取 `git show master:...`。
 *
 * WHY（复核批问题 3）：运行期取 master 版，在本分支**合入 master 之后**取到的是新文件（自己比自己）
 *   ⇒ ① 断言 `not.toContain('READ_PAGE_TEXT_CAP')` 必红（改前 0 处、改后 4 处）；
 *      ② 回归锚退化成「新 == 新」，判别力归零。
 * 固化后基线**不随 master 变动**：合入前后跑的是同一份字节。
 * fixture 内容 = 改前那份 content.js（`git show master:front/extension/content.js` 逐字节落盘，blob 36c02570）。
 * ⛔ fixture 缺失 ⇒ 模块加载即抛错（fail loud），绝不静默跳过回归锚。 */
const BASELINE_JS = path.resolve(FRONT_ROOT, 'src/extension/__tests__/fixtures/content.baseline.master.js')
if (!existsSync(BASELINE_JS)) {
  throw new Error(`回归基线 fixture 缺失：${BASELINE_JS}（本批把「运行期取 master 版」改为固化快照，勿删）`)
}
const BASELINE_SOURCE = readFileSync(BASELINE_JS, 'utf8')

/** 改前版本（固化快照）。保留函数形态，调用点零改动。 */
function baselineSource(): string {
  return BASELINE_SOURCE
}

/** CSS.escape 的最小**忠实**实现（jsdom 未提供 CSS 全局；见 boot() 里的桩说明）。
 *  ⛔ 不是用 `String` 冒名（identity）：该转义的要真转义，否则「带 id 的 selector」用例失去判别力。
 *  例：'a:b' → 'a\\:b'（identity 会得到 'a:b' —— 裸用是非法/歧义选择器）。
 *  覆盖规范里的 identifier 转义：首位数字按十六进制转义（'0' → '\\30 '），字母数字与 `-_` 之外一律反斜杠转义。 */
function cssEscape(value: string): string {
  const s = String(value)
  let out = ''
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    const code = ch.codePointAt(0) as number
    const isDigit = code >= 0x30 && code <= 0x39
    if (code === 0) { out += '�'; continue }
    if ((i === 0 && isDigit) || (i === 1 && isDigit && s[0] === '-')) {
      out += '\\' + code.toString(16) + ' '
      continue
    }
    const plain = code >= 0x80 || ch === '-' || ch === '_' || isDigit
      || (code >= 0x41 && code <= 0x5a) || (code >= 0x61 && code <= 0x7a)
    out += plain ? ch : '\\' + ch
  }
  return out
}

type Handler = (msg: unknown, sender: unknown, sendResponse: (r: any) => void) => unknown

/** 在 jsdom 窗口里执行 content.js，返回「经真实 onMessage handler 调工具」的入口。 */
function boot(source: string, html: string, innerText?: string) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'https://example.com/fixture' })
  const win: any = dom.window
  // jsdom 无布局引擎：getBoundingClientRect 恒 0 ⇒ 可交互索引恒空。补一个固定非零矩形，让
  // 「可见性」判定可被驱动（测试环境修复，不是逻辑复制；基线与新代码共用同一份补丁 ⇒ 对照成立）。
  win.Element.prototype.getBoundingClientRect = () => ({
    width: 100, height: 20, top: 0, left: 0, right: 100, bottom: 20, x: 0, y: 0, toJSON: () => ({}),
  })
  // jsdom 未实现 innerText（undefined）⇒ 用可控 getter 模拟浏览器里 innerText 漏掉离屏/虚拟化子树的行为
  if (innerText !== undefined) {
    Object.defineProperty(win.document.body, 'innerText', { configurable: true, get: () => innerText })
  }
  // jsdom 无 CSS 全局（实测 jsdom 25.0.1：typeof window.CSS === 'undefined'）⇒ buildSelector() 的
  // `#${CSS.escape(el.id)}` 分支在测试里会抛 "CSS is not defined"（真 Chrome content script **有**该全局
  // ⇒ 不是产品缺陷，是测试盲区 + 未来埋雷，见复核批问题 2）。此处补桩：忠实的最小实现，非 String 冒名。
  if (!win.CSS) win.CSS = { escape: cssEscape }
  const captured: { handler?: Handler } = {}
  win.chrome = { runtime: { onMessage: { addListener: (fn: Handler) => { captured.handler = fn } } } }
  win.eval(source)
  if (!captured.handler) throw new Error('content.js 未注册 chrome.runtime.onMessage 监听器')
  const handler = captured.handler
  return {
    win,
    call: (tool: string, args?: Record<string, unknown>) =>
      new Promise<any>((resolve) => {
        handler({ type: 'tool_call', id: 't1', tool, args: args || {} }, {}, resolve)
      }),
  }
}

/** 在元素树里找 text 恰等于给定串的节点（用于断言长正文真的落在某个节点的 text 里）。 */
function findByText(nodes: any[], text: string): any {
  for (const n of nodes) {
    if (n.text === text) return n
    const hit = findByText(n.children || [], text)
    if (hit) return hit
  }
  return null
}

/* ------------------------------------------------------------------ */
/*  fixtures                                                           */
/* ------------------------------------------------------------------ */

// 可交互元素 + 嵌套（label 里的 input 要单独编号）+ 非交互段落：驱动回归锚与 ref 快照
// LONG_INTERACTIVE_TEXT 专门 >200 字：可交互档单节点文本上限就是 200（find / filter:interactive 共用），
//   而原 fixture 全是短文本 ⇒ 「上限被改」这类输出差异锚抓不到（反面对照实测：200→201 锚仍恒绿）。
//   放一个 >200 字的可交互元素，把这条维度纳入回归锚的判别范围。
const LONG_INTERACTIVE_TEXT = `L${'L'.repeat(299)}`
const PAGE_INTERACTIVE = `<!doctype html><html><head><title>Fixture</title></head><body>
  <header>
    <a href="/home">Home</a>
    <button>Search</button>
  </header>
  <main>
    <label>Name<input type="text" placeholder="Your name"></label>
    <div role="button" aria-label="Play">Play</div>
    <select><option>one</option></select>
    <div data-testid="custom">Custom</div>
    <button>${LONG_INTERACTIVE_TEXT}</button>
    <p>plain paragraph not interactive</p>
  </main>
</body></html>`

const LONG_TEXT = 'LONG_START' + 'x'.repeat(1500) + 'LONG_END_MARKER'
const PAGE_LONG = `<!doctype html><html><head><title>Long</title></head><body>
  <article><h1>Title</h1><div class="answer">${LONG_TEXT}</div></article>
</body></html>`

const PAGE_DEPTH = `<!doctype html><html><head><title>Depth</title></head><body>
  <section><p>deep</p></section>
</body></html>`

const PAGE_REF = `<!doctype html><html><head><title>Ref</title></head><body>
  <nav><button><span>Menu</span><span>Item</span></button></nav>
  <p>OUTSIDE_TEXT</p>
</body></html>`

const PAGE_TEXT = `<!doctype html><html><head><title>Text</title></head><body>
  <div>VISIBLE_TEXT</div>
  <div class="offscreen">HIDDEN_BODY_TEXT</div>
  <script type="text/plain">var secret = 1;</script>
</body></html>`
const INNER_STUB = 'VISIBLE_TEXT'

// 带 id 的 fixture：驱动 buildSelector() 的 `#${CSS.escape(el.id)}` 分支（原 fixture 全无 id ⇒ 该分支从未执行）。
// ESCAPED_ID 故意含 `:`（裸用是非法/歧义选择器）⇒ 只有真转义过才会得到 `#a\:b`，identity 冒名会得到 `#a:b`。
const ESCAPED_ID = 'a:b'
const PAGE_WITH_ID = `<!doctype html><html><head><title>Id</title></head><body>
  <button id="${ESCAPED_ID}">Go</button>
  <div id="plain">Plain</div>
</body></html>`

/* ------------------------------------------------------------------ */

describe('read_page · filter:interactive 回归锚（与改前逐字节相同）', () => {
  it('(a) filter:interactive 输出 == 固化的改前基线在同一 fixture 上的输出', async () => {
    const base = baselineSource()
    // 非空对照（防「空 == 空」假绿）+ 基线确为「改前」形态（无本批新增物、readPage 还不收参数）
    expect(base.length).toBeGreaterThan(10000)
    expect(base).not.toContain('READ_PAGE_TEXT_CAP')
    expect(base).not.toContain('overBudget')
    expect(base).toContain('async function readPage() {')
    expect(base).not.toBe(SOURCE) // 基线 ≠ 当前实现（否则回归锚退化成自己比自己）

    const cur = boot(SOURCE, PAGE_INTERACTIVE)
    const old = boot(base, PAGE_INTERACTIVE)
    const gotCurrent = await cur.call('read_page', { filter: 'interactive' })
    const gotBaseline = await old.call('read_page', {})

    expect(JSON.stringify(gotCurrent)).toBe(JSON.stringify(gotBaseline))
    // 锚非空（否则「空 == 空」是假绿）：fixture 里确实编到了可交互元素
    expect(gotCurrent.result.nodeCount).toBeGreaterThan(0)
    expect(gotCurrent.result.nodes.map((n: any) => n.tag)).toEqual(
      gotBaseline.result.nodes.map((n: any) => n.tag),
    )
  })
})

describe('read_page · filter:all 元素树 + 长正文', () => {
  it('(b) filter:all 返回树、含 children，且长正文不再被 200 字砍死', async () => {
    const h = boot(SOURCE, PAGE_LONG)
    const out = await h.call('read_page', { filter: 'all' })
    expect(out.ok).toBe(true)
    expect(out.result.filter).toBe('all')
    expect(out.result.nodes[0].tag).toBe('body')
    expect(Array.isArray(out.result.nodes[0].children)).toBe(true)

    const json = JSON.stringify(out.result)
    // 1500+ 字正文的**末尾标记**必须出现：改前每节点 slice(0,200) 会把它砍掉
    expect(json).toContain('LONG_END_MARKER')
    const answer = findByText(out.result.nodes, LONG_TEXT)
    expect(answer).not.toBeNull()
    expect(answer.tag).toBe('div')
    expect(answer.text.length).toBeGreaterThan(200)

    // 对照：改前的 filter 等价物（可交互清单）里根本没有这段正文
    const old = await boot(baselineSource(), PAGE_LONG).call('read_page', {})
    expect(JSON.stringify(old)).not.toContain('LONG_END_MARKER')

    // 默认（不传 filter）= all（照后端 BrowserToolRegistry schema：默认 all elements）
    const def = await h.call('read_page', {})
    expect(def.result.filter).toBe('all')
  })
})

describe('read_page · depth', () => {
  it('(c) depth=1/2/3 节点数分别为 1/2/3，depth=1 无 children', async () => {
    const h = boot(SOURCE, PAGE_DEPTH)
    const d1 = await h.call('read_page', { filter: 'all', depth: 1 })
    const d2 = await h.call('read_page', { filter: 'all', depth: 2 })
    const d3 = await h.call('read_page', { filter: 'all', depth: 3 })
    expect(d1.result.nodeCount).toBe(1)
    expect(d1.result.depth).toBe(1)
    expect(d1.result.nodes[0].children).toEqual([])
    expect(d2.result.nodeCount).toBe(2)
    expect(d3.result.nodeCount).toBe(3)
    // 该 fixture 最深 3 层 ⇒ 默认 depth(15) 与 depth=3 等价
    const dflt = await h.call('read_page', { filter: 'all' })
    expect(dflt.result.depth).toBe(15)
    expect(dflt.result.nodeCount).toBe(3)
  })
})

describe('read_page · ref_id', () => {
  it('(d) ref_id 以该 ref 为根只回其子树；失效/越界/冷启动 ref 显式报错（不回落整页）', async () => {
    const h = boot(SOURCE, PAGE_REF)
    const idx = await h.call('read_page', { filter: 'interactive' })
    expect(idx.ok).toBe(true)
    const btn = idx.result.nodes.find((n: any) => n.tag === 'button')
    expect(btn).toBeTruthy()

    const out = await h.call('read_page', { filter: 'all', ref_id: btn.ref, depth: 5 })
    expect(out.ok).toBe(true)
    expect(out.result.nodes[0].tag).toBe('button')
    const json = JSON.stringify(out.result)
    expect(json).toContain('Menu')
    expect(json).toContain('Item')
    expect(json).not.toContain('OUTSIDE_TEXT') // 兄弟节点不在返回里

    const oob = await h.call('read_page', { filter: 'all', ref_id: 999 })
    expect(oob.ok).toBe(false)
    expect(oob.error).toContain('ref 失效')

    const noSnapshot = await boot(SOURCE, PAGE_REF).call('read_page', { filter: 'all', ref_id: 0 })
    expect(noSnapshot.ok).toBe(false)
    expect(noSnapshot.error).toContain('ref 失效')
  })
})

describe('read_page · max_chars', () => {
  it('(e) 超预算返回显式错误（不静默截断）；调大后成功', async () => {
    const h = boot(SOURCE, PAGE_LONG)
    const tight = await h.call('read_page', { filter: 'all', max_chars: 50 })
    expect(tight.ok).toBe(false)
    expect(tight.error).toContain('max_chars')
    expect(tight.error).toContain('depth') // 文案可操作：告诉模型怎么收窄
    expect(JSON.stringify(tight)).not.toContain('LONG_END_MARKER') // 报错优先，不返回半截树

    const roomy = await h.call('read_page', { filter: 'all', max_chars: 100000 })
    expect(roomy.ok).toBe(true)
    expect(roomy.result.filter).toBe('all')
  })
})

describe('read_page · filter:interactive 不静默吞参数（复核批问题 1）', () => {
  it('(h) interactive 档：depth/ref_id 显式报错；max_chars 与 all 档同一套预算报错', async () => {
    const h = boot(SOURCE, PAGE_INTERACTIVE)

    // 改前：早退分支把 depth/ref_id/max_chars 静默忽略（复验者实测 ok:true 且无错误）⇒ 现在必须显式报错
    const depthErr = await h.call('read_page', { filter: 'interactive', depth: 5 })
    expect(depthErr.ok).toBe(false)
    expect(depthErr.error).toContain('depth')
    expect(depthErr.error).toContain('filter:"all"')

    const refErr = await h.call('read_page', { filter: 'interactive', ref_id: 4242 })
    expect(refErr.ok).toBe(false)
    expect(refErr.error).toContain('ref_id')
    expect(refErr.error).toContain('filter:"all"')

    const tight = await h.call('read_page', { filter: 'interactive', max_chars: 5 })
    expect(tight.ok).toBe(false)
    expect(tight.error).toContain('max_chars')
    expect(tight.result).toBeUndefined() // 报错优先，不返回半截清单

    // 反向对照（防「一律报错」把上面三条蒙对）：预算够时同档照常返回结果
    const roomy = await h.call('read_page', { filter: 'interactive', max_chars: 100000 })
    expect(roomy.ok).toBe(true)
    expect(roomy.result.nodeCount).toBeGreaterThan(0)
  })
})

describe('read_page · buildSelector 的 CSS.escape 分支（复核批问题 2）', () => {
  it('(i) 带 id 的元素 selector 真走 CSS.escape（jsdom 无 CSS 全局 ⇒ 需桩，非产品缺陷）', async () => {
    const h = boot(SOURCE, PAGE_WITH_ID)
    const out = await h.call('read_page', { filter: 'interactive' })
    expect(out.ok).toBe(true)
    const btn = out.result.nodes.find((n: any) => n.tag === 'button')
    expect(btn).toBeTruthy()
    // `:` 必须被转义（桩若用 String 冒名会得到 '#a:b' ⇒ 本断言红）
    expect(btn.selector).toBe('#a\\:b')
    // 转义结果**真能用**：拿它 querySelector 必须命中同一个元素（证桩是忠实实现，不是「随便转两下」）
    expect(h.win.document.querySelector(btn.selector)).toBe(h.win.document.getElementById(ESCAPED_ID))

    // 无 id 的元素仍走 tag/nth-child 兜底（本批未动这条路径）
    const all = await h.call('read_page', { filter: 'all' })
    expect(findByText(all.result.nodes, 'Plain').selector).toBe('#plain')
  })
})

describe('get_page_text · mode', () => {
  it('(f) 默认 = innerText（与改前逐字相同）；mode:full 取到 innerText 漏掉的正文', async () => {
    const h = boot(SOURCE, PAGE_TEXT, INNER_STUB)
    const def = await h.call('get_page_text', {})
    expect(def.result.mode).toBe('inner')
    expect(def.result.text).toBe(INNER_STUB)

    const full = await h.call('get_page_text', { mode: 'full' })
    expect(full.result.mode).toBe('full')
    expect(full.result.text).toContain('HIDDEN_BODY_TEXT')
    expect(full.result.text.length).toBeGreaterThan(def.result.text.length)
    expect(full.result.text).not.toContain('var secret') // script 不属正文

    // 对照：改前版本在同一 innerText 桩上，text 逐字相同（旧行为 = 就取 innerText）
    const old = await boot(baselineSource(), PAGE_TEXT, INNER_STUB).call('get_page_text', {})
    expect(old.result.text).toBe(def.result.text)
  })

  it('(g) 诊断字段 innerTextLen / textContentLen 存在且数值正确', async () => {
    const h = boot(SOURCE, PAGE_TEXT, INNER_STUB)
    const out = await h.call('get_page_text', {})
    expect(out.result.innerTextLen).toBe(INNER_STUB.length)
    expect(out.result.textContentLen).toBe(h.win.document.body.textContent.length)
    // 诊断字段的意义：textContent 明显多于 innerText ⇒ 模型据此判断该换 mode:full
    expect(out.result.textContentLen).toBeGreaterThan(out.result.innerTextLen)
  })
})
