import { useEffect, useState } from 'react'
import { invoke, isTauri } from '@tauri-apps/api/core'
import type { FontSize, ThemeMode } from '@/types'
import { settingsApi } from '@/api/settings'
import { terminalApi } from '@/api/terminal'
import { ApiError } from '@/api/rest'

/**
 * 基础设置（Apple 风格轻量弹窗）。
 *
 * <p>与 {@link SettingsModal} 的关系：本弹窗只承载最高频的四项 —— 外观 / 字体大小 /
 * 本地数据目录 / 关于；「高级设置」按钮把用户交回现有完整设置，⛔ 不改 SettingsModal 内部。
 *
 * <p><b>状态来源</b>：外观与字号**直接接现有 settings reducer**（props 由 App 传入），
 * 写回沿用 `settingsApi.update`；挂载时读取一次 `settingsApi.get`，避免在从未打开过完整设置
 * 的会话里把分段控件显示成 reducer 初值（浅色 / 中）而与后端实际值不一致。
 */

interface BasicSettingsModalProps {
  theme: ThemeMode
  setTheme: (t: ThemeMode) => void
  fontSize: FontSize
  setFontSize: (s: FontSize) => void
  /** 本地数据目录（GET /settings 的只读 configHome；设置尚未加载时由 App 传 ~/.nexusai） */
  dataDir: string
  /** 关闭基础设置并打开现有完整设置弹窗（App 处理） */
  onOpenAdvanced: () => void
  close: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}

const THEMES: { value: ThemeMode; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'auto', label: '跟随系统' },
]

const FONT_SIZES: { value: FontSize; label: string }[] = [
  { value: 'small', label: '小' },
  { value: 'medium', label: '中' },
  { value: 'large', label: '大' },
]

/** 默认终端 shell 的本地记忆键（TerminalPanel 创建终端时读同一个 key） */
const SHELL_STORAGE_KEY = 'nexusai.terminal.shell'

export function BasicSettingsModal({
  theme,
  setTheme,
  fontSize,
  setFontSize,
  dataDir,
  onOpenAdvanced,
  close,
  showToast,
}: BasicSettingsModalProps) {
  const [version, setVersion] = useState('')
  const [shellOptions, setShellOptions] = useState<{ value: string; label: string }[]>([])
  const [shell, setShell] = useState<string>(() => {
    try { return localStorage.getItem(SHELL_STORAGE_KEY) ?? '' } catch { return '' }
  })

  // 挂载时从后端同步一次（与 SettingsModal 同一读源）：后端未就绪则沿用当前 reducer 值，不打扰。
  useEffect(() => {
    let cancelled = false
    settingsApi
      .get()
      .then((s) => {
        if (cancelled) return
        if (s.theme) setTheme(s.theme)
        if (s.fontSize) setFontSize(s.fontSize)
      })
      .catch(() => { /* 后端未就绪：保留本地状态 */ })
    return () => {
      cancelled = true
    }
    // 只在挂载时同步一次；setTheme/setFontSize 是 App 内联回调，纳入依赖会导致每次渲染重发 GET。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 终端 shell 列表：挂载拉一次；后端未就绪静默失败，下拉只剩「默认」一项。
  useEffect(() => {
    let cancelled = false
    terminalApi
      .shells()
      .then((res) => {
        if (cancelled) return
        const options = Array.isArray(res?.shells) ? res.shells : []
        setShellOptions(options)
        setShell((prev) => {
          const wanted = prev || res?.default || ''
          // 存过的值已不在本机（换机器 / 卸载）⇒ 回落接口给的默认值
          if (wanted && options.some((o) => o.value === wanted)) return wanted
          return res?.default || ''
        })
      })
      .catch(() => { /* 后端未就绪：下拉只剩「默认」项 */ })
    return () => {
      cancelled = true
    }
  }, [])

  // 版本：安装包版本（Tauri 命令读 package_info，见 src-tauri/src/updater.rs）。
  // 浏览器 dev 无壳 ⇒ 显示「开发版」，不伪造版本号。
  useEffect(() => {
    if (!isTauri()) {
      setVersion('开发版')
      return
    }
    let cancelled = false
    invoke<string>('app_version')
      .then((v) => { if (!cancelled) setVersion(v) })
      .catch(() => { if (!cancelled) setVersion('未知') })
    return () => { cancelled = true }
  }, [])

  const persist = (req: Parameters<typeof settingsApi.update>[0], failLabel: string) => {
    settingsApi.update(req).catch((e) => {
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      showToast(`${failLabel}失败：${msg}`, 'info')
    })
  }

  const pickTheme = (t: ThemeMode) => {
    setTheme(t)
    persist({ theme: t }, '保存外观')
  }

  const pickFontSize = (s: FontSize) => {
    setFontSize(s)
    persist({ fontSize: s }, '保存字体大小')
  }

  /** 默认终端只存本地（后端按请求参数取 shell，没有全局配置项可写）。 */
  const pickShell = (value: string) => {
    setShell(value)
    try { localStorage.setItem(SHELL_STORAGE_KEY, value) } catch { /* 隐私模式禁写：仅本次生效 */ }
  }

  /** 打开本地数据目录：复用壳已有的 `opener:allow-reveal-item-in-dir` 权限（与右栏文件树同一入口）。
   *  浏览器 / e2e 无桌面壳 ⇒ 复制路径兜底。 */
  const openDataDir = async () => {
    if (!isTauri()) {
      try { await navigator.clipboard.writeText(dataDir) } catch { /* ignore */ }
      showToast('浏览器环境无法打开数据目录，已复制路径', 'info')
      return
    }
    try {
      const { revealItemInDir } = await import('@tauri-apps/plugin-opener')
      await revealItemInDir(dataDir)
    } catch (e) {
      showToast(`打开数据目录失败：${e instanceof Error ? e.message : String(e)}`, 'info')
    }
  }

  return (
    <div className="bs-backdrop" onClick={close}>
      <div
        className="bs-sheet"
        role="dialog"
        aria-modal="true"
        aria-label="基础设置"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="bs-head">
          <span className="bs-title">设置</span>
          <button className="bs-x" onClick={close} title="关闭" aria-label="关闭">
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M3 3L11 11M11 3L3 11" />
            </svg>
          </button>
        </div>

        <div className="bs-body">
          <div className="bs-row">
            <div className="bs-main">
              <div className="bs-label">外观</div>
              <div className="bs-desc">跟随系统或固定浅色 / 深色</div>
            </div>
            <div className="bs-seg" role="group" aria-label="外观">
              {THEMES.map((t) => (
                <button
                  key={t.value}
                  className={theme === t.value ? 'on' : ''}
                  aria-pressed={theme === t.value}
                  onClick={() => pickTheme(t.value)}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </div>

          <div className="bs-row">
            <div className="bs-main">
              <div className="bs-label">字体大小</div>
              <div className="bs-desc">列表与对话正文</div>
            </div>
            <div className="bs-seg" role="group" aria-label="字体大小">
              {FONT_SIZES.map((s) => (
                <button
                  key={s.value}
                  className={fontSize === s.value ? 'on' : ''}
                  aria-pressed={fontSize === s.value}
                  onClick={() => pickFontSize(s.value)}
                >
                  {s.label}
                </button>
              ))}
            </div>
          </div>

          <div className="bs-row">
            <div className="bs-main">
              <div className="bs-label">默认终端</div>
              <div className="bs-desc">新终端使用哪个 shell（只列本机已安装的）</div>
              <div className="bs-desc">已打开的终端不受影响，重开生效</div>
            </div>
            <select
              className="bs-select"
              aria-label="默认终端"
              value={shell}
              onChange={(e) => pickShell(e.target.value)}
            >
              {shellOptions.length === 0 ? (
                <option value="">默认</option>
              ) : (
                shellOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)
              )}
            </select>
          </div>

          <div className="bs-row">
            <div className="bs-main">
              <div className="bs-label">本地数据</div>
              <div className="bs-desc bs-path" title={dataDir}>{dataDir}</div>
            </div>
            <button className="bs-pill" onClick={() => void openDataDir()}>打开目录</button>
          </div>

          <div className="bs-row">
            <div className="bs-main">
              <div className="bs-label">关于</div>
              <div className="bs-desc">NexusAI {version || '—'} · 本机运行</div>
            </div>
            <button
              className="bs-pill"
              onClick={() => {
                if (typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window) {
                  window.dispatchEvent(new Event('nexusai:check-update'))
                } else {
                  showToast('更新仅在桌面端可用', 'info')
                }
              }}
            >
              更新
            </button>
          </div>
        </div>

        <div className="bs-foot">
          <button className="bs-ghost" onClick={onOpenAdvanced}>高级设置</button>
          <button className="bs-primary" onClick={close}>完成</button>
        </div>
      </div>
    </div>
  )
}
