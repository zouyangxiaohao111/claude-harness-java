// NexusAI in Chrome 扩展 · Content Script
//
// 职责：在页面中执行浏览器工具里的 DOM 操作类工具，
//       结果统一经 chrome.runtime.onMessage 返回给 background：
//         { ok: true, result }  → background 回传 tool_result
//         { ok: false, error }  → background 回传 tool_error（fail loud）
//
// 说明：
//   · manifest content_scripts 注入 + background 兜底注入（executeScript）两种途径共用一份代码
//   · console 钩子每页注入一次（document_idle），只收集注入之后的控制台消息（MVP 够用）
//   · 多会话模型：background 按 sessionId 把每个会话路由到其「自己的 tab」，
//     本页只服务于一个会话，故 window.__NEXUSAI_CC_CONSOLE__ 等缓冲天然按会话隔离（无需按 sessionId 键控）
//   · chrome.tabs/windows 类工具（resize_window/tabs_*/switch_browser/javascript_tool/screenshot）
//     不在此处理，由 background 直接执行；此处收到会返回「未实现」占位

// 防重复注入：manifest 注入 + executeScript 兜底注入都会执行本文件
if (!window.__NEXUSAI_CC_LOADED__) {
  window.__NEXUSAI_CC_LOADED__ = true
  initConsoleHook()
  chrome.runtime.onMessage.addListener(onMessage)
}

/* ------------------------------------------------------------------ */
/*  工具函数                                                            */
/* ------------------------------------------------------------------ */

/** 把任意值转成可序列化结果（DOM 节点 / 循环引用兜底） */
function serialize(value) {
  try {
    return JSON.parse(JSON.stringify(value))
  } catch (e) {
    return String(value)
  }
}

/** 生成尽量稳定的 CSS 选择器（id > 逐级 tag + nth-child，最多 4 级） */
function buildSelector(el) {
  if (!el || el.nodeType !== 1) return ''
  if (el.id) return `#${CSS.escape(el.id)}`
  const parts = []
  let node = el
  while (node && node.nodeType === 1 && node !== document.documentElement) {
    let sel = node.tagName.toLowerCase()
    const parent = node.parentElement
    if (parent) {
      const siblings = Array.from(parent.children)
      if (siblings.filter((c) => c.tagName === node.tagName).length > 1) {
        sel += `:nth-child(${siblings.indexOf(node) + 1})`
      }
    }
    parts.unshift(sel)
    if (parts.length >= 4) break
    node = parent
  }
  return parts.join(' > ')
}

/** 读取元素基础信息（read_page / find 共用） */
function elementInfo(el) {
  const rect = el.getBoundingClientRect()
  return {
    tag: el.tagName.toLowerCase(),
    text: (el.textContent || '').trim().slice(0, 200),
    placeholder: el.getAttribute('placeholder') || undefined,
    ariaLabel: el.getAttribute('aria-label') || undefined,
    role: el.getAttribute('role') || undefined,
    href: el.getAttribute('href') || undefined,
    value: el.value != null ? String(el.value).slice(0, 100) : undefined,
    selector: buildSelector(el),
    visible: !!(rect.width && rect.height),
  }
}

/* ------------------------------------------------------------------ */
/*  单源可交互索引（read_page / find / computer 共用同一份编号）          */
/*  ref = 快照数组下标；一次扫描内顺序稳定。对齐 CCB/page-agent 的         */
/*  “interactive index” 思想，但保持 vanilla + 本项目中文注释风格。       */
/* ------------------------------------------------------------------ */

/** 可交互候选选择器：CCB/page-agent + 本项目原有关注选择器并集（嵌套去重见下方逻辑） */
const INTERACTIVE_SELECTOR = [
  'a', 'button', 'input', 'select', 'textarea', 'summary',
  '[contenteditable="true"]',
  '[role="button"]', '[role="link"]', '[role="tab"]', '[role="menuitem"]',
  '[role="menuitemradio"]', '[role="menuitemcheckbox"]', '[role="radio"]',
  '[role="checkbox"]', '[role="switch"]', '[role="slider"]', '[role="spinbutton"]',
  '[role="combobox"]', '[role="searchbox"]', '[role="textbox"]', '[role="listbox"]', '[role="option"]',
  '[onclick]', '[data-testid]', '[data-test]', 'label',
].join(', ')

/** 视为“独立交互”的 ARIA role（对齐 CCB DISTINCT_INTERACTIVE_ROLES 的可操作子集） */
const INTERACTIVE_ROLES = new Set([
  'button', 'link', 'tab', 'menuitem', 'menuitemradio', 'menuitemcheckbox',
  'radio', 'checkbox', 'switch', 'slider', 'spinbutton', 'combobox',
  'searchbox', 'textbox', 'listbox', 'option', 'gridcell', 'treeitem',
])

const INDEX_MAX = 500 // 与旧 read_page 上限一致，避免一次性扫描整页巨型 DOM
let interactiveElements = []         // Element[]：ref == 数组下标（最近一次 refresh 的快照）
const interactiveRefOfEl = new WeakMap() // element → ref（反向校验；元素被 GC 自动清理）

/** 元素可否入索引：有真实尺寸、未显隐/禁用（display:none 等坐标点击永远落不上的排除） */
function isIndexableInteractive(el) {
  if (!el || el.nodeType !== 1) return false
  if (el.disabled || el.inert) return false
  if (el.getAttribute && el.getAttribute('aria-disabled') === 'true') return false
  const style = window.getComputedStyle(el)
  if (style.visibility === 'hidden' || style.display === 'none') return false
  const r = el.getBoundingClientRect()
  return !!(r.width && r.height) // display:none 的 rect 为 0；不用 offsetWidth（SVG 元素无该属性）
}

/** 父级已编号时，子元素是否仍构成“独立交互”（需单独编号，如 <label> 里的 <input>） */
function isDistinctNested(el) {
  const tag = el.tagName.toLowerCase()
  if (['a', 'button', 'input', 'select', 'textarea', 'summary'].includes(tag)) return true
  if (el.isContentEditable || el.getAttribute('contenteditable') === 'true') return true
  if (el.hasAttribute('data-testid') || el.hasAttribute('data-test')) return true
  const role = el.getAttribute('role')
  if (role && INTERACTIVE_ROLES.has(role)) return true
  if (el.hasAttribute('onclick')) return true
  return false
}

/** 是否有已编号祖先（querySelectorAll 按文档序 → 祖先先于后代被收集） */
function hasIndexedAncestor(el, included) {
  let p = el.parentElement
  while (p && p.nodeType === 1) {
    if (included.has(p)) return true
    p = p.parentElement
  }
  return false
}

/**
 * 扫描一次单源可交互索引：按稳定文档序收集「可交互元素」数组，
 * 同步刷新模块级 interactiveElements（ref=下标），返回带 ref + elementInfo 的快照。
 * read_page / find / computer 三处都引用同一份索引，杜绝“两套编号源”错位。
 */
function refreshInteractiveIndex() {
  const els = []
  const info = []
  const included = new Set()
  for (const el of document.querySelectorAll(INTERACTIVE_SELECTOR)) {
    if (els.length >= INDEX_MAX) break
    if (!isIndexableInteractive(el)) continue
    if (hasIndexedAncestor(el, included) && !isDistinctNested(el)) continue // 父级已编号的子按钮不重复编号
    included.add(el)
    const ref = els.length
    interactiveRefOfEl.set(el, ref)
    els.push(el)
    info.push({ ref, ...elementInfo(el) })
  }
  interactiveElements = els
  return info
}

/* ------------------------------------------------------------------ */
/*  18 个工具实现（DOM 操作类）                                         */
/* ------------------------------------------------------------------ */

/** read_page：基于单源可交互索引输出可交互元素清单（每个带 ref=快照下标） */
async function readPage() {
  const nodes = refreshInteractiveIndex() // 与 find / computer 共用同一份索引
  return { ok: true, result: { url: location.href, title: document.title, nodeCount: nodes.length, nodes } }
}

/** get_page_text：整页可见文本（整页全交，不在扩展侧截断——超 CC 单结果内联上限
 *  DEFAULT_MAX_RESULT_SIZE_CHARS=50_000 的部分由后端落盘+文件路径预览承接，聚合 200k 兜底） */
async function getPageText() {
  const text = (document.body ? document.body.innerText : '') || ''
  return { ok: true, result: { url: location.href, text } }
}

/** find：在单源可交互索引快照内按文本/描述/selector 匹配，返回带 ref 的结果供 computer 定位 */
async function find(args = {}) {
  const { text, description, selector } = args
  const q = String(text || description || '').trim().toLowerCase()
  const selArg = selector ? String(selector).trim() : ''
  if (!q && !selArg) return { ok: false, error: 'find 需要 text/description 或 selector 参数' }
  const snapshot = refreshInteractiveIndex() // 与 read_page / computer 同一份编号
  const matches = []
  let selInvalid = false
  for (const item of snapshot) {
    if (matches.length >= 50) break
    const el = interactiveElements[item.ref] // 与 info 同源（单源索引）
    if (!el) continue
    let hit = false
    if (selArg) {
      try { if (el.matches(selArg)) hit = true } catch (e) { selInvalid = true; break }
    }
    if (!hit && q) {
      const t = (el.textContent || '').trim()
      const placeholder = el.getAttribute('placeholder') || ''
      const aria = el.getAttribute('aria-label') || ''
      const testid = el.getAttribute('data-testid') || el.getAttribute('data-test') || ''
      const hay = `${t} ${placeholder} ${aria} ${testid}`.toLowerCase()
      if (hay.includes(q)) hit = true
    }
    if (hit) matches.push({ ...item, index: item.ref }) // ref 对齐 computer；index 为向后兼容别名
  }
  if (selInvalid) return { ok: false, error: `find selector 非法：${selArg}` }
  return { ok: true, result: { found: matches.length, matches } }
}

/** form_input：设置表单值（React/Vue 等框架用原生 setter 触发受控组件） */
async function formInput(args = {}) {
  const { selector, index, value } = args
  let el = selector ? document.querySelector(selector) : null
  if (!el && index != null) {
    const all = document.querySelectorAll('input, textarea, select, [contenteditable="true"]')
    el = all[index] || null
  }
  if (!el) return { ok: false, error: `未找到表单元素（selector=${selector ?? ''} index=${index ?? ''}）` }
  const tag = el.tagName.toLowerCase()
  const strValue = String(value ?? '')
  if (tag === 'input' || tag === 'textarea') {
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set
    setter.call(el, strValue)
  } else if (el.isContentEditable) {
    el.textContent = strValue
  } else if (tag === 'select') {
    el.value = strValue
  } else {
    return { ok: false, error: `不支持的输入元素：${tag}` }
  }
  el.dispatchEvent(new Event('input', { bubbles: true }))
  el.dispatchEvent(new Event('change', { bubbles: true }))
  return { ok: true, result: { ok: true, tag, selector: buildSelector(el), value: strValue } }
}

/* ------------------------------------------------------------------ */
/*  computer：对齐 CCB @ant/claude-for-chrome-mcp computer schema       */
/*  （13 actions：left_click/right_click/double_click/triple_click/type/ */
/*   key/wait/scroll/scroll_to/hover/left_click_drag + screenshot/zoom  */
/*   由 background 处理 capture）。DOM 类在此执行（坐标/ref 定位 +       */
/*   MouseEvent/KeyboardEvent 派发）。                                  */
/* ------------------------------------------------------------------ */

/** 解析 modifiers（CCB: "ctrl" "shift" "alt" "cmd/meta" "win/windows"，可 "+" 组合）→ 事件 init */
function parseModifiers(modifiers) {
  const m = { ctrlKey: false, shiftKey: false, altKey: false, metaKey: false }
  if (!modifiers) return m
  for (const part of String(modifiers).toLowerCase().split('+')) {
    const p = part.trim()
    if (p === 'ctrl' || p === 'control') m.ctrlKey = true
    else if (p === 'shift') m.shiftKey = true
    else if (p === 'alt' || p === 'option') m.altKey = true
    else if (p === 'cmd' || p === 'meta' || p === 'command' || p === 'win' || p === 'windows') m.metaKey = true
  }
  return m
}

/** 解析 CCB 风格 ref（数字 / "ref_3" / "3"）→ 快照数组下标；解析不了返回 -1 */
function parseRef(ref) {
  if (typeof ref === 'number') return Number.isFinite(ref) ? Math.trunc(ref) : -1
  const s = String(ref).trim().replace(/^ref_?/i, '')
  return /^\d+$/.test(s) ? Number(s) : -1
}

/**
 * 定位操作目标：ref（单源索引下标，对齐 CCB ref 语义）> selector > coordinate 的 elementFromPoint。
 * ref 取自最近一次 read_page/find 的快照数组；元素已不在文档（isConnected=false）→ 明确报错。
 */
function locateElement(args) {
  const { ref, selector, coordinate, x, y } = args
  if (ref != null) {
    const idx = parseRef(ref)
    const el = idx >= 0 && idx < interactiveElements.length ? interactiveElements[idx] : null
    if (el && el.isConnected) return { ok: true, el }
    return { ok: false, error: `ref 失效，请重新 read_page/find（ref=${String(ref)}）` }
  }
  if (selector) {
    const el = document.querySelector(selector)
    if (el) return { ok: true, el }
    return { ok: false, error: `未找到匹配 selector 的元素：${selector}` }
  }
  const pos = coordinate || (x != null && y != null ? [Number(x), Number(y)] : null)
  if (pos && pos.length >= 2) {
    const el = document.elementFromPoint(Number(pos[0]), Number(pos[1]))
    if (el) return { ok: true, el }
    return { ok: false, error: `坐标点无元素（${Number(pos[0])}, ${Number(pos[1])}）` }
  }
  return { ok: false, error: '缺少定位参数（ref/selector/coordinate 至少其一）' }
}

function mouseInit(pos, mods) {
  return { clientX: pos ? Number(pos[0]) : 0, clientY: pos ? Number(pos[1]) : 0, bubbles: true, cancelable: true, view: window, ...mods }
}
function fireMouse(el, type, pos, mods) {
  el.dispatchEvent(new MouseEvent(type, mouseInit(pos, mods)))
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 找出元素自身或最近的可聚焦祖先（真实点击的 focus 目标，浏览器行为：聚焦最近可聚焦祖先） */
function nearestFocusable(el) {
  let n = el
  while (n && n.nodeType === 1) {
    const tag = n.tagName.toLowerCase()
    if (n.isContentEditable || tag === 'a' || tag === 'button' || tag === 'input' ||
        tag === 'select' || tag === 'textarea' || (typeof n.tabIndex === 'number' && n.tabIndex >= 0)) {
      return n
    }
    n = n.parentElement
  }
  return null
}

/**
 * 真实点击序列（治 P3a「坐标点击落空 / 点了没反应」）：
 *   scrollIntoView(center) → elementFromPoint 命中最深层真实元素（在意图元素内）
 *   → focus 最近可聚焦祖先 → pointer/mouse down+up → 原生 el.click() 兜底默认激活
 *   （<a> 导航 / <button>、<input type=submit> 提交 / checkbox、radio 切换等浏览器原生
 *   默认行为只有原生 click() 才会触发，纯合成 MouseEvent 不产生默认动作）。
 * @param point 可选 [x,y]：给定时作为命中/事件坐标（coordinate 点击保持精确）；缺省取元素中心。
 * @param opts  { button?:number, contextmenu?:boolean }：right_click 用 button=2 + contextmenu。
 */
function dispatchRealClick(el, point, mods, opts = {}) {
  const { button = 0 } = opts
  try { el.scrollIntoView({ block: 'center', inline: 'nearest' }) } catch (e) { /* 忽略滚动异常 */ }
  const rect = el.getBoundingClientRect()
  const x = point && point.length >= 2 ? Number(point[0]) : rect.left + rect.width / 2
  const y = point && point.length >= 2 ? Number(point[1]) : rect.top + rect.height / 2
  const doc = el.ownerDocument || document
  let hit = null
  try { hit = doc.elementFromPoint(x, y) } catch (e) { /* 忽略 */ }
  const target = hit && hit.nodeType === 1 && el.contains(hit) ? hit : el
  const focusable = nearestFocusable(el)
  if (focusable) { try { focusable.focus({ preventScroll: true }) } catch (e) { /* 某些元素 focus 抛错，忽略 */ } }
  const mBase = { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, button, ...mods }
  const pBase = { ...mBase, pointerType: 'mouse', isPrimary: true }
  const hasPointer = typeof PointerEvent !== 'undefined'
  if (hasPointer) target.dispatchEvent(new PointerEvent('pointerdown', pBase))
  target.dispatchEvent(new MouseEvent('mousedown', mBase))
  if (hasPointer) target.dispatchEvent(new PointerEvent('pointerup', pBase))
  target.dispatchEvent(new MouseEvent('mouseup', mBase))
  if (opts.contextmenu) {
    target.dispatchEvent(new MouseEvent('contextmenu', { ...mBase, button: 2 }))
  }
  if (button === 0 && !opts.contextmenu && typeof target.click === 'function') {
    try { target.click() } catch (e) { /* 点击异常不影响已派发的事件序列 */ }
  }
  return { x, y, target }
}

/** computer：13 actions 对齐 CCB（screenshot/zoom capture 在 background） */
async function computer(args = {}) {
  const { action, text, coordinate, start_coordinate, duration, scroll_direction, scroll_amount, repeat, modifiers } = args
  const mods = parseModifiers(modifiers)
  const pos = coordinate || (args.x != null && args.y != null ? [Number(args.x), Number(args.y)] : null)

  switch (action) {
    case 'left_click': {
      const r = locateElement(args)
      if (!r.ok) return { ok: false, error: `computer left_click ${r.error}` }
      const hit = dispatchRealClick(r.el, pos, mods)
      return { ok: true, result: { clicked: buildSelector(r.el), x: hit.x, y: hit.y } }
    }
    case 'right_click': {
      const r = locateElement(args)
      if (!r.ok) return { ok: false, error: `computer right_click ${r.error}` }
      // 右键：真实序列（滚动/命中/focus）+ contextmenu；不补 el.click()（避免误触发左键默认激活）
      dispatchRealClick(r.el, pos, mods, { button: 2, contextmenu: true })
      return { ok: true, result: { rightClicked: buildSelector(r.el) } }
    }
    case 'double_click': {
      const r = locateElement(args)
      if (!r.ok) return { ok: false, error: `computer double_click ${r.error}` }
      const first = dispatchRealClick(r.el, pos, mods)
      dispatchRealClick(r.el, pos, mods)
      fireMouse(r.el, 'dblclick', [first.x, first.y], mods)
      return { ok: true, result: { doubleClicked: buildSelector(r.el) } }
    }
    case 'triple_click': {
      const r = locateElement(args)
      if (!r.ok) return { ok: false, error: `computer triple_click ${r.error}` }
      let last = null
      for (let i = 0; i < 3; i++) last = dispatchRealClick(r.el, pos, mods)
      const p = last ? [last.x, last.y] : pos
      fireMouse(r.el, 'dblclick', p, mods)
      fireMouse(r.el, 'dblclick', p, mods)
      return { ok: true, result: { tripleClicked: buildSelector(r.el) } }
    }
    case 'type': {
      const r = locateElement(args)
      if (!r.ok) return { ok: false, error: `computer type ${r.error}` }
      const el = r.el
      el.focus()
      const strValue = String(text ?? '')
      if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
        const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
        const setter = Object.getOwnPropertyDescriptor(proto, 'value').set
        setter.call(el, strValue)
        el.dispatchEvent(new Event('input', { bubbles: true }))
        el.dispatchEvent(new Event('change', { bubbles: true }))
      } else if (el.isContentEditable) {
        el.textContent = strValue
        el.dispatchEvent(new Event('input', { bubbles: true }))
      }
      return { ok: true, result: { typed: strValue, target: buildSelector(el) } }
    }
    case 'key': {
      // CCB: text = 空格分隔按键序列（"Backspace Backspace Delete"），支持 "ctrl+a" 组合 + repeat
      const keys = String(text ?? '').split(/\s+/).filter(Boolean)
      if (keys.length === 0) return { ok: false, error: 'key 需要 text（空格分隔按键，如 "Backspace Delete"）' }
      const count = Math.min(Number(repeat ?? 1) || 1, 100)
      for (let r = 0; r < count; r++) {
        for (const raw of keys) {
          const combo = raw.split('+')
          let key = combo.length > 1 ? combo.pop() : raw
          const kMods = combo.length > 1 ? parseModifiers(combo.join('+')) : { ...mods }
          const el = document.activeElement || document.body
          const init = { key, bubbles: true, cancelable: true, view: window, ...kMods }
          el.dispatchEvent(new KeyboardEvent('keydown', init))
          el.dispatchEvent(new KeyboardEvent('keyup', init))
        }
      }
      return { ok: true, result: { keys, repeat: count } }
    }
    case 'wait': {
      const sec = Number(duration ?? 0)
      if (sec < 0 || sec > 30) return { ok: false, error: 'wait duration 需在 0-30 秒' }
      await sleep(sec * 1000)
      return { ok: true, result: { waited: sec } }
    }
    case 'scroll': {
      const dir = scroll_direction || 'down'
      const amount = Math.max(1, Math.min(10, Number(scroll_amount ?? 3) || 3))
      const delta = amount * 100
      if (dir === 'left' || dir === 'right') window.scrollBy({ left: dir === 'left' ? -delta : delta, behavior: 'smooth' })
      else window.scrollBy({ top: dir === 'up' ? -delta : delta, behavior: 'smooth' })
      return { ok: true, result: { scrolled: dir, amount } }
    }
    case 'scroll_to': {
      const r = locateElement(args)
      if (!r.ok) return { ok: false, error: `computer scroll_to ${r.error}` }
      r.el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      return { ok: true, result: { scrolledTo: buildSelector(r.el) } }
    }
    case 'hover': {
      const r = locateElement(args)
      if (!r.ok) return { ok: false, error: `computer hover ${r.error}` }
      const el = r.el
      fireMouse(el, 'mouseover', pos, mods)
      fireMouse(el, 'mouseenter', pos, mods)
      fireMouse(el, 'mousemove', pos, mods)
      return { ok: true, result: { hovered: buildSelector(el) } }
    }
    case 'left_click_drag': {
      const start = start_coordinate
      const end = coordinate
      if (!start || !end || start.length < 2 || end.length < 2) return { ok: false, error: 'left_click_drag 需要 start_coordinate 和 coordinate（[x,y]）' }
      const from = document.elementFromPoint(Number(start[0]), Number(start[1]))
      if (!from) return { ok: false, error: 'left_click_drag 起点找不到元素' }
      fireMouse(from, 'mousedown', start, mods)
      const steps = 8
      for (let i = 1; i <= steps; i++) {
        const mx = Number(start[0]) + (Number(end[0]) - Number(start[0])) * i / steps
        const my = Number(start[1]) + (Number(end[1]) - Number(start[1])) * i / steps
        const mid = document.elementFromPoint(mx, my) || document.body
        fireMouse(mid, 'mousemove', [mx, my], mods)
      }
      const to = document.elementFromPoint(Number(end[0]), Number(end[1])) || document.body
      fireMouse(to, 'mouseup', end, mods)
      return { ok: true, result: { dragged: { from: start, to: end } } }
    }
    case 'screenshot':
      return { ok: false, error: 'computer screenshot 由 background 处理（chrome.tabs.captureVisibleTab）' }
    case 'zoom':
      return { ok: false, error: 'computer zoom 由 background 处理（capture + 区域裁剪）' }
    default:
      return { ok: false, error: `不支持的 computer action：${action ?? '(空)'}` }
  }
}

/** navigate：跳转（先回结果再跳，导航会卸载本脚本） */
async function navigate(args = {}) {
  const { url } = args
  if (!url) return { ok: false, error: 'navigate 需要 url 参数' }
  setTimeout(() => { location.href = url }, 150)
  return { ok: true, result: { navigating: url } }
}

/** read_console_messages：返回本页注入后收集的控制台消息缓冲 */
async function readConsoleMessages(args = {}) {
  const { limit = 50 } = args || {}
  const buf = window.__NEXUSAI_CC_CONSOLE__ || []
  return { ok: true, result: { count: buf.length, messages: buf.slice(-Number(limit)) } }
}

/** read_network_requests：performance resource entries（含 future 条目需 PerformanceObserver，MVP 用快照） */
async function readNetworkRequests(args = {}) {
  const { limit = 50 } = args || {}
  const entries = performance.getEntriesByType('resource')
  const requests = entries.slice(-Number(limit)).map((e) => ({
    name: e.name,
    duration: Math.round(e.duration),
    transferSize: e.transferSize,
    initiatorType: e.initiatorType,
  }))
  return { ok: true, result: { count: requests.length, requests } }
}

/** 未实现工具 · fail loud 占位 */
function notImplemented(tool) {
  return { ok: false, error: `[fail loud] 工具 ${tool} 未实现（MVP 占位，待后续补齐）` }
}

/* ------------------------------------------------------------------ */
/*  分发                                                               */
/* ------------------------------------------------------------------ */

const HANDLERS = {
  read_page: readPage,
  get_page_text: getPageText,
  find,
  form_input: formInput,
  computer,
  navigate,
  read_console_messages: readConsoleMessages,
  read_network_requests: readNetworkRequests,
  // chrome.tabs/windows 类工具由 background 直接执行，此处明确 fail loud
  javascript_tool: () => notImplemented('javascript_tool'),
  resize_window: () => notImplemented('resize_window'),
  tabs_context_mcp: () => notImplemented('tabs_context_mcp'),
  tabs_create_mcp: () => notImplemented('tabs_create_mcp'),
  switch_browser: () => notImplemented('switch_browser'),
  // 未实现占位
  gif_creator: () => notImplemented('gif_creator'),
  upload_image: () => notImplemented('upload_image'),
  update_plan: () => notImplemented('update_plan'),
  shortcuts_list: () => notImplemented('shortcuts_list'),
  shortcuts_execute: () => notImplemented('shortcuts_execute'),
}

function onMessage(msg, sender, sendResponse) {
  if (!msg || msg.type !== 'tool_call') return false
  const { id, tool, args } = msg
  const handler = HANDLERS[tool]
  Promise.resolve(handler ? handler(args || {}) : notImplemented(tool))
    .then((out) => sendResponse(out || { ok: false, error: '工具返回空结果' }))
    .catch((e) => sendResponse({ ok: false, error: e instanceof Error ? e.message : String(e) }))
  return true // 异步响应：保持消息通道打开直到 Promise 落定
}

/* ------------------------------------------------------------------ */
/*  console 钩子（每页只装一次）                                        */
/* ------------------------------------------------------------------ */
function initConsoleHook() {
  window.__NEXUSAI_CC_CONSOLE__ = []
  for (const level of ['log', 'info', 'warn', 'error', 'debug']) {
    const orig = console[level]
    if (typeof orig !== 'function') continue
    console[level] = (...args) => {
      try {
        const buf = window.__NEXUSAI_CC_CONSOLE__ || (window.__NEXUSAI_CC_CONSOLE__ = [])
        const text = args.map((a) => {
          try {
            return typeof a === 'string' ? a : (a instanceof Error ? `Error: ${a.message}` : JSON.stringify(a))
          } catch (e) {
            return String(a)
          }
        }).join(' ')
        buf.push({ level, text: text.slice(0, 1000), time: Date.now() })
        if (buf.length > 500) buf.shift()
      } catch (e) {
        /* 钩子自身出错不影响原 console */
      }
      return orig.apply(console, args)
    }
  }
}
