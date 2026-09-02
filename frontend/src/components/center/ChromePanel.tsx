import { useCallback, useEffect, useState } from 'react'
import { chromeExtensionDir, installChromeExtension, isChromeInstalled } from '@/utils/chromeExtension'
import { api } from '@/api/rest'

/**
 * NexusAI in Chrome 连接面板（FNT-BROWSER-01）· 命令 /chrome
 *
 * 展示 Chrome 扩展连接引导（全局连接：popup 点「连接」一次，所有会话可用）+ 当前会话
 * sessionId（调试辅助，仅用于排查）。
 * 连接状态本身在扩展 popup 内可见（background 广播 ws-status）；
 * 后端暂无浏览器连接查询端点，故本面板为静态引导 + 会话标识透出。
 */
export function ChromePanel({ sessionId, onClose }: { sessionId: string; onClose: () => void }) {
  const [copied, setCopied] = useState(false)
  // FNT-BROWSER-02：浏览器扩展安装/状态（按钮触发检查，不做启动自动弹窗）
  const [extDir, setExtDir] = useState<string | null>(null)
  const [chromeInstalled, setChromeInstalled] = useState<boolean | null>(null)
  const [checking, setChecking] = useState(false)
  const [installing, setInstalling] = useState(false)
  const [installResult, setInstallResult] = useState<{ ok: boolean; msg: string } | null>(null)
  // 装过后本地标记（避免每次重复提示「已安装」）
  const [previouslyInstalled, setPreviouslyInstalled] = useState(() => !!localStorage.getItem('nexusai_chrome_extension_installed'))
  // [browser-status] 后端 WS 连接状态（GET /api/v1/browser/status · 全局连接：扩展 popup 点「连接」后为 true）
  const [wsConnected, setWsConnected] = useState<boolean | null>(null)

  const copySessionId = async () => {
    if (!sessionId) return
    try {
      await navigator.clipboard.writeText(sessionId)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      setCopied(false)
    }
  }

  // 检查浏览器状态：invoke is_chrome_installed + chrome_extension_dir，刷新面板内联区
  const checkStatus = useCallback(async () => {
    setChecking(true)
    try {
      const [dir, ok] = await Promise.all([chromeExtensionDir(), isChromeInstalled()])
      setExtDir(dir)
      setChromeInstalled(ok)
    } finally {
      setChecking(false)
    }
  }, [])

  // 挂载时自动查一次状态（面板打开即有结果；也可点「检查浏览器状态」重新检测）
  useEffect(() => {
    void checkStatus()
  }, [checkStatus])

  // [browser-status] 轮询后端 WS 连接状态（真实区分「扩展已加载 / WS 已连接」，不靠 localStorage 记忆）
  const checkWs = useCallback(async () => {
    try {
      const { connected } = await api<{ connected: boolean }>('/browser/status')
      setWsConnected(connected)
    } catch {
      setWsConnected(null) // 后端不可达（dev 未起 / 打包后未启动）→ 未知
    }
  }, [])

  useEffect(() => {
    void checkWs()
    const timer = setInterval(() => void checkWs(), 3000) // 3s 轮询，扩展连接/断开即时反馈
    return () => clearInterval(timer)
  }, [checkWs])

  // 一键安装：Rust 负责启动 Chrome + --load-extension，返回人类可读结果
  const handleInstall = async () => {
    setInstalling(true)
    setInstallResult(null)
    try {
      const msg = await installChromeExtension()
      setInstallResult({ ok: true, msg })
      localStorage.setItem('nexusai_chrome_extension_installed', '1')
      setPreviouslyInstalled(true)
      // 安装成功后刷新状态（Chrome 状态可能随之变化）
      void checkStatus()
    } catch (e) {
      setInstallResult({ ok: false, msg: e instanceof Error ? e.message : String(e) })
    } finally {
      setInstalling(false)
    }
  }

  const steps: { title: string; desc: string }[] = [
    { title: '安装 Chrome 扩展（持久化）', desc: '点「一键安装」：Chrome 未运行会自动冷启动加载；Chrome 运行中会打开 chrome://extensions 引导「开发者模式 → 加载已解压的扩展程序 → 选择扩展目录」——此方式 Chrome 重启后仍保留（命令行 --load-extension 重启会失效）。' },
    { title: '打开扩展面板点「连接」', desc: '点击浏览器右上角扩展图标（拼图），在 NexusAI in Chrome popup 中点击「连接」一次（无需填写 sessionId）。' },
    { title: '全局连接已建立', desc: '扩展连上 ws://localhost:3458/ws/browser（hello 不带 sessionId），一个连接服务所有会话；面板「扩展 WS 连接」显示已连接。' },
    { title: '在任意会话中使用', desc: '后端按 sessionId 路由浏览器工具调用，每个会话自动分配自己的浏览器标签页（对齐 CCB「每个对话自己的新 tab」）。' },
  ]

  return (
    <div className="fm-backdrop" onClick={onClose}>
      <div className="fm-modal" style={{ width: 560 }} onClick={(e) => e.stopPropagation()}>
        <div className="fm-header">
          <svg width={16} height={16} viewBox="0 0 14 14" fill="none" stroke="var(--accent)" strokeWidth={1.5} style={{ flexShrink: 0 }}>
            <rect x="1.5" y="2.5" width="11" height="9" rx="1.2" />
            <path d="M1.5 4.5h11" />
            <circle cx="7" cy="8.2" r="1.6" />
          </svg>
          <div className="fm-title">NexusAI in Chrome</div>
          <span className="fm-subtitle">/chrome</span>
        </div>

        <div className="fm-body">
          <div className="fm-field-hint">
            浏览器自动化扩展。后端 18 个 <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>mcp__nexusai-in-chrome__*</span> 工具
            经扩展在 Chrome 中执行（read_page / find / form_input / computer / navigate …）。
          </div>

          {/* FNT-BROWSER-02：一键安装扩展（桌面端 Tauri 生效；浏览器 dev 显示不可用） */}
          <div
            style={{
              display: 'flex', flexDirection: 'column', gap: 8, padding: '10px 12px', marginBottom: 14,
              background: 'var(--surface-2)', border: '1px solid var(--hairline)', borderRadius: 'var(--r-md)',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink)' }}>浏览器扩展安装</span>
              <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
                <button className="fm-btn" onClick={() => void checkStatus()} disabled={checking} style={{ flexShrink: 0 }}>
                  {checking ? '检查中…' : '检查浏览器状态'}
                </button>
                <button className="fm-btn primary" onClick={handleInstall} disabled={installing} style={{ flexShrink: 0 }}>
                  {installing ? '启动中…' : '一键安装扩展'}
                </button>
              </div>
            </div>
            {previouslyInstalled && !installResult && (
              <div style={{ fontSize: 11, color: 'var(--accent)', lineHeight: 1.5 }}>
                此前已一键安装过；若扩展未生效可重新安装。
              </div>
            )}
            {extDir !== null && (
              <div style={{ fontSize: 11, color: 'var(--ink-muted)', lineHeight: 1.5, overflowWrap: 'anywhere' }}>
                扩展资源路径：{extDir
                  ? <span style={{ fontFamily: 'var(--font-mono)', fontSize: 10.5 }}>{extDir}</span>
                  : '（未找到，桌面端打包后随安装包分发到 resources/extension）'}
              </div>
            )}
            <div style={{ fontSize: 11, color: 'var(--ink-muted)', lineHeight: 1.6 }}>
              <div>
                Chrome 浏览器：{chromeInstalled === null ? '（桌面端检测）' : chromeInstalled ? '已安装 ✓' : '未检测到，请先安装 Chrome'}
              </div>
              <div style={{ marginTop: 3 }}>
                扩展 WS 连接：
                {wsConnected === null
                  ? '（查询中 / 后端不可达）'
                  : wsConnected
                    ? <span style={{ color: '#34d399' }}>已连接 ✓（全局连接，所有会话可用）</span>
                    : <span style={{ color: '#e05c5c' }}>未连接 —— 点浏览器右上角扩展 popup「连接」</span>}
              </div>
            </div>
            {installResult && (
              <div style={{ fontSize: 11, lineHeight: 1.5, color: installResult.ok ? 'var(--accent)' : '#e05c5c', overflowWrap: 'anywhere' }}>
                {installResult.ok ? '✓ ' : '✗ '}{installResult.msg}
              </div>
            )}
          </div>

          {/* 会话标识（调试辅助）：全局连接下无需复制 sessionId 到扩展，仅排障时参考 */}
          <div className="fm-field" style={{ marginBottom: 14 }}>
            <div className="fm-field-label">当前会话 sessionId（调试辅助）</div>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
              <input
                readOnly
                value={sessionId || '（未进入会话）'}
                onFocus={(e) => e.currentTarget.select()}
                style={{
                  flex: 1, background: 'var(--surface-2)', border: '1px solid var(--hairline)', borderRadius: 'var(--r-sm)',
                  color: 'var(--ink-muted)', fontFamily: 'var(--font-mono)', fontSize: 12, padding: '7px 10px', outline: 'none',
                }}
              />
              <button className="fm-btn" onClick={copySessionId} disabled={!sessionId} style={{ flexShrink: 0 }}>
                {copied ? '已复制' : '复制'}
              </button>
            </div>
          </div>

          {/* 连接状态说明 */}
          <div
            style={{
              display: 'flex', alignItems: 'flex-start', gap: 8, padding: '10px 12px', marginBottom: 14,
              background: 'var(--surface-2)', border: '1px solid var(--hairline)', borderRadius: 'var(--r-md)',
            }}
          >
            <span
              style={{
                flexShrink: 0, width: 8, height: 8, borderRadius: '50%', marginTop: 4,
                background: 'var(--ink-faint)', boxShadow: '0 0 0 3px var(--surface-2)',
              }}
            />
            <span style={{ fontSize: 11.5, color: 'var(--ink-muted)', lineHeight: 1.6 }}>
              连接状态在扩展 popup 内查看（绿点=已连接）。全局连接：扩展 popup 点「连接」一次，所有会话即可用；后端按 sessionId 为每个会话分配独立浏览器标签页。
            </span>
          </div>

          {/* 使用步骤 */}
          <div className="fm-field-label" style={{ marginBottom: 8 }}>使用步骤</div>
          <ol style={{ margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 8 }}>
            {steps.map((s, i) => (
              <li key={s.title} style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                <span
                  style={{
                    flexShrink: 0, width: 18, height: 18, borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center',
                    background: 'var(--surface-2)', border: '1px solid var(--hairline)', color: 'var(--ink-muted)',
                    fontFamily: 'var(--font-mono)', fontSize: 10,
                  }}
                >
                  {i + 1}
                </span>
                <span style={{ fontSize: 12, lineHeight: 1.6, color: 'var(--ink-muted)' }}>
                  <span style={{ color: 'var(--ink)', fontWeight: 500 }}>{s.title}</span>
                  <span style={{ marginLeft: 6 }}>{s.desc}</span>
                </span>
              </li>
            ))}
          </ol>
        </div>

        <div className="fm-footer">
          <button className="fm-btn primary" onClick={onClose}>知道了</button>
        </div>
      </div>
    </div>
  )
}
