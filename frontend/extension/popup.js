// NexusAI in Chrome 扩展 · Popup
//
// 职责：触发全局连接（无需 sessionId）、展示连接状态（background 广播 ws-status）。
// 全局连接：一个扩展连接服务所有会话，popup 只负责「连接」开关 + 状态展示。

const $ = (sel) => document.querySelector(sel)
const btn = $('#connectBtn')
const dot = $('#statusDot')
const statusText = $('#statusText')
const wsUrlEl = $('#wsUrl')
const countEl = $('#sessionCount')

function render(status) {
  const connected = !!(status && status.connected)
  dot.className = 'dot ' + (connected ? 'on' : 'off')
  statusText.textContent = connected ? '已连接，可服务所有会话' : '未连接'
  btn.textContent = connected ? '已连接' : '连接'
  if (status && status.wsUrl) wsUrlEl.textContent = status.wsUrl
  if (status && status.sessionCount != null) {
    countEl.textContent = `当前服务会话数：${status.sessionCount}`
  }
}

// 初始化：查询 background 当前连接状态（全局连接，无 sessionId 需要恢复）
chrome.runtime.sendMessage({ type: 'status' }).then(render).catch(() => render({ connected: false }))

// 接收 background 连接状态广播
chrome.runtime.onMessage.addListener((msg) => {
  if (msg && msg.type === 'ws-status') render(msg)
})

btn.addEventListener('click', () => {
  chrome.runtime.sendMessage({ type: 'connect' }).then(render).catch(() => {
    statusText.textContent = '连接失败，请检查扩展上下文'
  })
})
