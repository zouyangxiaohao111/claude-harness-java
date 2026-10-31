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
  updatedAt: string | null
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
