// NexusAI in Chrome 扩展 · 后台 Service Worker（Manifest V3）
//
// 职责：
//   1. 连接后端原生 WebSocket（ws://localhost:3458/ws/browser），建连首条发送 hello（不带 sessionId，全局连接）
//   2. 一个扩展连接服务所有会话：tool_call 携带 sessionId → 按 sessionId 路由到该会话自己的 tab
//   3. 维护 per-session tab 组（Map<sessionId, {tabId,...}>）：tabs_context_mcp / tabs_create_mcp
//      首次某会话调用创建新 tab，后续复用（对齐 CCB「每个对话创建自己的新 tab」）
//   4. 结果回传带 sessionId（tool_result / tool_error 与 tool_call 的 id 一一对应）
//   5. 断线自动重连（指数退避 2s→4s→5s 封顶）；单次调用 30s 超时兜底（fail loud）

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
let reconnectAttempt = 0 // 指数退避计数（onopen 重置 0）
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

async function persistSessionTabs() {
  const obj = {}
  for (const [sid, entry] of sessionTabs) obj[sid] = entry
  await chrome.storage.local.set({ [SESSION_TABS_KEY]: obj }).catch(() => {})
}

async function restoreSessionTabs() {
  try {
    const saved = await chrome.storage.local.get(SESSION_TABS_KEY)
    const data = saved && saved[SESSION_TABS_KEY]
    if (!data || typeof data !== 'object') return
    for (const [sid, entry] of Object.entries(data)) {
      if (!entry || !entry.tabId) continue
      const tab = await getTab(entry.tabId).catch(() => null) // getTab 已定义（SW 内 getTab 兜底返回 null）
      if (tab) sessionTabs.set(sid, { ...entry, windowId: tab.windowId, url: tab.url, title: tab.title })
      // tab 不存在（用户关闭/浏览器重启）→ 不恢复，ensureSessionTab 之后按需重建一次
    }
  } catch { /* storage 不可用 → 空 Map */ }
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
    reconnectAttempt = 0 // 连上即重置退避计数
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
  const delay = Math.min(5000, RECONNECT_MS * Math.pow(2, reconnectAttempt)) // 2s→4s→5s 封顶
  reconnectAttempt++
  reconnectTimer = setTimeout(() => { connect() }, delay)
}

/* ------------------------------------------------------------------ */
/*  WS 心跳：每 20s 发 ping 并补一次 storage 写，保持连接活跃（防后端空闲  */
/*  断开 + Chrome<116 也重置 SW 空闲计时），配合 alarms 保活 + 指数退避重连 */
/*  让「连接过期」几乎无感。MV3 SW 无法真正永不过期（SW 空闲回收是 Chrome  */
/*  限制），但心跳+保活+重连把断开窗口压到最小。                          */
/* ------------------------------------------------------------------ */
function startHeartbeat() {
  clearInterval(heartbeatTimer)
  heartbeatTimer = setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'ping' }))
      // 真实扩展活动：重置 SW 空闲计时（不依赖 Chrome≥116 "WS 消息重置计时"）
      chrome.storage.local.set({ _hb: Date.now() }).catch(() => {})
    }
  }, 6_000) // 心跳压到 6s：实测这台 Chrome 的 SW ~13s 就被回收，20s 心跳追不上被杀速度；
  // 6s 发 ping + 后端回 pong（SW 每 ~6s 收到消息）→ 永不空闲满 13s，防回收断连
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
  console.log('[tool_call] dispatch', { tool, id, sessionId, tabId: (args && args.tabId) || null })

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
  if (tool === 'computer' && (args.action === 'screenshot' || args.action === 'zoom')) return true
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
  console.warn('[tool_error] ws send', { id, sessionId, error: String(error).slice(0, 300) })
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
  // 会话 tab 防自动丢弃（新建与复用的 tab 都关：SW 回收/tab 闲置不被 Chrome 整 tab 丢弃）
  try { await chrome.tabs.update(ensured.tab.id, { autoDiscardable: false }) } catch { /* 可忽略 */ }
  sessionTabs.set(sessionId, ensured.entry)
  await persistSessionTabs() // 原 fire-and-forget，改为写完成再返回（SW 回收前落盘更稳）
  return ensured
}

/**
 * 解析该会话的操作目标 tab。本模型每会话一个 tab（CCB「每个对话自己的新 tab」），
 * 故 args.tabId（模型可能携带的陈旧值）一律以会话 tab 为准 —— 即「定位到该会话的 tab，而非活动 tab」。
 */
async function resolveSessionTab(sessionId, args) {
  // 模型显式给 tabId（对齐 CCB/BrowserSkill 工具带 tabId 语义）：若指向存活的 http(s) 页则直接用，
  // 绝不新建 chrome://newtab——否则每次调用都冒一个新标签并截到空页。
  const tid = args && args.tabId
  if (tid != null && /^\d+$/.test(String(tid))) {
    const t = await chrome.tabs.get(Number(tid)).catch(() => null)
    if (t && IS_HTTP(t)) return t
  }
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
/*  截图辅助：免聚焦截图（去 OS raise / 去盲目 sleep / minimized 显式拒绝） */
/* ------------------------------------------------------------------ */

/** 是否 http(s) 页（可被内容脚本注入/被 CDP 捕获；chrome://、about:、扩展页除外）。 */
const IS_HTTP = (t) => t && t.url && /^https?:/i.test(t.url)

/** 查窗口最小化态（用于截图前快速拒绝，不做静默重试）。 */
async function windowIsMinimized(windowId) {
  try {
    const w = await chrome.windows.get(windowId)
    return w.state === 'minimized'
  } catch { return false } // 窗口没了由 captureVisibleTab 抛错兜底
}

/** 目标窗口当前活动 tab（决定是否要 tabs.update 激活——已 active 则跳过，省 ~840ms）。 */
async function activeTabInWindow(windowId) {
  const tabs = await chrome.tabs.query({ windowId, active: true })
  return tabs[0] || null
}

/** base64 dataUrl 的真实字节数（MV3 SW 有 atob）。 */
function dataUrlBytes(dataUrl) {
  if (!dataUrl) return 0
  try {
    const b64 = dataUrl.slice(dataUrl.indexOf(',') + 1)
    const bin = atob(b64)
    return bin.length
  } catch { return dataUrl.length }
}

/** 有界等待：ms 内不落定即抛错（截图路径绝不无限挂起，避免后端 30s send 掐掉却无诊断）。 */
function raceTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, rej) => setTimeout(() => rej(new Error(`[${label}] 超时 ${ms}ms`)), ms)),
  ])
}

/**
 * CDP per-tab 截图（renderer 级，不依赖窗口在前台 / 活动标签）。每次 attach→enable→
 * 焦点模拟→capture→detach。每步有界（8s），最坏 ~24s < 后端 30s，不依赖 SW 定时器存活。
 * Emulation.setFocusEmulationEnabled 让被遮挡/非前台页按“已聚焦”产帧（不抢 OS 焦点），
 * 治 occlusion 停帧导致 fromSurface 等不到新帧。
 */
async function cdpScreenshotTab(tabId, capFormat, quality) {
  let attached = false
  try {
    await raceTimeout(chrome.debugger.attach({ tabId }, '1.3'), 8000, 'cdp.attach')
    attached = true
    await raceTimeout(chrome.debugger.sendCommand({ tabId }, 'Page.enable'), 8000, 'cdp.enable')
    // 焦点模拟：页面视为已聚焦 → 动画/Canvas 持续产帧（配合 fromSurface 才能拿到新帧）
    await raceTimeout(
      chrome.debugger.sendCommand({ tabId }, 'Emulation.setFocusEmulationEnabled', { enabled: true }),
      8000, 'cdp.focusEmu').catch(() => {})
    const res = await raceTimeout(
      chrome.debugger.sendCommand({ tabId }, 'Page.captureScreenshot', {
        format: capFormat,
        quality: capFormat === 'jpeg' ? quality : undefined,
        fromSurface: true,
        captureBeyondViewport: false,
      }),
      8000, 'cdp.capture')
    if (!res || !res.data) return { ok: false, step: 'capture', msg: 'CDP 未返回图像数据' }
    if (!isPureBase64(res.data)) {
      console.warn('[screenshot][cdp] 返回 base64 不纯 len=' + String(res.data).length)
      return { ok: false, step: 'capture', msg: 'CDP 返回的 base64 不纯（尾部疑似混入异常文本）' }
    }
    return { ok: true, dataUrl: 'data:image/' + (capFormat === 'png' ? 'png' : 'jpeg') + ';base64,' + res.data }
  } catch (e) {
    const m = (e && e.message) || String(e)
    if (/another debugger/i.test(m)) return { ok: false, devtools: true, msg: m }
    console.warn('[screenshot][cdp] 失败', { tabId, capFormat, msg: m })
    return { ok: false, msg: m }
  } finally {
    if (attached) {
      try { await chrome.debugger.sendCommand({ tabId }, 'Emulation.setFocusEmulationEnabled', { enabled: false }) } catch { /* 忽略 */ }
      try { await chrome.debugger.detach({ tabId }) } catch { /* 忽略 */ }
    }
  }
}

/**
 * 免前台截图：优先 CDP per-tab（不依赖窗口前台/活动标签）；CDP 失败回退 captureVisibleTab。
 * 两路都带超时、绝不挂起。minimized → ERR_MINIMIZED（先拒，不做 18-57s 慢帧）。
 */
/** 严格 base64 纯度（CDP/Chrome 返回仅含 A-Za-z0-9+/=）；尾部混入其它字符即判定异常。 */
function isPureBase64(b64) {
  return typeof b64 === 'string' && /^[A-Za-z0-9+/]*={0,2}$/.test(b64)
}

async function screenshotTab(windowId, tabId, { format = 'jpeg', quality = 85 } = {}) {
  const t0 = performance.now()
  if (await windowIsMinimized(windowId)) {
    return { ok: false, code: 'ERR_MINIMIZED',
      msg: 'Chrome 窗口已最小化：无可见合成面，无法截图。请先恢复窗口，或改用 DOM 文本观察（get_page_text/read_page）。' }
  }
  // 目标 = 该窗口当前活动 http(s) 标签（用户正在看的页面——截图成熟做法），
  // 而非受管会话 tab：会话 tab 可能是残留 chrome://newtab，强行激活它反而截到空页。
  let act = await activeTabInWindow(windowId)
  if (!act || !IS_HTTP(act)) {
    // 活动标签非 http(s)（chrome://newtab/about:blank）→ 回退受管 tabId（若为 http(s)）并激活
    const stored = await chrome.tabs.get(tabId).catch(() => null)
    if (stored && IS_HTTP(stored)) {
      try { await chrome.tabs.update(tabId, { active: true }) } catch (e) {
        return { ok: false, code: 'ERR_ACTIVATE', msg: '激活目标 tab 失败：' + (e && e.message || e) }
      }
      act = stored
    }
  }
  if (!act || !IS_HTTP(act)) {
    const fallback = await chrome.tabs.get(tabId).catch(() => null)
    const shown = (act && act.url) || (fallback && fallback.url) || ''
    return { ok: false, code: 'ERR_TARGET_TAB',
      msg: `当前活动标签不可截图（URL=${shown || '(空)'}，多为 chrome:// 新标签/空白页）。请先 navigate 或把目标页面切为活动标签再截图。` }
  }
  tabId = act.id // 此后 captureVisibleTab/CDP 都针对该活动 http(s) 页
  const capFormat = format === 'png' ? 'png' : 'jpeg'

  // 主路① captureVisibleTab：直接抓 OS 合成面，动画/Canvas 页也秒出（对齐 mcp-chrome/BrowserSkill 默认）。
  // 前台门控：captureVisibleTab 读窗口合成面，窗口被遮挡(非前台)会 occlusion 停帧 → 只在前台时用它；
  // 非前台直接走 CDP（免前台 + 焦点模拟），避免 captureVisibleTab 永悬 + MV3 SW 回收带死看门狗。
  const winState = await chrome.windows.get(windowId).catch(() => null)
  const windowFocused = !!(winState && winState.focused)
  let cvtErr = null
  if (windowFocused) {
    console.log('[shot] cvt:try', { windowId, tabId, capFormat, focused: true })
    try {
      const dataUrl = await raceTimeout(
        chrome.tabs.captureVisibleTab(windowId, { format: capFormat, quality: capFormat === 'jpeg' ? quality : undefined }),
        8000, 'captureVisibleTab')
      const bytes = dataUrlBytes(dataUrl)
      const b64part = typeof dataUrl === 'string' ? dataUrl.slice(dataUrl.indexOf(',') + 1) : ''
      if (dataUrl && bytes >= 512 && isPureBase64(b64part)) {
        console.log('[shot] cvt:ok bytes=' + bytes + ' ms=' + Math.round(performance.now() - t0))
        return { ok: true, dataUrl, format: capFormat, bytes, ms: Math.round(performance.now() - t0), source: 'captureVisibleTab' }
      }
      cvtErr = (!dataUrl || bytes < 512) ? '返回空/坏帧' : '返回 base64 不纯'
      console.warn('[shot] cvt:err ' + cvtErr)
    } catch (e1) {
      cvtErr = (e1 && e1.message) || String(e1)
      console.warn('[shot] cvt:err ' + cvtErr)
    }
  } else {
    cvtErr = '窗口非前台(focused=false)，跳过 captureVisibleTab 直走 CDP'
    console.log('[shot] cvt:skip 窗口非前台')
  }

  // 主路② CDP per-tab（免前台；动画页可能等帧超时，故作回退而非主路）
  console.log('[shot] cdp:try')
  const cdp = await cdpScreenshotTab(tabId, capFormat, quality)
  if (cdp.ok) {
    const dataUrl = cdp.dataUrl
    const bytes = dataUrlBytes(dataUrl)
    if (bytes < 512) return { ok: false, code: 'ERR_BLANK', msg: 'CDP 截图内容为空/坏帧（bytes=' + bytes + '）。' }
    console.log('[shot] cdp:ok bytes=' + bytes + ' ms=' + Math.round(performance.now() - t0))
    return { ok: true, dataUrl, format: capFormat, bytes, ms: Math.round(performance.now() - t0), source: 'cdp' }
  }
  const devtoolsNote = cdp.devtools ? '（目标页开着 DevTools，占用了调试器）' : ''
  console.warn('[screenshot] 两路均失败', { cvtErr, cdp: cdp.msg, cdpStep: cdp.step })
  return { ok: false, code: 'ERR_CAPTURE',
    msg: `截图失败${devtoolsNote}。captureVisibleTab: ${cvtErr || '(无)'}；CDP: ${cdp.msg || cdp.step || '(无)'}` }
}

/**
 * 就绪门（navigate 专用）：轮询等待页面加载完成（document.readyState === 'complete'）。
 * 上限 ~15s / 200ms；页面不可注入（chrome:// 等）探测不到 readyState → 尽早按 timeout
 * 返回，不抛错、不无限等——导航本身已成功发起，加载态由模型用后续 DOM 工具自我校正。
 */
async function waitForTabComplete(tabId) {
  const DEADLINE_MS = 15_000
  const STEP_MS = 200
  const UNINJECTABLE_MAX = 5
  const deadline = Date.now() + DEADLINE_MS
  let uninjectable = 0
  while (Date.now() < deadline) {
    const tab = await getTab(tabId)
    if (!tab) return 'timeout' // tab 已不存在（被关闭/替换）→ 不再等
    if (tab.status === 'complete') {
      let rs = null
      try {
        const r = await chrome.scripting.executeScript({
          target: { tabId },
          func: () => document.readyState,
        })
        rs = (r && r[0] && r[0].result) || null
      } catch (e) {
        rs = null // 导航中/不可注入 → 按“未就绪”继续轮询
      }
      if (rs === 'complete') return 'complete'
      if (rs === null && ++uninjectable >= UNINJECTABLE_MAX) return 'timeout' // chrome:// 等不可注入页
    }
    await sleep(STEP_MS)
  }
  return 'timeout'
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
      //   [就绪门（P3c）] tabs.update/goForward/goBack 只发起导航、不等加载完成，随后 DOM
      //   工具可能打在「旧文档残留 / loading 中」页 → 用 waitForTabComplete 轮询等页面
      //   complete（上限 ~15s / 200ms）；等不到带 state:'timeout' 返回（不抛、不无限等）。
      const { url } = args
      if (!url) throw new Error('navigate 需要 url 参数')
      const { tab } = await ensureSessionTab(sessionId)
      let navMode = 'url'
      let navUrl = url
      if (url === 'forward') {
        await chrome.tabs.goForward(tab.id)
        navMode = 'forward'
      } else if (url === 'back') {
        await chrome.tabs.goBack(tab.id)
        navMode = 'back'
      } else {
        navUrl = /^[a-z][a-z0-9+.-]*:\/\//i.test(url) ? url : `https://${url}`
        await chrome.tabs.update(tab.id, { url: navUrl })
      }
      await sleep(200) // 给导航一帧提交窗口，避免轮询到旧文档的 readyState
      const state = await waitForTabComplete(tab.id)
      const cur = await getTab(tab.id)
      return {
        ok: true,
        result: {
          navigating: navMode === 'url' ? navUrl : navMode,
          url: (cur && cur.url) || navUrl,
          tabId: tab.id,
          state,
        },
      }
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
      // 尊重显式 tabId（对齐 computer）：否则会打到受管会话 tab（可能停在 chrome:///扩展页）→ Cannot access chrome-extension://
      const tab = await resolveSessionTab(sessionId, args)
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
      // 截图：走免聚焦 screenshotTab（去 raise / 去盲目 sleep / minimized 显式 reason-code）。
      // DOM 类 computer action 由 content script 处理（isBackgroundTool 已分流，此处仅截图/zoom）。
      const { action, format, region } = args
      if (action !== 'screenshot' && action !== 'zoom') {
        return { ok: false, error: `computer ${action} 应由 content script 执行（非截图 action 不应到此）` }
      }
      // 目标 tab：优先模型显式 tabId 指向的存活 http(s) 页（resolveSessionTab 已统一），不再绕受管会话 tab
      const tab = await resolveSessionTab(sessionId, args)
      const shot = await screenshotTab(tab.windowId, tab.id, {
        format: action === 'zoom' ? 'png' : (format || 'jpeg'),
      })
      if (!shot.ok) throw new Error(shot.code + ': ' + shot.msg)
      const full = shot.dataUrl
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
      return { ok: true, result: { dataUrl: full, format: shot.format } }
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

// 唤醒路径：idle 从非 active 回到 active / 浏览器启动 / 扩展安装升级 → 恢复原会话 tab + 重连 WS
async function reconnectIfNeeded() {
  await restoreSessionTabs()
  connect()
}
chrome.idle.onStateChanged.addListener((newState) => { if (newState === 'active') reconnectIfNeeded() })
chrome.runtime.onStartup.addListener(() => { reconnectIfNeeded() })
chrome.runtime.onInstalled.addListener(() => { reconnectIfNeeded() })

/* ------------------------------------------------------------------ */
/*  MV3 保活：service worker 空闲 ~30s 被 Chrome 回收 → WebSocket 断开    */
/*  （后端日志 code=1001 GOING_AWAY）。chrome.alarms 周期唤醒 SW，唤醒后  */
/*  检查 WS 并重连——跨 SW 回收保持长连接可用（alarms 最小周期 0.5min）。  */
/* ------------------------------------------------------------------ */
const KEEPALIVE_ALARM = 'nexusai-ws-keepalive'
chrome.alarms.create(KEEPALIVE_ALARM, { periodInMinutes: 0.5 })
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name !== KEEPALIVE_ALARM) return
  connect() // 无条件：OPEN/CONNECTING 时内部去重；否则强拉新连接，不等 readyState 变 CLOSED
})
