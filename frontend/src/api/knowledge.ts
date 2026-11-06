import { api } from './rest'
import { API_V1_BASE } from './base'

/**
 * 知识库 API 客户端 · 对齐 WeKnora 复刻后端（`/api/v1/knowledge-bases`）。
 *
 * <h2>为什么类型字段全 snake_case</h2>
 * 后端 DTO 逐字段 `@JsonProperty("xxx_yyy")` 钉死成源侧契约（见
 * `backend/.../model/knowledge/dto/KnowledgeDto.java`）。
 * ⛔ 不要在这里转 camelCase —— 那会多一层「转错就静默丢字段」的风险。
 *
 * <h2>时间字段口径</h2>
 * `created_at` / `updated_at` / `processed_at` 是后端产出的
 * `yyyy-MM-dd HH:mm:ss`（定长、空格分隔，见 OPEN_ISSUES O-7），
 * 前端直接展示即可，⛔ 不要用 `new Date()` 解析（Safari/WebKit 对该形态解析不可靠）。
 */

/** 知识库（后端 `KnowledgeBaseDto`，14 字段）。 */
export interface KnowledgeBase {
  id: string
  name: string
  description: string | null
  type: 'document' | 'faq' | 'wiki'
  is_temporary: boolean
  chunking_config: string
  image_processing_config: string
  embedding_model_id: string | null
  summary_model_id: string | null
  extract_config: string | null
  vector_store_id: string | null
  storage_backend_id: string | null
  /** 检索驱动（`retriever_engine`）；null/空 = 用全局默认 */
  retriever_engine?: string | null
  is_pinned?: boolean
  pinned_at?: string | null
  generated_profile?: string | null
  created_at: string | null
  updated_at: string | null
  /**
   * ⚠️ 以下 4 个是**写侧字段**：后端 `KnowledgeBaseDto` 只返回 18 个字段，
   * 这几项只能通过 `PATCH /knowledge-bases/{id}` 写，读不回来（D-13 的既定偏离）。
   * 所以这里标可选 —— 读接口永远拿不到，写接口可以传。
   */
  question_generation_config?: string | null
  auto_tag_config?: string | null
  wiki_config?: string | null
  /** 索引策略（`graph_enabled` 等）；写侧字段 */
  indexing_strategy?: string | null
}

/** 创建知识库请求。 */
export interface CreateKnowledgeBaseBody {
  name: string
  description?: string | null
  type?: string
  embedding_model_id?: string | null
  summary_model_id?: string | null
  chunking_config?: string | null
}

/** 更新知识库请求（null / 不传 = 不改）。 */
export interface UpdateKnowledgeBaseBody {
  name?: string
  description?: string | null
  embedding_model_id?: string | null
  summary_model_id?: string | null
  chunking_config?: string | null
}

/** 解析状态机取值（后端 `parse_status`）。 */
export type ParseStatus =
  | 'unprocessed'
  | 'pending'
  | 'processing'
  | 'finalizing'
  | 'completed'
  | 'failed'
  | 'deleting'
  | 'cancelled'

/** 知识条目（后端 `KnowledgeDto`，25 字段）。 */
export interface Knowledge {
  id: string
  knowledge_base_id: string
  type: string
  channel: string
  title: string
  description: string | null
  source: string
  parse_status: ParseStatus
  enable_status: 'enabled' | 'disabled'
  summary_status: string
  pending_subtasks_count: number
  embedding_model_id: string | null
  file_name: string | null
  file_type: string | null
  file_size: number | null
  file_path: string | null
  file_hash: string | null
  storage_size: number
  folder_path: string | null
  metadata: string | null
  custom_metadata: string | null
  error_message: string | null
  processed_at: string | null
  created_at: string | null
  updated_at: string | null
  /**
   * 文档身上的标签（后端列表/详情 JOIN `kb_knowledge_tag_relation` 得到）。
   *
   * <p>⚠️ 空数组 = 没有标签；**字段可能缺失**（单条更新/手工创建路径为了少一次往返不查标签）
   * ⇒ 用 `row.tags ?? []`，⛔ 不要假设它恒存在。
   */
  tags?: KnowledgeTagRef[]
}

/** 文档上的标签（后端 `KnowledgeDto.KnowledgeTagView`）。 */
export interface KnowledgeTagRef {
  id: string
  name: string
  color: string | null
}

/** 分页响应（源侧 `PageResponse` 形状，⛔ 不是本仓既有 camelCase 分页）。 */
export interface KnowledgePage {
  success: boolean
  data: Knowledge[]
  page: number
  page_size: number
  total: number
}

/** 列表筛选参数。 */
export interface KnowledgeListQuery {
  page?: number
  page_size?: number
  parse_status?: string
  file_type?: string
  keyword?: string
  folder_path?: string
}

/**
 * 构造带目录的上传名（文件夹上传）。
 *
 * <p>⭐ 这是**唯一**能产生子文件夹的途径：文件夹树从 `kb_knowledge.folder_path`
 * 派生，没有「新建空文件夹」接口。源侧同款（`folderTree.ts` 的 `buildUploadFileName`）。
 *
 * @param file         文件（目录选择时带 `webkitRelativePath`）
 * @param targetFolder 目标文件夹（可空 = 根）
 * @returns 形如 `目标/相对目录/文件名`；两者都空 ⇒ `undefined`（保持单文件上传的旧行为）
 */
export function buildUploadFileName(
  file: { name: string; webkitRelativePath?: string },
  targetFolder = ''
): string | undefined {
  const segments: string[] = []
  if (targetFolder) segments.push(...targetFolder.split('/').filter(Boolean))
  if (file.webkitRelativePath) {
    // ⚠️ 丢掉最后一段（那是文件名本身）
    segments.push(...file.webkitRelativePath.split('/').filter(Boolean).slice(0, -1))
  }
  if (segments.length === 0) return undefined
  return `${segments.join('/')}/${file.name}`
}

/** 把 query 对象转成查询串（跳过空值 —— 空串在源侧有语义差异，尤其 folder_path）。 */
function toQuery(params: Record<string, unknown>): string {
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null) continue
    const s = String(v)
    if (s === '') continue
    sp.set(k, s)
  }
  const q = sp.toString()
  return q ? `?${q}` : ''
}

/** 分块视图（后端 `ChunkController.ChunkView`，16 字段）。 */
export interface ChunkView {
  id: string
  knowledge_id: string
  knowledge_base_id: string
  content: string
  chunk_index: number | null
  is_enabled: boolean
  start_at: number | null
  end_at: number | null
  pre_chunk_id: string | null
  next_chunk_id: string | null
  parent_chunk_id: string | null
  chunk_type: string | null
  image_info: string | null
  content_revision: number | null
  index_status: string | null
  context_header: string | null
  source_locators: string | null
}

/** 检索命中项（后端 `SearchResultDto`）。 */
export interface SearchHit {
  id: string
  content: string
  context_header: string
  knowledge_id: string
  knowledge_title: string
  chunk_index: number
  score: number
  match_type: 'vector' | 'keyword' | 'hybrid'
}

/** 检索参数。 */
export interface HybridSearchBody {
  query_text: string
  match_count?: number
  disable_vector_match?: boolean
  disable_keywords_match?: boolean
  knowledge_ids?: string[]
  rerank_model_id?: string
  rerank_top_n?: number
}

export const knowledgeApi = {
  // ── 知识库 ──────────────────────────────────────────────────────
  listBases: () => api<KnowledgeBase[]>('/knowledge-bases'),

  getBase: (id: string) => api<KnowledgeBase>(`/knowledge-bases/${encodeURIComponent(id)}`),

  createBase: (body: CreateKnowledgeBaseBody) =>
    api<KnowledgeBase>('/knowledge-bases', { method: 'POST', body }),

  updateBase: (id: string, body: UpdateKnowledgeBaseBody) =>
    api<KnowledgeBase>(`/knowledge-bases/${encodeURIComponent(id)}`, { method: 'PUT', body }),

  deleteBase: (id: string) =>
    api<void>(`/knowledge-bases/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  // ── 知识条目 ────────────────────────────────────────────────────
  listKnowledge: (baseId: string, query: KnowledgeListQuery = {}) =>
    api<KnowledgePage>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/knowledge${toQuery(query as Record<string, unknown>)}`
    ),

  getKnowledge: (id: string) => api<Knowledge>(`/knowledge/${encodeURIComponent(id)}`),

  /**
   * 列出某篇文档的全部分块。
   *
   * <p>⚠️ 端点路径是 `/chunks/{knowledgeId}`（**不是** `/knowledge/{id}/chunks`），
   * 返回的是裸数组 `ChunkView[]`（后端 `ChunkController#list`）。
   */
  listChunks: (knowledgeId: string, opts: { enabledOnly?: boolean } = {}) =>
    api<ChunkView[]>(
      `/chunks/${encodeURIComponent(knowledgeId)}${opts.enabledOnly ? '?include_disabled=false' : ''}`
    ),

  deleteKnowledge: (id: string) =>
    api<void>(`/knowledge/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  reparse: (id: string) =>
    api<{ task_id: number }>(`/knowledge/${encodeURIComponent(id)}/reparse`, { method: 'POST' }),

  cancelParse: (id: string) =>
    api<void>(`/knowledge/${encodeURIComponent(id)}/cancel-parse`, { method: 'POST' }),

  /**
   * 混合检索（向量 + 关键词 → RRF 融合 → 可选重排）。
   *
   * <p>⚠️ 走 POST 而不是 GET：查询可能很长，且要传 `knowledge_ids` 数组。
   * 后端两个方法都提供，GET 版留给脚本/浏览器直连。
   */
  hybridSearch: (baseId: string, body: HybridSearchBody) =>
    api<SearchHit[]>(`/knowledge-bases/${encodeURIComponent(baseId)}/hybrid-search`, {
      method: 'POST',
      body,
    }),

  /**
   * 上传文件创建知识条目。
   *
   * <p>⚠️ **不能走 `api()`**：那个封装会 `JSON.stringify(body)` 并设
   * `Content-Type: application/json`，而这里是 `multipart/form-data`
   * （`Content-Type` 必须由浏览器带 boundary 自动生成，手写会缺 boundary）。
   * 故单独用原生 fetch。
   *
   * @param baseId   知识库 ID
   * @param file     文件
   * @param fileName 自定义文件名（可空；带 `/` 时后端会拆出 folder_path）
   */
  uploadKnowledge: async (baseId: string, file: File, fileName?: string): Promise<Knowledge> => {
    const form = new FormData()
    form.append('file', file)
    if (fileName) form.append('fileName', fileName)
    const { API_V1_BASE } = await import('./base')
    const res = await fetch(
      `${API_V1_BASE}/knowledge-bases/${encodeURIComponent(baseId)}/knowledge/file`,
      { method: 'POST', body: form, headers: { Accept: 'application/json', 'X-Client-Env': 'react' } }
    )
    if (!res.ok) {
      const { parseProblem } = await import('./rest')
      throw await parseProblem(res)
    }
    return (await res.json()) as Knowledge
  },

  /**
   * 上传整个文件夹（保留目录结构）。
   *
   * <p>⭐ 浏览器在「选择目录」时会给每个 File 填 `webkitRelativePath`
   * （如 `规范/运维/a.txt`）。把它拼进 `fileName` 后，后端按最后一个 `/` 拆成
   * `folder_path` + 文件名 —— 这就是子文件夹产生的<b>唯一</b>途径
   * （文件夹树是从 `folder_path` 派生的，没有「新建空文件夹」接口）。
   *
   * @param baseId       知识库 ID
   * @param files        目录选择得到的文件列表
   * @param targetFolder 目标文件夹（可空 = 根）
   */
  uploadFolder: async (
    baseId: string,
    files: File[],
    targetFolder = '',
    onProgress?: (done: number, total: number) => void
  ): Promise<{ ok: number; failures: string[] }> => {
    let ok = 0
    const failures: string[] = []
    for (let i = 0; i < files.length; i++) {
      const f = files[i] as File & { webkitRelativePath?: string }
      const name = buildUploadFileName(f, targetFolder)
      try {
        await knowledgeApi.uploadKnowledge(baseId, f, name)
        ok++
      } catch (e) {
        failures.push(`${f.name}: ${e instanceof Error ? e.message : String(e)}`)
      }
      onProgress?.(i + 1, files.length)
    }
    return { ok, failures }
  },

  // ── 知识库问答（RAG 链 · QA-13）─────────────────────────────────
  /**
   * 提交一轮知识库问答（`POST /api/v1/knowledge-chat/{sid}`）。
   *
   * <p>⚠️ 返回 **202 Accepted**：内容不在这里，走 STOMP
   * `/topic/sessions/{sid}/stream`（`message.chunk` / `citations` / `message.complete`）。
   * 调用方必须订阅该 topic 才能看到答案。
   */
  askQa: (sessionId: string, body: KbQaAskBody) =>
    api<KbQaAccepted>(`/knowledge-chat/${encodeURIComponent(sessionId)}`, {
      method: 'POST',
      body,
    }),

  /** 停止该会话正在跑的 RAG 轮（⛔ 与主对话的 `/sessions/{sid}/stop` 是两条路）。 */
  stopQa: (sessionId: string) =>
    api<{ stopped: boolean }>(`/knowledge-chat/${encodeURIComponent(sessionId)}/stop`, {
      method: 'POST',
    }),

  /**
   * 只检索不生成（`POST /api/v1/knowledge-search`）。
   *
   * <p>用于「问答前先看命中了什么」。不落消息、不落引用、不推流。
   */
  searchKnowledge: (body: KbQaSearchBody) =>
    api<KbQaSearchHit[]>('/knowledge-search', { method: 'POST', body }),

  /**
   * 取某条 assistant 消息的引用（`GET /api/v1/messages/{id}/citations`）。
   *
   * <p>是引用的<b>权威查询入口</b>：STOMP 的 `citations` 事件只是增量通知，
   * 刷新/历史回放必须走这里。
   */
  listCitations: (messageId: string) =>
    api<KbCitation[]>(`/messages/${encodeURIComponent(messageId)}/citations`),

  // ── 文档文件夹（派生树 · 源侧 KbFolderTree）────────────────────────
  /**
   * 文件夹树（`GET /knowledge-bases/{id}/knowledge/folders`）。
   *
   * <p>⚠️ 这是从 `kb_knowledge.folder_path` 字符串派生的**虚拟树**，
   * 与 Wiki 的 `kb_wiki_folder` 不是一回事；**空目录不存在**（有文档才有节点）。
   */
  listFolders: (baseId: string) =>
    api<{ folders: KbFolderNode[] }>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/knowledge/folders`
    ),

  /** 重命名/移动文件夹（`PUT .../knowledge/folders`，body `{from,to}`）。 */
  renameFolder: (baseId: string, from: string, to: string) =>
    api<{ affected: number }>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/knowledge/folders`,
      { method: 'PUT', body: { from, to } }
    ),

  /** 把文档移动到文件夹（`POST /knowledge/folder`，body `{knowledge_ids,folder_path}`）。 */
  moveToFolder: (knowledgeIds: string[], folderPath: string) =>
    api<{ moved: number }>('/knowledge/folder', {
      method: 'POST',
      body: { knowledge_ids: knowledgeIds, folder_path: folderPath },
    }),

  // ── 标签（源侧 KbTagManageDrawer / KnowledgeTagFilter）──────────────
  /** 库内标签列表（`GET /knowledge-bases/{id}/tags`）。 */
  listTags: (baseId: string, withStats = true) =>
    api<KbTag[]>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/tags${withStats ? '?with_stats=true' : ''}`
    ),

  /** 新建标签（`POST /knowledge-bases/{id}/tags`）。 */
  createTag: (baseId: string, body: { name: string; color?: string | null; sort_order?: number | null }) =>
    api<KbTag>(`/knowledge-bases/${encodeURIComponent(baseId)}/tags`, { method: 'POST', body }),

  /** 更新标签（`PUT /knowledge-bases/{id}/tags/{tagId}`）。 */
  updateTag: (
    baseId: string,
    tagId: string,
    body: { name?: string; color?: string | null; sort_order?: number | null }
  ) =>
    api<KbTag>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/tags/${encodeURIComponent(tagId)}`,
      { method: 'PUT', body }
    ),

  /** 删除标签（`DELETE /knowledge-bases/{id}/tags/{tagId}`）。 */
  deleteTag: (baseId: string, tagId: string) =>
    api<void>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/tags/${encodeURIComponent(tagId)}`,
      { method: 'DELETE' }
    ),

  /**
   * 批量设置文档标签（`PUT /knowledge/tags`）。
   *
   * <p>⚠️ **替换语义**：传进来的 `tag_ids` 就是最终集合。
   * 只想「加一个」也要把原有的一起传 —— 否则未列出的一律被解除。
   */
  setKnowledgeTags: (kbId: string, knowledgeIds: string[], tagIds: string[]) =>
    api<{ written: number }>('/knowledge/tags', {
      method: 'PUT',
      body: { kb_id: kbId, knowledge_ids: knowledgeIds, tag_ids: tagIds },
    }),

  // ── 批量操作 ────────────────────────────────────────────────────
  /** 批量删除（`POST /knowledge/batch-delete`）。 */
  batchDelete: (knowledgeIds: string[]) =>
    api<KbBatchResult>('/knowledge/batch-delete', {
      method: 'POST',
      body: { knowledge_ids: knowledgeIds },
    }),

  /** 批量重解析（`POST /knowledge/batch-reparse`）。 */
  batchReparse: (knowledgeIds: string[]) =>
    api<KbBatchResult>('/knowledge/batch-reparse', {
      method: 'POST',
      body: { knowledge_ids: knowledgeIds },
    }),

  /** 单条更新（标题/描述/启用状态/自定义元数据/文件夹路径；`PUT /knowledge/{id}`）。 */
  updateKnowledge: (
    id: string,
    body: {
      title?: string
      description?: string | null
      enable_status?: string
      custom_metadata?: string | null
      folder_path?: string
    }
  ) => api<Knowledge>(`/knowledge/${encodeURIComponent(id)}`, { method: 'PUT', body }),

  /** 切换置顶（`PUT /knowledge-bases/{id}/pin`，源侧语义是 toggle 无 body）。 */
  togglePin: (id: string) =>
    api<KnowledgeBase>(`/knowledge-bases/${encodeURIComponent(id)}/pin`, { method: 'PUT' }),

  /** 下载原文（`GET /knowledge/{id}/download`）。 */
  downloadUrl: (id: string) => `${API_V1_BASE}/knowledge/${encodeURIComponent(id)}/download`,

  // ── 其他入库来源（对齐源侧 KbUploadSourceDropdown 的四档）──────────
  /**
   * URL 导入（`POST /knowledge-bases/{id}/knowledge/url`）。
   *
   * <p>后端抓取网页正文后作为一条知识入库（类型 `url`）。
   */
  importUrl: (baseId: string, url: string, fileName?: string) =>
    api<Knowledge>(`/knowledge-bases/${encodeURIComponent(baseId)}/knowledge/url`, {
      method: 'POST',
      body: { url, file_name: fileName ?? null },
    }),

  /**
   * 手工录入（`POST /knowledge-bases/{id}/knowledge/manual`）。
   *
   * <p>⚠️ 保存后会**重新切块并重建索引** —— 否则搜索还是旧文本（后端已处理）。
   */
  createManual: (
    baseId: string,
    body: { title: string; content: string; metadata?: string | null }
  ) =>
    api<Knowledge>(`/knowledge-bases/${encodeURIComponent(baseId)}/knowledge/manual`, {
      method: 'POST',
      body,
    }),

  /** 更新手工知识（`PUT /knowledge/manual/{id}`；正文变更后后端会重建索引）。 */
  updateManual: (id: string, body: { title?: string; content?: string }) =>
    api<Knowledge>(`/knowledge/manual/${encodeURIComponent(id)}`, { method: 'PUT', body }),

  // ── 知识库设置（PATCH 语义：只传改动过的字段）──────────────────────
  /**
   * 局部更新知识库。
   *
   * <p>⚠️ **必须用 PATCH 不能用 PUT**：PUT 是「提交整张表单」，未传字段会被当清空
   * （本仓 `PatchKnowledgeBase` 用 `body.has(field)` 判「是否传了」）。
   */
  patchBase: (id: string, body: Record<string, unknown>) =>
    api<KnowledgeBase>(`/knowledge-bases/${encodeURIComponent(id)}`, { method: 'PATCH', body }),

  // ── 分享（后端 KnowledgeShareController）──────────────────────────
  /** 分享列表（`GET /knowledge-bases/{id}/shares`）。 */
  listShares: (baseId: string) =>
    api<Array<{ id: string; permission: string; effective?: boolean; resource_type?: string }>>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/shares`
    ),

  /** 分享到共享空间（`POST /knowledge-bases/{id}/shares`）。 */
  createShare: (baseId: string, body: { permission?: string }) =>
    api<{ id: string; permission: string; effective?: boolean }>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/shares`,
      { method: 'POST', body }
    ),

  /** 取消分享（`DELETE /knowledge-bases/{id}/shares/{shareId}`）。 */
  deleteShare: (baseId: string, shareId: string) =>
    api<void>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/shares/${encodeURIComponent(shareId)}`,
      { method: 'DELETE' }
    ),

  // ── 活动记录（后端 GET /knowledge-bases/{id}/activity）─────────────
  /** 活动流（对齐源侧 activity 端点；返回 `{items:[...]}`）。 */
  listActivity: async (baseId: string, limit = 50) => {
    const r = await api<{ items: Array<Record<string, unknown>> }>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/activity?limit=${limit}`
    )
    return r.items ?? []
  },

  // ── FAQ（后端 FaqController · 10 个端点）────────────────────────────
  /** FAQ 条目列表（`GET /knowledge-bases/{id}/faq/entries`）。 */
  listFaq: (baseId: string, q: KbFaqQuery = {}) =>
    api<{ data: KbFaqEntry[]; page: number; page_size: number; total: number }>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/faq/entries${toQuery(q as Record<string, unknown>)}`
    ),

  /** 单条 FAQ 详情。 */
  getFaq: (baseId: string, entryId: number) =>
    api<KbFaqEntry>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/faq/entries/${entryId}`
    ),

  /** 新建 FAQ 条目（`POST .../faq/entry`）。 */
  createFaq: (baseId: string, body: KbFaqEntryInput) =>
    api<KbFaqEntry>(`/knowledge-bases/${encodeURIComponent(baseId)}/faq/entry`, {
      method: 'POST',
      body,
    }),

  /** 更新 FAQ 条目（`PUT .../faq/entries/{entryId}`）。 */
  updateFaq: (baseId: string, entryId: number, body: KbFaqEntryInput) =>
    api<KbFaqEntry>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/faq/entries/${entryId}`,
      { method: 'PUT', body }
    ),

  /** 批量删除（`DELETE .../faq/entries`，body `{entry_ids}`）。 */
  deleteFaq: (baseId: string, entryIds: number[]) =>
    api<{ deleted: number }>(`/knowledge-bases/${encodeURIComponent(baseId)}/faq/entries`, {
      method: 'DELETE',
      body: { entry_ids: entryIds },
    }),

  /**
   * 批量改状态/推荐/标签（`PATCH .../faq/entries`）。
   *
   * <p>⚠️ 三态：字段传 `undefined` = 不改，传 `false` = 显式设为关。
   */
  patchFaq: (
    baseId: string,
    ids: number[],
    body: { is_enabled?: boolean; is_recommended?: boolean; tag_id?: number | null }
  ) =>
    api<{ updated: number }>(`/knowledge-bases/${encodeURIComponent(baseId)}/faq/entries`, {
      method: 'PATCH',
      body: { ids, ...body },
    }),

  /** 批量导入/更新（`POST .../faq/entries`，`mode` = create|upsert）。 */
  upsertFaq: (baseId: string, entries: KbFaqEntryInput[], mode: 'create' | 'upsert' = 'create') =>
    api<{ task_id: string; created: number; failed: number; created_seq_ids: number[] }>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/faq/entries`,
      { method: 'POST', body: { entries, mode } }
    ),
}

/** FAQ 列表查询参数。 */
export interface KbFaqQuery {
  page?: number
  page_size?: number
  tag_ids?: string
  tag_id?: number
  keyword?: string
  search_field?: string
  sort_order?: string
  is_enabled?: boolean
}

/** FAQ 条目（后端 `KbFaqService.Entry`，16 字段）。 */
export interface KbFaqEntry {
  id: number
  chunk_id: string
  knowledge_id: string
  knowledge_base_id: string
  tag_id: number
  tag_name: string
  is_enabled: boolean
  is_recommended: boolean
  standard_question: string
  similar_questions: string[]
  negative_questions: string[]
  answers: string[]
  answer_strategy: string
  index_mode: string
  created_at: string
  updated_at: string
  score: number | null
}

/** FAQ 写入载荷（对齐 `FaqEntryPayload`）。 */
export interface KbFaqEntryInput {
  standard_question: string
  similar_questions?: string[]
  negative_questions?: string[]
  answers?: string[]
  answer_strategy?: string
  tag_id?: number | null
  tag_name?: string | null
  is_enabled?: boolean
  is_recommended?: boolean
}

/** 批量操作结果（后端 `KnowledgeExtController.BatchResponse`）。 */
export interface KbBatchResult {
  succeeded: string[]
  failed: Record<string, string>
}

/** 文件夹树节点（后端 `KbKnowledgeExtService.FolderNode`）。 */
export interface KbFolderNode {
  path: string
  name: string
  children: KbFolderNode[]
  document_count: number
}

/** 标签（后端 `KbTagService.TagView`，9 字段）。 */
export interface KbTag {
  id: string
  knowledge_base_id: string
  name: string
  color: string | null
  sort_order: number | null
  seq_id: number | null
  /** 关联文档数（仅 `with_stats=true` 时有意义，否则为 0） */
  knowledge_count: number | null
  created_at: string | null
  updated_at: string | null
}

/** 问答请求体（后端 `KnowledgeChatController.AskRequest`）。 */
export interface KbQaAskBody {
  content: string
  /** 检索范围；null/空 ⇒ 智能体范围 > 全部可检索库 */
  knowledgeBaseIds?: string[] | null
  modelName?: string | null
  webSearchEnabled?: boolean
}

/** 问答受理响应（后端 `QaAccepted`）。 */
export interface KbQaAccepted {
  userMessageId: string
  assistantMessageId: string
  streamTopic: string
  accepted: boolean
}

/** 只检索请求体。 */
export interface KbQaSearchBody {
  content: string
  sessionId?: string | null
  knowledgeBaseIds?: string[] | null
  modelName?: string | null
  webSearchEnabled?: boolean
}

/** 只检索命中（后端 `SearchHit`，与 hybrid-search 的 `SearchHit` 不同形状）。 */
export interface KbQaSearchHit {
  chunk_id: string
  knowledge_id: string
  content: string
  context_header: string
  score: number
}

/**
 * 一条引用（后端 `kb_citation` 行 / STOMP `citations` 事件的 `CitationItem`）。
 *
 * <p>⚠️ 两个来源字段名不同：REST 返回的是实体列（驼峰 getter → 驼峰 JSON），
 * STOMP 事件里是 `@JsonProperty` 钉死的 snake_case。本类型按 **REST 实体**建模，
 * 事件侧在 socket 层就地转换（见 `KbQaPanel`）。
 */
export interface KbCitation {
  refIndex: number
  chunkId: string
  knowledgeId: string
  knowledgeBaseId: string
  knowledgeTitle: string
  snippet: string
  chunkIndex: number | null
}

/** 状态 → 中文标签（与后端状态机取值一一对应）。 */
export const PARSE_STATUS_LABEL: Record<ParseStatus, string> = {
  unprocessed: '未处理',
  pending: '排队中',
  processing: '解析中',
  finalizing: '收尾中',
  completed: '已完成',
  failed: '失败',
  deleting: '删除中',
  cancelled: '已取消',
}

/** 状态 → 视觉色（供状态点使用）。 */
export const PARSE_STATUS_TONE: Record<ParseStatus, 'idle' | 'running' | 'ok' | 'error'> = {
  unprocessed: 'idle',
  pending: 'running',
  processing: 'running',
  finalizing: 'running',
  completed: 'ok',
  failed: 'error',
  deleting: 'idle',
  cancelled: 'idle',
}
