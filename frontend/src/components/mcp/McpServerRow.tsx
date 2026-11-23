import type { ReactNode } from 'react'
import type { McpServer } from '@/api/types'
import { CONN_STATE, RUNTIME_LABEL } from '@/components/modals/mcpStatus'

/**
 * MCP 服务器列表行（**唯一实现**）。
 *
 * <p>设置 → MCP 管理 与 市场「自定义连接器」共用，DOM/类名沿用 MCPPanel 原有结构：
 * `.cli-card > .mcp-card-main`（点击区 + 启用开关，nowrap 一整行）→ `.cli-config`（展开内容，独占一行）。
 *
 * <p><b>为什么包一层 `.mcp-card-main`</b>：`.cli-card` 是 `flex-wrap: wrap`（展开区需要
 * `flex-basis:100%` 独占一行）。窄宽（13 寸）下如果把点击区和开关直接放在 wrap 容器里，
 * 长命令会把开关挤到第二行 —— 头部必须 nowrap，换行只允许发生在展开区。
 */
export function McpServerRow({
  server, open, pending = false, onToggleOpen, onToggleEnabled, children, testId,
}: {
  server: McpServer
  open: boolean
  pending?: boolean
  onToggleOpen: () => void
  /** 启用开关（省略 = 不渲染开关） */
  onToggleEnabled?: () => void
  /** 展开内容（由调用方决定：详情 / 编辑表单 / 删除确认等） */
  children?: ReactNode
  /** 行根节点 testid（市场自定义连接器列表用 custom-connector-row） */
  testId?: string
}) {
  const m = server
  const type = m.type ?? 'stdio'
  const remote = type !== 'stdio'
  // [V295] 运行时状态优先（后端现算，不落库）；老后端无 runtimeStatus → 回落三态映射
  const conn = m.runtimeStatus ? RUNTIME_LABEL[m.runtimeStatus] : (CONN_STATE[m.status] ?? CONN_STATE.stopped)
  const isConnected = m.runtimeStatus ? m.runtimeStatus === 'connected' : m.status === 'running'
  const desc = remote
    ? `URL: ${m.url ?? m.command}`
    : `Command: ${m.command}${m.args && m.args.length > 0 ? ' ' + m.args.join(' ') : ''}`
  const display = m.userFacingName ?? m.name
  const scopeText = m.scope ?? (remote ? 'remote' : 'local')
  const approvalNote = m.approvalStatus === 'pending'
    ? '待审批 · '
    : m.approvalStatus === 'rejected' ? '已拒绝 · ' : ''

  return (
    <div className={`cli-card mcp-card${open ? ' is-open' : ''}${m.enabled ? '' : ' is-off'}`} data-testid={testId}>
      <div className="mcp-card-main">
        <div
          role="button"
          tabIndex={0}
          className="cli-card-hit"
          aria-expanded={open}
          onClick={onToggleOpen}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              onToggleOpen()
            }
          }}
        >
          <span className="mcp-card-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
                 strokeLinecap="round" strokeLinejoin="round">
              <rect x="3.5" y="4.5" width="17" height="6" rx="2" />
              <rect x="3.5" y="13.5" width="17" height="6" rx="2" />
              <path d="M7 7.5h.01M7 16.5h.01" />
            </svg>
          </span>
          <span className="cli-card-body">
            <span className="cli-card-name">
              <span className="cli-card-title">{display}</span>
              <span className="cli-card-divider" aria-hidden="true">·</span>
              <span className="cli-card-tagline">{type} · {scopeText}</span>
              {isConnected && <span className="cli-dot" title="已连接" aria-hidden="true" />}
            </span>
            <span className="cli-card-meta" title={`${approvalNote}${m.enabled && m.autoStart ? '自启 · ' : ''}${conn.label} · ${desc}`}>
              {approvalNote}{m.enabled && m.autoStart ? '自启 · ' : ''}{conn.label} · {desc}
            </span>
          </span>
          <svg
            className="cli-card-chevron"
            viewBox="0 0 12 12"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M3 4.5L6 7.5L9 4.5" />
          </svg>
        </div>

        {/* 启用开关放在点击区外：它是独立动作，嵌在整行点击区里容易误触 */}
        {onToggleEnabled && (
          <div className="mcp-card-tail">
            <span className="mcp-switch-label">启用</span>
            <label className="settings-switch" title={m.enabled ? '停用' : '启用'}>
              <input
                type="checkbox"
                checked={m.enabled}
                onChange={onToggleEnabled}
                disabled={pending}
              />
              <span></span>
            </label>
          </div>
        )}
      </div>

      {open && <div className="cli-config">{children}</div>}
    </div>
  )
}
