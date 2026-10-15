/**
 * 主线程 agent（专家）胶囊 · Composer 顶部。
 * 语义变化（技能市场骨架）：点开不再展开下拉，改为触发打开 SkillMarketModal（技能市场弹窗，
 * App 持有 showMarket state 并渲染弹窗）。选中/使用 agent 在弹窗内完成，会话 mainThreadAgent 由
 * App 的 handleAgentChange（本地 PATCH）或 marketApi.useExpert（远端）写入后回传 currentAgent。
 * 胶囊文案：已选 → 显示 agentType；未选 → 「技能市场」入口。
 * 清除入口（2026-10-15，用户裁定「悬停出 × 可删」）：已选 agent 时，悬停胶囊把 ▾ 切换为 × ——
 *   点击走 onClear（App → handleAgentChange(null)，mainThreadAgent 清空回默认模式），
 *   stopPropagation 保证不误触「打开技能市场」。
 * 外观复用 composer-top 行 toolbar-select / agent-selector 胶囊样式（与 project-binder / mode 一致）。
 */
export function AgentSelector({ currentAgent, onOpen, onClear }: {
  /** 当前会话主线程 agent（null/空串 = 默认模式，胶囊显示「技能市场」入口） */
  currentAgent?: string | null
  /** 点击胶囊 → 打开技能市场弹窗（浏览不阻断；弹窗内「使用」由 App 判断 busy） */
  onOpen: () => void
  /** 清除当前专家（悬停胶囊时 ▾ 切换为 × 点击触发；未提供时无清除入口） */
  onClear?: () => void
}) {
  // 未选 agent（null/空串/全空白）时胶囊显示「专家」（打开技能市场选专家）；选中后显示具体 agentType
  const hasAgent = !!(currentAgent && currentAgent.trim())
  const label = hasAgent ? currentAgent : '专家'
  const canClear = hasAgent && !!onClear

  return (
    <div
      className={`toolbar-select agent-selector agent-market-trigger${hasAgent ? ' active' : ''}`}
      title={hasAgent
        ? `当前主线程 agent：${currentAgent} · 点击打开技能市场 · 悬停 ▾ 切换为 × 可清除`
        : '打开技能市场：选择专家驱动整轮对话'}
      onClick={() => onOpen()}
    >
      {hasAgent ? (
        // 已选 agent：人形图标
        <svg className="agent-selector-icon" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth={1.5} style={{ flexShrink: 0 }}>
          <circle cx="4.5" cy="4.5" r="2" />
          <path d="M1 9.5c0-1.5 1.6-2.5 3.5-2.5s3.5 1 3.5 2.5" />
          <circle cx="10.5" cy="5" r="1.5" />
          <path d="M8 9.3c.2-1 1.2-1.8 2.5-1.8 1.3 0 2.5.8 2.5 1.8" />
        </svg>
      ) : (
        // 未选：市场入口图标（店铺/货架）
        <svg className="agent-selector-icon" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth={1.5} style={{ flexShrink: 0 }}>
          <path d="M2 4.5L2.8 2.6C2.9 2.3 3.2 2.1 3.5 2.1H10.5C10.8 2.1 11.1 2.3 11.2 2.6L12 4.5" />
          <path d="M2 4.5V11C2 11.3 2.2 11.5 2.5 11.5H4V8.5H10V11.5H11.5C11.8 11.5 12 11.3 12 11V4.5" />
          <path d="M2 4.5H12" />
        </svg>
      )}
      <span>{label}</span>
      {/* ▾ 与 × 同挂载、CSS 按 hover 切换显隐（避免悬停瞬间布局跳动） */}
      <span className="agent-selector-chevron">▾</span>
      {canClear && (
        <span
          className="agent-clear"
          role="button"
          aria-label="清除当前专家"
          title="清除当前专家（回到默认模式）"
          onClick={(e) => { e.stopPropagation(); onClear?.() }}
        >
          <svg viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" width={10} height={10} aria-hidden="true">
            <path d="M2 2l6 6M8 2l-6 6" />
          </svg>
        </span>
      )}
    </div>
  )
}
