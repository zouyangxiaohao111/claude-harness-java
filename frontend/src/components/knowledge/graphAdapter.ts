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
