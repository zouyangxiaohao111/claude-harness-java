import { describe, expect, it } from 'vitest'
import { buildGraphView, buildSvgGraph, entityTypeToNodeType, entityTypeToSvgType, DOC_SLUG, ENTITY_SLUG, MAX_RENDER_NODES } from '../graphAdapter'
import type { GraphSearchResult } from '@/api/graph'

/**
 * {@link buildGraphView} 测试 · 批次 W7。
 *
 * <p>守两条「静默出错」：
 * ① 后端领域类型（11 种）→ 画布分类（5 种）映射漏了 ⇒ 查表 undefined ⇒ 渲染崩；
 * ② 截断后仍然画悬空边 ⇒ canvas 拿到不存在的节点坐标。
 */
function makeResult(over: Partial<GraphSearchResult> = {}): GraphSearchResult {
  return {
    knowledge_base_id: 'kb1',
    query: '',
    entities: [],
    relationships: [],
    source_chunks: [],
    meta: {
      truncated: false,
      truncation_reasons: [],
      max_nodes: 200,
      max_relations: 400,
      max_source_chunks: 100,
    },
    ...over,
  }
}

describe('entityTypeToNodeType', () => {
  it('概念/类别/作品 ⇒ measure', () => {
    expect(entityTypeToNodeType('Concept')).toBe('measure')
    expect(entityTypeToNodeType('Category')).toBe('measure')
    expect(entityTypeToNodeType('Work')).toBe('measure')
  })

  it('地点/机构/人物 ⇒ entity', () => {
    expect(entityTypeToNodeType('Location')).toBe('entity')
    expect(entityTypeToNodeType('Organization')).toBe('entity')
    expect(entityTypeToNodeType('Person')).toBe('entity')
  })

  it('⭐ 未知类型与 undefined 都不能返回 undefined（否则 PALETTE 查表崩）', () => {
    expect(entityTypeToNodeType('Widget')).toBe('entity')
    expect(entityTypeToNodeType(undefined)).toBe('entity')
    expect(entityTypeToNodeType('')).toBe('entity')
  })

  it('全部 11 种合法类型都有映射', () => {
    const types = ['Person', 'Organization', 'Location', 'Product', 'Event',
      'Date', 'Work', 'Concept', 'Resource', 'Category', 'Operation']
    for (const t of types) {
      expect(['entity', 'measure']).toContain(entityTypeToNodeType(t))
    }
  })
})

describe('buildGraphView', () => {
  it('空结果 ⇒ 空图（不含中心节点）', () => {
    const v = buildGraphView(makeResult(), '库')
    expect(v.nodes).toEqual([])
    expect(v.edges).toEqual([])
    expect(v.nodeCount).toBe(0)
  })

  it('null ⇒ 空图', () => {
    expect(buildGraphView(null).nodes).toEqual([])
  })

  it('实体 + 中心节点', () => {
    const v = buildGraphView(makeResult({
      entities: [
        { id: 'e1', name: '甲', knowledge_id: 'k1', attributes: [], source_chunk_ids: ['c1'] },
        { id: 'e2', name: '乙', knowledge_id: 'k1', attributes: [], source_chunk_ids: ['c1'] },
      ],
    }), '农村污水库')
    expect(v.nodeCount).toBe(2)
    // 中心 + 2 实体
    expect(v.nodes).toHaveLength(3)
    expect(v.nodes[0].type).toBe('core')
    expect(v.nodes[0].name).toBe('农村污水库')
  })

  it('每个实体都连到中心（不给孤立节点）', () => {
    const v = buildGraphView(makeResult({
      entities: [
        { id: 'e1', name: '甲', knowledge_id: 'k1', attributes: [], source_chunk_ids: [] },
      ],
    }))
    const coreEdges = v.edges.filter((e) => e.sourceId === '__kb_root__')
    expect(coreEdges).toHaveLength(1)
  })

  it('⭐ 关系两端都在返回集里才画（否则悬空边）', () => {
    const v = buildGraphView(makeResult({
      entities: [
        { id: 'e1', name: '甲', knowledge_id: 'k1', attributes: [], source_chunk_ids: [] },
        { id: 'e2', name: '乙', knowledge_id: 'k1', attributes: [], source_chunk_ids: [] },
      ],
      relationships: [
        { id: 'r1', source_id: 'e1', target_id: 'e2', type: '属于', source_chunk_ids: [] },
        { id: 'r2', source_id: 'e1', target_id: '不存在的实体', type: '悬空', source_chunk_ids: [] },
      ],
    }))
    const relEdges = v.edges.filter((e) => e.id === 'r1' || e.id === 'r2')
    expect(relEdges).toHaveLength(1)
    expect(relEdges[0].id).toBe('r1')
  })

  it('⭐⭐ 超量截断后，指向被截掉实体的关系也不再画', () => {
    const many = Array.from({ length: MAX_RENDER_NODES + 10 }, (_, i) => ({
      id: `e${i}`, name: `实体${i}`, knowledge_id: 'k1',
      attributes: [], source_chunk_ids: i === 0 ? ['c1', 'c2', 'c3'] : [],
    }))
    const v = buildGraphView(makeResult({
      entities: many,
      // 关系：指向一个必然被截掉的低度数实体
      relationships: [
        { id: 'r1', source_id: 'e0', target_id: `e${MAX_RENDER_NODES + 5}`, type: 'x', source_chunk_ids: [] },
      ],
    }))
    expect(v.truncated).toBe(true)
    expect(v.nodeCount).toBe(MAX_RENDER_NODES)
    // 悬空关系不能被画出来
    expect(v.edges.filter((e) => e.id === 'r1')).toHaveLength(0)
  })

  it('截断按度数（chunk 数）优先保留', () => {
    const entities = [
      { id: 'low', name: '低', knowledge_id: 'k1', attributes: [], source_chunk_ids: [] },
      { id: 'high', name: '高', knowledge_id: 'k1', attributes: [], source_chunk_ids: ['a', 'b', 'c'] },
      // ⚠️ 填充实体必须带 1 个 chunk（度数 > low 的 0），否则它们与 low 同分、
      //    稳定排序会把 low 留在前 400 里 ⇒ 这条断言就没有判别力
      ...Array.from({ length: MAX_RENDER_NODES }, (_, i) => ({
        id: `f${i}`, name: `填充${i}`, knowledge_id: 'k1',
        attributes: [], source_chunk_ids: ['x'],
      })),
    ]
    const v = buildGraphView(makeResult({ entities }))
    const ids = new Set(v.nodes.map((n) => n.id))
    expect(ids.has('high')).toBe(true)
    expect(ids.has('low')).toBe(false)
  })

  it('实体类型映射到节点分类', () => {
    const v = buildGraphView(makeResult({
      entities: [
        { id: 'e1', name: '杭州', knowledge_id: 'k1', attributes: [], source_chunk_ids: [], entity_type: 'Location' },
        { id: 'e2', name: '污水治理', knowledge_id: 'k1', attributes: [], source_chunk_ids: [], entity_type: 'Concept' },
      ],
    }))
    const byId = new Map(v.nodes.map((n) => [n.id, n]))
    expect(byId.get('e1')?.type).toBe('entity')
    expect(byId.get('e2')?.type).toBe('measure')
  })
})


// ══════════════════════════════════════════════════════════════
// buildSvgGraph —— 关系图谱复用 Wiki 图谱 SVG 引擎（2026-11-11）
// ══════════════════════════════════════════════════════════════

/**
 * 用户要求「图谱按照 wiki 图谱动画样式来 完整实现」⇒ 关系图谱不再用 canvas 星图，
 * 改成把数据转成 Wiki 图谱引擎的 `{nodes, edges}` 再注入。
 *
 * <p>这里守四条会静默出错的地方：
 * ① 文档 hub 没画 ⇒ 实体是一堆互不相连的团，看不出结构；
 * ② slug 不归一（实体 id 与文档 id 直接混用）⇒ 撞键，两个节点被当成一个；
 * ③ 截断后留下悬空边 ⇒ 引擎去查不存在的节点，边画到 (0,0)；
 * ④ 度数按「去重后的边」算 ⇒ 半径与标签优先级与 Wiki 图谱不一致。
 */
describe('entityTypeToSvgType', () => {
  it('概念类落 concept，其余落 entity', () => {
    expect(entityTypeToSvgType('Concept')).toBe('concept')
    expect(entityTypeToSvgType('Category')).toBe('concept')
    expect(entityTypeToSvgType('Work')).toBe('concept')
    expect(entityTypeToSvgType('Location')).toBe('entity')
    expect(entityTypeToSvgType(undefined)).toBe('entity')
  })
})

describe('buildSvgGraph', () => {
  it('空结果 ⇒ 空图，不造节点', () => {
    const g = buildSvgGraph(makeResult())
    expect(g.nodes).toHaveLength(0)
    expect(g.edges).toHaveLength(0)
    expect(g.truncated).toBe(false)
  })

  it('⭐ 每个文档出现一次 hub 节点，实体挂靠到 hub', () => {
    const g = buildSvgGraph(
      makeResult({
        entities: [
          { id: 'e1', name: '杭州', knowledge_id: 'k1', attributes: [], source_chunk_ids: [] },
          { id: 'e2', name: '污水', knowledge_id: 'k1', attributes: [], source_chunk_ids: [] },
          { id: 'e3', name: '规范', knowledge_id: 'k2', attributes: [], source_chunk_ids: [] },
        ],
      }),
      new Map([['k1', '文档一'], ['k2', '文档二']])
    )
    const docs = g.nodes.filter((n) => n.type === 'summary')
    expect(docs.map((n) => n.slug).sort()).toEqual([DOC_SLUG + 'k1', DOC_SLUG + 'k2'])
    expect(docs.find((n) => n.slug === DOC_SLUG + 'k1')?.title).toBe('文档一')
    // 两个文档 hub 各挂 2 / 1 个实体
    expect(docs.find((n) => n.slug === DOC_SLUG + 'k1')?.linkCount).toBe(2)
    expect(docs.find((n) => n.slug === DOC_SLUG + 'k2')?.linkCount).toBe(1)
    // 每个实体都有一条 文档→实体 的边
    expect(g.edges.filter((e) => e.source.startsWith(DOC_SLUG))).toHaveLength(3)
  })

  it('⭐⭐ slug 必须带前缀（实体 id 与文档 id 同值时不能撞成一个节点）', () => {
    const g = buildSvgGraph(
      makeResult({
        entities: [{ id: 'x', name: '同名实体', knowledge_id: 'x', attributes: [], source_chunk_ids: [] }],
      })
    )
    const slugs = g.nodes.map((n) => n.slug).sort()
    // sort 后 'e:' 在 'k:' 之前（字典序）
    expect(slugs).toEqual([ENTITY_SLUG + 'x', DOC_SLUG + 'x'])
    expect(slugs).toHaveLength(2)
  })

  it('⭐ 关系边两端都在 ⇒ 建边；双向关系标 bidir', () => {
    const g = buildSvgGraph(
      makeResult({
        entities: [
          { id: 'a', name: 'A', knowledge_id: 'k1', attributes: [], source_chunk_ids: [] },
          { id: 'b', name: 'B', knowledge_id: 'k1', attributes: [], source_chunk_ids: [] },
        ],
        relationships: [
          { id: 'r1', source_id: 'a', target_id: 'b', type: '属于', source_chunk_ids: [] },
          { id: 'r2', source_id: 'b', target_id: 'a', type: '包含', source_chunk_ids: [] },
        ],
      })
    )
    const rel = g.edges.filter((e) => e.source.startsWith(ENTITY_SLUG) && e.target.startsWith(ENTITY_SLUG))
    expect(rel).toHaveLength(1) // 同一对去重
    expect(rel[0].bidir).toBe(true)
    // 度数：a 与 b 各 2（两条有向行），与 Wiki 图谱 mapGraph 同口径
    const bySlug = new Map(g.nodes.map((n) => [n.slug, n]))
    expect(bySlug.get(ENTITY_SLUG + 'a')?.linkCount).toBe(2)
    expect(bySlug.get(ENTITY_SLUG + 'b')?.linkCount).toBe(2)
  })

  it('⭐⭐ 截断后不能留悬空边', () => {
    const entities = Array.from({ length: MAX_RENDER_NODES + 5 }, (_, i) => ({
      id: 'e' + i,
      name: '实体' + i,
      knowledge_id: 'k1',
      attributes: [],
      source_chunk_ids: [],
    }))
    const g = buildSvgGraph(
      makeResult({
        entities,
        relationships: [
          // 给最后一个实体（大概率被截断掉）挂一条关系
          { id: 'r', source_id: 'e0', target_id: 'e' + (MAX_RENDER_NODES + 4), type: 'x', source_chunk_ids: [] },
        ],
      })
    )
    expect(g.truncated).toBe(true)
    const nodeSlugs = new Set(g.nodes.map((n) => n.slug))
    for (const e of g.edges) {
      expect(nodeSlugs.has(e.source), `悬空边起点 ${e.source}`).toBe(true)
      expect(nodeSlugs.has(e.target), `悬空边终点 ${e.target}`).toBe(true)
    }
  })
})
