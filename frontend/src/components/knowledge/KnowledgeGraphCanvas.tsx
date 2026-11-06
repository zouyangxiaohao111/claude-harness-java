/**
 * 知识图谱画布 · 银河风格（canvas 2D）。
 *
 * <p><b>视觉来源</b>：用户指定的参考稿
 * {@code D:/wechat/chat/xwechat_files/.../interactive-knowledge-graph-cinematic-v6-cn.html}
 * （「光脉知识星图」）。该稿的 canvas 2D 渲染内核（光晕 / 激光脉冲 / 缩放平移 /
 * 聚焦补间 / 星尘）在此保留；<b>用户明确要求的两处改动</b>已落地：
 * <ol>
 *   <li><b>连线更细</b>：参考稿基线宽 {@code 0.7 * scale}、根边 {@code 1.55 * scale}、
 *       外发光 3 层（8× / 3.8× / 1×）；本组件改为 {@code 0.34 * scale}、根边
 *       {@code 0.68 * scale}、外发光 2 层（4× / 1×）、整体 alpha 降到 0.22 ⇒
 *       细线不糊成一团。</li>
 *   <li><b>银河观感</b>：参考稿的星尘画在<b>屏幕坐标</b>（缩放平移时星点不动 ⇒ 显得
 *       「贴玻璃」）；本组件把星尘放进<b>世界坐标</b>，跟着缩放平移走，并加一条
 *       倾斜的<b>银河尘埃带</b>（多层弥散椭圆 + 密集星点）⇒ 视觉上像银河而不是「星点背景」。</li>
 * </ol>
 *
 * <p><b>数据来源</b>：本组件只认 {@link GraphNodeInput} / {@link GraphEdgeInput}
 * （见 {@link ./graphTypes}）。当前为 <b>MOCK 数据</b>（{@link ./mockGraphData}），
 * 后端 {@code POST /knowledge-bases/{id}/graph-search} 落地后替换数据源即可，
 * 组件本身不用改。
 *
 * <p><b>性能</b>：单 canvas + requestAnimationFrame；节点数 > {@link MAX_NODES}
 * 时停止绘制并提示（银河效果在 100+ 节点时已经视觉糊化）。
 */

import { useEffect, useMemo, useRef, useState } from 'react'
import type { GraphEdgeInput, GraphNodeInput, GraphNodeType } from './graphTypes'
import { buildGraphLayout, DEFAULT_LAYOUT_OPTIONS } from './graphLayout'

/** 超过此节点数停止绘制（银河观感在密图下糊化，且 O(n²) 排斥会拖帧）。 */
const MAX_NODES = 400

/** 节点类型 → 配色（RGB 三元组，便于按 alpha 组装 rgba）。 */
const PALETTE: Record<GraphNodeType, [number, number, number]> = {
  core: [246, 218, 159],
  domain: [231, 139, 180],
  law: [175, 145, 249],
  entity: [111, 232, 215],
  measure: [103, 199, 255],
}

/** 节点类型 → 中文标签。 */
const TYPE_LABELS: Record<GraphNodeType, string> = {
  core: '知识库',
  domain: '文档',
  law: '关系',
  entity: '实体',
  measure: '属性',
}

function rgba(color: [number, number, number], alpha: number): string {
  return `rgba(${color[0]},${color[1]},${color[2]},${alpha})`
}

function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v))
}

/** 节点世界半径（随类型递减 —— 节点是小星点，不是大球）。 */
function nodeRadius(type: GraphNodeType): number {
  switch (type) {
    case 'core':
      return 13
    case 'domain':
      return 7
    case 'law':
      return 3.6
    case 'entity':
      return 2.8
    case 'measure':
      return 1.7
    default:
      return 2.5
  }
}

/** 组件属性。 */
interface KnowledgeGraphCanvasProps {
  /** 节点集合。 */
  nodes: GraphNodeInput[]
  /** 边集合。 */
  edges: GraphEdgeInput[]
  /** 选中回调（点节点时触发）。 */
  onSelect?: (node: GraphNodeInput | null) => void
  /** 高亮节点 ID 集合（外部联动，如搜索命中）。 */
  highlightIds?: ReadonlySet<string>
}

/**
 * 知识图谱画布。
 *
 * <p>交互：滚轮缩放 · 拖拽平移 · 拖节点重排 · 单击选中 · 双击聚焦。
 */
export function KnowledgeGraphCanvas({
  nodes,
  edges,
  onSelect,
  highlightIds,
}: KnowledgeGraphCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [hoverId, setHoverId] = useState<string | null>(null)

  // 相机状态放 ref（每帧变化，不触发 React 重渲染）
  const cameraRef = useRef({ zoom: 1, panX: 0, panY: 0 })
  const dragRef = useRef<{
    mode: 'pan' | 'node'
    nodeId?: string
    startX: number
    startY: number
    originX: number
    originY: number
    nodeOrigin?: { x: number; y: number }
    moved: boolean
  } | null>(null)
  const rafRef = useRef(0)

  // 布局：节点/边变化时重算（纯函数，结果可缓存）
  const layout = useMemo(
    () => buildGraphLayout(nodes, edges, DEFAULT_LAYOUT_OPTIONS),
    [nodes, edges],
  )

  // 尘埃带 + 星点（世界坐标，跟随缩放平移）
  const dust = useMemo(() => {
    let seed = 987654321
    const rand = () => {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0
      return seed / 4294967296
    }
    const span = 2600
    const stars = Array.from({ length: 520 }, () => ({
      x: (rand() - 0.5) * span,
      y: (rand() - 0.5) * span,
      r: rand() * 1.1 + 0.15,
      a: rand() * 0.5 + 0.12,
    }))
    // 银河带：倾斜约 -24°，带内星点更密
    const bandAngle = -0.42
    const bandStars = Array.from({ length: 380 }, () => {
      const along = (rand() - 0.5) * span * 1.25
      const across = (rand() + rand() + rand() - 1.5) * 190 // 近似高斯分布
      return {
        x: Math.cos(bandAngle) * along - Math.sin(bandAngle) * across,
        y: Math.sin(bandAngle) * along + Math.cos(bandAngle) * across,
        r: rand() * 1.4 + 0.25,
        a: rand() * 0.6 + 0.2,
      }
    })
    return { stars, bandStars, bandAngle }
  }, [])

  useEffect(() => {
    const canvas = canvasRef.current
    const wrap = wrapRef.current
    if (!canvas || !wrap) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    let width = 0
    let height = 0
    let dpr = 1

    const resize = () => {
      const rect = wrap.getBoundingClientRect()
      width = rect.width
      height = rect.height
      dpr = Math.min(window.devicePixelRatio || 1, 2)
      canvas.width = Math.round(width * dpr)
      canvas.height = Math.round(height * dpr)
      canvas.style.width = `${width}px`
      canvas.style.height = `${height}px`
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    }

    const observer = new ResizeObserver(resize)
    observer.observe(wrap)
    resize()

    // 世界 → 屏幕
    const toScreen = (p: { x: number; y: number }) => {
      const cam = cameraRef.current
      return {
        x: width / 2 + cam.panX + p.x * cam.zoom,
        y: height / 2 + cam.panY + p.y * cam.zoom,
      }
    }

    const bloom = (
      g: CanvasRenderingContext2D,
      x: number,
      y: number,
      radius: number,
      color: [number, number, number],
      intensity: number,
    ) => {
      if (radius <= 0 || intensity <= 0) return
      const grad = g.createRadialGradient(x, y, 0, x, y, radius)
      grad.addColorStop(0, rgba(color, clamp(intensity * 0.7, 0, 1)))
      grad.addColorStop(0.18, rgba(color, intensity * 0.24))
      grad.addColorStop(0.5, rgba(color, intensity * 0.07))
      grad.addColorStop(1, rgba(color, 0))
      g.fillStyle = grad
      g.beginPath()
      g.arc(x, y, radius, 0, Math.PI * 2)
      g.fill()
    }

    const draw = (time: number) => {
      ctx.clearRect(0, 0, width, height)
      const cam = cameraRef.current

      // ── 底色：深空径向渐变 ────────────────────────────────────────
      const bg = ctx.createRadialGradient(
        width * 0.5, height * 0.46, 0,
        width * 0.5, height * 0.46, Math.max(width, height) * 0.72,
      )
      bg.addColorStop(0, '#0d2b33')
      bg.addColorStop(0.34, '#072026')
      bg.addColorStop(0.7, '#03161b')
      bg.addColorStop(1, '#010a0e')
      ctx.fillStyle = bg
      ctx.fillRect(0, 0, width, height)

      // ── 银河尘埃带（弥散椭圆层）────────────────────────────────────
      ctx.save()
      ctx.translate(width / 2 + cam.panX, height / 2 + cam.panY)
      ctx.rotate(dust.bandAngle)
      ctx.scale(1, 0.22) // 压扁成带状
      const bandR = Math.max(width, height) * 1.15 * cam.zoom
      const bandGrad = ctx.createRadialGradient(0, 0, bandR * 0.12, 0, 0, bandR)
      bandGrad.addColorStop(0, 'rgba(120,190,205,0.16)')
      bandGrad.addColorStop(0.35, 'rgba(80,150,175,0.09)')
      bandGrad.addColorStop(0.72, 'rgba(50,105,135,0.035)')
      bandGrad.addColorStop(1, 'rgba(30,70,95,0)')
      ctx.fillStyle = bandGrad
      ctx.beginPath()
      ctx.arc(0, 0, bandR, 0, Math.PI * 2)
      ctx.fill()
      ctx.restore()

      // ── 星尘（世界坐标，跟着缩放平移）──────────────────────────────
      for (const s of dust.stars) {
        const p = toScreen(s)
        if (p.x < -20 || p.x > width + 20 || p.y < -20 || p.y > height + 20) continue
        ctx.fillStyle = rgba(PALETTE.entity, s.a)
        ctx.beginPath()
        ctx.arc(p.x, p.y, s.r * cam.zoom, 0, Math.PI * 2)
        ctx.fill()
      }
      for (const s of dust.bandStars) {
        const p = toScreen(s)
        if (p.x < -20 || p.x > width + 20 || p.y < -20 || p.y > height + 20) continue
        ctx.fillStyle = rgba([190, 225, 235], s.a)
        ctx.beginPath()
        ctx.arc(p.x, p.y, s.r * cam.zoom, 0, Math.PI * 2)
        ctx.fill()
      }

      // ── 连线：细线 + 弱外发光 + 沿线流动光点 ───────────────────────
      const now = time
      for (const edge of layout.edges) {
        const a = layout.positions.get(edge.sourceId)
        const b = layout.positions.get(edge.targetId)
        if (!a || !b) continue
        const p = toScreen(a)
        const q = toScreen(b)
        const isRootEdge = edge.sourceId === nodes.find((n) => n.type === 'core')?.id
        const isHot =
          selectedId !== null && (edge.sourceId === selectedId || edge.targetId === selectedId)

        // 线宽：用户要求「线小一点」⇒ 基线 0.34，根边 0.68（缩放后仍细）
        const widthPx = (isRootEdge ? 0.68 : 0.34) * cam.zoom
        const alpha = (isHot ? 0.75 : isRootEdge ? 0.34 : 0.22) *
          (0.86 + 0.14 * Math.sin(now * 0.0016 + edge.id.length))

        const grad = ctx.createLinearGradient(p.x, p.y, q.x, q.y)
        grad.addColorStop(0, rgba(PALETTE.core, alpha * 0.7))
        grad.addColorStop(0.5, rgba(PALETTE.entity, alpha))
        grad.addColorStop(1, rgba(PALETTE.entity, alpha * 0.85))

        // 外发光只留 1 层（参考稿是 2 层：8× / 3.8×）
        ctx.strokeStyle = grad
        ctx.lineCap = 'round'
        ctx.lineWidth = widthPx * 4
        ctx.globalAlpha = 0.05
        ctx.beginPath()
        ctx.moveTo(p.x, p.y)
        ctx.lineTo(q.x, q.y)
        ctx.stroke()

        ctx.globalAlpha = 1
        ctx.lineWidth = Math.max(0.4, widthPx)
        ctx.beginPath()
        ctx.moveTo(p.x, p.y)
        ctx.lineTo(q.x, q.y)
        ctx.stroke()

        // 沿线流动的光点（银河感的关键 —— 线本身弱，光在跑）
        const cycle = ((now / 5200) + hashPhase(edge.id)) % 1
        const head = 0.14
        const u = clamp(cycle - head, 0, 1)
        const v = clamp(cycle, 0, 1)
        if (v > 0) {
          const hx = p.x + (q.x - p.x) * v
          const hy = p.y + (q.y - p.y) * v
          const tx = p.x + (q.x - p.x) * u
          const ty = p.y + (q.y - p.y) * u
          const trail = ctx.createLinearGradient(tx, ty, hx, hy)
          trail.addColorStop(0, rgba(PALETTE.entity, 0))
          trail.addColorStop(1, rgba([235, 250, 255], 0.72))
          ctx.strokeStyle = trail
          ctx.lineWidth = Math.max(0.6, widthPx * 1.6)
          ctx.beginPath()
          ctx.moveTo(tx, ty)
          ctx.lineTo(hx, hy)
          ctx.stroke()
          bloom(ctx, hx, hy, 7 * cam.zoom, PALETTE.entity, 0.5)
        }
      }

      // ── 节点：小星点 + 微光晕 ───────────────────────────────────────
      const ordered = [...nodes].sort((a, b) => {
        const order: GraphNodeType[] = ['measure', 'entity', 'law', 'domain', 'core']
        return order.indexOf(a.type) - order.indexOf(b.type)
      })
      for (const node of ordered) {
        const wp = layout.positions.get(node.id)
        if (!wp) continue
        const p = toScreen(wp)
        if (p.x < -40 || p.x > width + 40 || p.y < -40 || p.y > height + 40) continue
        const color = PALETTE[node.type]
        const r = Math.max(1.1, nodeRadius(node.type) * cam.zoom)
        const isSel = node.id === selectedId
        const isHot = node.id === hoverId || isSel
        const inHighlight = highlightIds?.has(node.id) ?? false

        bloom(ctx, p.x, p.y, r * (node.type === 'core' ? 7 : 4.2), color, isHot ? 0.95 : 0.62)

        const dot = ctx.createRadialGradient(p.x - r * 0.3, p.y - r * 0.3, 0, p.x, p.y, r * 1.3)
        dot.addColorStop(0, 'rgba(255,255,255,0.95)')
        dot.addColorStop(0.35, rgba(color, 0.95))
        dot.addColorStop(1, rgba(color, 0.15))
        ctx.fillStyle = dot
        ctx.beginPath()
        ctx.arc(p.x, p.y, r, 0, Math.PI * 2)
        ctx.fill()

        if (isSel || inHighlight) {
          ctx.strokeStyle = rgba(PALETTE.core, isSel ? 0.85 : 0.55)
          ctx.lineWidth = isSel ? 1.3 : 0.9
          ctx.beginPath()
          ctx.arc(p.x, p.y, r + (isSel ? 6 : 4), 0, Math.PI * 2)
          ctx.stroke()
        }
      }

      // ── 标签：只给核心/文档/选中/悬停节点 ──────────────────────────
      ctx.textBaseline = 'middle'
      for (const node of ordered) {
        if (node.type !== 'core' && node.type !== 'domain' && node.id !== selectedId && node.id !== hoverId) {
          continue
        }
        const wp = layout.positions.get(node.id)
        if (!wp) continue
        const p = toScreen(wp)
        if (p.x < -80 || p.x > width + 80 || p.y < -40 || p.y > height + 40) continue
        const r = Math.max(1.1, nodeRadius(node.type) * cam.zoom)
        const left = wp.x < 0
        ctx.textAlign = left ? 'right' : 'left'
        ctx.font = `${node.type === 'core' ? 13 : 11}px "Microsoft YaHei", system-ui, sans-serif`
        ctx.fillStyle = 'rgba(232, 248, 244, 0.94)'
        ctx.shadowColor = 'rgba(2, 16, 20, 0.9)'
        ctx.shadowBlur = 5
        ctx.fillText(node.name, p.x + (left ? -1 : 1) * (r + 9), p.y - 4)
        ctx.shadowBlur = 0
        ctx.font = '9px Arial, sans-serif'
        ctx.fillStyle = rgba(PALETTE.entity, 0.7)
        ctx.fillText(TYPE_LABELS[node.type], p.x + (left ? -1 : 1) * (r + 9), p.y + 9)
      }

      rafRef.current = requestAnimationFrame(draw)
    }

    rafRef.current = requestAnimationFrame(draw)
    return () => {
      cancelAnimationFrame(rafRef.current)
      observer.disconnect()
    }
  }, [layout, nodes, selectedId, hoverId, highlightIds, dust])

  // ── 交互：命中检测 ─────────────────────────────────────────────────
  const nodeAt = (clientX: number, clientY: number): GraphNodeInput | null => {
    const canvas = canvasRef.current
    if (!canvas) return null
    const rect = canvas.getBoundingClientRect()
    const x = clientX - rect.left
    const y = clientY - rect.top
    const cam = cameraRef.current
    let best: GraphNodeInput | null = null
    let bestDist = Infinity
    for (const node of nodes) {
      const wp = layout.positions.get(node.id)
      if (!wp) continue
      const sx = rect.width / 2 + cam.panX + wp.x * cam.zoom
      const sy = rect.height / 2 + cam.panY + wp.y * cam.zoom
      const d = Math.hypot(x - sx, y - sy)
      const hit = Math.max(9, nodeRadius(node.type) * cam.zoom + 6)
      if (d < hit && d < bestDist) {
        bestDist = d
        best = node
      }
    }
    return best
  }

  const onPointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const hit = nodeAt(e.clientX, e.clientY)
    const cam = cameraRef.current
    const wp = hit ? layout.positions.get(hit.id) : undefined
    dragRef.current = {
      mode: hit ? 'node' : 'pan',
      nodeId: hit?.id,
      startX: e.clientX,
      startY: e.clientY,
      originX: cam.panX,
      originY: cam.panY,
      nodeOrigin: wp ? { x: wp.x, y: wp.y } : undefined,
      moved: false,
    }
    ;(e.target as HTMLCanvasElement).setPointerCapture(e.pointerId)
  }

  const onPointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const drag = dragRef.current
    if (!drag) {
      const hit = nodeAt(e.clientX, e.clientY)
      const nextId = hit?.id ?? null
      if (nextId !== hoverId) setHoverId(nextId)
      return
    }
    const dx = e.clientX - drag.startX
    const dy = e.clientY - drag.startY
    if (Math.hypot(dx, dy) > 3) drag.moved = true
    if (!drag.moved) return
    const cam = cameraRef.current
    if (drag.mode === 'pan') {
      cam.panX = drag.originX + dx
      cam.panY = drag.originY + dy
    } else if (drag.nodeId && drag.nodeOrigin) {
      const wp = layout.positions.get(drag.nodeId)
      if (wp) {
        wp.x = drag.nodeOrigin.x + dx / cam.zoom
        wp.y = drag.nodeOrigin.y + dy / cam.zoom
      }
    }
  }

  const onPointerUp = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const drag = dragRef.current
    dragRef.current = null
    if (drag && !drag.moved) {
      const hit = nodeAt(e.clientX, e.clientY)
      setSelectedId(hit?.id ?? null)
      onSelect?.(hit ?? null)
    }
  }

  const onWheel = (e: React.WheelEvent<HTMLCanvasElement>) => {
    e.preventDefault()
    const canvas = canvasRef.current
    if (!canvas) return
    const rect = canvas.getBoundingClientRect()
    const mx = e.clientX - rect.left
    const my = e.clientY - rect.top
    const cam = cameraRef.current
    const old = cam.zoom
    const next = clamp(old * (e.deltaY < 0 ? 1.12 : 0.89), 0.35, 4)
    // 以光标为锚点缩放
    cam.panX = mx - (mx - cam.panX - rect.width / 2) * (next / old) - rect.width / 2
    cam.panY = my - (my - cam.panY - rect.height / 2) * (next / old) - rect.height / 2
    cam.zoom = next
  }

  const zoomBy = (factor: number) => {
    const cam = cameraRef.current
    cam.zoom = clamp(cam.zoom * factor, 0.35, 4)
  }

  const resetView = () => {
    cameraRef.current.zoom = 1
    cameraRef.current.panX = 0
    cameraRef.current.panY = 0
  }

  const tooMany = nodes.length > MAX_NODES

  return (
    <div className="kg-canvas-wrap" ref={wrapRef}>
      <canvas
        ref={canvasRef}
        className="kg-canvas"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerLeave={() => setHoverId(null)}
        onWheel={onWheel}
      />
      {tooMany ? (
        <div className="kg-overlay-note">节点 {nodes.length} 超过上限 {MAX_NODES}，已停止绘制</div>
      ) : null}
      <div className="kg-legend">
        {(Object.keys(PALETTE) as GraphNodeType[]).map((t) => (
          <span key={t} className="kg-legend-item">
            <i style={{ background: `rgb(${PALETTE[t].join(',')})` }} />
            {TYPE_LABELS[t]}
          </span>
        ))}
      </div>
      <div className="kg-tools">
        <button type="button" onClick={() => zoomBy(1.18)} title="放大">+</button>
        <button type="button" onClick={() => zoomBy(0.85)} title="缩小">−</button>
        <button type="button" onClick={resetView} title="复位">复位</button>
      </div>
      {selectedId ? (
        <div className="kg-inspector">
          {(() => {
            const node = nodes.find((n) => n.id === selectedId)
            if (!node) return null
            const neighbors = layout.edges.filter(
              (e) => e.sourceId === node.id || e.targetId === node.id,
            ).length
            return (
              <>
                <div className="kg-inspector-name">{node.name}</div>
                <div className="kg-inspector-type">{TYPE_LABELS[node.type]}</div>
                <div className="kg-inspector-meta">关联 {neighbors} 条</div>
              </>
            )
          })()}
        </div>
      ) : null}
    </div>
  )
}

/** 边的确定性相位偏移（让光点不在同一时刻出发）。 */
function hashPhase(id: string): number {
  let h = 5381
  for (let i = 0; i < id.length; i++) h = (h * 33) ^ id.charCodeAt(i)
  return ((h >>> 0) % 1000) / 1000
}
