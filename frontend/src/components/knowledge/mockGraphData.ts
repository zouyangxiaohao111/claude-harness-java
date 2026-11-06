/**
 * 图谱 MOCK 数据 · 形状对齐 WeKnora 真实图谱（便于后续直接换 API 数据）。
 *
 * <p><b>⚠️ 本文件是占位数据，不是真实业务数据。</b>
 * 字段名对齐源侧 {@code GraphSearchResult}（WeKnora `internal/types/graph_search.go:107`），
 * 但<b>内容是编造的</b>，仅用于验证图谱画布的视觉与交互。
 *
 * <p><b>真实数据的形状对照</b>（据源侧 `graph_search.go` + `extract_entity.go`）：
 * <ul>
 *   <li>源侧图谱是「文档内」的关系图：entities 属于某个 knowledge，
 *       relationships 连接<b>同文档内</b>的两个实体（源侧注释：same-document entities）；
 *       跨文档不做合并 ⇒ 同名实体在不同文档是不同节点。</li>
 *   <li>因此本 mock 采用「中心知识库 → 文档 → 关系 → 实体 → 属性」五层结构，
 *       关系节点挂在它连接的两个实体之间。</li>
 * </ul>
 *
 * <p>后端落地后，替换方式：把 {@link buildMockGraph} 的返回换成
 * {@code GraphSearchResultDto} → {@link GraphNodeInput}/{@link GraphEdgeInput} 的转换，
 * 画布组件不用改。
 */

import type { GraphEdgeInput, GraphNodeInput } from './graphTypes'

/** MOCK 图谱产物。 */
export interface MockGraph {
  nodes: GraphNodeInput[]
  edges: GraphEdgeInput[]
}

/**
 * 构造一份 MOCK 图谱。
 *
 * @param docCount 文档数（每文档带若干实体/关系/属性）
 * @returns 节点与边
 */
export function buildMockGraph(docCount = 6): MockGraph {
  const nodes: GraphNodeInput[] = []
  const edges: GraphEdgeInput[] = []

  const coreId = 'kb-root'
  nodes.push({ id: coreId, name: '农村污水治理知识库', type: 'core' })

  const docNames = [
    '农村生活污水治理技术规范',
    '城镇污水处理提质增效实施方案',
    '污水管网排查与修复技术指南',
    '农村污水设施运行维护管理办法',
    '污水处理污泥处置技术导则',
    '污水资源化利用政策汇编',
  ]
  const entityNames = [
    ['工艺选择', '进水水质', '排放标准', '主体责任'],
    ['提质增效', '管网改造', '进水提升泵站', '再生水回用'],
    ['管道检测', 'CCTV 检测', '缺陷修复', '闭路电视'],
    ['运行台账', '巡检制度', '运维人员', '绩效付费'],
    ['污泥脱水', '浓缩池', '脱水机房', '掺合利用'],
    ['深度处理', '膜组件', '回用灌溉', '再生水厂'],
  ]
  const relationTypes = ['前置要求', '支撑', '约束', '产出']

  for (let d = 0; d < docCount; d++) {
    const docId = `doc-${d}`
    nodes.push({ id: docId, name: docNames[d % docNames.length], type: 'domain' })
    edges.push({ id: `e-root-${d}`, sourceId: coreId, targetId: docId, type: '包含' })

    const names = entityNames[d % entityNames.length]
    const entityIds: string[] = []
    for (let i = 0; i < names.length; i++) {
      const eid = `ent-${d}-${i}`
      nodes.push({
        id: eid,
        name: names[i],
        type: 'entity',
        knowledgeId: docId,
        attributes: [],
      })
      entityIds.push(eid)
      edges.push({ id: `e-doc-${d}-${i}`, sourceId: docId, targetId: eid, type: '包含' })
    }

    // 关系节点：连接同文档内两个实体
    for (let i = 0; i + 1 < entityIds.length; i++) {
      const rid = `rel-${d}-${i}`
      nodes.push({ id: rid, name: relationTypes[i % relationTypes.length], type: 'law' })
      edges.push({ id: `e-rel-${d}-${i}-a`, sourceId: rid, targetId: entityIds[i], type: relationTypes[i % relationTypes.length] })
      edges.push({ id: `e-rel-${d}-${i}-b`, sourceId: rid, targetId: entityIds[i + 1], type: relationTypes[i % relationTypes.length] })
    }

    // 属性：挂在第一个实体旁
    const attrNames = ['执行主体', '适用范围', '关键指标']
    for (let i = 0; i < attrNames.length; i++) {
      const mid = `mea-${d}-${i}`
      nodes.push({
        id: mid,
        name: attrNames[i],
        type: 'measure',
        knowledgeId: docId,
        attributes: [],
      })
      edges.push({ id: `e-mea-${d}-${i}`, sourceId: mid, targetId: entityIds[0], type: '属性' })
    }
  }

  return { nodes, edges }
}
