import { MarketBrowser, type MarketTab } from './MarketBrowser'

export type { MarketTab } from './MarketBrowser'

/**
 * 市场整页（主区换页 · 照 IM 频道页范式，用户 2026-11-23 终稿）。
 *
 * <p>挂载在 `<div className="center">` 内，对话面保持挂载、由
 * `globals.css` 的 `[data-page="market"] .center > :not(.market-page)` 隐藏
 * （⛔ 不条件卸载：卸载会丢 Composer 草稿/附件）。
 *
 * <p>内容与弹窗共用 {@link MarketBrowser}，只有外壳与右上动作不同；
 * 关闭（← / Esc / 点会话 / 新建会话）由 App 把 `workspacePage` 切回 `chat`。
 */
export function MarketPage({
  sessionId, currentAgent, busy, initialTab, onExit, onUseLocalAgent, onSummonSkill, onCreateExpert, showToast,
}: {
  sessionId: string
  currentAgent?: string | null
  busy?: boolean
  initialTab?: MarketTab
  /** 返回对话页 */
  onExit: () => void
  onUseLocalAgent?: (agentType: string) => void
  onSummonSkill?: (commandName: string, displayName?: string) => void
  /** 「我的专家」空态 → 创建专家（App 把创建提示写进输入框） */
  onCreateExpert?: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}) {
  return (
    <div className="market-page">
      <MarketBrowser
        sessionId={sessionId}
        currentAgent={currentAgent}
        busy={busy}
        initialTab={initialTab}
        mode="page"
        onClose={onExit}
        onUseLocalAgent={onUseLocalAgent}
        onSummonSkill={onSummonSkill}
        onCreateExpert={onCreateExpert}
        showToast={showToast}
      />
    </div>
  )
}
