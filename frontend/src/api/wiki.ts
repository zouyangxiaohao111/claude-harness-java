import { api } from './rest'

/**
 * Wiki API 客户端 · W3。
 *
 * <h2>为什么单独一个文件而不是塞进 `knowledge.ts`</h2>
 * `knowledge.ts` 已经 244 行、覆盖库/条目/检索三块。Wiki 有 22 个端点，
 * 混进去会变成 500+ 行的巨型模块 —— 改一处要想「会不会碰到别处」。
 * 两者唯一的耦合是知识库 ID，传参即可。
 *
 * <h2>⚠️ 路径是两段式 `<type>/<name>`，不是 `slug`</h2>
 * 后端的 `GET /wiki/pages/{type}/{name}` 接收<b>分开的两段</b>再拼回 `type/name`。
 * 原因：Spring 6 的正则路径变量**不跨路径段**，带斜杠的 slug 用 `{slug:.+}` 会静默 404
 * （已在 3472 真机验证）。⇒ 前端调用时必须自己拆。
 *
 * <h2>⚠️ 字段全 snake_case</h2>
 * 后端 DTO 逐字段 `@JsonProperty` 钉死成源侧契约。
 * ⛔ 不要在这里转 camelCase：多一层转换就多一处「转错就静默丢字段」。
 */

// ══════════════════════════════════════════════════════════════
// 类型
// ══════════════════════════════════════════════════════════════

/** 页面类型（与后端 `KbWikiSlug` 的类型前缀一一对应）。 */
export type WikiPageType = 'entity' | 'concept' | 'summary' | 'index' | 'synthesis' | 'comparison'

/** Wiki 页面（后端 `WikiPageDto`）。 */
export interface WikiPage {
  id: string
  slug: string
  title: string
  page_type: string
  status: string
  summary: string | null
  content: string
  folder_id: string
  wiki_path: string
  depth: number
  aliases: string[]
  source_refs: string[]
  chunk_refs: string[]
  out_links: string[]
  in_links: string[]
  version: number
  created_at: string | null
  updated_at: string | null
}

/** 目录（后端 `FolderDto`）。 */
export interface WikiFolder {
  id: string
  parent_id: string
  name: string
  path: string
  depth: number
  sort_order: number
}

/** 图谱节点。 */
export interface WikiGraphNode {
  id: string
  title: string
  type: string
}

/** 图谱边。 */
export interface WikiGraphEdge {
  source: string
  target: string
}

/** 图谱数据。 */
export interface WikiGraph {
  nodes: WikiGraphNode[]
  edges: WikiGraphEdge[]
}

/** Wiki 统计。 */
export interface WikiStats {
  total_pages: number
  total_links: number
  broken_links: number
  orphan_count: number
  folder_count: number
  by_type: Record<string, number>
}

/** 索引页。 */
export interface WikiIndex {
  title: string
  intro: string
  toc: string
  page_count: number
}

/** Lint 单条问题。 */
export interface WikiLintIssue {
  type: string
  severity: 'error' | 'warning' | 'info'
  page_slug: string
  target_slug: string | null
  description: string
  auto_fixable: boolean
}

/** Lint 报告。 */
export interface WikiLintReport {
  issues: WikiLintIssue[]
  health_score: number
  summary: string
  error_count: number
  warning_count: number
  info_count: number
}

/** 已落库的问题。 */
export interface WikiIssue {
  id: string
  slug: string
  issue_type: string
  severity: string
  description: string
  status: 'pending' | 'resolved' | 'ignored'
  reported_by: string
  created_at: string | null
}

/** 版本快照。 */
export interface WikiRevision {
  id: string
  slug: string
  version: number
  title: string
  page_type: string
  summary: string | null
  content: string
  edit_source: string
  edited_at: string | null
}

/**
 * 手动生成 Wiki 的结果（后端 `WikiRebuildResponse`）。
 *
 * <p>⚠️ `status` 是判定依据，⛔ 不要只看 `created_pages.length`：
 * `skipped` 时后端 HTTP 仍是 200，且各计数全为 0 —— 当成成功会骗用户。
 */
export interface WikiRebuildResult {
  /** `done | skipped | failed` */
  status: 'done' | 'skipped' | 'failed' | string
  /** 人话说明（用户直接看到） */
  detail: string
  docs_processed: number
  docs_skipped: number
  docs_failed: number
  created_pages: string[]
  updated_pages: string[]
  rejected_pages: number
  dead_links_removed: number
  cross_links_added: number
}

// ══════════════════════════════════════════════════════════════
// slug 拆分
// ══════════════════════════════════════════════════════════════

/**
 * 把 `entity/acme` 拆成路径用的两段。
 *
 * <p>⚠️ <b>必须拆</b>：后端路径是 `{type}/{name}`（见文件头注释）。
 * 直接把 `entity/acme` 塞进一个路径段会 404，而且 404 的原因是
 * 「路径不匹配」而不是「页面不存在」，日志里看不出区别。
 *
 * <p>兜底：没有斜杠时 type 用 `entity`（后端 `joinSlug` 在 type 为空时会返回 name，
 * 但那会让所有无类型 slug 撞在一起）。
 *
 * @param slug 完整 slug
 * @returns `[type, name]`，两者都已 URL 编码
 */
export function splitSlug(slug: string): [string, string] {
  const s = (slug ?? '').trim()
  const i = s.indexOf('/')
  if (i <= 0 || i === s.length - 1) {
    return ['entity', encodeURIComponent(s)]
  }
  return [
    encodeURIComponent(s.slice(0, i)),
    encodeURIComponent(s.slice(i + 1)),
  ]
}

/**
 * 拼出页面路径段。
 *
 * @param baseId 知识库 ID
 * @param slug   完整 slug
 * @returns 形如 `/knowledge-bases/xxx/wiki/pages/entity/acme`
 */
function pagePath(baseId: string, slug: string): string {
  const [type, name] = splitSlug(slug)
  return `/knowledge-bases/${encodeURIComponent(baseId)}/wiki/pages/${type}/${name}`
}

// ══════════════════════════════════════════════════════════════
// 客户端
// ══════════════════════════════════════════════════════════════

export const wikiApi = {
  // ── 页面 ──────────────────────────────────────────────────────
  listPages: (baseId: string, pageType?: string) =>
    api<WikiPage[]>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/wiki/pages` +
        (pageType ? `?page_type=${encodeURIComponent(pageType)}` : '')
    ),

  getPage: (baseId: string, slug: string) => api<WikiPage>(pagePath(baseId, slug)),

  createPage: (
    baseId: string,
    body: { slug: string; title: string; content?: string; summary?: string; aliases?: string[] }
  ) => api<WikiPage>(`/knowledge-bases/${encodeURIComponent(baseId)}/wiki/pages`, {
    method: 'POST',
    body,
  }),

  /** 更新页面（null / 不传 = 不改）。 */
  updatePage: (
    baseId: string,
    slug: string,
    body: { title?: string; content?: string; summary?: string; aliases?: string[] }
  ) => api<WikiPage>(pagePath(baseId, slug), { method: 'PUT', body }),

  deletePage: (baseId: string, slug: string) =>
    api<{ deleted: boolean; slug: string }>(pagePath(baseId, slug), { method: 'DELETE' }),

  // ── 目录 ──────────────────────────────────────────────────────
  listFolders: (baseId: string) =>
    api<WikiFolder[]>(`/knowledge-bases/${encodeURIComponent(baseId)}/wiki/folders`),

  createFolder: (baseId: string, path: string[]) =>
    api<WikiFolder>(`/knowledge-bases/${encodeURIComponent(baseId)}/wiki/folders`, {
      method: 'POST',
      body: { path },
    }),

  updateFolder: (baseId: string, folderId: string, name: string) =>
    api<WikiFolder>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/wiki/folders/${encodeURIComponent(folderId)}`,
      { method: 'PUT', body: { name } }
    ),

  deleteFolder: (baseId: string, folderId: string) =>
    api<{ deleted: boolean; path: string }>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/wiki/folders/${encodeURIComponent(folderId)}`,
      { method: 'DELETE' }
    ),

  // ── 索引 / 搜索 / 图 / 统计 ────────────────────────────────────
  getIndex: (baseId: string) =>
    api<WikiIndex>(`/knowledge-bases/${encodeURIComponent(baseId)}/wiki/index`),

  search: (baseId: string, q: string, limit = 20) =>
    api<WikiPage[]>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/wiki/search` +
        `?q=${encodeURIComponent(q)}&limit=${limit}`
    ),

  /**
   * 全库搜索。
   *
   * <p>⚠️ 与文档 chunk 检索分开：wiki 页面是「整理后的事实」，
   * 混进 chunk 检索会让问答引到 Wiki 而不是原文证据。
   */
  searchAll: (body: { query: string; knowledge_base_ids?: string[]; limit?: number }) =>
    api<WikiPage[]>('/wiki-search', { method: 'POST', body }),

  getGraph: (baseId: string) =>
    api<WikiGraph>(`/knowledge-bases/${encodeURIComponent(baseId)}/wiki/graph`),

  getStats: (baseId: string) =>
    api<WikiStats>(`/knowledge-bases/${encodeURIComponent(baseId)}/wiki/stats`),

  // ── Lint / 问题 ───────────────────────────────────────────────
  lint: (baseId: string) =>
    api<WikiLintReport>(`/knowledge-bases/${encodeURIComponent(baseId)}/wiki/lint`),

  autoFix: (baseId: string) =>
    api<{ fixed_issues: number; dead_links_removed: number }>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/wiki/auto-fix`,
      { method: 'POST' }
    ),

  listIssues: (baseId: string, status?: string) =>
    api<WikiIssue[]>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/wiki/issues` +
        (status ? `?status=${encodeURIComponent(status)}` : '')
    ),

  updateIssueStatus: (baseId: string, issueId: string, status: string) =>
    api<WikiIssue>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/wiki/issues/${encodeURIComponent(issueId)}/status`,
      { method: 'PUT', body: { status } }
    ),

  // ── 链接 / 版本 ───────────────────────────────────────────────
  rebuildLinks: (baseId: string) =>
    api<{ rebuilt: boolean }>(`/knowledge-bases/${encodeURIComponent(baseId)}/wiki/rebuild-links`, {
      method: 'POST',
    }),

  revisions: (baseId: string, slug: string) => {
    const [type, name] = splitSlug(slug)
    return api<WikiRevision[]>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/wiki/revisions/${type}/${name}`
    )
  },

  revert: (baseId: string, slug: string, version: number) =>
    api<WikiPage>(`/knowledge-bases/${encodeURIComponent(baseId)}/wiki/revert`, {
      method: 'POST',
      body: { slug, version },
    }),

  movePage: (baseId: string, slug: string, path: string[]) =>
    api<WikiPage>(`/knowledge-bases/${encodeURIComponent(baseId)}/wiki/move-page`, {
      method: 'PUT',
      body: { slug, path },
    }),

  // ── 手动生成（对齐 graphApi.rebuild）─────────────────────────────
  /**
   * 手动生成 / 补生成本库 Wiki。
   *
   * <p><b>为什么需要</b>：Wiki 原本只在文档入库后自动生成。上传时没配模型、或那一轮
   * 生成失败，之后配好模型也<b>没有补生成入口</b> —— 只能整篇重新解析（重跑分块与索引，
   * 成本高且块 ID 会变）。
   *
   * <p>⚠️ <b>同步调用</b>：整库多篇文档时可能几十秒到几分钟（每篇一次 LLM 调用）。
   * 调用方必须自己维护 loading 态，否则用户会以为「点了没反应」。
   *
   * @param baseId      知识库 ID
   * @param knowledgeId 文档 ID（不传 ⇒ 整库：所有已解析完成且已启用的文档）
   * @param model       模型名（不传 ⇒ 后端按 wiki_config.synthesis_model_id ⇒ summary_model_id）
   */
  rebuild: (baseId: string, knowledgeId?: string, model?: string) => {
    const qs = new URLSearchParams()
    if (knowledgeId) qs.set('knowledge_id', knowledgeId)
    if (model) qs.set('model', model)
    const suffix = qs.toString() ? `?${qs.toString()}` : ''
    return api<WikiRebuildResult>(
      `/knowledge-bases/${encodeURIComponent(baseId)}/wiki/rebuild${suffix}`,
      { method: 'POST' }
    )
  },
}

// ══════════════════════════════════════════════════════════════
// 展示辅助
// ══════════════════════════════════════════════════════════════

/** 页面类型 → 中文标签。 */
export const WIKI_PAGE_TYPE_LABEL: Record<string, string> = {
  entity: '实体',
  concept: '概念',
  summary: '文档摘要',
  index: '索引',
  synthesis: '综述',
  comparison: '对比',
}

/** Lint 严重度 → 中文标签。 */
export const WIKI_SEVERITY_LABEL: Record<string, string> = {
  error: '错误',
  warning: '警告',
  info: '建议',
}

/** 问题类型 → 中文标签。 */
export const WIKI_ISSUE_TYPE_LABEL: Record<string, string> = {
  orphan_page: '孤立页面',
  broken_link: '失效链接',
  empty_content: '内容过少',
  stale_ref: '过期来源',
  missing_cross_ref: '缺少交叉引用',
  duplicate_slug: '重复 slug',
}

/** 健康分 → 展示档位（用于配色）。 */
export function healthTone(score: number): 'ok' | 'warn' | 'bad' {
  if (score >= 80) return 'ok'
  if (score >= 50) return 'warn'
  return 'bad'
}
