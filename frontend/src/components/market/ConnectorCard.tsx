import type { ConnectorDto } from '@/api/connectors'
import { connectorLogo } from '@/assets/connectors'
import { MarketCard } from './MarketCard'
import {
  HIDDEN_BADGE, HIDDEN_REASON,
  STATUS_LABEL, TIER_NOTE, categoryLabel, connectorColor, effectiveTier, primaryActionLabel, statusClass,
} from './connectorMeta'
import './marketConnector.css'

/**
 * 连接器卡：渲染结构走共享 {@link MarketCard}（三类卡片同一份 DOM/样式），
 * 自身只负责分档、图标、状态与动作。
 */
export function ConnectorCard({ connector, onOpen }: {
  connector: ConnectorDto
  onOpen: () => void
}) {
  const nm = connector.name || connector.key
  const tier = effectiveTier(connector)
  const disabled = tier === 'unavailable'
  const connected = connector.connected && connector.status !== 'reauth_required'
  const accent = connector.color || connectorColor(nm)
  const logo = connectorLogo(connector.icon, connector.key)
  const statusCls = connected ? 'connected' : statusClass(connector.status)
  const actionLabel = disabled ? '暂不支持' : connected ? '管理' : primaryActionLabel(connector.authType)
  const tierNote = tier === 'supported'
    ? null
    : (connector.hidden
      ? (connector.unavailableReason || HIDDEN_REASON)
      : (connector.unavailableReason || TIER_NOTE[tier]))

  return (
    <MarketCard
      testId="connector-card"
      accent={accent}
      dataAttrs={{
        'data-connector-key': connector.key,
        'data-auth-type': connector.authType,
        'data-tier': tier,
        'data-hidden': connector.hidden ? 'true' : undefined,
        'data-disabled-title': disabled ? (tierNote ?? undefined) : undefined,
      }}
      disabled={disabled}
      onClick={onOpen}
      icon={logo
        ? <img className="mc-logo" src={logo} alt="" loading="lazy" />
        : <span className="mc-ico-fallback" style={{ background: accent }}>{nm.charAt(0).toUpperCase()}</span>}
      title={nm}
      chip={categoryLabel(connector.category)}
      badges={(
        <>
          {connector.hidden && <span className="mc-badge mc-badge-hidden">{HIDDEN_BADGE}</span>}
          {connector.official && <span className="mc-badge mc-badge-official">官方 MCP</span>}
          {connected && <span className="mc-configured" title="已连接">✓ 已配置</span>}
        </>
      )}
      description={connector.description}
      footerLeft={(
        <>
          <span className={`mc-status ${statusCls}`} data-testid="connector-status">
            <span className="mc-dot" />
            {STATUS_LABEL[connector.status] ?? connector.status}
          </span>
          {tierNote && <span className="mc-tier-note" data-testid="connector-tier-note" title={tierNote}>{tierNote}</span>}
        </>
      )}
      footerRight={(
        <button
          className={`mc-btn${connected || disabled ? '' : ' primary'}`}
          disabled={disabled}
          title={disabled ? (tierNote ?? '暂不支持') : actionLabel}
          onClick={(e) => { e.stopPropagation(); if (!disabled) onOpen() }}
        >
          {actionLabel}
        </button>
      )}
    />
  )
}
