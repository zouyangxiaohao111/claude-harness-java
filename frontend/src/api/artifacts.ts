import { api, parseProblem, BASE_URL } from './rest'

/**
 * 会话产物 / 来源（后端轮末异步抽取写 session_artifact · 只读接口）。
 *
 * <p>抽取是**轮末异步**触发的 ⇒ 同一轮结束后数据可能晚几秒才出现，调用方需要轮询/延迟重拉。
 * <p>字段可空（来源只有 url、产物只有 localPath；sizeBytes / summary / updatedAt 均可能为 null）。
 */
export interface SessionArtifactDto {
  id: string
  kind: 'deliverable' | 'source'
  title: string
  localPath: string | null
  url: string | null
  sizeBytes: number | null
  summary: string | null
  /**
   * 归属的用户消息 id（= 产出它的那一轮的轮键）。
   *
   * <p>{@code null} = 归属未知（V276 之前写入的历史行，或写入方拿不到轮次）。
   * 前端**只把有归属的产物**挂到对应的那一轮；{@code null} 的不挂到任何一轮
   * （挂到「最后一条」会让历史产物看起来像本轮产出）。它们仍出现在右侧栏总量里。
   */
  sourceMessageId: string | null
  /**
   * 产物来源通道（后端 V279 起写入）。
   *
   * <p>`deliverables` = 写进会话交付物目录（Agent 主动交付）；
   * `project-write` = 本轮 Write/Edit 写过的项目内文档。
   *
   * <p>`null` / `undefined` = 未标注（V279 之前的历史行，或旧后端）——
   * ⛔ 不要当成任一种来画角标，那会伪造来源。可选是为兼容未升级的后端。
   */
  origin?: 'deliverables' | 'project-write' | null
  /**
   * 产出该产物的那一轮的用户提问原文（后端 join messages 带出 · 已压单行、截断 500 字）。
   *
   * <p><b>WHY 需要（2026-11-12）</b>：右侧栏「产物」tab 按对话分组的组头就是这句话。
   * 前端消息窗口只保留最近 2 轮（{@code FOLD_DEFAULT_TURNS=2}）⇒ 更早轮次的产物在窗口里
   * 找不到提问，全部落进「历史产物」组（真机：1 个产物组 + 历史产物 3 个）。
   * 本字段由后端 join 出来 ⇒ 与前端加载了多少消息无关。
   *
   * <p>{@code null} / {@code undefined} = 该轮不是真人提问 / 老后端 / 查不到消息 ——
   * 前端据此回落窗口映射，仍取不到则进「历史产物」组。可选是为兼容未升级的后端。
   */
  turnQuestion?: string | null
  /** 上述提问消息的 created_at 原串；未知为 {@code null}。 */
  turnCreatedAt?: string | null
  updatedAt: string | null
  /**
   * `localPath` 指向的是**目录**而不是文件（后端**读时判定**，不落库）。
   *
   * <p>目录不会被归档 ⇒ 路径不在会话归档目录内 ⇒ 不能预览/不能编辑，
   * 前端要画文件夹图标、大小行写「目录」、点击 = 打开所在目录（桌面）/ 复制路径（浏览器）。
   */
  directory: boolean
}

export interface SessionArtifacts {
  deliverables: SessionArtifactDto[]
  sources: SessionArtifactDto[]
}

/**
 * 产物内容预览（GET .../artifacts/{artifactId}/content）。
 *
 * <p>产物 {@code localPath} 是**归档副本**的绝对路径（{@code {configHome}/artifacts/<sessionId>/…}），
 * 不在任何项目根内 ⇒ ⛔ 不能走项目文件通道（会报「文件路径非法」），只能走本接口。
 *
 * <p>{@code binary=true} 时 {@code content} 恒 null（后端不下发 base64，避免打爆响应体）。
 * {@code size} 是文件**实际**字节数；{@code truncated=true} 表示只回了前 512KB。
 */
export interface ArtifactContent {
  content: string | null
  mime: string
  size: number
  truncated: boolean
  binary: boolean
}

/**
 * 产物原始字节端点（**绝对**地址）。
 *
 * <p>dev 下 {@link BASE_URL} 是相对路径（走 vite proxy）⇒ 补页源 origin；
 * 打包版 {@link BASE_URL} 已是绝对地址（Tauri WebView 里相对路径指向 WebView 自己 ⇒ 全废）。
 * 「在浏览器中打开」要交给系统浏览器（openUrl），必须是绝对地址 ⇒ 统一在这里收口。
 */
export function artifactRawUrl(sessionId: string, artifactId: string): string {
  const path = `/sessions/${encodeURIComponent(sessionId)}/artifacts/${encodeURIComponent(artifactId)}/raw`
  if (BASE_URL.startsWith('http')) return `${BASE_URL}${path}`
  const origin = typeof location !== 'undefined' ? location.origin : ''
  return `${origin}${BASE_URL}${path}`
}

/** 保存产物内容后的返回（写回归档副本，不动项目源文件）。 */
export interface ArtifactSaveResult {
  id: string
  sizeBytes: number
  updatedAt: string
}

export const artifactsApi = {
  list: (sessionId: string) =>
    api<SessionArtifacts>(`/sessions/${encodeURIComponent(sessionId)}/artifacts`),
  content: (sessionId: string, artifactId: string) =>
    api<ArtifactContent>(
      `/sessions/${encodeURIComponent(sessionId)}/artifacts/${encodeURIComponent(artifactId)}/content`),
  /**
   * 产物原始字节（GET .../artifacts/{id}/raw）。
   *
   * <p>⛔ **不走 `api()`**：那个封装强制 JSON 解析，raw 返回的是二进制（docx/xlsx/pdf/图片）
   * ⇒ 只能拿到 Response 自己 arrayBuffer / blob。
   */
  raw: async (sessionId: string, artifactId: string): Promise<Response> => {
    const res = await fetch(artifactRawUrl(sessionId, artifactId), { headers: { Accept: '*/*' } })
    if (!res.ok) throw await parseProblem(res)
    return res
  },
  /** 写回产物内容（PUT .../artifacts/{id}/content · body `{content}`）→ 写回归档副本。 */
  saveContent: (sessionId: string, artifactId: string, content: string) =>
    api<ArtifactSaveResult>(
      `/sessions/${encodeURIComponent(sessionId)}/artifacts/${encodeURIComponent(artifactId)}/content`,
      { method: 'PUT', body: { content } },
    ),
}
