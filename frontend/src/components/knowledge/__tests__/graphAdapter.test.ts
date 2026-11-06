import { describe, expect, it } from 'vitest'
import { buildGraphView, entityTypeToNodeType, MAX_RENDER_NODES } from '../graphAdapter'
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
