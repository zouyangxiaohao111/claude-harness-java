import { useEffect, useRef, useState } from 'react'
import type { SessionDto } from '@/api/types'

/** 思考深度档位（会话级 · V31 effort_level） */
type EffortLevel = NonNullable<SessionDto['effortLevel']>
const LEVELS: EffortLevel[] = ['low', 'medium', 'high', 'xhigh', 'max']

interface EffortModalProps {
  value: SessionDto['effortLevel']
  /** 会话级 ultracode 开关（V32）· true = 回显 ultracode 选中态（effort 层 xhigh） */
  ultracodeEnabled?: boolean | null
  /** 保存（App 调 /effort 命令写当前会话）· ultracode 直接传（后端 V32 真实概念） */
  onSave: (v: EffortLevel | 'ultracode') => void
  onClose: () => void
}

/** Effort 选择弹窗 · 柔和淡青渐变条 + ultracode 作为第 6 档（会话级 effort · V32 后端真实 ultracode 概念） */
export function EffortModal({ value, ultracodeEnabled, onSave, onClose }: EffortModalProps) {
  const [draft, setDraft] = useState<EffortLevel>(value ?? 'high')
  const [ultra, setUltra] = useState(ultracodeEnabled ?? false)
  const trackRef = useRef<HTMLDivElement>(null)
  const idx = LEVELS.indexOf(draft)

  const pickFromEvent = (clientX: number) => {
    const rect = trackRef.current?.getBoundingClientRect()
    if (!rect) return
    const p = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
    const slot = Math.round(p * 5) // 6 格：5 档 + ultracode
    if (slot === 5) setUltra(true)
    else { setUltra(false); setDraft(LEVELS[slot]) }
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') {
        if (ultra) setUltra(false)
        else setDraft(LEVELS[Math.max(0, idx - 1)])
      } else if (e.key === 'ArrowRight') {
        if (idx === LEVELS.length - 1) setUltra(true)
        else setDraft(LEVELS[Math.min(LEVELS.length - 1, idx + 1)])
      } else if (e.key === 'Enter') { onSave(ultra ? 'ultracode' : draft); onClose() }
      else if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [draft, ultra, idx, onSave, onClose])

  return (
    <div className="eff-backdrop" onClick={onClose}>
      <div className="eff-modal" onClick={(e) => e.stopPropagation()}>
        <div className="eff-header">
          <div className="eff-title">思考深度</div>
          <div className="eff-status">{ultra ? 'ultracode' : draft}</div>
        </div>

        {/* 柔和淡青渐变条 · 6 档（5 档 + ultracode） */}
        <div
          className="eff-track"
          ref={trackRef}
          onPointerDown={(e) => pickFromEvent(e.clientX)}
          onPointerMove={(e) => { if (e.buttons === 1) pickFromEvent(e.clientX) }}
          onPointerUp={() => { onSave(ultra ? 'ultracode' : draft); onClose() }}
        >
          {LEVELS.map((l) => (
            <div key={l} className={`eff-option ${l === draft && !ultra ? 'active' : ''}`} onClick={() => { setUltra(false); setDraft(l) }}>
              <span className="eff-option-text">{l}</span>
              {l === draft && !ultra && <span className="eff-dot" />}
            </div>
          ))}
          <div className={`eff-option ${ultra ? 'active' : ''}`} onClick={() => setUltra(true)}>
            <span className="eff-option-text">ultracode</span>
            {ultra && <span className="eff-dot" />}
          </div>
        </div>

        <div className="eff-footer">
          <div><span className="eff-key">←</span><span className="eff-key">→</span> 选择</div>
          <div><span className="eff-key">↵</span> 确认</div>
          <div><span className="eff-key">Esc</span> 取消</div>
        </div>
      </div>
    </div>
  )
}
