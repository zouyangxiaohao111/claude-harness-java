import { createPortal } from 'react-dom'
import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties } from 'react'

/**
 * TourOverlay — 通用首次引导 Spotlight 基建（受控组件，L4a）。
 *
 * 视觉基准：启动动画预览-v7.html。真实首启面板（SettingsModal / ProvidersPanel /
 * ModelSettingsPanel）逐步接入时，只需把「真实 DOM 节点」用 getTarget 动态返回：
 * 遮罩会挖孔点亮该控件、其余变暗，旁边挂气泡（标题 + 说明 + 下一步/跳过）。
 *
 * 关键交互保证：
 *  - 变暗遮罩 .tour-shade 是 pointer-events:none —— 整层点击穿透，
 *    用户仍可直接点击被点亮的真实控件（如「＋添加提供商」、模型 chips），
 *    气泡 .tour-bubble 才是唯一可交互（pointer-events:auto）。
 *  - 目标可位于任意 overflow/滚动容器内：每步/滚动/缩放都用
 *    getBoundingClientRect() 取视口坐标，再用「固定层 + CSS mask 抠孔」实现点亮，
 *    不做 box-shadow-spread 式描边，天然适配滚动容器。
 *
 * 挖孔实现：.tour-shade 通过内联 mask-image: url("#<maskId>") 引用同文档内联
 * SVG <mask>（userSpaceOnUse + 白色全屏 + 黑色圆角矩形孔），
 * 黑孔即透明 → 露出真实控件。无目标步骤（getTarget 返回 null）则不挂 mask，
 * 退化为全屏柔和变暗 + 居中气泡/完成卡。
 */

export interface TourStep {
  /** 当前要 spotlight 的真实 DOM 节点（每步动态取；返回 null = 无目标，居中气泡） */
  getTarget: () => Element | null
  title?: string
  desc: string
  /** 「下一步」文案；缺省则隐藏“下一步”（用于“以真实操作为推进”的步骤，由父级监听操作后调 next） */
  nextLabel?: string
  /** 绑定校验：返回 true 才可点下一步；省略视为始终可点 */
  canNext?: () => boolean
}

export interface TourOverlayProps {
  active: boolean
  stepIndex: number
  steps: TourStep[]
  onNext: () => void
  onSkip: () => void
  /** true → 显示结束态（居中完成卡），按钮“开始使用”回调 onNext */
  done?: boolean
}

/** 目标矩形（视口坐标） */
interface Rect {
  left: number
  top: number
  width: number
  height: number
  right: number
  bottom: number
}

interface BubblePos {
  x: number
  y: number
  /** 首次测量完成前先隐藏，避免未定位气泡闪现在 0,0 */
  ready: boolean
}

/** 气泡与目标/视口边缘的间距 */
const GAP = 18
const EDGE = 14

const clamp = (v: number, min: number, max: number) => Math.max(min, Math.min(max, v))

/** 抠孔/描边圆角：矮胖控件 = 胶囊、近似方块 = 圆形 */
const spotRadius = (w: number, h: number) => Math.max(8, Math.round(Math.min(w, h) / 2))

const fitsView = (p: { x: number; y: number }, bw: number, bh: number, vw: number, vh: number) =>
  p.x >= EDGE && p.y >= EDGE && p.x + bw <= vw - EDGE && p.y + bh <= vh - EDGE

/** 气泡放置：优先右/下/左/上，能完整放进视口即用；否则挑“余量最大”的边并夹紧 */
function placeBubble(tr: Rect, bw: number, bh: number, vw: number, vh: number) {
  const cx = tr.left + tr.width / 2
  const cy = tr.top + tr.height / 2
  const candidates = [
    { x: tr.right + GAP, y: clamp(cy - bh / 2, EDGE, Math.max(EDGE, vh - bh - EDGE)) },
    { x: clamp(cx - bw / 2, EDGE, Math.max(EDGE, vw - bw - EDGE)), y: tr.bottom + GAP },
    { x: tr.left - GAP - bw, y: clamp(cy - bh / 2, EDGE, Math.max(EDGE, vh - bh - EDGE)) },
    { x: clamp(cx - bw / 2, EDGE, Math.max(EDGE, vw - bw - EDGE)), y: tr.top - GAP - bh },
  ]
  const hit = candidates.find((p) => fitsView(p, bw, bh, vw, vh))
  if (hit) return hit
  const score = (p: { x: number; y: number }) =>
    Math.min(p.x - EDGE, vw - EDGE - (p.x + bw)) + Math.min(p.y - EDGE, vh - EDGE - (p.y + bh))
  const best = candidates.reduce((a, b) => (score(b) > score(a) ? b : a))
  return {
    x: clamp(best.x, EDGE, Math.max(EDGE, vw - bw - EDGE)),
    y: clamp(best.y, EDGE, Math.max(EDGE, vh - bh - EDGE)),
  }
}

/** 视口内居中（无目标步骤 / 完成卡） */
function centerBubble(bw: number, bh: number, vw: number, vh: number) {
  return {
    x: clamp((vw - bw) / 2, EDGE, Math.max(EDGE, vw - bw - EDGE)),
    y: clamp((vh - bh) / 2, EDGE, Math.max(EDGE, vh - bh - EDGE)),
  }
}

const rectsEq = (a: Rect | null, b: Rect | null) => {
  if (!a || !b) return a === b
  return (
    Math.abs(a.left - b.left) <= 1 &&
    Math.abs(a.top - b.top) <= 1 &&
    Math.abs(a.width - b.width) <= 1 &&
    Math.abs(a.height - b.height) <= 1
  )
}

export function TourOverlay({ active, stepIndex, steps, onNext, onSkip, done = false }: TourOverlayProps) {
  const [hole, setHole] = useState<Rect | null>(null)
  const [bubble, setBubble] = useState<BubblePos>({ x: 0, y: 0, ready: false })
  const bubbleRef = useRef<HTMLDivElement | null>(null)
  const maskId = useMemo(() => `tourMask_${Math.random().toString(36).slice(2, 9)}`, [])

  const step = steps[stepIndex]
  const hasNext = !done && !!step?.nextLabel
  // canNext 在渲染层求值：父级状态变化 → 重渲染 → 禁用态/提示随之刷新
  const canProceed = hasNext ? (step!.canNext ? step!.canNext() : true) : false

  // 每步 / 滚动 / 缩放 时：重读目标位置 + 测量气泡后重新放置
  useLayoutEffect(() => {
    if (!active) return
    let raf = 0
    const layout = () => {
      cancelAnimationFrame(raf)
      raf = requestAnimationFrame(() => {
        // ① 目标矩形（视口坐标）
        let rect: Rect | null = null
        if (!done) {
          const target = stepIndex < steps.length ? steps[stepIndex]?.getTarget?.() ?? null : null
          if (target && target.isConnected) {
            const r = target.getBoundingClientRect()
            if (r.width > 0 || r.height > 0) {
              rect = { left: r.left, top: r.top, width: r.width, height: r.height, right: r.right, bottom: r.bottom }
            }
          }
        }
        setHole((prev) => (rectsEq(prev, rect) ? prev : rect))

        // ② 测量气泡尺寸并放置（有孔 = 绕目标排布；无孔 = 居中）
        const el = bubbleRef.current
        if (el) {
          const b = el.getBoundingClientRect()
          const vw = window.innerWidth
          const vh = window.innerHeight
          const pos = rect ? placeBubble(rect, b.width, b.height, vw, vh) : centerBubble(b.width, b.height, vw, vh)
          setBubble((prev) =>
            prev.ready && Math.abs(prev.x - pos.x) <= 1 && Math.abs(prev.y - pos.y) <= 1
              ? prev
              : { x: pos.x, y: pos.y, ready: true },
          )
        }
      })
    }
    layout()
    window.addEventListener('resize', layout)
    // capture 阶段监听滚动：可捕获到任意滚动容器内的 scroll（scroll 本身不冒泡）
    window.addEventListener('scroll', layout, true)
    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', layout)
      window.removeEventListener('scroll', layout, true)
    }
  }, [active, done, stepIndex, steps, canProceed, hasNext])

  if (!active) return null
  if (!done && !step) return null

  const maskOn = !!hole && !done
  const r = hole ? spotRadius(hole.width, hole.height) : 0
  const shadeStyle: CSSProperties | undefined = maskOn
    ? { maskImage: `url("#${maskId}")`, WebkitMaskImage: `url("#${maskId}")` }
    : undefined
  const bubbleStyle: CSSProperties = bubble.ready
    ? { left: bubble.x, top: bubble.y, visibility: 'visible' }
    : { visibility: 'hidden' }

  return createPortal(
    <div className="tour-root">
      {/* 挖孔用 SVG mask 定义（宽高 0 仅作引用，不参与布局；id 每挂载唯一） */}
      <svg className="tour-maskdefs" width="0" height="0" aria-hidden="true" focusable="false">
        <defs>
          <mask id={maskId} maskUnits="userSpaceOnUse" maskContentUnits="userSpaceOnUse">
            {/* 白色 = 显示暗色遮罩 */}
            <rect x={0} y={0} width={100000} height={100000} fill="white" />
            {/* 黑色圆角矩形 = 抠出透明孔 → 露出真实控件 */}
            {hole && !done ? (
              <rect x={hole.left} y={hole.top} width={hole.width} height={hole.height} rx={r} fill="black" />
            ) : null}
          </mask>
        </defs>
      </svg>

      {/* 变暗遮罩（有孔挂 mask，无孔全屏柔和变暗）；pointer-events 由 CSS 置 none */}
      <div className={maskOn ? 'tour-shade' : 'tour-shade no-hole'} style={shadeStyle} />

      {/* 目标边缘主题橙描边（纯装饰，pointer-events:none） */}
      {hole && !done ? (
        <div
          className="tour-ring"
          style={{ left: hole.left, top: hole.top, width: hole.width, height: hole.height, borderRadius: r }}
          aria-hidden="true"
        />
      ) : null}

      {done ? (
        <div
          key="tour-done"
          ref={bubbleRef}
          className="tour-bubble tour-card-done"
          style={bubbleStyle}
          role="dialog"
          aria-label="引导完成"
        >
          <div className="tour-doneIcon" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M4.5 12.5l5 5L19.5 7" />
            </svg>
          </div>
          <div className="tour-ttl">引导完成</div>
          <div className="tour-desc">设置已就绪，去开始你的第一个对话吧。</div>
          <div className="tour-bbtns tour-bbtns-center">
            <button type="button" className="tour-next" onClick={onNext}>
              开始使用
            </button>
          </div>
        </div>
      ) : step ? (
        <div
          key={`tour-bubble-${stepIndex}`}
          ref={bubbleRef}
          className="tour-bubble"
          style={bubbleStyle}
          role="dialog"
          aria-live="polite"
        >
          {step.title ? <div className="tour-ttl">{step.title}</div> : null}
          <div className="tour-desc">{step.desc}</div>
          {hasNext && !canProceed ? <div className="tour-hint">↑ 先操作一下上方控件，满足条件后“下一步”才可点击</div> : null}
          <div className="tour-bbtns">
            <button type="button" className="tour-skip" onClick={onSkip}>
              跳过引导
            </button>
            {hasNext ? (
              <button type="button" className="tour-next" disabled={!canProceed} onClick={onNext}>
                {step.nextLabel ?? (stepIndex >= steps.length - 1 ? '完成' : '下一步')}
              </button>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>,
    document.body,
  )
}
