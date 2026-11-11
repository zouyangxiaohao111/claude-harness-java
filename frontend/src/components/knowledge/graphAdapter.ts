/**
 * 图谱数据适配 · 把后端 `GraphSearchResult` 转成星图画布的输入。
 *
 * <p><b>⭐ 为什么单独一个文件而不是塞进组件</b>：这段转换有两条容易错的语义，
 * 抽出来才能单测（不启 React、不画 canvas）：
 * <ol>
 *   <li><b>实体类型 → 节点分类</b>：后端给的是 11 种领域类型（Person/Organization/…），
 *       画布只有 5 种视觉分类（core/domain/law/entity/measure）。
 *       映射不是 1:1，⛔ 不能把领域类型直接当画布类型（会命中 undefined ⇒ 渲染崩）。</li>
 *   <li><b>超量截断</b>：图谱可能有上千实体。画布超过 400 节点会糊成一团，
 *       所以这里先按「度数高的优先」截断，而不是简单切片。</li>
 * </ol>
 */

import type { GraphEdgeInput, GraphNodeInput, GraphNodeType } from './graphTypes'
import type { GraphSearchResult } from '@/api/graph'

/** 画布能有效绘制的节点上限（与 KnowledgeGraphCanvas.MAX_NODES 对齐）。 */
export const MAX_RENDER_NODES = 400

/**
 * 领域实体类型 → 画布节点分类。
 *
 * <p>映射依据是「这个实体在星图里的角色」：
 * <ul>
 *   <li>Location / Organization / Person ⇒ {@code entity}（青）
 *       —— 它们是图谱的主干，数量最多</li>
 *   <li>Concept / Category / Work ⇒ {@code measure}（蓝）
 *       —— 抽象概念，通常挂在具体实体旁边</li>
 *   <li>其余（Product / Event / Date / Resource / Operation）⇒ {@code entity}</li>
 * </ul>
 *
 * <p>⚠️ 未知类型也走 {@code entity}（⛔ 不返回 undefined —— 那会让 PALETTE 查表崩）。
 */
export function entityTypeToNodeType(entityType: string | undefined): GraphNodeType {
  switch ((entityType || '').trim()) {
    case 'Concept':
    case 'Category':
    case 'Work':
      return 'measure'
    case 'Location':
    case 'Organization':
    case 'Person':
    case 'Product':
    case 'Event':
    case 'Date':
    case 'Resource':
    case 'Operation':
      return 'entity'
    default:
      return 'entity'
  }
}

/** 转换结果。 */
export interface GraphView {
  nodes: GraphNodeInput[]
  edges: GraphEdgeInput[]
  /** 是否因为超量被截断（前端要显式告诉用户，⛔ 不能静默少画）。 */
  truncated: boolean
  /** 实际画布上的节点数。 */
  nodeCount: number
}

/**
 * 把后端图谱转成画布输入。
 *
 * @param result    后端结果（可 null ⇒ 空图）
 * @param baseName  知识库名（作为中心节点标签）
 * @returns 画布输入
 */
export function buildGraphView(
  result: GraphSearchResult | null,
  baseName = '知识库'
): GraphView {
  if (!result || result.entities.length === 0) {
    return { nodes: [], edges: [], truncated: false, nodeCount: 0 }
  }

  // ── ① 按度数高的优先截断（度数在 chunk 数上体现，用 source_chunk_ids 长度近似）
  const sorted = [...result.entities].sort(
    (a, b) => (b.source_chunk_ids?.length ?? 0) - (a.source_chunk_ids?.length ?? 0)
  )
  const truncated = sorted.length > MAX_RENDER_NODES
  const kept = truncated ? sorted.slice(0, MAX_RENDER_NODES) : sorted
  const keptIds = new Set(kept.map((e) => e.id))

  // ── ② 中心节点 + 实体节点
  const coreId = '__kb_root__'
  const nodes: GraphNodeInput[] = [{ id: coreId, name: baseName, type: 'core' }]
  const edges: GraphEdgeInput[] = []

  for (const e of kept) {
    nodes.push({
      id: e.id,
      name: e.name,
      type: entityTypeToNodeType(e.entity_type),
      attributes: e.attributes ?? [],
    })
    // 每条实体连到中心，星图才有「旋臂」结构；不然孤立实体飘在画布外
    edges.push({
      id: `core-${e.id}`,
      sourceId: coreId,
      targetId: e.id,
      type: '属于文档',
    })
  }

  // ── ③ 实体间关系（⭐ 两端都必须在 kept 里，否则会画出悬空边）
  for (const r of result.relationships ?? []) {
    if (!keptIds.has(r.source_id) || !keptIds.has(r.target_id)) continue
    edges.push({
      id: r.id,
      sourceId: r.source_id,
      targetId: r.target_id,
      type: r.type || '相关',
    })
  }

  return { nodes, edges, truncated, nodeCount: kept.length }
}

// ══════════════════════════════════════════════════════════════
// SVG 引擎（KbWikiGraph）输入 —— 关系图谱复用 Wiki 图谱的动画与交互
// ══════════════════════════════════════════════════════════════

/**
 * 实体类型 → Wiki 图谱的视觉类型。
 *
 * <p>⭐ 关系图谱「按 Wiki 图谱动画样式来」（用户 2026-11-11 要求）⇒ 直接复用
 * `KbWikiGraph` 的六类视觉方案，实体类型只需要落到其中三类：
 * <ul>
 *   <li>{@code concept}（橙）—— 抽象概念：Concept / Category / Work</li>
 *   <li>{@code entity}（绿）—— 其余具体实体</li>
 *   <li>{@code summary}（蓝）—— 文档节点（关系图谱的 hub）</li>
 * </ul>
 *
 * <p>⛔ 不在这里返回 {@code undefined}：颜色查表要有键，否则节点会变灰点。
 */
export function entityTypeToSvgType(entityType: string | undefined): string {
  switch ((entityType || '').trim()) {
    case 'Concept':
    case 'Category':
    case 'Work':
      return 'concept'
    default:
      return 'entity'
  }
}

/** SVG 图谱画布的一行节点（形状对齐 `KbWikiGraph` 的 `GraphNodeLike`）。 */
export interface SvgGraphNode {
  /** 唯一键：实体 `e:<id>` / 文档 `k:<id>`。 */
  slug: string
  title: string
  type: string
  /** 与 Wiki 图谱同口径：**有向边原始行**里该节点出现的次数。 */
  linkCount: number
}

/** SVG 图谱画布的一条边。 */
export interface SvgGraphEdge {
  source: string
  target: string
  /** 反向边也存在 ⇒ 两端都画箭头。 */
  bidir: boolean
}

/** SVG 图谱画布输入（= `KbWikiGraph` 的 `GraphData`）。 */
export interface SvgGraphData {
  total: number
  nodes: SvgGraphNode[]
  edges: SvgGraphEdge[]
}

/** 实体 slug 前缀（避免与文档 id 撞键）。 */
export const ENTITY_SLUG = 'e:'
/** 文档 slug 前缀。 */
export const DOC_SLUG = 'k:'

/**
 * 后端 `GraphSearchResult` → SVG 图谱画布输入。
 *
 * <p>与 {@link buildGraphView} 的区别：那份给 canvas 星图用（`core` 根节点 + 5 类），
 * 这份给 **Wiki 图谱那套 SVG 引擎**用（文档作 hub、实体挂靠、关系为边）。
 *
 * <p>⭐ 为什么把**文档**也画出来：实体来自不同文档，若不画文档层，图上只有一堆
 * 互不相连的实体团；画了文档 hub 之后才有「文档 → 实体 → 关系」的层次，
 * 与 Wiki 图谱「摘要 → 实体 → 概念」的观感一致。
 *
 * <p>⚠️ 截断：超过 {@link MAX_RENDER_NODES} 时按度数（关系数）取前 N，
 * 否则上千实体画出来是一块糊。调用方拿 `truncated` 显式告诉用户。
 */
export function buildSvgGraph(
  result: GraphSearchResult | null,
  docTitles: Map<string, string> = new Map()
): SvgGraphData & { truncated: boolean } {
  const entities = result?.entities ?? []
  if (entities.length === 0) {
    return { total: 0, nodes: [], edges: [], truncated: false }
  }

  // ── ① 度数：关系两端各记 1（与 KbWikiGraph.mapGraph 同口径）
  const degree = new Map<string, number>()
  entities.forEach((e) => degree.set(e.id, 0))
  const rels = (result?.relationships ?? []).filter(
    (r) => degree.has(r.source_id) && degree.has(r.target_id)
  )
  rels.forEach((r) => {
    degree.set(r.source_id, (degree.get(r.source_id) ?? 0) + 1)
    degree.set(r.target_id, (degree.get(r.target_id) ?? 0) + 1)
  })

  // ── ② 超量截断（按关系度数高的优先，⛔ 不是简单切片）
  const sorted = [...entities].sort((a, b) => (degree.get(b.id) ?? 0) - (degree.get(a.id) ?? 0))
  const truncated = sorted.length > MAX_RENDER_NODES
  const kept = truncated ? sorted.slice(0, MAX_RENDER_NODES) : sorted
  const keptIds = new Set(kept.map((e) => e.id))

  // ── ③ 节点：文档 hub + 实体
  const nodes: SvgGraphNode[] = []
  const edges: SvgGraphEdge[] = []
  const docSeen = new Set<string>()
  for (const e of kept) {
    if (e.knowledge_id && !docSeen.has(e.knowledge_id)) {
      docSeen.add(e.knowledge_id)
      nodes.push({
        slug: DOC_SLUG + e.knowledge_id,
        /* ⚠️ 取不到标题 = 这篇文档已删除（实体行还在）。⛔ 不能回落到「文档」——
           那看起来像个真标题，用户会以为是文档名就叫「文档」。 */
        title: docTitles.get(e.knowledge_id) || '（文档已删除）',
        type: 'summary',
        linkCount: 0,
      })
    }
    nodes.push({
      slug: ENTITY_SLUG + e.id,
      title: e.name,
      type: entityTypeToSvgType(e.entity_type),
      linkCount: degree.get(e.id) ?? 0,
    })
    if (e.knowledge_id) {
      edges.push({
        source: DOC_SLUG + e.knowledge_id,
        target: ENTITY_SLUG + e.id,
        bidir: false,
      })
    }
  }

  // ── ④ 实体间关系（两端都必须在 kept 里，否则会画出悬空边）
  const pairSet = new Set(rels.map((r) => r.source_id + '→' + r.target_id))
  const seen = new Set<string>()
  for (const r of rels) {
    if (!keptIds.has(r.source_id) || !keptIds.has(r.target_id)) continue
    const key = [r.source_id, r.target_id].sort().join('↔')
    if (seen.has(key)) continue
    seen.add(key)
    edges.push({
      source: ENTITY_SLUG + r.source_id,
      target: ENTITY_SLUG + r.target_id,
      bidir: pairSet.has(r.target_id + '→' + r.source_id),
    })
  }

  // ── ⑤ 文档 hub 的度数 = 它挂靠的实体数（供节点半径使用）
  const byNode = new Map<string, SvgGraphNode>(nodes.map((n) => [n.slug, n]))
  nodes.forEach((n) => {
    if (n.type !== 'summary') return
    n.linkCount = edges.filter((e) => e.source === n.slug).length
    byNode.set(n.slug, n)
  })

  return { total: entities.length, nodes, edges, truncated }
}
