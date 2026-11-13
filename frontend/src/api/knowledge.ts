import { api, ApiError } from './rest'
import { API_V1_BASE } from './base'
import type { ProjectDto } from './projects'
import type { SpansResponse } from '../utils/knowledgeTrace'

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
   * ⭐ **读侧已补**（B7，2026-11-10）：后端 `KnowledgeBaseDto` 现在返回这 4 项。
   *
   * <p>补之前的真实故障：「AI 问题生成 / 自动关联标签」开关保存成功后，
   * `onSaved` 重新拉取仍是 `null` ⇒ **开关永远显示关闭**，展开的子字段也永不出现，
   * 用户以为没保存。所以这几个字段必须读回，⛔ 不能只写不读。
   */
  question_generation_config?: string | null
  auto_tag_config?: string | null
  wiki_config?: string | null
  /**
   * ASR 配置（`asr_config`）。⚠️ 同为写侧字段（DTO 不返回），且**后端零消费者**
   * —— 存进去不会让音频被真正转写，界面里已如实提示。
   */
  asr_config?: string | null
  /** 画像配置（`profile_config`）。⚠️ 写侧字段 + **后端零消费者**。 */
  profile_config?: string | null
  /**
   * FAQ 配置（`faq_config`）。⭐ `index_mode` **后端真消费**
   * （`KbFaqMetadata.resolveIndexMode` → `KbFaqService` 渲染正文）。
   */
  faq_config?: string | null
  /**
   * 索引策略 JSON（`wiki_enabled` / `graph_enabled`）。
   *
   * ⭐ **读侧可拿**：`KnowledgeBaseDto` 原先漏了这个字段，导致前端无法判断
   * Wiki / 图谱 tab 是否该显示（`KnowledgeBase.vue:99` 的 isWiki 判据）⇒ 两个 tab 永远隐藏。
   * 已补进出站 DTO。
   */
  indexing_strategy?: string | null
  /**
   * 图片管线 · 是否生成图片描述（`image_caption_enabled`，V286 顶层列）。
   *
   * <p>⭐ 读侧必读（`KnowledgeBaseDto` 已回传）—— 设置弹窗的三个控件靠它回显；
   * 只写不读会复现「保存成功但永远显示旧值」。
   *
   * <p>⚠️ 与 `image_processing_config.image_pipeline`（同名不同义、取值 `""`/`"attrs"`、
   * 后端零消费者）不是一回事，⛔ 不要拿旧键回填这三个控件。
   */
  image_caption_enabled?: boolean
  /** 图片管线 · 是否做 OCR（`image_ocr_enabled`，V286 顶层列）。 */
  image_ocr_enabled?: boolean
  /** 图片管线方案（`image_pipeline`，V286 顶层列）：只允许 `default` | `smartocr`。 */
  image_pipeline?: KbImagePipelineName
}

/** 图片管线方案（后端 `KbImageSettings.PIPELINE_*`）· 只此两值。 */
export type KbImagePipelineName = 'default' | 'smartocr'

/** 创建知识库请求。 */
export interface CreateKnowledgeBaseBody {
  name: string
  description?: string | null
  type?: string
  embedding_model_id?: string | null
  summary_model_id?: string | null
  chunking_config?: string | null
  /**
   * 索引策略 —— **Wiki / RAG 开关的唯一载体**。
   *
   * <p>源侧创建时同一个请求就带上它（`KnowledgeBaseEditorModal.vue:1690-1694`）：
   * `{ vector_enabled, keyword_enabled, wiki_enabled, graph_enabled }`。
   * ⚠️ 源侧注释写死「Wiki enablement is carried solely by
   * `indexing_strategy.wiki_enabled`」⇒ Wiki **不是** `type` 的一个取值。
   *
   * <p>不传 = 不写（FAQ 库走这条）。
   */
  indexing_strategy?: {
    vector_enabled: boolean
    keyword_enabled: boolean
    wiki_enabled: boolean
    graph_enabled?: boolean
  } | null
  /**
   * 抽取配置 JSON（`extract_config`）。
   *
   * <p>⭐ 图谱判据是 `indexing_strategy.graph_enabled` ∧ `extract_config.enabled`
   * （`KbPostProcessConfig.graphEnabled`）⇒ 创建开图谱时必须两个字段一起写。
   *
   * <p>⚠️ 后端该字段是 **String**（TEXT 存 JSON）⇒ 调用方**已序列化**后传进来。
   * 本层⛔ 不要再 `JSON.stringify`（会二次转义，落库成带引号的字符串）。
   */
  extract_config?: string | null
  /** 图片管线 · 是否生成图片描述（`image_caption_enabled`，V286；缺省 = 后端默认 true）。 */
  image_caption_enabled?: boolean
  /** 图片管线 · 是否做 OCR（`image_ocr_enabled`，V286；缺省 = 后端默认 true）。 */
  image_ocr_enabled?: boolean
  /** 图片管线方案（V286）：只允许 `default` | `smartocr`，其它值后端 400。 */
  image_pipeline?: KbImagePipelineName
}

/** 更新知识库请求（null / 不传 = 不改）。 */
export interface UpdateKnowledgeBaseBody {
  name?: string
  description?: string | null
  embedding_model_id?: string | null
  summary_model_id?: string | null
  chunking_config?: string | null
  /** 图片管线三项（V286）· 不传 = 不改。 */
  image_caption_enabled?: boolean
  image_ocr_enabled?: boolean
  image_pipeline?: KbImagePipelineName
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

/**
 * 分块视图（后端 `ChunkController.ChunkView`，18 字段）。
 *
 * <p>`metadata` 是**原始 JSON 字符串**（后端原样透传，不解析）：
 * <pre>
 * {"generated_questions":[{"id":"q...","question":"...","content_revision":3}],
 *  "generated_questions_revision":3}
 * </pre>
 * 形态定义见后端 `KbGeneratedQuestions` 类注释。⛔ 前端也**不要**在这里解析成对象：
 * 消费点各自用 {@link parseGeneratedQuestions} 容错解析，坏 JSON 只影响自己的展示。
 */
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
  /** 原始 metadata JSON（可能为 null / 坏 JSON） */
  metadata?: string | null
}

/** 分块编辑历史的一条（后端 `ChunkController.RevisionView`，11 字段）。 */
export interface ChunkRevision {
  id: string
  chunk_id: string
  knowledge_id: string
  knowledge_base_id: string
  /** 版本号（1-based，逐条递增；回滚会**追加**新版本，不删历史） */
  revision: number | null
  content: string
  is_enabled: boolean
  editor_id: string | null
  /** 编辑来源：`user` / `rollback` 等（后端 `KbChunkService#writeSnapshot` 的入参） */
  edit_source: string | null
  /** `yyyy-MM-dd HH:mm:ss`（⛔ 不要 `new Date()` 解析） */
  edited_at: string | null
  /** `yyyy-MM-dd HH:mm:ss` */
  created_at: string | null
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

/**
 * 解析引擎能力（后端 `GET /api/v1/system/parser-engines` 的 `data[]` 元素）。
 *
 * <p>⚠️ **键名大写开头是对齐源站的**：源侧 Go 结构体未打 json tag，
 * 序列化出 `Name` / `Description` / `FileTypes` / `Available` / `UnavailableReason`；
 * 本仓 `ParserEngineInfo` 用 `@JsonProperty` 逐字锁定同一批键名
 * （`backend/.../kb/app/parser/ParserEngineInfo.java:24-28`）。
 * ⛔ 不要「顺手」改成 camelCase —— 后端就是按这套键名吐的。
 *
 * <p>`Description` 在本仓承载的是**展示名**（`KbDocumentParser#displayName()`，
 * 如「内置 PDF 解析」），不是长描述，可直接当下拉选项文案。
 */
export interface ParserEngineInfo {
  Name: string
  Description: string
  FileTypes: string[]
  Available: boolean
  UnavailableReason: string
}

/**
 * `rawBytes` 专用错误（二进制通道，不复用 {@link ApiError}）。
 *
 * <p><b>WHY 另起一个类而不是复用 ApiError</b>：`ApiError` 的语义是「JSON 接口失败」，
 * 调用方（列表 / 编辑弹层）会拿它的 `userMessage()` 直接展示；二进制取字节失败需要
 * 单独区分「有没有原文件」（404）与「网络/服务端故障」（0 / 5xx），
 * 混用会让「该文档没有原文件」被当成「服务异常，请重试」。
 */
export class KbRawBytesError extends Error {
  /** HTTP 状态码；0 = 网络层失败（连不上） */
  status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'KbRawBytesError'
    this.status = status
  }

  /** 后端明确说「没有原始文件」（手工创建 / URL 导入 / 文件已被清理）。 */
  get isMissingFile(): boolean {
    return this.status === 404
  }
}

/**
 * 上传并发上限 · 对齐源站 `stores/uploadTasksCore.ts` 的 `CONCURRENCY = 3`。
 *
 * <p>源站注释给的理由同样适用本仓：后端对每个文件「落盘 + 算哈希 + 入库」完才回包，
 * 3 条并发既能把管道填满，又不会把 SQLite 单写锁压成 `SQLITE_BUSY_SNAPSHOT`。
 */
export const UPLOAD_CONCURRENCY = 3

/**
 * 解析状态轮询间隔 · 对齐源站 `stores/uploadTasksCore.ts` 的 `POLL_INTERVAL_MS = 3000`。
 *
 * <p>上传完成不等于可用：后端还要切块、建索引、生成摘要/问题。太快只会把数据库
 * 连接占满（一次状态查询就要一条连接），太慢用户会以为卡住。
 */
export const UPLOAD_POLL_INTERVAL_MS = 3000

/**
 * 一次状态查询最多带多少个知识 id · 对齐源站 `POLL_CHUNK_SIZE = 40`。
 *
 * <p>钉住这个数是因为 `GET /knowledge/batch?ids=…` 走 query string：40 个 UUID
 * 约 1.5KB，离 URL 长度上限还很远；再多就有被网关截断的风险。
 */
export const UPLOAD_POLL_CHUNK_SIZE = 40

/** 同时在飞的**状态查询**条数上限 · 对齐源站 `POLL_CONCURRENCY = 4`（每条占一条后端数据库连接）。 */
export const UPLOAD_POLL_CONCURRENCY = 4

/** 连续几轮「查不到这一行」才判定文档已被删除（对齐源站 `MISSING_POLLS_BEFORE_DELETED = 3`）。 */
export const MISSING_POLLS_BEFORE_DELETED = 3

/** 一个待上传文件；`fileName` 非空 ⇒ 带目录的上传名（见 {@link buildUploadFileName}）。 */
export interface UploadManyItem {
  file: File
  fileName?: string
}

/** 并发池里单个任务的结局（靠 `ok` 判别：成功必有 `value`，失败必有 `error`）。 */
export type PoolOutcome<T, R> =
  | { item: T; ok: true; value: R; error?: undefined }
  | { item: T; ok: false; value?: undefined; error: unknown }

/**
 * 有界并发池（通用）。
 *
 * <h2>并发峰值为什么不会超过 `concurrency`</h2>
 * 只创建 `min(concurrency, items.length)` 个 worker，每个 worker 的循环是
 * 「取下一个下标 → await task → 结算后再取下一下标」——
 * 同一 worker 绝不会同时持有两条在飞任务 ⇒ 在飞任务数 ≤ worker 数。
 *
 * <h2>为什么单个失败不中断其它文件</h2>
 * worker 内部 `try/catch` 把异常收成 `{ok:false}`，既不打断自己的循环，
 * 也不会让外层 `Promise.all` 提前 reject。
 *
 * <p>`outcomes` 与 `items` **同序**（按下标回填），调用方可直接对位。
 */
export async function runPool<T, R>(
  items: readonly T[],
  concurrency: number,
  task: (item: T) => Promise<R>,
  options: { onSettled?: (done: number, total: number) => void } = {}
): Promise<Array<PoolOutcome<T, R>>> {
  const total = items.length
  if (total === 0) return []
  const outcomes = new Array<PoolOutcome<T, R>>(total)
  const workers = Math.min(Math.max(1, Math.floor(concurrency) || 1), total)
  let next = 0
  let done = 0
  const worker = async () => {
    for (;;) {
      const index = next++
      if (index >= total) return
      const item = items[index]
      try {
        outcomes[index] = { item, ok: true, value: await task(item) }
      } catch (error) {
        outcomes[index] = { item, ok: false, error }
      }
      done++
      options.onSettled?.(done, total)
    }
  }
  await Promise.all(Array.from({ length: workers }, worker))
  return outcomes
}

/**
 * 全局上传槽位（模块级）。
 *
 * <p>⭐ 源站只有一个 `uploadQueue` 实例 ⇒ `CONCURRENCY = 3` 是**整个应用**的上限。
 * 本仓有两个上传入口（文档面板头部的 `KbUploadSource` 与空态「上传文档」），
 * 各自开池会叠成 6 ⇒ 这里用模块级信号量把总在飞 POST 钉在 3。
 *
 * <p>槽位「直接交接」：release 时若有等待者就<b>不减计数</b>、直接把名额转交给它，
 * 避免「先减、被别人抢走、醒来再 ++」这条空窗 ⇒ 任何瞬间都不会超过 3。
 */
let uploadSlotsInUse = 0
const uploadSlotWaiters: Array<() => void> = []

function acquireUploadSlot(): Promise<void> {
  if (uploadSlotsInUse < UPLOAD_CONCURRENCY) {
    uploadSlotsInUse++
    return Promise.resolve()
  }
  return new Promise<void>((resolve) => uploadSlotWaiters.push(resolve))
}

function releaseUploadSlot(): void {
  const next = uploadSlotWaiters.shift()
  if (next) next()
  else uploadSlotsInUse--
}

/** 一个失败的上传项；`item` 原样保留，可直接再喂回 `uploadMany` 做重试。 */
export interface UploadFailure {
  /** 展示名：带目录时是 `规范/a.md`，否则是 `a.md` */
  name: string
  message: string
  item: UploadManyItem
}

/**
 * 一个**重复**文件：后端同库同 `file_hash` 返回 **409**（`ConflictException`）。
 *
 * <p>⭐ 重复不是失败：文件已经在库里，用户不需要重试，界面上要单列成「重复（已存在）」。
 * 混进 `failures` 会让「明明传成功了」报成失败，还给出一个必然再 409 的重试按钮。
 */
export interface UploadDuplicate {
  /** 展示名：带目录时是 `规范/a.md`，否则 `a.md` */
  name: string
  /** 后端 detail 原文（含既有条目标题；⛔ 不拿它当接口契约解析） */
  message: string
  item: UploadManyItem
}

/** 单条上传的终局。 */
export type UploadItemState = 'uploaded' | 'failed' | 'duplicate' | 'cancelled'

/** 一条输入的结局 · 上层据此做 item 级状态渲染与「只重跑这一条」。 */
export interface UploadSettledItem {
  item: UploadManyItem
  name: string
  state: UploadItemState
  /** 成功上传创建的知识 id（读取解析状态要用） */
  knowledgeId?: string
  message?: string
}

/** {@link knowledgeApi.uploadMany} 的结果。 */
export interface UploadManyResult {
  /** 成功条数 */
  ok: number
  /** 失败项明细（含原始 item ⇒ 支持「重试失败项」） */
  failures: UploadFailure[]
  /** 重复文件（409）—— **不计入失败**，界面上单列 */
  duplicates: UploadDuplicate[]
  /** 被取消（AbortSignal）而没传完的项；已成功的不回滚 */
  cancelled: UploadManyItem[]
  /** 成功的响应体，按**输入顺序**（不是完成顺序） */
  results: Knowledge[]
  /** 每条输入的结局，**与输入同序**（item 级重试/状态渲染用） */
  settled: UploadSettledItem[]
}

/** 整批取消时给池里还没跑完的任务用的哨兵错误（⛔ 不要与真实网络失败混为一谈）。 */
export class UploadCancelledError extends Error {
  constructor() {
    super('已取消上传')
    this.name = 'UploadCancelledError'
  }
}

/** 该错误是不是「后端判重」（同库同 hash ⇒ 409）。 */
export function isDuplicateUploadError(e: unknown): boolean {
  return e instanceof ApiError && e.status === 409
}

/** 该错误是不是取消（AbortController 中断 / 池内哨兵）。 */
export function isUploadCancellation(e: unknown): boolean {
  if (e instanceof UploadCancelledError) return true
  return typeof e === 'object' && e !== null
    && (e as { name?: unknown }).name === 'AbortError'
}

/** 上传失败文案：优先用 `ApiError.userMessage()` 的中文 detail。 */
function describeUploadError(e: unknown): string {
  if (e instanceof ApiError) return e.userMessage()
  if (e instanceof Error) return e.message
  return String(e)
}

export const knowledgeApi = {
  // ── 知识库 ──────────────────────────────────────────────────────
  listBases: () => api<KnowledgeBase[]>('/knowledge-bases'),

  getBase: (id: string) => api<KnowledgeBase>(`/knowledge-bases/${encodeURIComponent(id)}`),

  createBase: (body: CreateKnowledgeBaseBody) =>
    api<KnowledgeBase>('/knowledge-bases', {
      method: 'POST',
      body: {
        ...body,
        // ⚠️ 后端 `CreateKnowledgeBaseRequest.indexingStrategy` 是 **String**
        //    （列本身就是 TEXT 存 JSON），不是嵌套对象 ⇒ 必须在这里序列化。
        //    直接传对象会被 Jackson 当字符串转义成 `{"vector_enabled":...}` 字面量，
        //    落库后 `indexing_strategy` 变成一段双引号包裹的字符串，下游 parse 全空。
        ...(body.indexing_strategy === undefined || body.indexing_strategy === null
          ? {}
          : { indexing_strategy: JSON.stringify(body.indexing_strategy) }),
      },
    }),

  updateBase: (id: string, body: UpdateKnowledgeBaseBody) =>
    api<KnowledgeBase>(`/knowledge-bases/${encodeURIComponent(id)}`, { method: 'PUT', body }),

  deleteBase: (id: string) =>
    api<void>(`/knowledge-bases/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  /**
   * 解析引擎清单（后端注册表驱动 · `GET /api/v1/system/parser-engines`）。
   *
   * <p>响应外层是 `{success, data:[...]}`（与本仓 `/system/vector-stores/types`、
   * `/system/storage-backends/types` 同一种「下拉元数据」形状），这里解包出 `data`。
   * 其余知识库端点是**裸返 DTO**，不带这层包装 —— ⛔ 别把两种形状混起来。
   *
   * <p>⚠️ `api()` 只做 JSON 解析、**不做**包装解包（见 `api/rest.ts:140` 的
   * `return JSON.parse(raw) as T`），故解包必须写在这里。
   */
  listParserEngines: () =>
    api<{ success: boolean; data: ParserEngineInfo[] }>('/system/parser-engines')
      .then((r) => r?.data ?? []),

  // ── 知识条目 ────────────────────────────────────────────────────
  listKnowledge: (baseId: string, query: KnowledgeListQuery = {}) =>
    api<KnowledgePage>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/knowledge${toQuery(query as Record<string, unknown>)}`
    ),

  getKnowledge: (id: string) => api<Knowledge>(`/knowledge/${encodeURIComponent(id)}`),

  /**
   * 批量取知识条目（`GET /knowledge/batch?ids=a,b,c` · 后端 `KnowledgeExtController#batchGet`）。
   *
   * <p>⭐ 上传后的解析状态轮询走这条：后端**一次最多带 40 个 id**（见
   * {@link UPLOAD_POLL_CHUNK_SIZE}）比逐条 `GET /knowledge/{id}` 省 N 倍连接。
   *
   * <p>⚠️ 响应是**裸数组** `KnowledgeDto[]`（⛔ 不是 `{success,data}` 包装），
   * `ids` 用**逗号**分隔（后端 `splitIds` 只认 `,`，不是重复参数）。
   * 查不到的 id 直接不返回（⛔ 不报 404）⇒ 调用方要自己按「缺席」处理。
   */
  batchGetKnowledge: (ids: string[]) =>
    api<Knowledge[]>(`/knowledge/batch${toQuery({ ids: ids.join(',') })}`),

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

  /**
   * 按分块 ID 取单个分块（后端 `ChunkController#getById` · `GET /chunks/by-id/{id}`）。
   *
   * <p>「父块上下文」按钮用这个：`parent_chunk_id` 可能属于**另一篇文档**
   * （摘要块挂靠首个正文块时同一文档，但字段语义不保证）⇒ 不能靠 `listChunks` 过滤，
   * 必须走这条不需要 knowledgeId 的端点。
   */
  getChunkById: (id: string) =>
    api<ChunkView>(`/chunks/by-id/${encodeURIComponent(id)}`),

  /**
   * 更新分块（后端 `ChunkController#update` · `PUT /chunks/{knowledgeId}/{id}`）。
   *
   * <p>⚠️ 请求体是 **snake_case** `is_enabled`；发 `isEnabled` 会被 Jackson 静默忽略。
   * 两个字段都可省（undefined/null = 不改）。
   *
   * <p>⚠️ **索引重建结果不可知**：后端先提交事务再重建索引，索引失败只 `log.error`，
   * 响应仍是 200 ⇒ 前端**只能**说「内容已保存」，⛔ 不能说「已重建索引 / 搜索已更新」。
   */
  updateChunk: (
    knowledgeId: string,
    id: string,
    body: { content?: string; is_enabled?: boolean }
  ) =>
    api<ChunkView>(
      `/chunks/${encodeURIComponent(knowledgeId)}/${encodeURIComponent(id)}`,
      { method: 'PUT', body }
    ),

  /**
   * 分块编辑历史（后端 `ChunkController#revisions`，裸数组，**倒序**）。
   *
   * <p>⚠️ 本仓只在**编辑/回滚时写快照**（`KbChunkService#writeSnapshot`）⇒
   * 从未编辑过的分块返回空数组，这是真实状态，⛔ 不要拿「当前内容」伪造一条。
   */
  listChunkRevisions: (knowledgeId: string, id: string) =>
    api<ChunkRevision[]>(
      `/chunks/${encodeURIComponent(knowledgeId)}/${encodeURIComponent(id)}/revisions`
    ),

  /**
   * 回滚到指定版本（后端 `ChunkController#revert` · `POST /chunks/{knowledgeId}/{id}/revert`）。
   *
   * <p>⚠️ 语义是「把目标版本内容写回正文 + **追加**一条新 revision」，⛔ 不是删历史，
   * 也不可撤销（回滚本身也会产生一个新版本，可以再回滚回来）。
   * `revision` 必填，缺 ⇒ 后端 400。
   */
  revertChunk: (knowledgeId: string, id: string, revision: number) =>
    api<ChunkView>(
      `/chunks/${encodeURIComponent(knowledgeId)}/${encodeURIComponent(id)}/revert`,
      { method: 'POST', body: { revision } }
    ),

  /**
   * 新增 / 修改一条生成问句（后端 `ChunkController#upsertGeneratedQuestion`
   * · `PUT /chunks/by-id/{id}/questions`）。
   *
   * <p>⚠️ **本仓问句没有独立 id**（存在 `metadata.generated_questions` 里）⇒
   * `question_id` 的语义是「要替换的**旧问句文本**」：
   * 空 ⇒ 新增；非空 ⇒ 原地替换。⛔ 传一个不存在的旧文本 ⇒ 后端 400。
   *
   * <p>⚠️ 响应的 `index_rebuilt=false` 表示「问句已保存但索引没重建好」——
   * 这时搜索仍是旧文本，⛔ 不要笼统说「已保存」。
   */
  upsertChunkQuestion: (chunkId: string, body: { question_id?: string; question: string }) =>
    api<{
      chunk: ChunkView
      added: boolean
      questions: string[]
      index_rebuilt: boolean
    }>(`/chunks/by-id/${encodeURIComponent(chunkId)}/questions`, { method: 'PUT', body }),

  /**
   * 重新生成某分块的辅助问句（后端 `POST /chunks/by-id/{id}/questions/regenerate`）。
   *
   * <p>⚠️ 会真的调一次模型（几秒）⇒ 按钮要显示 loading、禁止重复点。
   * ⚠️ `status` 可能是 `SKIPPED`（问句配置关闭 / 无模型 / 无可生成文本）——
   * 必须把 `detail` 显示给用户，⛔ 不能说「已重新生成」。
   */
  regenerateChunkQuestions: (chunkId: string) =>
    api<{
      chunk: ChunkView
      questions: string[]
      status: string
      detail: string | null
    }>(`/chunks/by-id/${encodeURIComponent(chunkId)}/questions/regenerate`, { method: 'POST' }),

  /**
   * 删除分块里的一条生成问句（后端 `DELETE /chunks/by-id/{id}/questions`）。
   *
   * <p>⚠️ 后端按**问句文本**匹配（本仓问句无独立 id）⇒ 直接传文本。
   * 响应只有 `{deleted}`，**不返回新列表** ⇒ 调用方要自己刷新。
   */
  deleteChunkQuestion: (chunkId: string, questionText: string) =>
    api<{ deleted: boolean; note?: string }>(
      `/chunks/by-id/${encodeURIComponent(chunkId)}/questions`,
      { method: 'DELETE', body: { question_id: questionText } }
    ),

  // ⚠️ 2026-11-13 删除 getKnowledgeStages：它声明的 title/spans/attempts 与后端
  //   /stages 实际返回的 KnowledgeSpansDto（/spans 同一 handler）一个字段都对不上，
  //   全仓零调用方。处理流水线统一走 getKnowledgeSpans + KbProcessingTimeline。

  deleteKnowledge: (id: string) =>
    api<void>(`/knowledge/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  reparse: (id: string) =>
    api<{ task_id: number }>(`/knowledge/${encodeURIComponent(id)}/reparse`, { method: 'POST' }),

  /**
   * 某一次尝试的 span 树（后端 `GET /api/v1/knowledge/{id}/spans`）。
   *
   * <p>⚠️ 响应**带 `{success, data}` 包装**（与 `/system/parser-engines` 同一种「元数据」形状，
   * 不像多数知识库端点是裸 DTO）⇒ 这里**不解包**：调用方要能区分「成功但没有 trace」与
   * 「请求失败」，包装里的 `success` 是唯一判据（`KbProcessingTimeline.fetchSpans` 按
   * `res?.success && res.data` 判定）。
   *
   * <p>`attempt` 只在 `> 0` 时才拼 query —— 后端 `?attempt=` 是「取第 N 次尝试」，
   * 传 0/负数会落到「最新一次」的兜底分支，与不传等价但会污染日志。
   */
  getKnowledgeSpans: (id: string, attempt?: number) =>
    api<{ success: boolean; data?: SpansResponse }>(
      `/knowledge/${encodeURIComponent(id)}/spans${toQuery({
        attempt: typeof attempt === 'number' && attempt > 0 ? attempt : undefined,
      })}`,
    ),

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
   * @param signal   取消信号（「取消全部」用；中断在飞的请求）
   */
  uploadKnowledge: async (
    baseId: string,
    file: File,
    fileName?: string,
    signal?: AbortSignal
  ): Promise<Knowledge> => {
    const form = new FormData()
    form.append('file', file)
    if (fileName) form.append('fileName', fileName)
    const { API_V1_BASE } = await import('./base')
    const res = await fetch(
      `${API_V1_BASE}/knowledge-bases/${encodeURIComponent(baseId)}/knowledge/file`,
      {
        method: 'POST',
        body: form,
        headers: { Accept: 'application/json', 'X-Client-Env': 'react' },
        // ⚠️ 不能自己设 Content-Type：multipart 的 boundary 必须由浏览器生成
        ...(signal ? { signal } : {}),
      }
    )
    if (!res.ok) {
      const { parseProblem } = await import('./rest')
      throw await parseProblem(res)
    }
    return (await res.json()) as Knowledge
  },

  /**
   * 多文件上传 · **并发池**（⛔ 不是 `for-await` 串行）。
   *
   * <p>对齐源站 `stores/uploadQueue.ts` 的 `pump()`：最多
   * {@link UPLOAD_CONCURRENCY} 条 `POST /knowledge-bases/{id}/knowledge/file`
   * 同时在飞。后端**没有批量端点**，所以每个文件仍是一次独立 POST（⛔ 不合并）。
   *
   * <p>单个文件失败只进 `failures`，不影响其它文件继续上传；
   * `failures[i].item` 可原样再传给本方法 ⇒ 上层能实现「重试失败项」。
   * ⭐ **重复文件（后端 409）进 `duplicates`，不计入 `failures`** —— 它不是失败。
   *
   * <p>⭐ 并发同时受两层约束：本池 worker 数（`concurrency`，默认 3）与
   * **模块级全局槽位**（恒 3，跨入口共享）—— 见 {@link UPLOAD_CONCURRENCY}。
   *
   * @param options.concurrency 覆盖本池 worker 数（默认 3；全局槽位仍是 3）
   * @param options.onProgress  每有一个文件结算（成功或失败）回调一次 `(done, total)`
   * @param options.signal       取消信号（「取消全部」）；已成功的项不回滚，没跑完的进 `cancelled`
   */
  uploadMany: async (
    baseId: string,
    items: UploadManyItem[],
    options: {
      concurrency?: number
      onProgress?: (done: number, total: number) => void
      signal?: AbortSignal
    } = {}
  ): Promise<UploadManyResult> => {
    const { signal } = options
    const outcomes = await runPool(
      items,
      options.concurrency ?? UPLOAD_CONCURRENCY,
      async (item) => {
        // 已取消：⛔ 连槽位都不占（否则取消后还要排队等前一个传完）
        if (signal?.aborted) throw new UploadCancelledError()
        await acquireUploadSlot()
        try {
          if (signal?.aborted) throw new UploadCancelledError()
          return await knowledgeApi.uploadKnowledge(baseId, item.file, item.fileName, signal)
        } finally {
          releaseUploadSlot()
        }
      },
      { onSettled: options.onProgress }
    )
    const results: Knowledge[] = []
    const failures: UploadFailure[] = []
    const duplicates: UploadDuplicate[] = []
    const cancelled: UploadManyItem[] = []
    const settled: UploadSettledItem[] = []
    for (const outcome of outcomes) {
      const name = outcome.item.fileName || outcome.item.file.name
      if (outcome.ok) {
        results.push(outcome.value)
        settled.push({ item: outcome.item, name, state: 'uploaded', knowledgeId: outcome.value.id })
      } else if (isUploadCancellation(outcome.error)) {
        cancelled.push(outcome.item)
        settled.push({ item: outcome.item, name, state: 'cancelled' })
      } else if (isDuplicateUploadError(outcome.error)) {
        const message = describeUploadError(outcome.error)
        duplicates.push({ name, message, item: outcome.item })
        settled.push({ item: outcome.item, name, state: 'duplicate', message })
      } else {
        const message = describeUploadError(outcome.error)
        failures.push({ name, message, item: outcome.item })
        settled.push({ item: outcome.item, name, state: 'failed', message })
      }
    }
    return { ok: results.length, failures, duplicates, cancelled, results, settled }
  },

  /**
   * 上传整个文件夹（保留目录结构）。
   *
   * <p>⭐ 浏览器在「选择目录」时会给每个 File 填 `webkitRelativePath`
   * （如 `规范/运维/a.txt`）。把它拼进 `fileName` 后，后端按最后一个 `/` 拆成
   * `folder_path` + 文件名 —— 这就是子文件夹产生的<b>唯一</b>途径
   * （文件夹树是从 `folder_path` 派生的，没有「新建空文件夹」接口）。
   *
   * <p>本方法是 {@link knowledgeApi.uploadMany} 的薄封装：目录名仍在**每个 item**
   * 上拼好，再交给同一个并发池 ⇒ 保留目录结构与并发上传不冲突。
   *
   * @param baseId       知识库 ID
   * @param files        目录选择得到的文件列表
   * @param targetFolder 目标文件夹（可空 = 根）
   * @param onProgress   每结算一个文件回调 `(done, total)`
   */
  uploadFolder: async (
    baseId: string,
    files: File[],
    targetFolder = '',
    onProgress?: (done: number, total: number) => void
  ): Promise<{ ok: number; failures: string[] }> => {
    const r = await knowledgeApi.uploadMany(
      baseId,
      files.map((f) => ({
        file: f,
        fileName: buildUploadFileName(f as File & { webkitRelativePath?: string }, targetFolder),
      })),
      { onProgress }
    )
    return { ok: r.ok, failures: r.failures.map((f) => `${f.name}: ${f.message}`) }
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
   * 知识库对话**统一入口**（`POST /api/v1/kb/chat/{sid}`）。
   *
   * <p>后端按会话绑定的 agent 的 `agent_mode` 分流：`quick-answer` → RAG 流水线，
   * 其余 → ReAct 工具循环。⛔ 调用方不要自己判断模式 —— 那等于把分流规则抄两遍。
   *
   * <p>返回 202；内容走 STOMP `/topic/sessions/{sid}/stream`（与两条链同一个 topic）。
   */
  kbChat: (sessionId: string, body: { content: string; knowledgeBaseIds?: string[]; knowledgeIds?: string[]; attachments?: Array<{ type: string; filename: string }>; modelName?: string }) =>
    api<{ route?: string; userMessageId?: string; assistantMessageId?: string; streamTopic?: string }>(
      `/kb/chat/${encodeURIComponent(sessionId)}`,
      { method: 'POST', body },
    ),

  /**
   * 知识库工作区项目（`GET /api/v1/kb/workspace/project`）。
   *
   * <p>助手 / 问答面板建专用会话时，`mainProjectId` 必须传它 —— ⛔ 不要再取
   * `projectApi.list()[0]`：项目列表按 SQLite rowid 返回（后端无 ORDER BY），
   * 随手拿到的那条是用户第一个注册的仓库，会把助手会话的 cwd / 记忆 / 产物写进用户仓库。
   *
   * <p>服务端按 `<安装目录>/workspace/kb` → `<配置根>/workspace/kb` 降级解析并幂等注册，
   * 浏览器不需要知道目录在哪。
   */
  workspaceProject: () => api<ProjectDto>('/kb/workspace/project'),

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

  /**
   * 会话级引用（全部行，含 assistantMessageId）。
   *
   * <p>对齐源站 {@code message.KnowledgeReferences}：刷新后前端按消息分桶，
   * 每一轮都能重画 chip 的文档名（不只最新一轮）。
   */
  listSessionCitations: (sessionId: string) =>
    api<KbCitation[]>(`/sessions/${encodeURIComponent(sessionId)}/citations`),

  /**
   * 知识库图谱（`GET /knowledge-bases/{id}/graph`）。
   *
   * <p>只用于「推荐问题」取真实业务概念：实体名比文档标题更接近人话
   * （源站推荐问题样本就是「介绍一下义乌市绣诚…」「什么是人工智能+治理能力？」）。
   */
  getGraph: (baseId: string) => api<{
    entities?: Array<{ id?: string; name?: string; entity_type?: string; source_chunk_ids?: string[] }>
    relationships?: unknown[]
  }>(`/knowledge-bases/${encodeURIComponent(baseId)}/graph`),

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

  /**
   * 跨知识库移动：可选的**目标知识库**列表（`GET /knowledge-bases/{id}/move-targets`）。
   *
   * <p>⚠️ 后端排除**源库自己**（返回里不会有当前库）⇒ 列表为空 = 没有别的库可移，
   * 界面要如实说「没有其它知识库」，⛔ 不要拿当前库凑一个选项。
   */
  listMoveTargets: (baseId: string) =>
    api<Array<{
      id: string
      name: string
      description: string | null
      type: string | null
      knowledge_count: number
    }>>(`/knowledge-bases/${encodeURIComponent(baseId)}/move-targets`),

  /**
   * 把文档移动到另一个知识库（`POST /knowledge/move`）。
   *
   * <p>⚠️ 本仓是**同步**完成（源侧是异步任务）⇒ 返回后即可刷新列表，不需要轮询进度。
   */
  moveKnowledge: (body: { source_kb_id: string; target_kb_id: string; knowledge_ids: string[] }) =>
    api<{ moved: number }>('/knowledge/move', { method: 'POST', body }),

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

  /**
   * 取文档原始字节（预览用）。
   *
   * <p>⚠️ 与 {@link downloadUrl} 的区别：那个返回 **URL**（给 `<PdfPreview>` / 下载链接直接消费），
   * 这个直接 fetch 成 `Uint8Array`（给 docx / xlsx 的行内渲染器消费，也用于先探测「有没有原文件」）。
   *
   * <p>⚠️ **不能**用 {@link api}：它会 `res.text()` + `JSON.parse`，二进制会被当非法 JSON 抛错。
   * 这里走裸 `fetch`，只在 HTTP 失败时按 RFC 7807 解析错误体。
   *
   * <p>⚠️ 无原始文件时后端返回 **404**（`KnowledgeExtController#download` →
   * `NotFoundException("文档 X 没有可下载的原始文件（手工创建 / URL 导入的文档不保存原文件）")`），
   * 抛 {@link KbRawBytesError}（带 `status`），调用方据此显示「只能查看分块」而不是「重试」。
   */
  rawBytes: async (id: string): Promise<Uint8Array> => {
    const url = `${API_V1_BASE}/knowledge/${encodeURIComponent(id)}/download`
    let res: Response
    try {
      res = await fetch(url, {
        // 后端 download 的 produces 是 application/octet-stream；显式声明避免内容协商 406
        headers: { Accept: 'application/octet-stream', 'X-Client-Env': 'react' },
      })
    } catch (e) {
      // 网络层失败（断网 / 后端未起 / CORS）
      throw new KbRawBytesError(
        `网络错误：无法连接后端（${e instanceof Error ? e.message : String(e)}）`,
        0
      )
    }
    if (!res.ok) {
      // 错误体是 RFC 7807 Problem（JSON）· 解析失败时退回 statusText
      let detail = ''
      try {
        const body = (await res.json()) as { detail?: string; message?: string; title?: string }
        detail = body?.detail ?? body?.message ?? body?.title ?? ''
      } catch {
        // 非 JSON 错误体（网关页 / 空响应）—— 不掩盖，下面用状态码兜底
      }
      throw new KbRawBytesError(
        `HTTP ${res.status}${detail ? `：${detail}` : ` ${res.statusText}`}`,
        res.status
      )
    }
    return new Uint8Array(await res.arrayBuffer())
  },

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
  /**
   * 活动流。
   *
   * <p>⭐ **契约修正（B8）**：后端 `KnowledgeBaseAdvancedController#activity` 返回的是
   * `{"data":[...]}`（`record ActivityPage(@JsonProperty("data") List<ActivityItem> data)`），
   * 而这里原来读的是 `r.items` ⇒ **永远返回空数组** ⇒ 活动记录面板恒显示
   * 「暂无活动记录」，接口 200、零报错。
   *
   * <p>字段名同样修正：后端是 `details`（复数），面板原来读 `detail`。
   */
  listActivity: async (baseId: string, limit = 50) => {
    const r = await api<{
      data?: Array<{
        id: number
        action: string
        outcome: string
        actor_user_id: string
        actor_role: string
        target_type: string
        target_id: string
        details: string
        created_at: string
      }>
    }>(`/knowledge-bases/${encodeURIComponent(baseId)}/activity?limit=${limit}`)
    return r?.data ?? []
  },

  // ── 知识库画像（`POST /knowledge-bases/{id}/profile/generate`）──────
  /**
   * 生成 AI 画像描述（源侧 `POST /knowledge-bases/{id}/profile/generate`）。
   *
   * <p>响应 `{profile:{gist,topics}, status, document_count, model}`；
   * `status` 非成功时 `profile` 可能为空对象（不是 null），调用方按 `profile?.gist` 判空。
   */
  /**
   * 向量存储列表（`GET /api/v1/vector-stores`）。
   *
   * <p>本仓后端有 `VectorStoreController`（list/types/get/update/test/delete），
   * **前端此前一条都没接** ⇒ 「向量存储」面板只能显示假的默认值。
   */
  listVectorStores: () =>
    api<Array<{
      id: string
      name: string
      engine_type?: string
      provider?: string
      status?: string
      is_default?: boolean
      available?: boolean
      unavailable_reason?: string
    }>>('/vector-stores'),

  /**
   * 存储后端实例列表（`GET /api/v1/storage-backends`）。
   *
   * <p>⚠️ 与向量库同构：后端有全套 CRUD，前端此前未接。
   */
  listStorageBackends: () =>
    api<Array<{
      id: string
      name: string
      provider: string
      source?: string
      status?: string
      available?: boolean
      is_default?: boolean
      unavailable_reason?: string
    }>>('/storage-backends'),

  /**
   * 生成知识库画像（源站 `POST /knowledge-bases/{id}/profile/generate`）。
   *
   * ⭐ 2026-11-11：`profile` 的形态从「字符串」改成「对象」，与后端新契约一致
   * （后端此前返字符串、前端按对象读 ⇒ 契约错配，「AI 生成描述」按钮拿不到 gist）。
   * 对象字段照源站 `KnowledgeBaseProfile`。
   */
  generateProfile: (baseId: string, body?: { model?: string }) =>
    api<{
      profile: {
        gist?: string
        topics?: string[]
        typical_questions?: string[]
        status?: string
        error?: string
        model_id?: string
        generated_at?: string
        aggregate_hash?: string
        stats?: {
          document_count?: number
          profiled_count?: number
          earliest_at?: string
          latest_at?: string
          file_types?: string[]
          tags?: string[]
          folders?: string[]
          raw_topics?: string[]
        }
      } | null
      status: string
      document_count: number
      model: string
    }>(`/knowledge-bases/${encodeURIComponent(baseId)}/profile/generate`, {
      method: 'POST',
      body: body ?? {},
    }),

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

  // ── 摘要（对齐源侧 knowledge-base/index.ts:709-718）─────────────────

  /**
   * 手动重新生成摘要（`POST /knowledge/{id}/regenerate-summary`）。
   *
   * <p><b>同步返回</b>：后端是一次 LLM 调用，做完才返回 ⇒ 前端必须自己维护 loading，
   * 否则用户会以为「点了没反应」。
   *
   * <p>⚠️ 判定依据是 `summary_status`，不是「有没有 description」：
   * `failed` 时 description 仍可能是上一次的旧值。
   */
  regenerateSummary: (id: string) =>
    api<{ summary_status: string; description: string; detail?: string }>(
      `/knowledge/${encodeURIComponent(id)}/regenerate-summary`,
      { method: 'POST' },
    ),

  /**
   * 保存手改的摘要正文（`PUT /knowledge/{id}` 的 `description` 字段）。
   *
   * <p>⚠️ 只发改动过的字段：该端点是「传什么写什么」，把整行回传容易冲掉并发改动。
   */
  updateSummary: (id: string, description: string) =>
    api<Knowledge>(`/knowledge/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: { description },
    }),

  /**
   * 保存自定义元数据（`PUT /knowledge/{id}` 的 `custom_metadata` 字段）。
   *
   * <p>⚠️ 后端该列是 **TEXT 存 JSON 文本**（`KnowledgeDto.customMetadata: String`），
   * 而源侧接的是对象 ⇒ 这里必须 `JSON.stringify`。传对象会让后端反序列化失败。
   */
  updateCustomMetadata: (id: string, metadata: Record<string, unknown>) =>
    api<Knowledge>(`/knowledge/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: { custom_metadata: JSON.stringify(metadata) },
    }),
}

/**
 * 解析完成（正文已可检索）。
 *
 * <p>`finalizing` 也算完成：源站的 `itemPhase` 把 `finalizing` 映射成 `ready` ——
 * 切块/索引已经落好，摘要、问题生成、图谱这些后台增强还在跑。
 */
export function isParseCompleted(status: string | undefined): boolean {
  return status === 'completed' || status === 'finalizing'
}

/**
 * 是否还要继续轮询 · 与源站 `uploadTasksState.needsParsePolling` 同口径。
 *
 * <p>`finalizing` 仍要盯：下一轮它才会落成 `completed`（面板据此收敛）。
 * 未取到状态（`undefined`）也算「还要轮」—— 刚上传那几秒后端就是这么答的。
 */
export function needsParsePolling(status: string | undefined): boolean {
  if (status === undefined || status === '') return true
  return status === 'unprocessed' || status === 'pending' || status === 'processing' || status === 'finalizing'
}

/** 终态（不再轮询）：`completed` / `failed` / `cancelled` / `deleting` / `deleted`。 */
export function isParseSettled(status: string | undefined): boolean {
  return !needsParsePolling(status)
}

/** 状态查询返回的一行（只要轮询用得上的两个字段）。 */
export interface ParseStatusRow {
  id: string
  parse_status?: string
}

/** {@link pollParseStatuses} 的注入点（网络与等待都可替换 ⇒ 纯逻辑可测）。 */
export interface PollParseStatusOptions {
  /** 轮间隔，默认 {@link UPLOAD_POLL_INTERVAL_MS}（3000ms） */
  intervalMs?: number
  /** 单次查询最多带多少 id，默认 {@link UPLOAD_POLL_CHUNK_SIZE}（40） */
  chunkSize?: number
  /** 同时在飞的查询条数，默认 {@link UPLOAD_POLL_CONCURRENCY}（4） */
  concurrency?: number
  /** 硬上限轮数（默认 400 轮 ≈ 20 分钟），防后端永远不收敛时无限轮询 */
  maxRounds?: number
  signal?: AbortSignal
  /** 每轮结束回调一份「id → parse_status」全量快照（面板据此实时刷新） */
  onUpdate?: (statuses: Record<string, string>) => void
  /** 单批查询；默认走 {@link knowledgeApi.batchGetKnowledge} */
  query?: (ids: string[]) => Promise<ParseStatusRow[] | null>
  /** 等待实现；默认 `setTimeout`（测试注入即可免等真 3 秒） */
  sleep?: (ms: number) => Promise<void>
}

/**
 * 轮询一批知识条目的解析状态，**全部 settled 后自动停止**。
 *
 * <h2>对齐源站 `uploadQueue.ts` 的 `pollNow()`</h2>
 * <ul>
 *   <li>间隔 {@link UPLOAD_POLL_INTERVAL_MS}（3000ms）</li>
 *   <li>一次查询最多 {@link UPLOAD_POLL_CHUNK_SIZE}（40）个 id —— 走
 *       `GET /knowledge/batch?ids=a,b,c`（源站同款 `batchQueryKnowledge`）</li>
 *   <li>查询并发 ≤ {@link UPLOAD_POLL_CONCURRENCY}（4），每条各占一条后端数据库连接</li>
 *   <li>某 id 连续 {@link MISSING_POLLS_BEFORE_DELETED}（3）轮查不到 ⇒ 记 `deleted`
 *       并停止轮它（文档在别处被删；⛔ 一轮缺席就判死会误报）</li>
 * </ul>
 *
 * <p>单次查询失败（网络抖动 / 500）只等下一轮，不把整批判死 —— 返回 `null` 即跳过本轮。
 *
 * @param ids     要轮询的知识 id（重复会被自然去重）
 * @returns 最终「id → parse_status」表；未出现在表里 = 一直没查到
 */
export async function pollParseStatuses(
  ids: readonly string[],
  options: PollParseStatusOptions = {}
): Promise<Record<string, string>> {
  const intervalMs = options.intervalMs ?? UPLOAD_POLL_INTERVAL_MS
  const chunkSize = Math.max(1, options.chunkSize ?? UPLOAD_POLL_CHUNK_SIZE)
  const concurrency = Math.max(1, options.concurrency ?? UPLOAD_POLL_CONCURRENCY)
  const maxRounds = Math.max(1, options.maxRounds ?? 400)
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)))
  const query = options.query ?? (async (batch: string[]) => {
    try {
      return await knowledgeApi.batchGetKnowledge(batch)
    } catch {
      return null
    }
  })

  const statuses: Record<string, string> = {}
  const pending = new Set(ids.filter(Boolean))
  const misses = new Map<string, number>()

  for (let round = 0; round < maxRounds && pending.size > 0; round++) {
    if (options.signal?.aborted) break
    const all = [...pending]
    const chunks: string[][] = []
    for (let i = 0; i < all.length; i += chunkSize) chunks.push(all.slice(i, i + chunkSize))

    await runPool(chunks, concurrency, async (chunk) => {
      const rows = await query(chunk)
      // 查询失败 ⇒ 本轮跳过（下一轮再问），⛔ 不把条目判成失败
      if (!rows || options.signal?.aborted) return
      const byId = new Map(rows.map((r) => [r.id, r]))
      for (const id of chunk) {
        const row = byId.get(id)
        if (!row) {
          const n = (misses.get(id) ?? 0) + 1
          misses.set(id, n)
          if (n >= MISSING_POLLS_BEFORE_DELETED) {
            statuses[id] = 'deleted'
            pending.delete(id)
            misses.delete(id)
          }
          continue
        }
        misses.delete(id)
        const status = row.parse_status || 'pending'
        statuses[id] = status
        if (needsParsePolling(status)) pending.add(id)
        else pending.delete(id)
      }
    })

    options.onUpdate?.({ ...statuses })
    if (pending.size === 0 || options.signal?.aborted) break
    // ⭐ 已全部 settled ⇒ 不再等待（收敛后立刻停）
    await sleep(intervalMs)
  }

  return statuses
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
  /** 归属的 assistant 消息 id（会话级接口按它分桶，刷新后每轮各自重画 chip） */
  assistantMessageId?: string
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
