import { useEffect } from 'react'

/**
 * 语言选择（设置 → 通用 → 语言）· 值写回后端 settings.language。
 * 值语义对齐后端 LanguageResolver：存语言显示名（中文/English/日本語…）或特殊值 auto（按本机时区自动解析）。
 */
export interface LanguageOption {
  value: string
  label: string
  /** 选项小字说明（仅「自动」携带） */
  desc?: string
}

export const LANGUAGE_OPTIONS: LanguageOption[] = [
  { value: 'auto', label: '自动', desc: '按系统时区自动选择' },
  { value: '中文', label: '中文' },
  { value: 'English', label: 'English' },
  { value: '日本語', label: '日本語' },
  { value: '한국어', label: '한국어' },
  { value: 'Deutsch', label: 'Deutsch' },
  { value: 'Français', label: 'Français' },
  { value: 'Español', label: 'Español' },
  { value: 'Português', label: 'Português' },
  { value: 'Русский', label: 'Русский' },
  { value: 'Italiano', label: 'Italiano' },
  { value: 'العربية', label: 'العربية' },
  { value: 'हिन्दी', label: 'हिन्दी' },
  { value: 'Nederlands', label: 'Nederlands' },
  { value: 'Türkçe', label: 'Türkçe' },
]

/** 当前值展示文案：null / 空 / 'auto' → 「自动」；其余语言名（值即显示名） */
export function languageDisplayName(language: string | null | undefined): string {
  if (!language || language === 'auto') return '自动'
  return LANGUAGE_OPTIONS.find((o) => o.value === language)?.label ?? language
}

interface LanguagePickerModalProps {
  /** 当前 settings.language（可空=未配置，视为 auto） */
  value: string | null | undefined
  /** 选中即写回（父级调用 settings update） */
  onPick: (value: string) => void
  onClose: () => void
}

/** 语言选择弹窗 · 单选列表 + 当前项高亮勾选；选中即回调，由父级持久化 settings.language */
export function LanguagePickerModal({ value, onPick, onClose }: LanguagePickerModalProps) {
  // Esc 关闭（对齐 EffortModal / MemoryEditorModal 交互）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const active = value && value !== 'auto' ? value : 'auto'

  return (
    <div className="lang-backdrop" onClick={onClose}>
      <div className="lang-modal" onClick={(e) => e.stopPropagation()}>
        <div className="lang-header">
          <span className="lang-title">选择语言</span>
          <button className="settings-close" onClick={onClose} title="关闭">
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '14px', height: '14px' }}>
              <path d="M3 3L11 11M11 3L3 11" />
            </svg>
          </button>
        </div>
        <div className="lang-list">
          {LANGUAGE_OPTIONS.map((o) => {
            const isActive = active === o.value
            return (
              <div
                key={o.value}
                className={`lang-option ${isActive ? 'active' : ''}`}
                onClick={() => onPick(o.value)}
                title={o.desc}
              >
                <div className="lang-option-text">
                  <span className="lang-option-label">{o.label}</span>
                  {o.desc && <span className="lang-option-desc">{o.desc}</span>}
                </div>
                {isActive && (
                  <svg className="lang-option-check" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="2">
                    <path d="M2.5 7.5L5.5 10.5L11.5 4" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
