import { MarketBrowser, type MarketTab } from '@/components/market/MarketBrowser'

export type { MarketTab } from '@/components/market/MarketBrowser'

/**
 * 技能市场弹窗（薄壳）。
 *
 * <p><b>为什么还留着：</b>知识库是**独立 Tauri WebView**（`main.tsx` → `KbWindowView`），
 * 主窗口的 `<div className="center">` 与 `workspacePage` 在它那里不存在 ⇒
 * `KbNewChatPane` 必须继续用弹窗。主窗口入口已全部改为市场整页（`MarketPage`）。
 *
 * <p>内容全部在 `market/MarketBrowser.tsx`，本文件只包 `.sm-backdrop/.sm-panel` 外壳，
 * DOM 与类名与旧弹窗一致（现有市场测试按 `.sm-tab-flat/.sm-installed-pill/.sm-subtab` 定位）。
 */
export function SkillMarketModal({
  sessionId, currentAgent, busy, initialTab, onClose, onUseLocalAgent, onSummonSkill, showToast,
}: {
  sessionId: string
  initialTab?: MarketTab
  currentAgent?: string | null
  /** 对话进行中标记（⛔ 不作为任何操作门禁，仅测试回归用） */
  busy?: boolean
  /** 关闭弹窗（✕ / 遮罩 / Esc） */
  onClose: () => void
  onUseLocalAgent?: (agentType: string) => void
  onSummonSkill?: (commandName: string, displayName?: string) => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}) {
  return (
    <div className="sm-backdrop" onClick={onClose}>
      <div className="sm-panel" onClick={(e) => e.stopPropagation()}>
        <MarketBrowser
          sessionId={sessionId}
          currentAgent={currentAgent}
          busy={busy}
          initialTab={initialTab}
          mode="modal"
          onClose={onClose}
          onUseLocalAgent={onUseLocalAgent}
          onSummonSkill={onSummonSkill}
          showToast={showToast}
        />
      </div>
    </div>
  )
}
