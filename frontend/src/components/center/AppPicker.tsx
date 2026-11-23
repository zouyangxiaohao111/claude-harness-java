/**
 * 输入框工具区「应用多选」面板（样式原值照搬 Octop · 只读参考 `D:/tmp/octop-src`）。
 *
 * <p><b>形态</b>：Popover 弹层 + 逐行多选（行整体是 checkbox，右侧是自绘的小开关指示——
 * ⛔ 不用单选 pill，也 ⛔ 不引入 AntD Switch；本仓自绘，窄栏（<700px）收文字规则不影响图标）。
 *
 * <p><b>职责边界</b>：本组件只渲染 + 上报「点了哪一行」；选中集与 localStorage 记忆在调用方
 * （Composer 受控/内部态），目录拉取也在调用方（惰性）。⛔ 本组件不发请求。
 */
import { useMemo, useState } from 'react'
import { APP_KIND_LABEL, type AppEntry } from '@/api/apps'
import '@/styles/app-picker.css'

export interface AppPickerProps {
  apps: AppEntry[]
  /** 会话级**已启动**的应用 key（独立开关 = 启动/停止 → 进 schema） */
  startedKeys: string[]
  /** 本轮**强调**的应用 key（点行切换；只进发送体，不改 schema） */
  emphasizedKeys: string[]
  /** 独立开关：启动/停止（回调收到单个 key，整表替换由调用方做） */
  onToggleStart: (key: string) => void
  /** 点行：选择/取消**本轮强调**（只影响通知文案） */
  onToggleEmphasis: (key: string) => void
  /** 启动中（不可用但有 canStart 的应用）· 行尾开关显示「启动中」 */
  startingKeys?: string[]
  /** 启动失败原因（key → 真实错误文案）· 行尾开关回落可重试 */
  startErrors?: Record<string, string>
  /** 启动不可用应用（后端 MCP server）· 仅 canStart+serverId 时可用 */
  onStartApp?: (key: string) => void
  loading?: boolean
  /** 目录加载失败的文字（真实原因，⛔ 不用「加载失败」糊过去） */
  error?: string | null
  onRetry?: () => void
  /** 打开「连接器」管理页（空态与页脚入口）；未传则不渲染入口 */
  onOpenConnectors?: () => void
}

/** 面板本体（锚点/外点关闭由调用方持有） */
export function AppPicker({ apps, startedKeys, emphasizedKeys, onToggleStart, onToggleEmphasis, startingKeys, startErrors, onStartApp, loading, error, onRetry, onOpenConnectors }: AppPickerProps) {
  const [q, setQ] = useState('')
  /**
   * [2026-11-23 用户裁定] 浏览器 / 电脑操作 **不走应用指定**，回工具面板开合
   * ⇒ 本面板只列 MCP 应用。后端后续也会移除静态条目，这里先兜住（⛔ 不展示选不了的条目）。
   */
  const mcpApps = useMemo(() => apps.filter((a) => a.kind === 'mcp'), [apps])
  /** 已展开工具清单的应用 key（可同时展开多个；不可用的应用也能展开看工具） */
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const toggleExpanded = (key: string) => {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }
  const started = useMemo(() => new Set(startedKeys), [startedKeys])
  const emphasized = useMemo(() => new Set(emphasizedKeys), [emphasizedKeys])
  const starting = useMemo(() => new Set(startingKeys ?? []), [startingKeys])
  /** 图标加载失败的应用 key ⇒ 回落首字母色块（⛔ 不显示裂图） */
  const [failedIcons, setFailedIcons] = useState<Set<string>>(new Set())
  const markIconFailed = (key: string) => {
    setFailedIcons((prev) => {
      if (prev.has(key)) return prev
      const next = new Set(prev)
      next.add(key)
      return next
    })
  }
  const query = q.trim().toLowerCase()
  const list = useMemo(() => {
    if (!query) return mcpApps
    return mcpApps.filter((a) => (
      a.name.toLowerCase().includes(query)
      || a.key.toLowerCase().includes(query)
      || (a.description ?? '').toLowerCase().includes(query)
    ))
  }, [mcpApps, query])

  return (
    <div className="app-picker-panel" data-testid="app-picker-panel" role="group" aria-label="选择应用">
      <div className="app-picker-search">
        <input
          className="app-picker-search-input"
          type="text"
          value={q}
          placeholder="搜索应用"
          aria-label="搜索应用"
          onChange={(e) => setQ(e.target.value)}
          // 打开即聚焦搜索框（键盘直接筛，不用先 Tab）
          autoFocus
        />
        <svg className="app-picker-search-ico" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
          <circle cx="6" cy="6" r="4" />
          <path d="M9 9l3 3" strokeLinecap="round" />
        </svg>
      </div>

      <div className="app-picker-list">
        {loading && <div className="app-picker-empty">正在加载应用…</div>}

        {!loading && error && (
          <div className="app-picker-empty">
            <span>{error}</span>
            {onRetry && (
              <button type="button" className="app-picker-link" onClick={onRetry}>重试</button>
            )}
          </div>
        )}

        {!loading && !error && mcpApps.length === 0 && (
          <div className="app-picker-empty">
            <span>还没有可用的应用</span>
            {onOpenConnectors && (
              <button type="button" className="app-picker-link" onClick={onOpenConnectors}>去接入连接器</button>
            )}
          </div>
        )}

        {!loading && !error && mcpApps.length > 0 && list.length === 0 && (
          <div className="app-picker-empty">没有匹配的应用</div>
        )}

        {!loading && !error && list.map((app) => {
          // 两个动作分开：独立开关 = 启动/停止（进 schema）；点行 = 本轮强调（只影响通知）
          const isStarted = app.usable && started.has(app.key)
          const isEmphasized = app.usable && emphasized.has(app.key)
          /** 不可用但后端允许启动（canStart + serverId）⇒ 开关仍可点（真启动，不是假交互） */
          const startable = !app.usable && !!app.canStart && !!app.serverId && !!onStartApp
          const isStarting = starting.has(app.key)
          const startError = startErrors?.[app.key]
          const iconSrc = app.icon
          const showIcon = !!iconSrc && !failedIcons.has(app.key)
          const toolNames = app.tools ?? []
          const canExpand = toolNames.length > 0
          const isExpanded = expanded.has(app.key)
          return (
            <div key={app.key} className="app-picker-item">
              <div
                role="checkbox"
                aria-checked={isEmphasized}
                aria-disabled={app.usable ? undefined : true}
                tabIndex={app.usable ? 0 : -1}
                className={`app-picker-row${isEmphasized ? ' on' : ''}${app.usable ? '' : ' off'}`}
                data-app-key={app.key}
                title={app.usable
                  ? (isEmphasized ? `取消本轮强调：${app.name}` : `本轮强调：${app.name}（只影响通知文案）`)
                  : `${app.name}：${app.unusableReason}`}
                onClick={() => { if (app.usable) onToggleEmphasis(app.key) }}
                onKeyDown={(e) => {
                  if (!app.usable) return
                  if (e.key !== 'Enter' && e.key !== ' ') return
                  e.preventDefault()
                  onToggleEmphasis(app.key)
                }}
              >
              <span className="app-picker-avatar" aria-hidden="true">
                {showIcon ? (
                  // 有图标就渲染 img；加载失败（onError）切回首字母色块
                  <img
                    className="app-picker-avatar-img"
                    src={iconSrc}
                    alt=""
                    draggable={false}
                    onError={() => markIconFailed(app.key)}
                  />
                ) : (
                  (app.name.trim()[0] ?? '?').toUpperCase()
                )}
              </span>
              <span className="app-picker-text">
                <span className="app-picker-title">
                  <span className="app-picker-name">{app.name}</span>
                  {/* 类别徽章（当前只会有 MCP；保留映射以兼容后端返回的其它 kind） */}
                  <span className={`app-picker-badge kind-${app.kind}`}>{APP_KIND_LABEL[app.kind]}</span>
                  {typeof app.toolCount === 'number' && (
                    <span className="app-picker-tools">{app.toolCount} 个工具</span>
                  )}
                </span>
                <span className="app-picker-desc">
                  {(() => {
                    // 状态副标题：启动中 / 启动失败 / 已启动 / 已停止 / 后端不可用原因
                    if (isStarting) return '启动中…'
                    if (startError) return `启动失败：${startError}`
                    const status = app.usable ? (isStarted ? '已启动' : '已停止') : (app.unusableReason ?? '不可用')
                    return app.description ? `${status} · ${app.description}` : status
                  })()}
                </span>
              </span>
              {/* 展开工具清单（不可用行也能展开）· 独立按钮，⛔ 不触发行选择 */}
              {canExpand && (
                <button
                  type="button"
                  className={`app-picker-expand${isExpanded ? ' open' : ''}`}
                  aria-expanded={isExpanded}
                  aria-label={`${isExpanded ? '收起' : '查看'}${app.name}的工具`}
                  title={`${isExpanded ? '收起' : '查看'}工具清单（${toolNames.length}）`}
                  onClick={(e) => { e.stopPropagation(); toggleExpanded(app.key) }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') e.stopPropagation()
                  }}
                >
                  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M3 4.5L6 7.5L9 4.5" />
                  </svg>
                </button>
              )}
              {/* 独立开关 = 启动/停止（写会话 enabledApps，进 schema）；⛔ 与行点击（强调）分开。
                  不可用但后端允许启动（canStart+serverId）时也可点：真调启动，不假装可用。 */}
              <button
                type="button"
                role="switch"
                className={`app-picker-sw${isStarted ? ' on' : ''}${isStarting ? ' starting' : ''}`}
                aria-checked={isStarted}
                aria-disabled={app.usable || startable ? undefined : true}
                disabled={(!app.usable && !startable) || isStarting}
                aria-label={`${isStarted ? '停止' : '启动'}：${app.name}`}
                title={
                  app.usable
                    ? (isStarted ? `停止 ${app.name}（停止后不再进入工具集）` : `启动 ${app.name}（启动后才进工具集）`)
                    : startable
                      ? (startError ? `启动失败：${startError}（点击重试）` : `启动 ${app.name}（当前${app.unusableReason ?? '不可用'}）`)
                      : `${app.name}：${app.unusableReason}`
                }
                onClick={(e) => {
                  e.stopPropagation()
                  if (app.usable) onToggleStart(app.key)
                  else if (startable) onStartApp?.(app.key)
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') e.stopPropagation()
                }}
              ><i /></button>
              </div>
              {isExpanded && (
                <div className="app-picker-tool-list" data-testid="app-tool-list" data-app-key={app.key}>
                  {toolNames.map((name) => (
                    <code key={name} className="app-picker-tool">{name}</code>
                  ))}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {onOpenConnectors && (
        <button type="button" className="app-picker-footer" onClick={onOpenConnectors}>
          管理连接器
        </button>
      )}
    </div>
  )
}
