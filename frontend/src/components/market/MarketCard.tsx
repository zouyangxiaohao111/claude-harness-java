import type { ReactNode } from 'react'
import './marketConnector.css'

/**
 * 市场卡片统一外壳（专家 / 技能 / 连接器共用同一份真实 DOM）。
 *
 * <p>结构与连接器卡完全一致：44 图标容器（40 图）→ 标题 15/600 + 分类 chip →
 * 描述 13/1.45 两行截断 → 底栏（状态/提示 + 右侧动作）。⛔ 不是「外观近似」：
 * 三类卡片都渲染这套 `mc-card*` 节点，分类 chip/截断/悬浮行为只有一份实现。
 *
 * <p>可扩展位：`extra`（作者行/统计行等）与 `overlay`（⋯ 菜单等绝对定位内容），
 * 连接器两处都不传 ⇒ DOM 与原来一致。
 */
export function MarketCard({
  icon, title, chip, badges, description, extra, footerLeft, footerRight, overlay,
  onClick, disabled, accent, extraClass, testId, dataAttrs,
}: {
  icon: ReactNode
  title: string
  chip?: string | null
  badges?: ReactNode
  description?: string | null
  /** 描述与底栏之间的附加行（作者/统计） */
  extra?: ReactNode
  footerLeft?: ReactNode
  footerRight?: ReactNode
  /** 卡片内绝对定位内容（如 ⋯ 菜单） */
  overlay?: ReactNode
  onClick?: () => void
  disabled?: boolean
  accent?: string
  extraClass?: string
  testId?: string
  dataAttrs?: Record<string, string | undefined>
}) {
  const style: Record<string, string> = {}
  if (accent) style['--connector-accent'] = accent
  const attrs = dataAttrs ?? {}
  return (
    <div
      className={`mc-card market-card-base${extraClass ? ` ${extraClass}` : ''}${disabled ? ' is-disabled' : ''}`}
      style={style}
      role={disabled ? undefined : 'button'}
      tabIndex={disabled ? undefined : 0}
      data-testid={testId}
      {...attrs}
      title={disabled ? attrs['data-disabled-title'] : undefined}
      onClick={disabled ? undefined : onClick}
      onKeyDown={disabled ? undefined : (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick?.() }
      }}
    >
      <div className="mc-card-top">
        <span className="mc-ico-lg">{icon}</span>
        <div className="mc-titles">
          <div className="mc-title" title={title}>{title}</div>
          <div className="mc-chip-row">
            {chip && <span className="mc-chip">{chip}</span>}
            {badges}
          </div>
        </div>
      </div>

      <div className="mc-desc" title={description ?? undefined}>{description || '暂无说明'}</div>
      {extra}
      {(footerLeft || footerRight) && (
        <div className="mc-foot">
          {footerLeft}
          {footerRight}
        </div>
      )}
      {overlay}
    </div>
  )
}
