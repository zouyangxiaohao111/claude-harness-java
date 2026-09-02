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
/*  18 个工具实现（DOM 操作类）                                         */
/* ------------------------------------------------------------------ */

/** read_page：页面结构 + 可交互元素清单（简化 accessibility tree） */
async function readPage() {
  const nodes = []
  const selectors = [
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'button', 'a', 'input', 'select', 'textarea',
    '[role]', 'nav', 'main', 'article', 'section', 'img', 'label',
  ]
  for (const sel of selectors) {
    for (const el of document.querySelectorAll(sel)) {
      if (nodes.length >= 500) break
      nodes.push(elementInfo(el))
    }
    if (nodes.length >= 500) break
  }
  return { ok: true, result: { url: location.href, title: document.title, nodeCount: nodes.length, nodes } }
}

/** get_page_text：整页可见文本 */
async function getPageText() {
  const text = (document.body ? document.body.innerText : '') || ''
  return { ok: true, result: { url: location.href, text: text.slice(0, 20000) } }
}

/** find：按文本/描述/aria/data-testid 查找元素，返回选择器 + 索引供后续 computer 定位 */
async function find(args = {}) {
  const { text, description } = args
  const q = String(text || description || '').trim().toLowerCase()
  if (!q) return { ok: false, error: 'find 需要 text 或 description 参数' }
  const all = document.querySelectorAll(
    'button, a, input, textarea, select, [contenteditable="true"], [role="button"], [role="link"], [role="tab"], [role="menuitem"], label, h1,h2,h3,h4,h5,h6, [data-testid], [data-test]',
  )
  const matches = []
  for (const el of all) {
    if (matches.length >= 50) break
    const t = (el.textContent || '').trim()
    const placeholder = el.getAttribute('placeholder') || ''
    const aria = el.getAttribute('aria-label') || ''
    const testid = el.getAttribute('data-testid') || el.getAttribute('data-test') || ''
    const hay = `${t} ${placeholder} ${aria} ${testid}`.toLowerCase()
    if (hay.includes(q)) {
      matches.push({ index: matches.length, ...elementInfo(el) })
    }
  }
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

/** 定位操作目标：ref（find 返回的索引，对齐 CCB ref 语义）> selector > coordinate 的 elementFromPoint */
function locateTarget(args) {
  const { ref, selector, coordinate, x, y } = args
  if (ref != null) {
    // CCB ref：read_page/find 的 element ref ID（我们返回 index，兼容 "ref_1"/数字/字符串）
    const idx = typeof ref === 'number' ? ref : /^\d+$/.test(String(ref)) ? Number(String(ref).replace(/^ref_?/, '')) : -1
    if (idx >= 0) {
      const interactive = document.querySelectorAll('button, a, input, textarea, select, [contenteditable="true"], [role], label, h1,h2,h3,h4,h5,h6')
      const el = interactive[idx] || null
      if (el) return el
    }
    return document.querySelector(`[data-nexusai-ref="${CSS.escape(String(ref))}"]`) || null
  }
  if (selector) return document.querySelector(selector)
  const pos = coordinate || (x != null && y != null ? [Number(x), Number(y)] : null)
  if (pos && pos.length >= 2) return document.elementFromPoint(Number(pos[0]), Number(pos[1]))
  return null
}

function mouseInit(pos, mods) {
  return { clientX: pos ? Number(pos[0]) : 0, clientY: pos ? Number(pos[1]) : 0, bubbles: true, cancelable: true, view: window, ...mods }
}
function fireMouse(el, type, pos, mods) {
  el.dispatchEvent(new MouseEvent(type, mouseInit(pos, mods)))
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** computer：13 actions 对齐 CCB（screenshot/zoom capture 在 background） */
async function computer(args = {}) {
  const { action, text, coordinate, start_coordinate, duration, scroll_direction, scroll_amount, repeat, modifiers } = args
  const mods = parseModifiers(modifiers)
  const pos = coordinate || (args.x != null && args.y != null ? [Number(args.x), Number(args.y)] : null)

  switch (action) {
    case 'left_click': {
      const el = locateTarget(args)
      if (!el) return { ok: false, error: 'computer left_click 找不到目标（coordinate/ref/selector）' }
      fireMouse(el, 'mousedown', pos, mods)
      fireMouse(el, 'mouseup', pos, mods)
      fireMouse(el, 'click', pos, mods)
      return { ok: true, result: { clicked: buildSelector(el), x: pos ? pos[0] : undefined, y: pos ? pos[1] : undefined } }
    }
    case 'right_click': {
      const el = locateTarget(args)
      if (!el) return { ok: false, error: 'computer right_click 找不到目标' }
      fireMouse(el, 'contextmenu', pos, mods)
      return { ok: true, result: { rightClicked: buildSelector(el) } }
    }
    case 'double_click': {
      const el = locateTarget(args)
      if (!el) return { ok: false, error: 'computer double_click 找不到目标' }
      for (let i = 0; i < 2; i++) { fireMouse(el, 'mousedown', pos, mods); fireMouse(el, 'mouseup', pos, mods); fireMouse(el, 'click', pos, mods) }
      fireMouse(el, 'dblclick', pos, mods)
      return { ok: true, result: { doubleClicked: buildSelector(el) } }
    }
    case 'triple_click': {
      const el = locateTarget(args)
      if (!el) return { ok: false, error: 'computer triple_click 找不到目标' }
      for (let i = 0; i < 3; i++) { fireMouse(el, 'mousedown', pos, mods); fireMouse(el, 'mouseup', pos, mods); fireMouse(el, 'click', pos, mods) }
      fireMouse(el, 'dblclick', pos, mods)
      fireMouse(el, 'dblclick', pos, mods)
      return { ok: true, result: { tripleClicked: buildSelector(el) } }
    }
    case 'type': {
      const el = locateTarget(args)
      if (!el) return { ok: false, error: 'computer type 找不到目标' }
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
      const el = locateTarget(args)
      if (!el) return { ok: false, error: 'computer scroll_to 找不到目标 ref' }
      el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      return { ok: true, result: { scrolledTo: buildSelector(el) } }
    }
    case 'hover': {
      const el = locateTarget(args)
      if (!el) return { ok: false, error: 'computer hover 找不到目标' }
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
