import type { ConnectorDto } from '@/api/connectors'
import { connectorLogo } from '@/assets/connectors'
import {
  HIDDEN_BADGE, HIDDEN_REASON,
  STATUS_LABEL, TIER_NOTE, categoryLabel, connectorColor, effectiveTier, primaryActionLabel, statusClass,
} from './connectorMeta'
import './marketConnector.css'

/**
 * 连接器卡（技能市场「连接器」tab · 内置）。
 *
 * <p>分区固定（Octop 结构），任何长文本都不许把卡片撑破：
 * <ol>
 *   <li>头部：44 图标容器（40 图）+ 标题（15/600 单行省略）+ 分类 chip（10px）+ 隐藏徽标；</li>
 *   <li>描述：13/1.45，**固定两行**（超出省略，`title` 兜底全文）；</li>
 *   <li>底部：状态胶囊 + 提示（单行省略）+ 右侧动作按钮（`flex:none`）。</li>
 * </ol>
 *
 * <p>分档：`hidden` 条目（qcc/tencent-ardot）→ unavailable（置灰、不可点、标「内部/企业」）；
 * 其余按 `supportTier`（缺失才按 authType 推导）。⛔ 连接器不是插件，没有安装路径。
 */
export function ConnectorCard({ connector, onOpen }: {
  connector: ConnectorDto
  /** 打开授权/凭证抽屉 */
  onOpen: () => void
}) {
  const nm = connector.name || connector.key
  const tier = effectiveTier(connector)
  const disabled = tier === 'unavailable'
  const connected = connector.connected && connector.status !== 'reauth_required'
  const accent = connector.color || connectorColor(nm)
  // 图标：优先后端 icon key，其次 connector key，最后首字母
  const logo = connectorLogo(connector.icon, connector.key)
  const catLabel = categoryLabel(connector.category)
  const statusCls = connected ? 'connected' : statusClass(connector.status)
  const actionLabel = disabled ? '暂不支持' : connected ? '管理' : primaryActionLabel(connector.authType)
  /** 单行提示：hidden 用固定的「内部/企业」原因，其余用后端原因，再兜底 */
  const tierNote = tier === 'supported'
    ? null
    : (connector.hidden
      ? (connector.unavailableReason || HIDDEN_REASON)
      : (connector.unavailableReason || TIER_NOTE[tier]))
  const desc = connector.description || '暂无说明'

  return (
    <div
      className={`mc-card${connected ? ' on' : ''}${disabled ? ' is-disabled' : ''}`}
      style={{ ['--connector-accent' as string]: accent }}
      role={disabled ? undefined : 'button'}
      tabIndex={disabled ? undefined : 0}
      data-testid="connector-card"
      data-connector-key={connector.key}
      data-auth-type={connector.authType}
      data-tier={tier}
      data-hidden={connector.hidden ? 'true' : undefined}
      title={disabled ? (tierNote ?? undefined) : (connected ? `管理 ${nm}` : `${actionLabel}：${nm}`)}
      onClick={disabled ? undefined : onOpen}
      onKeyDown={disabled ? undefined : (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen() }
      }}
    >
      {/* ① 头部：图标 + 标题 + 分类/隐藏徽标（全部单行，min-width:0） */}
      <div className="mc-card-top">
        <span className="mc-ico-lg">
          {logo
            ? <img className="mc-logo" src={logo} alt="" loading="lazy" />
            : <span className="mc-ico-fallback" style={{ background: accent }}>{nm.charAt(0).toUpperCase()}</span>}
        </span>
        <div className="mc-titles">
          <div className="mc-title" title={nm}>{nm}</div>
          <div className="mc-chip-row">
            {catLabel && <span className="mc-chip">{catLabel}</span>}
            {connector.hidden && <span className="mc-badge mc-badge-hidden">{HIDDEN_BADGE}</span>}
            {connector.official && <span className="mc-badge mc-badge-official">官方 MCP</span>}
          </div>
        </div>
        {connected && <span className="mc-configured" title="已连接">✓ 已配置</span>}
      </div>

      {/* ② 描述：固定两行，超出省略；title 给全文 */}
      <div className="mc-desc" title={desc}>{desc}</div>

      {/* ③ 底部：状态 + 单行提示（省略）+ 固定宽按钮 */}
      <div className="mc-foot">
        <span className={`mc-status ${statusCls}`} data-testid="connector-status">
          <span className="mc-dot" />
          {STATUS_LABEL[connector.status] ?? connector.status}
        </span>
        {tierNote && (
          <span className="mc-tier-note" data-testid="connector-tier-note" title={tierNote}>{tierNote}</span>
        )}
        <button
          className={`mc-btn${connected || disabled ? '' : ' primary'}`}
          disabled={disabled}
          title={disabled ? (tierNote ?? '暂不支持') : actionLabel}
          onClick={(e) => { e.stopPropagation(); if (!disabled) onOpen() }}
        >
          {actionLabel}
        </button>
      </div>
    </div>
  )
}
