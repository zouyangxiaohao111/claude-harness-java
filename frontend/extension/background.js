// NexusAI in Chrome 扩展 · 后台 Service Worker（Manifest V3）
//
// 职责：
//   1. 连接后端原生 WebSocket（ws://localhost:3458/ws/browser），建连首条发送 hello（不带 sessionId，全局连接）
//   2. 一个扩展连接服务所有会话：tool_call 携带 sessionId → 按 sessionId 路由到该会话自己的 tab
//   3. 维护 per-session tab 组（Map<sessionId, {tabId,...}>）：tabs_context_mcp / tabs_create_mcp
//      首次某会话调用创建新 tab，后续复用（对齐 CCB「每个对话创建自己的新 tab」）
//   4. 结果回传带 sessionId（tool_result / tool_error 与 tool_call 的 id 一一对应）
//   5. 断线自动重连（2s 退避）；单次调用 30s 超时兜底（fail loud）

const WS_URL = 'ws://localhost:3458/ws/browser'
const TIMEOUT_MS = 30_000 // 后端约定 30s 内必须响应
const RECONNECT_MS = 2_000
/** screenshot 捕获超时（用户拍板：截图慢时耐心等 1 分钟，超时再报——不快速失败，配合日志区分截图/传输） */
const SCREENSHOT_TIMEOUT_MS = 60_000

/** sleep · background.js 独立定义（content.js 有同名函数，但两者不同 context——此前 computer 截图调 sleep 未定义 → ReferenceError） */
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** Blob → data URL（zoom 裁剪结果回传用；MV3 SW 无 canvas.toDataURL，用 OffscreenCanvas.convertToBlob + FileReader） */
function blobToDataUrl(blob) {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(r.result)
    r.onerror = () => reject(r.error || new Error('Blob 转 data URL 失败'))
    r.readAsDataURL(blob)
  })
}

let ws = null
let reconnectTimer = null
let heartbeatTimer = null
let connected = false
/** id -> { tool, timer, sessionId } · 未完成的 tool_call，用于超时清理 */
const pending = new Map()
/** sessionId -> { tabId, windowId, url, title, createdAt } · per-session tab（对齐 CCB「每个对话自己的新 tab」） */
const sessionTabs = new Map()

/* ------------------------------------------------------------------ */
/*  sessionTabs 持久化：MV3 service worker 空闲被回收时内存 Map 清空，  */
/*  下次工具调用 ensureSessionTab 找不到会话 tab → 重建新标签（用户观察  */
/*  「超时后每次调用都开新标签」根因）。持久化到 chrome.storage，SW 恢复  */
/*  后 restore 读回，复用原 tab。                                      */
/* ------------------------------------------------------------------ */
const SESSION_TABS_KEY = 'nexusai_session_tabs'

function persistSessionTabs() {
  const obj = {}
  for (const [sid, entry] of sessionTabs) obj[sid] = entry
  chrome.storage.local.set({ [SESSION_TABS_KEY]: obj }).catch(() => {})
}

async function restoreSessionTabs() {
  try {
    const saved = await chrome.storage.local.get(SESSION_TABS_KEY)
    const data = saved && saved[SESSION_TABS_KEY]
    if (data && typeof data === 'object') {
      for (const [sid, entry] of Object.entries(data)) {
        // 只恢复仍存在的 tab（tabId 可能已被用户关闭 → ensureSessionTab 的 getTab 兜底重建）
        if (entry && entry.tabId) sessionTabs.set(sid, entry)
      }
    }
  } catch { /* storage 不可用 → 空 Map（下次重建，可接受） */ }
}

/* ------------------------------------------------------------------ */
/*  状态广播（给 popup / 写入 storage 持久化）                           */
/* ------------------------------------------------------------------ */
function broadcastStatus() {
  const status = { type: 'ws-status', connected, wsUrl: WS_URL, sessionCount: sessionTabs.size }
  // popup 未打开时无接收端，静默失败
  chrome.runtime.sendMessage(status).catch(() => {})
  chrome.storage.local.set({ connected }).catch(() => {})
}

/* ------------------------------------------------------------------ */
/*  WebSocket 生命周期                                                  */
/* ------------------------------------------------------------------ */
function connect() {
  clearTimeout(reconnectTimer)
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
  try {
    ws = new WebSocket(WS_URL)
  } catch (e) {
    scheduleReconnect()
    return
  }
  ws.onopen = () => {
    connected = true
    sendHello()
    broadcastStatus()
    startHeartbeat()
  }
  ws.onmessage = (ev) => handleMessage(ev.data)
  ws.onclose = () => {
    stopHeartbeat()
    connected = false
    broadcastStatus()
    ws = null
    // 连接断开：未完成调用全部 fail loud（明确报错，绝不静默丢弃）
    for (const [id, { timer, sessionId }] of pending) {
      clearTimeout(timer)
      sendError(id, sessionId, '扩展与后端的 WebSocket 连接已断开')
    }
    pending.clear()
    scheduleReconnect()
  }
  ws.onerror = () => { /* onclose 会跟随触发重连 */ }
}

function sendHello() {
  if (ws && ws.readyState === WebSocket.OPEN) {
    // 全局连接：hello 不带 sessionId（一个连接服务所有会话）
    ws.send(JSON.stringify({ type: 'hello' }))
  }
}

function scheduleReconnect() {
  clearTimeout(reconnectTimer)
  reconnectTimer = setTimeout(connect, RECONNECT_MS)
}

/* ------------------------------------------------------------------ */
/*  WS 心跳：每 25s 发 ping，保持连接活跃（防后端空闲断开），配合 alarms  */
/*  保活 + 2s 快速重连让「连接过期」几乎无感。MV3 SW 无法真正永不过期     */
/*  （SW 空闲回收是 Chrome 限制），但心跳+保活+重连把断开窗口压到最小。  */
/* ------------------------------------------------------------------ */
function startHeartbeat() {
  clearInterval(heartbeatTimer)
  heartbeatTimer = setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'ping' }))
    }
  }, 25_000)
}
function stopHeartbeat() {
  clearInterval(heartbeatTimer)
  heartbeatTimer = null
}

/* ------------------------------------------------------------------ */
/*  消息处理：tool_call → 分派                                          */
/* ------------------------------------------------------------------ */
function handleMessage(data) {
  let msg
  try {
    msg = JSON.parse(data)
  } catch (e) {
    return // 非 JSON 帧忽略
  }
  if (msg && msg.type === 'tool_call') {
    dispatchToolCall(msg)
  }
}

function dispatchToolCall(msg) {
  const { id, tool, args, sessionId } = msg
  if (!id || !tool) {
    sendError(id || 'unknown', sessionId, 'tool_call 缺少 id 或 tool')
    return
  }
  // 全局连接协议：所有 tool_call 必须带 sessionId 才能路由到对应会话的 tab
  if (!sessionId) {
    sendError(id, null, 'tool_call 缺少 sessionId（全局连接协议要求）')
    return
  }
  // 30s 超时兜底
  const timer = setTimeout(() => {
    if (pending.has(id)) {
      pending.delete(id)
      sendError(id, sessionId, `tool 执行超时（${TIMEOUT_MS / 1000}s）`)
    }
  }, TIMEOUT_MS)
  pending.set(id, { tool, timer, sessionId })

  runTool(id, tool, args || {}, sessionId)
    .then((out) => resolve(id, sessionId, out))
    .catch((e) => resolve(id, sessionId, { ok: false, error: e instanceof Error ? e.message : String(e) }))
}

/** 返回约定：{ ok: true, result } 或 { ok: false, error } */
async function runTool(id, tool, args, sessionId) {
  if (isBackgroundTool(tool, args)) {
    return runInBackground(tool, args, sessionId)
  }
  return runInSessionTab(id, tool, args, sessionId)
}

/** 需要 chrome.tabs/windows API 的工具在 SW 内执行，不依赖页面 DOM */
function isBackgroundTool(tool, args) {
  if (['resize_window', 'tabs_context_mcp', 'tabs_create_mcp', 'switch_browser', 'javascript_tool', 'navigate'].includes(tool)) return true
  if (tool === 'computer' && args.action === 'screenshot') return true
  return false
}

function resolve(id, sessionId, out) {
  const p = pending.get(id)
  if (!p) return // 已超时或被 onclose 清理
  clearTimeout(p.timer)
  pending.delete(id)
  if (out && out.ok === true) sendResult(id, sessionId, out.result)
  else sendError(id, sessionId, (out && out.error) || '未知错误')
}

function sendResult(id, sessionId, result) {
  const payload = JSON.stringify({ type: 'tool_result', id, sessionId, result })
  if (ws && ws.readyState === WebSocket.OPEN) {
    // [截图诊断] 记录回传体积（区分「截图失败」vs「传输超时」：capture OK 但大 payload 传输慢）
    console.log('[tool_result] ws send', { id, bytes: payload.length })
    ws.send(payload)
  } else {
    console.warn('[tool_result] ws 未连接，结果丢弃', { id, bytes: payload.length })
  }
}

function sendError(id, sessionId, error) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'tool_error', id, sessionId, error }))
  }
}

/* ------------------------------------------------------------------ */
/*  per-session tab 管理（对齐 CCB「每个对话创建自己的新 tab」）          */
/* ------------------------------------------------------------------ */

async function getTab(tabId) {
  try {
    return await chrome.tabs.get(tabId)
  } catch (e) {
    return null // tab 已被关闭 / 不存在
  }
}

/**
 * 不可被 content script 注入的 URL 前缀：
 * chrome://newtab / chrome://extensions（一键安装打开的窗口）/ chrome-extension://（扩展自身页面，
 * 或接管新标签页的第三方扩展）/ about: / edge:// 等。工具操作这些页被 Chrome 安全限制拒绝
 * （executeScript 报 "Cannot access contents of url ..."）。
 */
const NON_INJECTABLE_URL = /^(chrome|chrome-extension|edge|devtools|opera|vivaldi|about):/i

/**
 * 确保 tab 可被 content script 注入：命中 {@link NON_INJECTABLE_URL} 的受限页 → 导航到
 * about:blank（match_about_blank 可注入；sendMessage 失败时 executeScript 兜底注入）。
 *
 * <p>WHY（Cannot access a chrome-extension:// URL 修复）：会话 tab 可能停在 chrome://newtab
 * （新建默认）、chrome://extensions（一键安装打开的窗口）或 chrome-extension://（接管新标签页的
 * 第三方扩展 / 扩展自身页面），工具要操作这些页被 Chrome 安全限制拒绝。
 */
async function ensureInjectableTab(tab, entry) {
  if (tab && tab.url && NON_INJECTABLE_URL.test(tab.url)) {
    const blank = await chrome.tabs.update(tab.id, { url: 'about:blank' })
    const fixed = { ...entry, url: 'about:blank', title: '' }
    return { tab: blank, entry: fixed }
  }
  return { tab, entry }
}

/**
 * 确保某会话有自己的 tab：首次创建，后续复用；若会话 tab 已被用户关闭则重新创建。
 *
 * @param sessionId 会话 ID（tool_call 携带）
 * @param url 可选初始 URL（当前 tabs_create_mcp 语义为空 tab，暂不传）
 */
/** 从 storage 读某会话的 tab 映射（SW 恢复后 restoreSessionTabs 未完成/丢失时的兜底） */
async function getSessionTabFromStorage(sessionId) {
  try {
    const saved = await chrome.storage.local.get(SESSION_TABS_KEY)
    const data = saved && saved[SESSION_TABS_KEY]
    return (data && data[sessionId]) || null
  } catch {
    return null
  }
}

async function ensureSessionTab(sessionId, url) {
  let entry = sessionTabs.get(sessionId)
  if (!entry) {
    // [storage 兜底] 用户观察「重连后开新 tab」根因：SW 回收后 sessionTabs 空，restoreSessionTabs
    //   async 未及时完成 / storage 读失败 → ensureSessionTab 重建新 tab。这里从 storage 读回该会话
    //   entry 复用原 tab（tab 仍存在则复用，已关则重建）。
    entry = await getSessionTabFromStorage(sessionId)
    if (entry) sessionTabs.set(sessionId, entry)
  }
  let tab = entry ? await getTab(entry.tabId) : null
  if (entry && tab) {
    // 刷新缓存（url/title/windowId 可能随导航变化）
    entry = { ...entry, windowId: tab.windowId, url: tab.url, title: tab.title }
  } else {
    // 无会话 tab（MV3 SW 回收后 sessionTabs 清空 / tab 被用户关闭）→ 创建新 tab
    if (entry) { sessionTabs.delete(sessionId); persistSessionTabs() }
    const createParams = {}
    if (url) createParams.url = url
    tab = await chrome.tabs.create(createParams)
    entry = { tabId: tab.id, windowId: tab.windowId, url: tab.url, title: tab.title, createdAt: Date.now() }
  }
  // ensureInjectableTab 可能导航（chrome:// 等 → about:blank）→ finalEntry 写回 sessionTabs + 持久化
  //   （防止 SW 回收后会话 tab 信息丢失 → 复用原 tab，不再每次重建新标签）
  const ensured = await ensureInjectableTab(tab, entry)
  sessionTabs.set(sessionId, ensured.entry)
  persistSessionTabs()
  return ensured
}

/**
 * 解析该会话的操作目标 tab。本模型每会话一个 tab（CCB「每个对话自己的新 tab」），
 * 故 args.tabId（模型可能携带的陈旧值）一律以会话 tab 为准 —— 即「定位到该会话的 tab，而非活动 tab」。
 */
async function resolveSessionTab(sessionId, args) {
  const { tab } = await ensureSessionTab(sessionId)
  return tab
}

/* ------------------------------------------------------------------ */
/*  会话 tab + content script 交互（DOM 类工具）                         */
/* ------------------------------------------------------------------ */

async function runInSessionTab(id, tool, args, sessionId) {
  const tab = await resolveSessionTab(sessionId, args)
  const send = () => chrome.tabs.sendMessage(tab.id, { type: 'tool_call', id, tool, args, sessionId })
  try {
    return await send()
  } catch (e) {
    // content script 未注入（扩展安装前已打开的页面）→ 主动注入后重试一次
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ['content.js'] })
    return await send()
  }
}

/* ------------------------------------------------------------------ */
/*  SW 内直接执行的工具                                                  */
/* ------------------------------------------------------------------ */
async function runInBackground(tool, args, sessionId) {
  switch (tool) {
    case 'navigate': {
      // [navigate 修 background] 用 chrome.tabs.update 直接导航（不依赖 content.js 注入）——
      //   WHY（Cannot access chrome-extension:// URL 根因）：tabs.create({}) 建的空 tab 会被
      //   接管新标签页的第三方扩展劫持成 chrome-extension:// 页，content.js 无法注入（navigate
      //   原实现在 content script 用 location.href → sendMessage/注入失败）。tabs.update 直接
      //   把 tab 导航到目标 http URL，绕开不可注入页（导航后 DOM 工具在 http 页正常）。
      //   对齐 CCB @ant/claude-for-chrome-mcp browserTools.ts:212-230 navigate schema：
      //   url 无协议默认补 https://；"forward"/"back" 走浏览器历史导航。
      const { url } = args
      if (!url) throw new Error('navigate 需要 url 参数')
      const { tab } = await ensureSessionTab(sessionId)
      if (url === 'forward') {
        await chrome.tabs.goForward(tab.id)
        return { ok: true, result: { navigating: 'forward', tabId: tab.id } }
      }
      if (url === 'back') {
        await chrome.tabs.goBack(tab.id)
        return { ok: true, result: { navigating: 'back', tabId: tab.id } }
      }
      const target = /^[a-z][a-z0-9+.-]*:\/\//i.test(url) ? url : `https://${url}`
      await chrome.tabs.update(tab.id, { url: target })
      return { ok: true, result: { navigating: target, tabId: tab.id } }
    }
    case 'resize_window': {
      const { width, height } = args
      if (!width || !height) throw new Error('resize_window 需要 width 和 height')
      const { tab } = await ensureSessionTab(sessionId)
      const win = await chrome.windows.get(tab.windowId)
      await chrome.windows.update(win.id, { width, height })
      return { ok: true, result: { width, height } }
    }
    case 'tabs_context_mcp': {
      // [tabs_context 增强] 返回所有 http 标签页（模型可查看/选择其他打开的标签），
      //   会话 tab 标记 `session:true`。不创建新 tab（对齐 CCB 查询语义）；
      //   无任何 http 标签 → count=0（模型用 tabs_create 显式创建）。
      const entry = sessionTabs.get(sessionId)
      const all = await chrome.tabs.query({})
      const httpTabs = all.filter((t) => t.url && !NON_INJECTABLE_URL.test(t.url))
      const tabs = httpTabs.map((t) => ({
        id: t.id, title: t.title, url: t.url, active: t.active, index: t.index,
        windowId: t.windowId, pinned: t.pinned,
        session: !!(entry && entry.tabId === t.id),
      }))
      return { ok: true, result: { groupId: sessionId, count: tabs.length, sessionTabId: entry ? entry.tabId : null, tabs } }
    }
    case 'tabs_create_mcp': {
      // 每个对话自己的新 tab：首次创建，后续复用
      const { tab } = await ensureSessionTab(sessionId)
      return { ok: true, result: { tab: { id: tab.id, url: tab.url, title: tab.title, index: tab.index } } }
    }
    case 'switch_browser': {
      // 聚焦该会话 tab 所在的窗口（简化对齐 CCB「切换到目标浏览器」）
      const { tab } = await ensureSessionTab(sessionId)
      const win = await chrome.windows.get(tab.windowId)
      await chrome.windows.update(win.id, { focused: true })
      await chrome.tabs.update(tab.id, { active: true })
      return { ok: true, result: { switched: true, windowId: win.id, tabId: tab.id } }
    }
    case 'javascript_tool': {
      // 在页面 MAIN world 执行任意脚本（eval），返回可序列化结果
      // CCB 契约入参键为 action/text/tabId；向后兼容旧扩展使用的 code/fn 键
      const code = args.code != null ? args.code : args.text
      const fn = args.fn
      const payload = code != null ? String(code) : fn != null ? `return (${fn})()` : null
      if (payload == null) throw new Error('javascript_tool 需要 code/text 或 function')
      const { tab } = await ensureSessionTab(sessionId)
      const res = await chrome.scripting.executeScript({
        target: { tabId: tab.id },
        world: 'MAIN',
        func: (src) => {
          try {
            // eslint-disable-next-line no-eval
            const value = (0, eval)(src)
            return { ok: true, result: JSON.parse(JSON.stringify(value)) }
          } catch (e) {
            return { ok: false, error: e instanceof Error ? e.message : String(e) }
          }
        },
        args: [payload],
      })
      const out = res && res[0] && res[0].result
      if (!out) return { ok: true, result: null }
      if (out.ok) return { ok: true, result: out.result }
      throw new Error(out.error)
    }
    case 'computer': {
      // computer：screenshot / zoom（capture）在 background 处理；其余 DOM action（click/type/key/
      //   wait/scroll/scroll_to/hover/drag）由 content script 执行（对齐 CCB 13 actions 分流）
      const { action, format, region } = args
      const { tab } = await ensureSessionTab(sessionId)
      await chrome.windows.update(tab.windowId, { focused: true })
      await chrome.tabs.update(tab.id, { active: true })
      // 短暂等待聚焦生效（captureVisibleTab 在窗口未聚焦时可能失败/黑屏——Windows 程序无法
      //   强制聚焦，用户在 NexusAI 应用里操作时 Chrome 窗口未聚焦）
      await sleep(200)
      // [截图] captureVisibleTab（不弹 debugger 提示）+ jpeg（base64 小，防 WS 回传超时）+ 8s
      //   超时保护（Promise.race 卡住快速 tool_error，不让后端 30s 干等）。zoom 用 png（无损裁剪源）。
      const capFormat = action === 'zoom' ? 'png' : (format || 'jpeg')
      const timeout = new Promise((_, rej) =>
        setTimeout(() => rej(new Error(`screenshot 捕获超时（${SCREENSHOT_TIMEOUT_MS / 1000}s）`)), SCREENSHOT_TIMEOUT_MS))
      let full
      try {
        full = await Promise.race([
          chrome.tabs.captureVisibleTab(tab.windowId, { format: capFormat, quality: capFormat === 'jpeg' ? 85 : undefined }),
          timeout,
        ])
        console.log('[screenshot] captureVisibleTab OK', { format: capFormat, bytes: full ? full.length : 0 })
      } catch (e) {
        // [截图诊断] 截图失败/超时日志（区别：capture 失败 vs 传输慢——capture 日志缺失 = capture 卡住）
        console.error('[screenshot] captureVisibleTab FAIL/超时', { format: capFormat, err: e && e.message })
        throw e
      }
      if (action === 'zoom') {
        // zoom：capture 全屏 + OffscreenCanvas 裁剪 region（[x0,y0,x1,y1]）→ jpeg（对齐 CCB zoom 语义）
        if (!region || region.length < 4) throw new Error('zoom 需要 region（[x0,y0,x1,y1]）')
        const [x0, y0, x1, y1] = region
        const w = Math.max(1, Math.abs(Number(x1) - Number(x0)))
        const h = Math.max(1, Math.abs(Number(y1) - Number(y0)))
        const blob = await (await fetch(full)).blob()
        const bmp = await createImageBitmap(blob)
        const canvas = new OffscreenCanvas(w, h)
        const ctx = canvas.getContext('2d')
        ctx.drawImage(bmp, Number(x0), Number(y0), w, h, 0, 0, w, h)
        const outBlob = await canvas.convertToBlob({ type: 'image/jpeg', quality: 0.85 })
        const dataUrl = await blobToDataUrl(outBlob)
        return { ok: true, result: { dataUrl, format: 'jpeg', region: { x0: Number(x0), y0: Number(y0), x1: Number(x1), y1: Number(y1) } } }
      }
      return { ok: true, result: { dataUrl: full, format: capFormat } }
    }
    default:
      throw new Error(`后台工具未实现：${tool}`)
  }
}

/* ------------------------------------------------------------------ */
/*  popup 消息通道                                                      */
/* ------------------------------------------------------------------ */
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (!msg) return false
  if (msg.type === 'status') {
    sendResponse({ type: 'ws-status', connected, wsUrl: WS_URL, sessionCount: sessionTabs.size })
    return false
  }
  if (msg.type === 'set-session' || msg.type === 'connect') {
    // 全局连接：无需 sessionId，直接连接（向后兼容旧 popup 的 set-session 消息）
    connect()
    if (ws && ws.readyState === WebSocket.OPEN) sendHello()
    sendResponse({ type: 'ws-status', connected, wsUrl: WS_URL, sessionCount: sessionTabs.size })
    return false
  }
  return false
})

/* ------------------------------------------------------------------ */
/*  启动：清理旧 sessionId，全局连接自动拉起                              */
/* ------------------------------------------------------------------ */
chrome.storage.local.remove('sessionId').catch(() => {})
// SW 启动恢复 sessionTabs（MV3 回收后读回，复用原会话 tab 不再每次重建新标签）
void restoreSessionTabs()
connect()

/* ------------------------------------------------------------------ */
/*  MV3 保活：service worker 空闲 ~30s 被 Chrome 回收 → WebSocket 断开    */
/*  （后端日志 code=1001 GOING_AWAY）。chrome.alarms 周期唤醒 SW，唤醒后  */
/*  检查 WS 并重连——跨 SW 回收保持长连接可用（alarms 最小周期 0.5min）。  */
/* ------------------------------------------------------------------ */
const KEEPALIVE_ALARM = 'nexusai-ws-keepalive'
chrome.alarms.create(KEEPALIVE_ALARM, { periodInMinutes: 0.5 })
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name !== KEEPALIVE_ALARM) return
  // SW 被唤醒：WS 非 OPEN 则重连（connect 内部 OPEN/CONNECTING 去重）
  if (!ws || ws.readyState === WebSocket.CLOSED || ws.readyState === WebSocket.CLOSING) {
    connect()
  }
})
