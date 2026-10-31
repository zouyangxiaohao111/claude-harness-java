import { useEffect, useRef, useState } from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import type { Client, StompSubscription } from '@stomp/stompjs'
import '@xterm/xterm/css/xterm.css'
import { terminalApi, type TerminalInfo, type TerminalOutputEvent } from '@/api/terminal'
import { onSocketConnect } from '@/api/socket'
import { ApiError } from '@/api/rest'

/**
 * 右栏「终端」tab：xterm.js 渲染 + REST 写入 + STOMP 输出。
 *
 * <p><b>实例生命周期选择：每个终端一个 {@link Terminal} 实例</b>（不是单实例切换重建）。
 * 理由：切换 tab 时保留各自的滚动缓冲与光标位置（单实例方案必须 dispose + 重拉 snapshot，
 * 会丢历史且闪一下）；后台终端的 STOMP 输出始终有落点（单实例方案后台输出无处可写）。
 * 非激活终端的容器 `hidden`，切回时再 `fit()` + `resize`。
 *
 * <p><b>attach 顺序</b>：先 GET snapshot 回放，再订阅 STOMP —— 反了会把「回放前的实时帧」
 * 写在旧内容之前/重复。
 */
export interface TerminalPanelProps {
  sessionId: string
  /** 仅用于状态文案（会话是否有 run 在跑） */
  sessionRunning?: boolean
  /** 复用的 STOMP 连接（App 的 useChatSocket clientRef）· 省略 = 不订阅输出（测试/嵌入方） */
  clientRef?: { current: Client | null }
  showToast?: (msg: string, type?: 'success' | 'info') => void
}

/** 输入合并窗口（每次按键一个 REST 请求会把后端打满） */
const INPUT_FLUSH_MS = 16

const TERM_THEME = {
  background: '#1c1c1e',
  foreground: '#d6d6d8',
  cursor: '#d6d6d8',
  cursorAccent: '#1c1c1e',
  selectionBackground: 'rgba(214, 214, 216, 0.25)',
}

const TERM_FONT = 'ui-monospace, SFMono-Regular, "SF Mono", Consolas, "Cascadia Mono", monospace'

/** 默认终端 shell 的记忆键（与 BasicSettingsModal 共用） */
const SHELL_STORAGE_KEY = 'nexusai.terminal.shell'

/** 用户选过的默认 shell；未选过 / 存储不可用 ⇒ null（交给后端默认） */
function readPreferredShell(): string | null {
  try {
    const v = localStorage.getItem(SHELL_STORAGE_KEY)
    return v && v.trim() ? v : null
  } catch {
    return null
  }
}

/** shell 可执行名 → 短名（白名单值见后端 TerminalShellResolver） */
function shellLabel(shell: string): string {
  const s = (shell ?? '').toLowerCase()
  if (s.includes('pwsh')) return 'pwsh'
  if (s.includes('powershell')) return 'PowerShell'
  if (s.includes('cmd')) return 'cmd'
  if (s.includes('wsl')) return 'WSL'
  if (s.includes('bash')) return 'bash'
  const base = s.split(/[\\/]/).pop() ?? s
  return base.replace(/\.exe$/, '') || 'shell'
}

/** 单终端句柄：xterm 实例 + 它的订阅 / 输入合并 / 尺寸观察 */
interface TermHandle {
  term: Terminal
  fit: FitAddon
  el: HTMLDivElement
  ro: ResizeObserver | null
  sub: StompSubscription | null
  inputBuf: string
  inputTimer: ReturnType<typeof setTimeout> | null
  /** 进程已退出（本地标记 = !alive 或收到 closed 帧）→ 停止发输入 */
  closed: boolean
  /** snapshot 回放 + 订阅已完成 */
  attached: boolean
  lastCols: number
  lastRows: number
}

const TerminalIcon = () => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 22, height: 22 }}>
    <rect x="3" y="4" width="18" height="16" rx="3" />
    <path d="M7 9l3 3-3 3M13 15h4" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)

const PlusIcon = () => (
  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 13, height: 13 }}>
    <path d="M6 2.5V9.5M2.5 6H9.5" />
  </svg>
)

const ClearIcon = () => (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" style={{ width: 13, height: 13 }}>
    <path d="M2.5 4.5h11M6 4.5V2.5h4v2M4 4.5l.7 9.5h6.6l.7-9.5" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)

const CloseIcon = () => (
  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
    <path d="M4 4l8 8M12 4l-8 8" strokeLinecap="round" />
  </svg>
)

export function TerminalPanel({ sessionId, sessionRunning, clientRef, showToast }: TerminalPanelProps) {
  const [terminals, setTerminals] = useState<TerminalInfo[]>([])
  const [activeId, setActiveId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [creating, setCreating] = useState(false)
  /** 已退出终端（closed 帧或列表里 alive=false） */
  const [exited, setExited] = useState<Record<string, boolean>>({})
  const [dims, setDims] = useState({ cols: 0, rows: 0 })
  const [error, setError] = useState<string | null>(null)

  /**
   * [2026-10-29] 错误友好化：后端未重启（旧构建）时 list 会返回
   * `NoResourceFoundException: No static resource .../terminals`，直接贴给用户既吓人又无用。
   * 真实错误仍进 console（排查用），界面只给可操作的文案。
   */
  const friendlyError = (e: unknown): string => {
    const raw = e instanceof ApiError ? e.userMessage() : String(e)
    // eslint-disable-next-line no-console
    console.warn('[TerminalPanel] 终端接口失败:', raw)
    if (/NoResourceFound|No static resource|Network Error|Failed to fetch|40[45]/i.test(raw)) {
      return '终端服务暂不可用：请确认后端已启动并加载了终端接口'
    }
    return raw
  }

  const handlesRef = useRef<Map<string, TermHandle>>(new Map())
  /** 每终端一个稳定的 ref 回调（内联箭头函数每次渲染都会 ref(null)→ref(el)，会把实例建了又销毁） */
  const screenRefCbsRef = useRef<Map<string, (el: HTMLDivElement | null) => void>>(new Map())
  const sessionRef = useRef(sessionId)
  sessionRef.current = sessionId
  const activeRef = useRef(activeId)
  activeRef.current = activeId
  /** 最新终端列表（稳定 ref 回调里不能用渲染闭包里的 terminals —— 那是创建时的旧数组） */
  const terminalsRef = useRef(terminals)
  terminalsRef.current = terminals
  /** 最新连接 ref（subscribe 里读 ref，不用首次渲染的 prop 闭包） */
  const clientHolderRef = useRef(clientRef)
  clientHolderRef.current = clientRef

  // ---- 卸载 / 切会话：释放全部 xterm 实例与订阅（STOMP 连接本身不动） ----
  function disposeHandle(id: string) {
    const h = handlesRef.current.get(id)
    // ⛔ 不删 screenRefCbsRef 里的回调：删了下次 render 会生成新函数 → React 判定 ref 变化，
    //    先 cb(null)（又 dispose）再 cb(el)（又建实例）→ setState → 再 render → 死循环（实测渲染崩溃）。
    if (!h) return
    handlesRef.current.delete(id)
    if (h.inputTimer != null) clearTimeout(h.inputTimer)
    try { h.ro?.disconnect() } catch { /* 忽略 */ }
    try { h.sub?.unsubscribe() } catch { /* 连接已失效 */ }
    try { h.term.dispose() } catch { /* 忽略 */ }
  }

  function disposeAll() {
    for (const id of Array.from(handlesRef.current.keys())) disposeHandle(id)
  }

  // ---- 挂载 / 切会话：拉列表（⛔ 不自动创建终端：用户 2026-10-29 裁定只由用户手动建） ----
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    terminalApi.list(sessionId)
      .then((list) => {
        if (cancelled) return
        setTerminals(list)
        setExited(Object.fromEntries(list.filter((t) => !t.alive).map((t) => [t.id, true])))
        setActiveId((prev) => (prev && list.some((t) => t.id === prev) ? prev : (list[0]?.id ?? null)))
      })
      .catch((e) => {
        if (cancelled) return
        setError(friendlyError(e))
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => {
      cancelled = true
      disposeAll()
      setTerminals([])
      setActiveId(null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId])

  // ---- 连接建立（含重连）→ 重订各终端 topic（断线后订阅随连接失效） ----
  useEffect(() => {
    const off = onSocketConnect(() => {
      for (const [id, h] of handlesRef.current) {
        if (h.sub) { try { h.sub.unsubscribe() } catch { /* 已失效 */ } h.sub = null }
        if (h.attached && !h.closed) subscribe(id)
      }
    })
    return off
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** 订阅单终端输出（幂等 · 未连接时留给 onSocketConnect 补订）。 */
  function subscribe(id: string) {
    const h = handlesRef.current.get(id)
    if (!h || h.sub) return
    const client = clientHolderRef.current?.current
    if (!client?.connected) return
    const topic = `/topic/sessions/${sessionRef.current}/terminal/${id}`
    h.sub = client.subscribe(topic, (msg) => {
      const hh = handlesRef.current.get(id)
      if (!hh) return
      let evt: TerminalOutputEvent
      try { evt = JSON.parse(msg.body) as TerminalOutputEvent } catch { return }
      if (evt.data) hh.term.write(evt.data)
      if (evt.closed) {
        hh.closed = true
        hh.term.write('\r\n\x1b[90m[进程已退出]\x1b[0m\r\n')
        setExited((prev) => (prev[id] ? prev : { ...prev, [id]: true }))
        setTerminals((prev) => prev.map((t) => (t.id === id ? { ...t, alive: false } : t)))
        try { hh.sub?.unsubscribe() } catch { /* 忽略 */ }
        hh.sub = null
      }
    })
  }

  /** 首次 attach：先 snapshot 回放（纯文本，补 \r），再订阅实时帧 —— 顺序不能反。 */
  async function attach(id: string) {
    const h = handlesRef.current.get(id)
    if (!h || h.attached) return
    h.attached = true
    try {
      const snap = await terminalApi.snapshot(sessionRef.current, id)
      // 等待期间实例可能已被销毁/重建（StrictMode 双挂载、切会话）→ 只写回自己的那一份
      if (handlesRef.current.get(id) !== h) return
      const lines = typeof snap?.lines === 'string' ? snap.lines : ''
      if (lines) h.term.write(lines.split('\n').join('\r\n'))
    } catch {
      // 快照失败（旧后端 / 网络）→ 仍订阅实时输出，不回滚 attach
    }
    if (handlesRef.current.get(id) !== h) return
    if (h.closed) h.term.write('\r\n\x1b[90m[进程已退出]\x1b[0m\r\n')
    subscribe(id)
  }

  /** 容器尺寸变化 → fit + POST resize；隐藏（非激活 tab）时跳过。 */
  function fitAndSync(id: string) {
    const h = handlesRef.current.get(id)
    if (!h) return
    if (h.el.offsetParent === null) return
    try { h.fit.fit() } catch { return }
    if (h.term.cols === h.lastCols && h.term.rows === h.lastRows) return
    h.lastCols = h.term.cols
    h.lastRows = h.term.rows
    if (activeRef.current === id) setDims((prev) => (prev.cols === h.term.cols && prev.rows === h.term.rows ? prev : { cols: h.term.cols, rows: h.term.rows }))
    void terminalApi.resize(sessionRef.current, id, h.term.cols, h.term.rows).catch(() => {})
  }

  /** 建 xterm 实例（每个终端一个 · 非激活时容器 hidden，实例仍接输出）。 */
  function registerScreen(info: TerminalInfo, el: HTMLDivElement | null) {
    if (!el) { disposeHandle(info.id); return }
    const existing = handlesRef.current.get(info.id)
    if (existing && existing.el === el) return
    if (existing) disposeHandle(info.id)

    const term = new Terminal({
      theme: TERM_THEME,
      fontFamily: TERM_FONT,
      fontSize: 12.5,
      cursorBlink: true,
      scrollback: 2000,
    })
    const fit = new FitAddon()
    term.loadAddon(fit)
    term.open(el)
    // [2026-10-29] 字体度量延迟：xterm 在系统等宽字体真正可用前测 cell 宽度会偏大
    //   （表现为「字特别大 + 每行列数偏少 + 内容左右被裁」）。字体就绪后再 fit 一次，
    //   另加 120ms 兜底（Chromium/WebView2 字体度量有延迟）。
    const fitAfterFonts = () => { try { fit.fit() } catch { /* 容器尚未布局，忽略 */ } }
    fitAfterFonts()
    try { document.fonts?.ready.then(fitAfterFonts).catch(() => {}) } catch { /* 忽略 */ }
    window.setTimeout(fitAfterFonts, 120)

    const h: TermHandle = {
      term, fit, el,
      ro: null, sub: null,
      inputBuf: '', inputTimer: null,
      closed: !info.alive,
      attached: false,
      lastCols: 0, lastRows: 0,
    }
    handlesRef.current.set(info.id, h)

    // 键盘输入 → 16ms 合并 → POST（中文输入法：xterm 在 composition 结束才发字符，整段一起进缓冲）
    term.onData((data) => {
      const hh = handlesRef.current.get(info.id)
      if (!hh || hh.closed) return
      hh.inputBuf += data
      if (hh.inputTimer != null) return
      hh.inputTimer = setTimeout(() => {
        const cur = handlesRef.current.get(info.id)
        if (!cur) return
        cur.inputTimer = null
        const payload = cur.inputBuf
        cur.inputBuf = ''
        if (payload) void terminalApi.input(sessionRef.current, info.id, payload).catch(() => {})
      }, INPUT_FLUSH_MS)
    })

    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(() => {
        if (el.offsetParent === null) return
        requestAnimationFrame(() => fitAndSync(info.id))
      })
      ro.observe(el)
      h.ro = ro
    }

    fitAndSync(info.id)
    void attach(info.id)
  }

  /** 稳定 ref 回调（见 screenRefCbsRef 注释） */
  function screenRef(id: string) {
    let cb = screenRefCbsRef.current.get(id)
    if (!cb) {
      cb = (el) => {
        const info = terminalsRef.current.find((t) => t.id === id)
        if (info) registerScreen(info, el)
      }
      screenRefCbsRef.current.set(id, cb)
    }
    return cb
  }

  // ---- 切 tab / 列表变化：激活项 fit + 聚焦 ----
  useEffect(() => {
    if (!activeId) return
    const raf = requestAnimationFrame(() => {
      const h = handlesRef.current.get(activeId)
      if (!h) return
      fitAndSync(activeId)
      setDims((prev) => (prev.cols === h.term.cols && prev.rows === h.term.rows ? prev : { cols: h.term.cols, rows: h.term.rows }))
      try { h.term.focus() } catch { /* 忽略 */ }
    })
    return () => cancelAnimationFrame(raf)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeId, terminals.length])

  async function handleCreate() {
    if (creating) return
    setCreating(true)
    try {
      const shell = readPreferredShell()
      let info: TerminalInfo
      if (shell) {
        // 尺寸沿用当前面板（未知则省略，后端按默认 80x24）
        const size = dims.cols > 0 && dims.rows > 0 ? { cols: dims.cols, rows: dims.rows } : {}
        try {
          info = await terminalApi.create(sessionId, { shell, ...size })
        } catch (e) {
          // 400 = 该 shell 已不在本机白名单（换机器 / 卸载）⇒ 回落默认一次，并告知
          if (e instanceof ApiError && e.status === 400) {
            info = await terminalApi.create(sessionId)
            showToast?.('已回落到默认终端', 'info')
          } else {
            throw e
          }
        }
      } else {
        info = await terminalApi.create(sessionId)
      }
      setError(null)
      setTerminals((prev) => [...prev, info])
      setActiveId(info.id)
      setExited((prev) => { const next = { ...prev }; delete next[info.id]; return next })
    } catch (e) {
      const msg = friendlyError(e)
      setError(msg)
      showToast?.(msg, 'info')
    } finally {
      setCreating(false)
    }
  }

  async function handleClose(id: string) {
    try {
      await terminalApi.close(sessionId, id)
    } catch {
      // 404 = 后端已无该终端 → 照常从 UI 移除
    }
    disposeHandle(id)
    setError(null)
    const remaining = terminals.filter((t) => t.id !== id)
    setTerminals(remaining)
    if (activeId === id) setActiveId(remaining[0]?.id ?? null)
  }

  function handleClear() {
    if (!activeId) return
    const h = handlesRef.current.get(activeId)
    try { h?.term.clear() } catch { /* 忽略 */ }
  }

  const active = terminals.find((t) => t.id === activeId) ?? null
  const activeExited = !!activeId && (!!exited[activeId] || (active ? !active.alive : false))

  if (loading) {
    return <div className="term-empty"><span className="term-empty-text">正在加载终端…</span></div>
  }

  if (terminals.length === 0) {
    return (
      <div className="term-empty">
        <span className="term-empty-icon"><TerminalIcon /></span>
        <h3>当前会话还没有终端</h3>
        <p>终端会在本会话绑定的项目目录里启动，切换会话互不影响。Agent 可读取终端输出，经审批后也能创建或写入终端。</p>
        {error && <p className="term-empty-error">{error}</p>}
        <button className="term-create" type="button" onClick={() => void handleCreate()} disabled={creating}>
          <PlusIcon />新建终端
        </button>
      </div>
    )
  }

  return (
    <div className="term-panel">
      <div className="term-tabs">
        {terminals.map((t) => (
          <button
            key={t.id}
            type="button"
            className={`term-tab${t.id === activeId ? ' on' : ''}`}
            title={`${shellLabel(t.shell)} · ${t.cwd}`}
            onClick={() => setActiveId(t.id)}
          >
            <span className="nm">{shellLabel(t.shell)}</span>
            <span
              className="x"
              role="button"
              tabIndex={0}
              aria-label={`关闭终端 ${shellLabel(t.shell)}`}
              onClick={(e) => { e.stopPropagation(); void handleClose(t.id) }}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.stopPropagation(); void handleClose(t.id) } }}
            >
              ×
            </span>
          </button>
        ))}
        <div className="term-actions">
          <button className="term-icon-btn" type="button" title="新建终端" aria-label="新建终端" onClick={() => void handleCreate()} disabled={creating}>
            <PlusIcon />
          </button>
          <button className="term-icon-btn" type="button" title="清屏" aria-label="清屏" onClick={handleClear}>
            <ClearIcon />
          </button>
          <button
            className="term-icon-btn"
            type="button"
            title="关闭当前终端"
            aria-label="关闭当前终端"
            onClick={() => { if (activeId) void handleClose(activeId) }}
          >
            <CloseIcon />
          </button>
        </div>
      </div>
      {active && <div className="term-cwd" title={active.cwd}>{active.cwd}</div>}
      <div className="term-screens">
        {terminals.map((t) => (
          <div key={t.id} className="term-screen" hidden={t.id !== activeId}>
            {/* [2026-10-29] xterm 挂在内层（无 padding）：padding 放在外层，
                否则 canvas 相对 padding box 定位会整体左移、左侧字符被裁。 */}
            <div className="term-screen-inner" ref={screenRef(t.id)} />
          </div>
        ))}
      </div>
      <div className="term-status">
        <span>
          <span className={`dot${activeExited ? ' off' : ''}`} />
          {active ? shellLabel(active.shell) : '终端'}
          {activeExited ? ' · 已退出' : ' · 运行中'}
          {sessionRunning ? ' · 会话运行中' : ''}
        </span>
        {activeExited && <span className="hint">进程已退出，输入已禁用</span>}
        <span className="right">{dims.cols > 0 ? `${dims.cols} × ${dims.rows}` : ''}</span>
      </div>
    </div>
  )
}
