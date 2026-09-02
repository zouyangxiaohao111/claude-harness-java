/**
 * Shared type definitions for NexusAI UI.
 * Centralized here so every component / hook / data file imports from a single source.
 */

export interface Project {
  /** 项目 id（后端 ProjectDto.id；mock 数据可缺省，绑定时必填） */
  id?: string
  name: string
  branch: string
  dirty: number
  agents: number
  path: string
}

export interface DiffLine {
  type: 'ctx' | 'add' | 'del'
  oldNum?: number
  newNum?: number
  text: string
}

export interface DiffHunk {
  oldStart: number
  newStart: number
  lines: DiffLine[]
}

export interface DiffFile {
  name: string
  path: string
  adds: number
  dels: number
  isNew?: boolean
  hunks: DiffHunk[]
}

export type ModelTag = 'DS' | 'CL' | 'GP' | 'QW'

/**
 * 与 Java 端 `config.provider.model.ModelConfig.ModelType` 枚举一一对应
 * (CHAT/TEXT/VISION/MULTIMODAL/IMAGE_GENERATION/EMBEDDING/AUDIO/RERANK/MODERATION)
 * 取小写化的 enum 名称，与 JSON 序列化形式保持一致。
 */
export type ModelType =
  | 'chat'              // 文本对话（默认）
  | 'text'              // 文本补全
  | 'vision'            // 图像理解
  | 'multimodal'        // 多模态（图片+视频+音频）
  | 'image_generation'  // 图像生成
  | 'embedding'         // 向量嵌入
  | 'audio'             // 音频（语音识别/合成）
  | 'rerank'            // 重排序
  | 'moderation'        // 审核

export interface ModelInfo {
  id: string
  name: string                 // 实际调用 API 的名称（对应 Java 'model'）
  alias?: string               // 显示名（可选，默认 = name）
  tag: ModelTag
  desc: string
  type: ModelType              // 默认 'chat'
  maxTokens: number            // 单次最大输出 token；默认 65536（来自 initDefaultModelConfigs）
  temperature: number          // -1 表示使用 provider 默认（Java 侧 sentinel: -1D）
  topP: number | null          // null 表示使用 provider 默认（Java 侧 Double 默认为 null）
  contextWindow: number        // 可输入的最大 token；默认 512000
  /**
   * 思考/推理模式 + 额外请求体参数（JSON 字符串）。
   * 合并 Java 端 `Map<String, Object> think` 和 `extraBody` 两个字段。
   * - 不填 / 空字符串：不启用思考模式，不附加额外参数
   * - `{"type": "enabled", "clear_thinking": false}`：智谱 GLM 思考模式
   * - `{"reasoning": true}`：DeepSeek 思考模式
   * - `{"custom_param": "value"}`：合并到请求 body 的额外参数
   *
   * 注：v1 设计上 think 和 extraBody 合一（UI 一致性）。
   *     后端可在 LlmProvider 层根据 provider 类型拆回 think vs extraBody。
   */
  think: string
  enabled: boolean             // 在选择器中是否可见
}

export type SessionGroup = 'current' | 'today' | 'yesterday' | 'week'

export interface Session {
  id: string
  model: ModelTag         // 提供商标识（颜色 chip 用）
  modelName: string       // 实际调用的完整模型名（per-tab 独立）
  title: string
  time: string
  group: SessionGroup
  tabId?: string
}

export interface TabInfo {
  subtitle: string
  icon: string
}

export type SearchItemType = 'session' | 'project' | 'file' | 'command'

export interface SearchItem {
  type: SearchItemType
  id: string
  title: string
  sub: string
  tag: string
}

export type ToastType = 'success' | 'info'

export interface Toast {
  msg: string
  type: ToastType
}

export type ThemeMode = 'light' | 'dark' | 'auto'
export type FontSize = 'small' | 'medium' | 'large'

export type RightTab = 'files' | 'tasks' | 'projects'

/** Settings navigation tabs (P2 expands from 4 → 8). */
export type SettingsTab =
  | 'general'
  | 'appearance'
  | 'env'
  | 'model'
  | 'providers'
  | 'skills'
  | 'mcp'
  | 'database'
  | 'schedules'
  | 'hooks'
  | 'business'
  | 'advanced'

export interface ContextMenuState {
  x: number
  y: number
  project: Project
}

export interface SplitPair {
  left: { num?: number; text: string; type: 'ctx' | 'del' | 'empty' }
  right: { num?: number; text: string; type: 'ctx' | 'add' | 'empty' }
}

/* ------------------------------------------------------------------ */
/*  P2 — Provider / Skill / MCP / Database / Scheduler                 */
/* ------------------------------------------------------------------ */

/** A LLM provider (DeepSeek, Anthropic, OpenAI, Qwen, ...). */
export interface Provider {
  id: string
  name: string
  baseUrl: string
  apiKeyMasked: string         // never store the raw key; show last 4 chars
  enabled: boolean
  models: ModelInfo[]
}

/** A installable skill (web search, code exec, file manager, ...). */
export interface Skill {
  id: string
  name: string
  description: string
  enabled: boolean
  builtin: boolean             // builtin = read-only
}

/** A MCP server entry. */
export interface MCPServer {
  id: string
  name: string
  command: string              // e.g. "npx"
  args: string                 // e.g. "-y @modelcontextprotocol/server-filesystem /tmp"
  envSummary: string           // e.g. "GITHUB_TOKEN=*** (1 var)"
  status: 'running' | 'stopped' | 'error'
  enabled: boolean
}

/** A database connection. */
export interface DatabaseConnection {
  id: string
  name: string
  type: 'postgres' | 'mysql' | 'sqlite' | 'mongodb'
  host: string
  port: number
  database: string
  user: string
  status: 'connected' | 'disconnected' | 'error'
}

/** A scheduled task (cron job). */
export interface Schedule {
  id: string
  name: string
  cron: string                 // e.g. "0 2 * * *"
  command: string              // e.g. "scripts/backup.sh"
  description: string
  enabled: boolean
  lastRun?: string             // human-readable relative time
  nextRun?: string
}

/* ------------------------------------------------------------------ */
/*  Per-session context (right panel + chat history per session)      */
/* ------------------------------------------------------------------ */

/** A file row in the right panel "Files" sub-tab. */
export interface SessionFile {
  name: string
  path: string
  adds: number
  dels: number
  isNew?: boolean
}

/** A track/activity row in the right panel "Tracks" sub-tab. */
export interface TrackItem {
  dot: 'running' | 'ok' | 'warn'
  name: string
  time: string
}

/** A single chat message in a session. */
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool'
  author: string
  time: string
  content: string              // plain text (may contain \n for newlines)
  reasoning?: string           // collapsible reasoning block
  /** @deprecated 过渡兼容：迁移到 toolCalls 列表（任务 9 MessageList 改造后移除） */
  toolCard?: { name: string; status: string; body: string }
  toolCalls?: { name: string; status: string; body: string; isError?: boolean }[]
  finishReason?: string
  inputTokens?: number
  outputTokens?: number
  isApiErrorMessage?: boolean
  error?: string
  streaming?: boolean
}

/** Per-session context: the data behind the right panel + chat history. */
export interface SessionContext {
  files: SessionFile[]
  tracks: TrackItem[]
  mainProject: Project
  subProjects: Project[]
  messages: ChatMessage[]
}
