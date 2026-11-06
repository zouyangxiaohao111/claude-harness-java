/**
 * 知识图谱布局 · 银河式（golden-arm spiral）确定性布局。
 *
 * <p><b>为什么不用参考 HTML 的硬编码坐标</b>：那份 cinematic HTML 用
 * {@code domainSpec} 数组写死 12 个领域的 XY 坐标（见
 * {@code interactive-knowledge-graph-cinematic-v6-cn.html} 的 {@code domainSpec}）——
 * 只对那套示例数据成立。真实图谱的节点数、文档数、关系数都是动态的，必须有布局算法。
 *
 * <p><b>为什么不用力导向</b>：力导向每次渲染结果可能不同（同输入不同输出），
 * 没法做回归测试，也没法保证「同一份图每次打开位置一样」。本布局是<b>纯函数</b>：
 * 同输入 ⇒ 逐字节同输出。
 *
 * <p><b>算法骨架</b>（借鉴 WeKnora `frontend/src/views/knowledge/wiki/wikiGraphLayout.ts`
 * 的「连通分量分组 + 黄金角散布」思路，但换掉它的力导向模拟）：
 * <ol>
 *   <li>核心节点居中于世界原点；</li>
 *   <li>文档（domain）节点沿<b>黄金角螺旋</b>散布 —— 半径随序号增长，避免中心拥挤；</li>
 *   <li>关系（law）节点挂在它连接的两个实体<b>中点沿法向偏移</b>处 —— 避免压住实体；</li>
 *   <li>实体（entity）挂在所属文档的旋臂上，角度带确定性抖动；</li>
 *   <li>属性（measure）挂在实体旁的小半径上；</li>
 *   <li>最后跑 N 轮<b>确定性别名排斥</b>（按 id 排序遍历 ⇒ 结果与遍历顺序无关）。</li>
 * </ol>
 *
 * <p><b>⚠️ 坐标系</b>：本模块输出的是<b>世界坐标</b>（未缩放），画布渲染时再乘
 * {@code scale * zoom} 并加平移偏移。视口尺寸只影响「世界可见范围」，不影响节点坐标
 * —— 这样窗口缩放时节点不会跳位。
 */

import type { GraphEdgeInput, GraphNodeInput, GraphNodeType } from './graphTypes'

/** 布局产物：节点坐标 + 边（带两端坐标，供渲染直接消费）。 */
export interface GraphLayout {
  /** 节点 ID → 世界坐标。 */
  positions: Map<string, { x: number; y: number }>
  /** 布局后的边（source/target 均为节点 ID）。 */
  edges: GraphEdgeInput[]
  /** 世界坐标包围盒。 */
  bounds: { minX: number; minY: number; maxX: number; maxY: number }
}

/** 布局参数（全部可调，但默认值对应「银河」观感）。 */
export interface GraphLayoutOptions {
  /** 文档节点到中心的起始半径。 */
  domainRadius: number
  /** 相邻文档节点之间的角度增量（弧度）。 */
  domainAngleStep: number
  /** 同文档下相邻实体节点的半径增量。 */
  entityRadiusStep: number
  /** 关系节点相对实体中点的法向偏移。 */
  relationOffset: number
  /** 别名排斥迭代轮数。 */
  relaxIterations: number
  /** 两节点最小间距。 */
  minDistance: number
}

/** 默认布局参数。 */
export const DEFAULT_LAYOUT_OPTIONS: GraphLayoutOptions = {
  domainRadius: 260,
  domainAngleStep: 0.9,
  entityRadiusStep: 52,
  relationOffset: 46,
  relaxIterations: 40,
  minDistance: 34,
}

/** 黄金角（≈137.5°）—— 让螺旋散布均匀且无明显规律感。 */
const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5))

/** 确定性伪随机（按 id 哈希），保证同输入同输出。 */
function hash01(seed: string): number {
  let h = 2166136261
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  // >>> 0 转无符号；再除以 2^32 得 [0,1)
  return (h >>> 0) / 4294967296
}

/** 节点类型 → 基础半径（世界单位）。 */
function baseRadius(type: GraphNodeType): number {
  switch (type) {
    case 'core':
      return 26
    case 'domain':
      return 15
    case 'law':
      return 8
    case 'entity':
      return 6
    case 'measure':
      return 3.5
    default:
      return 5
  }
}

/**
 * 计算图谱布局。
 *
 * @param nodes 节点集合（id 不得重复）
 * @param edges 边集合（两端节点必须存在于 nodes，否则该边被丢弃）
 * @param options 布局参数
 * @returns 布局结果
 */
export function buildGraphLayout(
  nodes: GraphNodeInput[],
  edges: GraphEdgeInput[],
  options: GraphLayoutOptions = DEFAULT_LAYOUT_OPTIONS,
): GraphLayout {
  const byId = new Map(nodes.map((n) => [n.id, n]))
  const core = nodes.find((n) => n.type === 'core')
  const domains = nodes.filter((n) => n.type === 'domain')
  const entities = nodes.filter((n) => n.type === 'entity')
  const relations = nodes.filter((n) => n.type === 'law')
  const measures = nodes.filter((n) => n.type === 'measure')

  const positions = new Map<string, { x: number; y: number }>()

  // ── 1. 核心节点居中 ────────────────────────────────────────────────
  if (core) positions.set(core.id, { x: 0, y: 0 })

  // ── 2. 文档节点沿黄金角螺旋散布 ────────────────────────────────────
  // 排序保证「谁在第几个位置」与传入顺序无关（否则同图不同顺序 ⇒ 不同布局）。
  const sortedDomains = [...domains].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
  const domainAngle = new Map<string, number>()
  sortedDomains.forEach((d, i) => {
    const angle = i * GOLDEN_ANGLE
    const radius = options.domainRadius * (1 + i * 0.16)
    domainAngle.set(d.id, angle)
    positions.set(d.id, {
      x: Math.cos(angle) * radius,
      y: Math.sin(angle) * radius,
    })
  })

  // ── 3. 实体挂在所属文档的旋臂上 ────────────────────────────────────
  // 每个文档下的实体按 id 排序后依次取角度偏移，半径按序号递增。
  const entitiesByDomain = new Map<string, GraphNodeInput[]>()
  for (const e of entities) {
    const key = e.knowledgeId ?? ''
    const bucket = entitiesByDomain.get(key)
    if (bucket) bucket.push(e)
    else entitiesByDomain.set(key, [e])
  }
  for (const [domainId, bucket] of entitiesByDomain) {
    const sorted = [...bucket].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
    const docAngle = domainAngle.get(domainId) ?? hash01(domainId) * Math.PI * 2
    const docPos = positions.get(domainId)
    // 文档节点不存在时，实体挂到中心附近（避免落出可见范围）
    const anchor = docPos ?? { x: 0, y: 0 }
    sorted.forEach((e, i) => {
      // 确定性抖动：同一 id 每次都一样
      const jitter = (hash01(e.id) - 0.5) * 0.9
      const angle = docAngle + (i - sorted.length / 2) * 0.55 + jitter
      const radius = options.entityRadiusStep * (1 + Math.floor(i / 4) * 0.7) + hash01(e.id + ':r') * 18
      positions.set(e.id, {
        x: anchor.x + Math.cos(angle) * radius,
        y: anchor.y + Math.sin(angle) * radius,
      })
    })
  }

  // ── 4. 关系节点挂在两端实体中点的法向方向 ──────────────────────────
  // 中点 + 法向偏移 ⇒ 关系读起来是「在两者之间的一个中介」，且不压住端点。
  for (const r of relations) {
    const outgoing = edges.find((e) => e.sourceId === r.id)
    const incoming = edges.find((e) => e.targetId === r.id)
    const a = outgoing ? positions.get(outgoing.targetId) : undefined
    const b = incoming ? positions.get(incoming.sourceId) : undefined
    if (a && b) {
      const mx = (a.x + b.x) / 2
      const my = (a.y + b.y) / 2
      const dx = b.x - a.x
      const dy = b.y - a.y
      const len = Math.hypot(dx, dy) || 1
      // 法向 = (-dy, dx) 归一化
      const sign = hash01(r.id) > 0.5 ? 1 : -1
      const offset = options.relationOffset * sign
      positions.set(r.id, {
        x: mx + (-dy / len) * offset,
        y: my + (dx / len) * offset,
      })
    } else {
      // 缺一端（关系指向的实体不在本图内）⇒ 放在中心外围的确定位置
      const angle = hash01(r.id) * Math.PI * 2
      const radius = options.domainRadius * 0.55
      positions.set(r.id, {
        x: Math.cos(angle) * radius,
        y: Math.sin(angle) * radius,
      })
    }
  }

  // ── 5. 属性挂在实体旁小半径 ────────────────────────────────────────
  const measuresByEntity = new Map<string, GraphNodeInput[]>()
  for (const m of measures) {
    const owner = edges.find((e) => e.sourceId === m.id)?.targetId
      ?? edges.find((e) => e.targetId === m.id)?.sourceId
    if (!owner) continue
    const bucket = measuresByEntity.get(owner)
    if (bucket) bucket.push(m)
    else measuresByEntity.set(owner, [m])
  }
  for (const [ownerId, bucket] of measuresByEntity) {
    const anchor = positions.get(ownerId)
    if (!anchor) continue
    const sorted = [...bucket].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
    sorted.forEach((m, i) => {
      const angle = hash01(m.id) * Math.PI * 2 + (i * GOLDEN_ANGLE) / 2
      const radius = 16 + hash01(m.id + ':d') * 10
      positions.set(m.id, {
        x: anchor.x + Math.cos(angle) * radius,
        y: anchor.y + Math.sin(angle) * radius,
      })
    })
  }

  // ── 6. 丢弃端点缺失的边 ───────────────────────────────────────────
  const validEdges = edges.filter(
    (e) => byId.has(e.sourceId) && byId.has(e.targetId) && e.sourceId !== e.targetId,
  )

  // ── 7. 确定性排斥松弛 ─────────────────────────────────────────────
  // 按 id 排序遍历 ⇒ 结果与输入顺序无关（力导向最容易踩的坑）。
  const movable = nodes.filter((n) => n.type !== 'core')
  const ordered = [...movable].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
  for (let iter = 0; iter < options.relaxIterations; iter++) {
    const cooling = 1 - iter / options.relaxIterations
    for (let i = 0; i < ordered.length; i++) {
      const p = positions.get(ordered[i].id)
      if (!p) continue
      for (let j = i + 1; j < ordered.length; j++) {
        const q = positions.get(ordered[j].id)
        if (!q) continue
        const dx = q.x - p.x
        const dy = q.y - p.y
        const dist = Math.hypot(dx, dy)
        const need = options.minDistance + baseRadius(ordered[i].type) + baseRadius(ordered[j].type)
        if (dist >= need || dist === 0) continue
        const push = ((need - dist) / dist) * 0.5 * cooling
        const ox = dx * push
        const oy = dy * push
        p.x -= ox
        p.y -= oy
        q.x += ox
        q.y += oy
      }
    }
  }

  // ── 8. 包围盒 ─────────────────────────────────────────────────────
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const p of positions.values()) {
    if (p.x < minX) minX = p.x
    if (p.y < minY) minY = p.y
    if (p.x > maxX) maxX = p.x
    if (p.y > maxY) maxY = p.y
  }
  if (!Number.isFinite(minX)) {
    minX = 0
    minY = 0
    maxX = 0
    maxY = 0
  }

  return { positions, edges: validEdges, bounds: { minX, minY, maxX, maxY } }
}
