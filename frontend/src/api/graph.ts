/**
 * 知识图谱 API 客户端 · 对齐后端 `GraphController`（W7）。
 *
 * <p><b>契约来源</b>：WeKnora `internal/types/graph_search.go:107` 的 `GraphSearchResult`。
 * 本仓在源侧基础上多了三个扩展字段（都有默认值，旧调用方可忽略）：
 * <ul>
 *   <li>entity 的 {@code entity_type} —— 实体类型（11 种），用于星图着色</li>
 *   <li>relation 的 {@code weight} / {@code strength} —— 用于线的粗细</li>
 * </ul>
 *
 * <p>⛔ 字段名保持 snake_case：它是后端 JSON 契约，不是前端视图模型。
 */

import { api } from './rest'

/** 图谱实体。 */
export interface GraphEntity {
  id: string
  name: string
  knowledge_id: string
  attributes: string[]
  source_chunk_ids: string[]
  /** 本仓扩展：实体类型（Person / Organization / Location / …）。 */
  entity_type?: string
}

/** 图谱关系。 */
export interface GraphRelation {
  id: string
  source_id: string
  target_id: string
  type: string
  source_chunk_ids: string[]
  /** 本仓扩展：权重 1..10（PMI 0.6 + 强度 0.4）。 */
  weight?: number
  /** 本仓扩展：强度 5..10。 */
  strength?: number
}

/** 证据块（受限投影：无文件路径 / metadata / 图片 URL）。 */
export interface GraphSourceChunk {
  id: string
  knowledge_id: string
  title: string
  content: string
  chunk_index: number
  start_at: number
  end_at: number
  content_truncated: boolean
  title_truncated: boolean
}

/** 截断信息。 */
export interface GraphMeta {
  truncated: boolean
  truncation_reasons: string[]
  max_nodes: number
  max_relations: number
  max_source_chunks: number
}

/** 图谱检索结果。 */
export interface GraphSearchResult {
  knowledge_base_id: string
  query: string
  entities: GraphEntity[]
  relationships: GraphRelation[]
  source_chunks: GraphSourceChunk[]
  meta: GraphMeta
}

/** 重建结果。 */
export interface GraphRebuildResult {
  status: 'done' | 'skipped' | 'superseded' | 'failed'
  detail: string
}

export const graphApi = {
  /**
   * 取知识库图谱。
   *
   * @param baseId 知识库 ID
   * @param query  查询文本（空 ⇒ 全库概览）
   * @param maxNodes 实体上限
   */
  get: (baseId: string, query = '', maxNodes?: number) => {
    const params = new URLSearchParams()
    if (query) params.set('query', query)
    if (maxNodes != null) params.set('max_nodes', String(maxNodes))
    const qs = params.toString()
    return api<GraphSearchResult>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/graph${qs ? `?${qs}` : ''}`
    )
  },

  /**
   * 手动重建某篇文档的图谱（同步，可能几秒到几十秒）。
   *
   * @param baseId      知识库 ID
   * @param knowledgeId 文档 ID
   * @param model       模型名（空 ⇒ 知识库摘要模型）
   */
  rebuild: (baseId: string, knowledgeId: string, model = '') => {
    const params = new URLSearchParams({ knowledge_id: knowledgeId })
    if (model) params.set('model', model)
    return api<GraphRebuildResult>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/graph/rebuild?${params.toString()}`,
      { method: 'POST' }
    )
  },
}
