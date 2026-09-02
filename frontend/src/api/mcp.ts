/**
 * MCP server REST 端点封装
 * 对应 nexusai-backend Phase C2 端点
 */
import { api } from './rest'
import type { McpServer, McpImportResult, CreateMcpRequest, UpdateMcpRequest, TestConnectionResponse } from './types'

/** .mcp.json 导入请求（对齐后端 McpImportRequest：files = scope → .mcp.json 绝对路径） */
export interface McpImportRequest {
  files: Record<string, string>
}

/** 通道白名单条目（对齐后端 ChannelAllowlistEntry：marketplace/plugin/createdAt）。
 *  <p>后端 id 由 SQLite 生成，但 toDomain()/create() 未回传 → 前端置为可选；DELETE 依赖 id。 */
export interface ChannelAllowlistEntry {
  id?: string
  marketplace: string
  plugin: string
  createdAt?: string
}

/** 新增通道白名单请求体（对齐后端 POST body：{marketplace, plugin}） */
export interface AddChannelAllowlistRequest {
  marketplace: string
  plugin: string
}

export const mcpApi = {
  list: () => api<McpServer[]>('/mcp'),
  create: (req: CreateMcpRequest) => api<McpServer>('/mcp', { method: 'POST', body: req }),
  update: (id: string, req: UpdateMcpRequest) =>
    api<McpServer>(`/mcp/${encodeURIComponent(id)}`, { method: 'PATCH', body: req }),
  remove: (id: string) =>
    api<void>(`/mcp/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  /** .mcp.json 导入 → 落库 pending server（经列表可见），返回 imported/blocked/suppressed */
  import: (req: McpImportRequest) =>
    api<McpImportResult>('/mcp/import', { method: 'POST', body: req }),
  /** 审批通过（pending → approved + enabled）· Q-25 状态机 */
  approve: (id: string) =>
    api<McpServer>(`/mcp/${encodeURIComponent(id)}/approve`, { method: 'POST' }),
  /** 审批拒绝（→ rejected + enabled=false） */
  reject: (id: string) =>
    api<McpServer>(`/mcp/${encodeURIComponent(id)}/reject`, { method: 'POST' }),
  /** 启动 MCP server（202 + 更新后 DTO）· 对齐 POST /mcp/{id}/start（pending/disabled 抛 409 Conflict） */
  start: (id: string) =>
    api<McpServer>(`/mcp/${encodeURIComponent(id)}/start`, { method: 'POST' }),
  /** 停止 MCP server（202 + 更新后 DTO）· 对齐 POST /mcp/{id}/stop */
  stop: (id: string) =>
    api<McpServer>(`/mcp/${encodeURIComponent(id)}/stop`, { method: 'POST' }),
  /** 测试连接 · 对齐 POST /mcp/{id}/test（失败抛 500 Problem） */
  test: (id: string) =>
    api<TestConnectionResponse>(`/mcp/${encodeURIComponent(id)}/test`, { method: 'POST' }),

  // ---- FM-17 通道白名单（独立命名空间 /mcp/channel-allowlist · Q-37 ledger DB 表） ----
  listAllowlist: () => api<ChannelAllowlistEntry[]>('/mcp/channel-allowlist'),
  addAllowlist: (req: AddChannelAllowlistRequest) =>
    api<ChannelAllowlistEntry>('/mcp/channel-allowlist', { method: 'POST', body: req }),
  removeAllowlist: (id: string) =>
    api<void>(`/mcp/channel-allowlist/${encodeURIComponent(id)}`, { method: 'DELETE' }),
}
