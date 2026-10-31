import { api } from './rest'

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

export const artifactsApi = {
  list: (sessionId: string) =>
    api<SessionArtifacts>(`/sessions/${encodeURIComponent(sessionId)}/artifacts`),
  content: (sessionId: string, artifactId: string) =>
    api<ArtifactContent>(
      `/sessions/${encodeURIComponent(sessionId)}/artifacts/${encodeURIComponent(artifactId)}/content`),
}
