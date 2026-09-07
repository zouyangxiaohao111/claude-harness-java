import { useState } from 'react'
import { TIER_ITEMS } from '@/hooks/useTierInheritGuide'

/**
 * TierInheritModal — 模型档位「一键套用主模型」确认弹窗（Task-B）。
 *
 * 结构对齐现有确认弹窗（ia-* 系列）：遮罩 / 卡片 / 标题 / 说明 / 列表 / 提示 / 按钮组。
 *  - 「一键套用」：调 onApply 把六档统一 PUT 成主模型；成功后由 hook 负责关窗 + toast；
 *  - 「暂不」 / 遮罩 / X：只写 dismissed，不碰 settings。
 */
interface TierInheritModalProps {
  /** 主模型全名（hook active 时保证非空） */
  mainModelName: string
  /** 多模态/降级/TTS/ASR 是否全部未配置（true 时正文附一句单独配置提示） */
  showExtrasNote: boolean
  onApply: () => Promise<void>
  onDismiss: () => void
}

function CloseIcon() {
  return (
    <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
      <path d="M2 2L10 10M10 2L2 10" />
    </svg>
  )
}

export function TierInheritModal({ mainModelName, showExtrasNote, onApply, onDismiss }: TierInheritModalProps) {
  // 防连点：PUT 在途时禁用主按钮（失败 hook 会 toast 并保持弹窗，可重试）
  const [busy, setBusy] = useState(false)

  const handleApply = () => {
    if (busy) return
    setBusy(true)
    void onApply().finally(() => setBusy(false))
  }

  return (
    <div className="ti-backdrop" onClick={onDismiss}>
      <div className="ti-modal" onClick={(e) => e.stopPropagation()}>
        <div className="ti-header">
          <span className="ti-icon">✦</span>
          <span className="ti-title">让其余档位跟随主模型</span>
          <button className="ti-close" onClick={onDismiss} aria-label="关闭">
            <CloseIcon />
          </button>
        </div>

        <div className="ti-desc">
          你已经把主模型配成 <span className="ti-model">{mainModelName}</span>。下面这几个档位还没有专属模型，
          可以把它们一键统一设成主模型；想单独挑更省钱/更强的，也可以之后到「设置 → 模型」里微调。
        </div>

        <div className="ti-list">
          {TIER_ITEMS.map((t) => (
            <div key={t.key} className="ti-item">
              <span className="ti-glyph">{t.glyph}</span>
              <span className="ti-label">{t.label}</span>
              <span className="ti-to">→ 跟随主模型</span>
            </div>
          ))}
        </div>

        {showExtrasNote && (
          <div className="ti-note">
            小提示：多模态 / 降级 / 语音（TTS / ASR）这几项能力无法复用主模型；如需使用，请到 设置 → 模型 单独配置。
          </div>
        )}

        <div className="ti-btn-group">
          <button className="ti-btn-ghost" disabled={busy} onClick={onDismiss}>暂不</button>
          <button className="ti-btn-primary" disabled={busy} onClick={handleApply}>
            {busy ? '套用中…' : '一键套用'}
          </button>
        </div>
      </div>
    </div>
  )
}
