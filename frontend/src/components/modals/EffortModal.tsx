import { useEffect, useState } from 'react'
import type { SessionDto } from '@/api/types'

/** 思考深度档位（会话级 · V31 effort_level） */
type EffortLevel = NonNullable<SessionDto['effortLevel']>
/**
 * 档位顺序（越往后越费）。
 *
 * ⭐ 首位 'off' = 关闭思考（后端存 sessions.effort_level='off' → 请求发
 * thinking:{type:"disabled"} 且不发 effort）。它**不是**一个 effort 档位，
 * 只是和 effort 共用同一条轴 —— 用户心智里「关掉推理」与「想多深」是同一件事。
 */
const LEVELS: EffortLevel[] = ['off', 'low', 'medium', 'high', 'xhigh', 'max']

/** 每档的显示文案（off 用中文，其余保留 CC 原文档位名）。 */
const LEVEL_LABELS: Record<EffortLevel, string> = {
  off: '关闭',
  low: 'Low',
  medium: 'Medium',
  high: 'High',
  xhigh: 'xHigh',
  max: 'Max',
}

/** 每档的一句话说明（弹窗里直接展示，不让用户去猜档位差别）。 */
const LEVEL_DESC: Record<EffortLevel, string> = {
  off: '不输出思考过程，响应最快、消耗最少',
  low: '轻量推理，适合简单问答',
  medium: '平衡速度与深度',
  high: '默认档位，较深推理',
  xhigh: '更深推理，耗时与消耗更高',
  max: '最大推理深度，最慢也最贵',
}

interface EffortModalProps {
  value: SessionDto['effortLevel']
  /** 会话级 ultracode 开关（V32）· true = 回显 ultracode 选中态（effort 层 xhigh） */
  ultracodeEnabled?: boolean | null
  /** 保存（App 调 /effort 命令写当前会话）· ultracode 直接传（后端 V32 真实概念） */
  onSave: (v: EffortLevel | 'ultracode') => void
  onClose: () => void
}

/**
 * 推理等级选择弹窗（会话级）。
 *
 * <p>版式：干净的纵向单选列表 —— 一行一个档位，名称 + 说明 + 选中勾。
 * 点一行即应用并关闭；方向键移动、Enter 确认、Esc 取消。
 */
export function EffortModal({ value, ultracodeEnabled, onSave, onClose }: EffortModalProps) {
  const currentLevel = value ?? 'high'
  const currentUltra = !!ultracodeEnabled
  const [draft, setDraft] = useState<EffortLevel>(currentLevel)
  const [ultra, setUltra] = useState(currentUltra)

  /** 合并档位与 ultracode 后的选中索引（键盘移动用） */
  const activeIndex = ultra ? LEVELS.length : Math.max(0, LEVELS.indexOf(draft))

  const commit = () => {
    onSave(ultra ? 'ultracode' : draft)
    onClose()
  }

  const move = (delta: number) => {
    const next = Math.min(LEVELS.length, Math.max(0, activeIndex + delta))
    if (next === LEVELS.length) setUltra(true)
    else { setUltra(false); setDraft(LEVELS[next]) }
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
        e.preventDefault()
        move(-1)
      } else if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
        e.preventDefault()
        move(1)
      } else if (e.key === 'Enter') {
        commit()
      } else if (e.key === 'Escape') {
        onClose()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  return (
    <div className="eff-backdrop" onClick={onClose}>
      <div className="eff-modal" role="dialog" aria-modal="true" aria-label="推理等级" onClick={(e) => e.stopPropagation()}>
        <div className="eff-head">
          <span className="eff-title">推理等级</span>
          <span className="eff-sub">当前会话</span>
        </div>

        <div className="eff-options" role="radiogroup" aria-label="推理等级">
          {LEVELS.map((l) => {
            const isCurrent = !currentUltra && l === currentLevel
            const active = !ultra && l === draft
            return (
              <button
                key={l}
                type="button"
                role="radio"
                aria-checked={isCurrent}
                className={`eff-item${isCurrent ? ' is-current' : ''}${active ? ' is-active' : ''}`}
                onClick={() => { onSave(l); onClose() }}
              >
                <span className="eff-item-label">{LEVEL_LABELS[l]}</span>
                <span className="eff-item-desc">{LEVEL_DESC[l]}</span>
                {isCurrent
                  ? <span className="eff-item-current">当前</span>
                  : active ? <CheckIcon /> : null}
              </button>
            )
          })}
          <button
            type="button"
            role="radio"
            aria-checked={currentUltra}
            className={`eff-item${currentUltra ? ' is-current' : ''}${ultra ? ' is-active' : ''}`}
            onClick={() => { onSave('ultracode'); onClose() }}
          >
            <span className="eff-item-label">Ultracode</span>
            <span className="eff-item-desc">最高强度（xHigh 档 + Ultracode 多智能体模式）</span>
            {currentUltra
              ? <span className="eff-item-current">当前</span>
              : ultra ? <CheckIcon /> : null}
          </button>
        </div>

        <div className="eff-foot">↑↓ 选择 · Enter 确认 · Esc 取消</div>

        <button className="eff-close" onClick={onClose} aria-label="关闭">
          <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
            <path d="M3 3L11 11M11 3L3 11" />
          </svg>
        </button>
      </div>
    </div>
  )
}

function CheckIcon() {
  return (
    <span className="eff-item-check" aria-hidden="true">
      <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d="M2.5 7.5l3 3 6-6.5" />
      </svg>
    </span>
  )
}
