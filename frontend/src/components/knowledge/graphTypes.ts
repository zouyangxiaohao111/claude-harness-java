/**
 * 知识图谱数据契约 · 对齐 WeKnora `GraphSearchResult`。
 *
 * <p><b>源侧真源</b>：WeKnora `internal/types/graph_search.go:107-114`
 * （revision 80e8345，2026-11-03 核）：
 * <pre>
 *   GraphSearchResult {
 *     knowledge_base_id string
 *     query             string
 *     entities          []GraphSearchEntity      // id/name/knowledge_id/attributes/source_chunk_ids
 *     relationships     []GraphSearchRelation    // id/source_id/target_id/type/source_chunk_ids
 *     source_chunks     []GraphSearchSourceChunk // id/knowledge_id/title/content/chunk_index/start_at/end_at
 *     meta              GraphSearchMeta          // truncated / truncation_reasons / max_nodes ...
 *   }
 * </pre>
 *
 * <p><b>为什么契约先行</b>：源侧把「实体」与「关系」分成两个数组，而图谱画布要的是
 * 「节点 + 边」。本文件保留<b>源侧原始形状</b>（entities/relationships），另给出
 * {@link GraphNodeType} 节点分类 —— 节点类型是<b>前端视图分类</b>，不是源侧字段。
 *
 * <p><b>⛔ 边界</b>：源侧 {@code GraphSearchEntity} 只有 name 与 attributes，
 * <b>没有实体类型字段</b>（无「人物/机构/地点」之类标签）。本项目的
 * {@link GraphNodeType} 由「知识库 → 文档 → 关系 → 实体 → 属性」这层
 * <b>结构位置</b>推导，不假装源侧有这个语义。
 */

/** 图谱节点分类（前端视图分类，非源侧字段）。 */
export type GraphNodeType =
  /** 知识库根节点 —— 星图中心。 */
  | 'core'
  /** 文档（knowledge）—— 一条旋臂的起点。 */
  | 'domain'
  /** 关系节点 —— 由源侧 relationship 提升而来（source → relation → target）。 */
  | 'law'
  /** 实体 —— 源侧 entities。 */
  | 'entity'
  /** 属性/指标 —— 源侧 entity.attributes 的可视化落点。 */
  | 'measure'

/** 图谱节点（画布输入）。 */
export interface GraphNodeInput {
  /** 节点唯一 ID。源侧 entity.id / relation.id / knowledge.id。 */
  id: string
  /** 显示名。 */
  name: string
  /** 节点分类（视图分类，见上方说明）。 */
  type: GraphNodeType
  /** 所属文档 ID（源侧 knowledge_id）；根节点为空。 */
  knowledgeId?: string
  /** 属性列表（仅 measure/entity 节点有意义）。 */
  attributes?: string[]
}

/** 图谱边（画布输入）。 */
export interface GraphEdgeInput {
  /** 边唯一 ID。 */
  id: string
  /** 起点节点 ID。 */
  sourceId: string
  /** 终点节点 ID。 */
  targetId: string
  /** 关系类型文本（源侧 relationship.type）。 */
  type: string
}

/**
 * 源侧 `GraphSearchEntity` 的逐字形状（`graph_search.go:64-70`）。
 *
 * <p>字段名保持 snake_case —— 它是<b>后端 JSON 契约</b>，不是前端视图模型。
 */
export interface GraphSearchEntityDto {
  id: string
  name: string
  knowledge_id: string
  attributes: string[]
  source_chunk_ids: string[]
}

/**
 * 源侧 `GraphSearchRelation` 的逐字形状（`graph_search.go:73-79`）。
 */
export interface GraphSearchRelationDto {
  id: string
  source_id: string
  target_id: string
  type: string
  source_chunk_ids: string[]
}

/**
 * 源侧 `GraphSearchSourceChunk` 的逐字形状（`graph_search.go:83-95`）。
 *
 * <p>注意源侧注释：它是<b>受限的实时 SQL 投影</b>，不是裸 Chunk 行 —— 不含文件路径、
 * 元数据、图片 URL、属主信息。
 */
export interface GraphSearchSourceChunkDto {
  id: string
  knowledge_id: string
  title: string
  content: string
  chunk_index: number
  start_at: number
  end_at: number
  content_truncated?: boolean
  title_truncated?: boolean
}

/** 源侧 `GraphSearchMeta` 的逐字形状（`graph_search.go:98-104`）。 */
export interface GraphSearchMetaDto {
  truncated: boolean
  truncation_reasons: string[]
  max_nodes: number
  max_relations: number
  max_source_chunks: number
}

/**
 * 源侧 `GraphSearchResult` 的逐字形状（`graph_search.go:107-114`）。
 *
 * <p>后端端点：`POST /knowledge-bases/{id}/graph-search`（源侧 swagger tag「知识管理」）。
 */
export interface GraphSearchResultDto {
  knowledge_base_id: string
  query: string
  entities: GraphSearchEntityDto[]
  relationships: GraphSearchRelationDto[]
  source_chunks: GraphSearchSourceChunkDto[]
  meta: GraphSearchMetaDto
}
