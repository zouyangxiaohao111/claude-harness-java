/**
 * /memory 记忆文件 REST 端点封装
 * 对应 nexusai-backend MemoryController（GET /api/v1/memory/files · PUT /api/v1/memory/files · GET/PUT /api/v1/memory/config）
 *
 * type 语义契约（2026-08-23 用户拍板，取代旧的绝对路径 upsert）：
 *   GET /files[?sessionId=] → {type,file,path,content,exists,editable}[] 三档视图
 *     - Managed: editable=false 只读；User: configHome 主文件；Project: sessionId → boundProject 文件
 *   PUT /files[?sessionId=] body {type,file?,content} → 覆盖写（无 upsert）
 *     Managed → 403；不存在 → 404；content 缺失 → 400
 *   sessionId 走 query（定位 Project boundProject）；path 仅展示/调试，前端不再作为保存键
 */
import { api } from './rest'

/** 记忆文件条目（GET /memory/files 返回 · type 三档 + editable 只读标记） */
export interface MemoryFileEntry {
  type: 'Managed' | 'User' | 'Project'
  file: string      // 相对路径（User 主文件 / Project 相对 boundProject 的文件名）
  path: string      // 绝对路径（仅展示/调试，前端不再作为保存键）
  content: string   // 文件内容（缺失槽位空串）
  exists: boolean   // 是否存在于磁盘（false → (new) 标记）
  editable: boolean // false = 只读（Managed）
}

/** GET /api/v1/memory/files[?sessionId=] · 记忆文件三档视图（Project 需 sessionId 定位 boundProject） */
export async function listMemoryFiles(sessionId?: string): Promise<MemoryFileEntry[]> {
  return api<MemoryFileEntry[]>(`/memory/files${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''}`)
}

/** PUT /api/v1/memory/files[?sessionId=] · 覆盖写保存（type + file 语义，无 upsert）。
 *  body: { type, file?, content, sessionId? }；Managed → 403；不存在 → 404；content 缺失 → 400。
 *  sessionId 走 query；file 必须=GET 返回的 file（Project 多文件 / User 主文件）。 */
export async function saveMemoryFile(req: {
  type: 'Managed' | 'User' | 'Project'
  file?: string
  content: string
  sessionId?: string
}): Promise<{
  type: string
  file?: string
  path: string
  relativePath: string
  content: string
  message: string
}> {
  const { sessionId, ...body } = req
  return api(`/memory/files${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''}`, { method: 'PUT', body })
}

/** 记忆配置（GET/PUT /api/v1/memory/config · auto-memory/auto-dream 开关与 dream 状态。
 *  D5：auto-memory 与旧 settings API `autoMemoryEnabled` 为同一设置，统一走此端点读写） */
export interface MemoryConfig {
  autoMemoryEnabled: boolean
  autoDreamEnabled: boolean
  dreamStatus: 'never' | 'last_ran'
  lastConsolidatedAtMs: number
}

/** GET /api/v1/memory/config · 读记忆开关（auto-memory/auto-dream）与 dream 整合状态 */
export async function getMemoryConfig(): Promise<MemoryConfig> {
  return api<MemoryConfig>('/memory/config')
}

/** PUT /api/v1/memory/config · 部分更新开关（缺省键不触碰；后端双写 DB+settings.json） */
export async function updateMemoryConfig(update: { autoMemoryEnabled?: boolean; autoDreamEnabled?: boolean }): Promise<MemoryConfig> {
  return api<MemoryConfig>('/memory/config', { method: 'PUT', body: update })
}
