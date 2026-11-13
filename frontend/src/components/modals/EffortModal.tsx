import { useEffect, useRef, useState } from 'react'
import type { SessionDto } from '@/api/types'
import { slotFromRatio } from '@/utils/effortSlider'

/** 思考深度档位（会话级 · V31 effort_level） */
type EffortLevel = NonNullable<SessionDto['effortLevel']>
/**
 * 滑块档位（左→右：越右越费）。
 *
 * ⭐ 首位 'off' = 关闭思考（后端存 sessions.effort_level='off' → 请求发
 * thinking:{type:"disabled"} 且不发 effort）。它**不是**一个 effort 档位，
 * 只是和 effort 共用同一个滑块 —— 用户心智里「关掉推理」与「想多深」是同一条轴。
 */
const LEVELS: EffortLevel[] = ['off', 'low', 'medium', 'high', 'xhigh', 'max']

/** 每档的显示文案（off 用中文，其余保留 CC 原文档位名）。 */
const LEVEL_LABELS: Record<EffortLevel, string> = {
  off: '关闭',
  low: 'low',
  medium: 'medium',
  high: 'high',
  xhigh: 'xhigh',
  max: 'max',
}

/** 滑块总格数 = 档位数 + 1（末格 ultracode）。 */
const SLOT_COUNT = LEVELS.length + 1

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
    const p = (clientX - rect.left) / rect.width
    // 坐标 → 格索引的实现与边界见 utils/effortSlider（有单测钉住：
    // 旧写法 round(p*(n-1)) 会让第 1 格右半边落到 low；写死 slot===5 会让末格落到 max）。
    const slot = slotFromRatio(p, SLOT_COUNT)
    // ⛔ 不能写死数字：末格索引恒等于 LEVELS.length（档位数），加档后写死的值会指到 max。
    if (slot === LEVELS.length) setUltra(true)
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
          <div className="eff-status">{ultra ? 'ultracode' : LEVEL_LABELS[draft] ?? draft}</div>
        </div>

        {/* 柔和淡青渐变条 · 7 格（关闭 + 5 档 + ultracode） */}
        <div
          className="eff-track"
          ref={trackRef}
          onPointerDown={(e) => pickFromEvent(e.clientX)}
          onPointerMove={(e) => { if (e.buttons === 1) pickFromEvent(e.clientX) }}
          onPointerUp={() => { onSave(ultra ? 'ultracode' : draft); onClose() }}
        >
          {LEVELS.map((l) => (
            <div key={l} className={`eff-option ${l === draft && !ultra ? 'active' : ''}`} onClick={() => { setUltra(false); setDraft(l) }}>
              <span className="eff-option-text">{LEVEL_LABELS[l] ?? l}</span>
              {l === draft && !ultra && <span className="eff-dot" />}
            </div>
          ))}
          <div className={`eff-option ${ultra ? 'active' : ''}`} onClick={() => setUltra(true)}>
            <span className="eff-option-text">ultracode</span>
            {ultra && <span className="eff-dot" />}
          </div>
        </div>

        {draft === 'off' && (
          <div className="eff-hint">
            关闭后模型不再输出思考过程，响应更快、消耗更少。
          </div>
        )}

        <div className="eff-footer">
          <div><span className="eff-key">←</span><span className="eff-key">→</span> 选择</div>
          <div><span className="eff-key">↵</span> 确认</div>
          <div><span className="eff-key">Esc</span> 取消</div>
        </div>
      </div>
    </div>
  )
}
