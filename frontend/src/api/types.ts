/**
 * 后端 DTO ↔ 前端 TS 类型映射
 * 与 nexusai-backend 的 io.nexusai.dto.* 一一对应
 */

// ---- Provider ----
export type ProviderType = 'openai_compatible' | 'anthropic'

/** 后端响应：ProviderDto（含嵌套 models） */
export interface Provider {
  id: string
  name: string
  type: ProviderType
  baseUrl: string
  apiKeyMasked: string
  extraHeaders: Record<string, string> | null
  enabled: boolean
  models: Model[]
  createdAt?: string
  updatedAt?: string
}

/** POST 请求：完整 key 由后端脱敏 */
export interface CreateProviderRequest {
  name: string
  type?: ProviderType
  baseUrl: string
  apiKey: string
  extraHeaders?: Record<string, string>
  enabled?: boolean
}

/** PATCH 请求：所有字段可选 */
export interface UpdateProviderRequest {
  name?: string
  type?: ProviderType
  baseUrl?: string
  apiKey?: string
  extraHeaders?: Record<string, string> | null
  enabled?: boolean
}

// ---- Model ----
export type ModelTag = 'DS' | 'CL' | 'GP' | 'QW' | 'KIMI'
export type ModelType =
  | 'chat' | 'text' | 'vision' | 'multimodal'
  | 'image_generation' | 'embedding' | 'audio' | 'rerank' | 'moderation'
  | 'tts' | 'asr'  // 0.4.0 契约：语音合成/识别（保留 audio 兼容）

export interface Model {
  id: string
  name: string
  alias: string | null
  tag: ModelTag
  desc: string
  type: ModelType
  maxTokens: number
  temperature: number | null
  topP: number | null
  think: string | null
  enabled: boolean
  /** 模型级上下文窗口（models.max_context_tokens 列 · 影响压缩阈值计算） */
  maxContextTokens: number | null
}

export interface CreateModelRequest {
  name: string
  alias?: string | null
  tag: ModelTag
  desc?: string
  type: ModelType
  maxTokens?: number
  /** 模型级上下文窗口（models.max_context_tokens 列 · 影响压缩阈值计算） */
  maxContextTokens?: number | null
  temperature?: number | null
  topP?: number | null
  think?: string | null
  enabled?: boolean
}

export interface UpdateModelRequest {
  name?: string
  alias?: string | null
  desc?: string
  type?: ModelType
  maxTokens?: number
  /** 模型级上下文窗口（models.max_context_tokens 列 · PATCH 语义：null 不更新，传 null 显式清空） */
  maxContextTokens?: number | null
  temperature?: number | null
  topP?: number | null
  think?: string | null
  enabled?: boolean
  // tag 锁定：不在此
}

// ---- Test connection ----
export interface TestConnectionResponse {
  ok: boolean
  latencyMs: number | null
  message: string
  details: Record<string, unknown> | null
}

// ---- Skill ----
export interface Skill {
  id: string
  name: string
  description: string
  enabled: boolean
  builtin: boolean
  config: Record<string, unknown> | null
}
export interface CreateSkillRequest {
  name: string
  description?: string
  enabled?: boolean
  config?: Record<string, unknown>
}
export interface UpdateSkillRequest extends Partial<CreateSkillRequest> {}

// ---- Skill Improvement（FNT-DC-01/FE-10）----
/** 改进项（对齐后端 SkillImprovementHook.SkillUpdate：section/change/reason） */
export interface SkillUpdate {
  section: string
  change: string
  reason: string
}
/** GET /api/v1/skill-improvement/suggestion 响应（对齐后端 PendingSuggestion） */
export interface SkillImprovementSuggestion {
  skillName: string
  updates: SkillUpdate[]
}
/** POST /api/v1/skill-improvement/decision 响应 */
export interface SkillImprovementDecisionResponse {
  status: string
  skillName: string
}

// ---- 子代理（FNT-SUB-01/07）----
/**
 * 子代理颜色 key → 主题色（本地映射）。
 * 后端 SubagentTool.AGENT_COLOR_TO_THEME_COLOR（red → red_FOR_SUBAGENTS_ONLY 等）无出站端点，
 * 前端无法拿到运行时的 agent→color 关联，故本地定义常用色 key → 主题色渲染（不穷举）。
 */
export const SUBAGENT_THEME_COLORS: Record<string, string> = {
  red: 'var(--error)',
  orange: '#D08954',
  yellow: 'var(--warning)',
  green: 'var(--success)',
  cyan: 'var(--running)',
  blue: '#5B8DC9',
  purple: '#8B6FB5',
  pink: '#D47A9E',
}

/** 子代理颜色兜底调色板（未命中显式色 key 时按名稳定取色） */
const SUBAGENT_PALETTE = [
  'var(--accent)', '#5B8DC9', 'var(--success)', 'var(--warning)',
  '#8B6FB5', '#D47A9E', '#D08954', 'var(--error)',
]

/**
 * 子代理名 → 颜色点（前端本地稳定配色）。
 * 显式色 key（AGENT_COLORS 8 色）命中直接返回；否则按名字 hash 取调色板，保证同名同色。
 */
export function subagentColor(author: string | null | undefined): string {
  if (!author) return 'var(--accent)'
  const key = author.toLowerCase()
  const explicit = SUBAGENT_THEME_COLORS[key]
  if (explicit) return explicit
  let h = 0
  for (let i = 0; i < author.length; i++) h = (h * 31 + author.charCodeAt(i)) >>> 0
  return SUBAGENT_PALETTE[h % SUBAGENT_PALETTE.length]
}

// ---- McpServer ----
export type McpStatus = 'running' | 'stopped' | 'error' | 'pending'
export interface McpServer {
  id: string
  name: string
  /** 展示名（后端 McpServerDto.userFacingName · 列表优先展示） */
  userFacingName?: string | null
  command: string
  args: string[] | null
  env: Record<string, string> | null
  status: McpStatus
  lastError: string | null
  enabled: boolean
  createdAt?: string
  /** 审批态（后端 McpServerDto.approvalStatus）：'approved'|'rejected'|'pending'；pending 时 status 仍为运行时态 */
  approvalStatus?: 'approved' | 'rejected' | 'pending' | null
  /** 传输类型（后端 McpServerDto.type：'stdio'|'sse'|'http'...，缺省 stdio） */
  type?: string
  /** 远程反解 URL（type≠stdio 时，后端取自 command 列） */
  url?: string | null
  /** 远程请求头（sse/http） */
  headers?: Record<string, string> | null
  /** 远程 OAuth 配置（sse/http） */
  oauth?: Record<string, unknown> | null
  /** 写目标 scope（list 不回传，仅 create/update 响应） */
  scope?: string | null
  filePath?: string | null
  warnings?: string[] | null
}
export interface CreateMcpRequest {
  name: string
  /** stdio 传输时必填启动命令；sse/http 远程 server 用 url（后端条件校验，command 去 @NotBlank） */
  command?: string
  args?: string[]
  env?: Record<string, string>
  enabled?: boolean
  /** 传输类型 stdio|sse|http，缺省 stdio（后端 McpCreateRequest.type） */
  type?: string
  /** 远程（sse/http）地址 */
  url?: string
  /** 远程请求头（sse/http） */
  headers?: Record<string, string>
  /** 远程 OAuth 配置（sse/http） */
  oauth?: McpOAuthRequest
  /** OAuth client secret · 仅当 oauth.clientId 同时存在才生效（落 keychain 等价物，不进 config） */
  clientSecret?: string
  /** 写目标 scope（local|user|project|...，REST 缺省 project） */
  scope?: string
}
/** 远程 OAuth 配置（对齐后端 McpOAuthRequest） */
export interface McpOAuthRequest {
  clientId?: string
  /** 字符串；非数字/0 静默丢弃 */
  callbackPort?: string
  authServerMetadataUrl?: string
  xaa?: boolean
}
export interface UpdateMcpRequest extends Partial<CreateMcpRequest> {}

/** .mcp.json 导入结果（对齐后端 McpServerService.McpServerImportResult：imported/blocked/suppressed）。
 *  pending server 不在此结果中——导入即落库（approvalStatus='pending'），前端经 McpServer 列表可见。 */
export interface McpImportResult {
  imported: number
  blocked: string[]
  suppressed: string[]
}

// ---- DatabaseConnection ----
export type DatabaseType = 'postgres' | 'mysql' | 'sqlite' | 'mongodb'
export interface DatabaseConnection {
  id: string
  name: string
  type: DatabaseType
  host: string
  port: number
  database: string
  user: string
  passwordMasked: string  // always '****'
  status: string
  lastError: string | null
}
export interface CreateDatabaseRequest {
  name: string
  type: DatabaseType
  host: string
  port: number
  database: string
  user?: string
  password: string
}
export interface UpdateDatabaseRequest extends Partial<Omit<CreateDatabaseRequest, 'password'>> {
  password?: string
}

// ---- Schedule ----
export type ScheduleKind = 'cron' | 'once' | 'interval'
/**
 * 任务生命周期（后端 ScheduleScope 枚举）。
 * - `SESSION`（**默认** · 对齐 CC `CronCreateTool.ts:117` `durable = false`）：**仅存活于该会话**
 *   —— 会话结束自动清理（后端 `cleanupBySession`）；`sessionId` 表示**生命周期绑定**；
 *   会话已关则**不再消费**。
 * - `DURABLE`：落盘持久，跨进程/重启保留；`sessionId` 表示**归属对话/注入目标**
 *   （fire 时 transcript 归创建会话；创建会话已关则 headless 代跑）。
 * ⚠️ 两者**都带 sessionId**（后端无条件落库），生命周期判定以 scope 列为权威。
 * ⚠️ 缺省值只管**新建**；存量行读侧以落库的 scope 列为权威，不因缺省值改动而变化。
 */
export type ScheduleScope = 'DURABLE' | 'SESSION'
export interface Schedule {
  id: string
  name: string
  kind: ScheduleKind
  cron: string | null
  intervalSeconds: number | null
  runAt: string | null
  command: string
  description: string
  lastRunAt: string | null
  lastRunStatus: string | null
  /**
   * 生命周期（后端 `ScheduleDto.scope`）。两 scope 都带 sessionId，⛔ 不要用它反推 scope。
   * 缺省（旧行 scope 列为 NULL）时按 `DURABLE` 处理（对齐后端 lookupScope 兜底）。
   */
  scope?: ScheduleScope | null
  /** 归属/绑定的会话（见 {@link ScheduleScope}：DURABLE=归属对话，SESSION=生命周期绑定） */
  sessionId?: string | null
  /** 创建者 teammate agentId（主线程恒 null） */
  agentId?: string | null
  /** 任务的项目锚 · 仅 DURABLE 非 null（由后端按 sessionId 解析并覆盖请求体，客户端不可伪造）；SESSION 恒 null */
  boundProject?: string | null
}
export interface CreateScheduleRequest {
  name: string
  kind: ScheduleKind
  cron?: string
  intervalSeconds?: number
  runAt?: string
  command?: string              // hidden from UI but accepted in API
  description?: string
  /**
   * 任务所属会话（[cwd3]）。**两个 scope 都必填**（REST 路径）：
   * - `DURABLE`：后端从它解析任务的项目锚（boundProject），并**忽略**请求体里的 boundProject
   *   （防客户端伪造锚）。缺 id / 传 "no-session" 哨兵 / 解析不到绑定项目根 ⇒ 400。
   * - `SESSION`：仅要求非空（后端 service 层校验），语义是**生命周期绑定**。
   * ⇒ 无活动会话时**两种任务都建不了**（前端置灰按钮并说明，见 SchedulesPanel）。
   */
  sessionId?: string
  /**
   * 生命周期：`SESSION`（默认 · 对齐 CC）| `DURABLE`。缺省由后端两处三元决定
   * （`ScheduleController.create` / `ScheduleService.create`，同为 SESSION），UI 选择器初值同。
   * ⚠️ [cwd3 D6] **创建后不可变** —— update 请求带 scope/sessionId 会被后端 400 拒绝。
   */
  scope?: ScheduleScope
}
export interface UpdateScheduleRequest extends Partial<CreateScheduleRequest> {}

export interface RunNowResponse {
  executed: boolean
  output: string
}

/** 权限模式（对齐 CC permissions.defaultMode · 6 种全量；计划模式 plan 并入权限模块，工具旁不再独立展示） */
export type PermissionMode = 'default' | 'plan' | 'acceptEdits' | 'bypassPermissions' | 'dontAsk' | 'auto'
/**
 * 权限模式中文标签（环境配置 + Composer 选择器用）。
 *
 * <p>⛔ `dontAsk` 的短标签必须能自证「不是自动放行」：Composer 的 `pm-value` 芯片**只显示标签**
 * （抽屉里才有说明小字），写成「不询问」会被读成「不问就放行」—— 与真实语义相反。
 */
export const PERMISSION_MODE_LABELS: Record<PermissionMode, string> = {
  default: '默认',
  plan: '计划模式',
  acceptEdits: '接受编辑',
  bypassPermissions: '绕过权限',
  dontAsk: '不询问（拒绝）',
  auto: '自动',
}
/**
 * 权限模式说明（抽屉选项旁小字 · 超宽省略 + hover title 显示完整）。
 *
 * <p>6 条逐条对照 CC 语义定义（{@code claude-code-best/src/entrypoints/sdk/coreSchemas.ts:347-355}
 * 的 6 句 describe），中文说清功能、不直译：
 * <ul>
 *   <li>{@code default} = "Standard behavior, prompts for dangerous operations."</li>
 *   <li>{@code acceptEdits} = "Auto-accept file edit operations."</li>
 *   <li>{@code bypassPermissions} = "Bypass all permission checks."</li>
 *   <li>{@code plan} = "Planning mode, no actual tool execution."</li>
 *   <li>{@code dontAsk} = <b>"Don't prompt for permissions, deny if not pre-approved."</b>
 *       —— ⚠️ 语义是<b>不弹窗 + 未预先批准的一律拒绝</b>（严格模式），
 *       <b>不是</b>「不询问就自动批准」（那是 {@code bypassPermissions}）。
 *       后端实现一致：{@code ToolPermissionGate.applyDontAskTransform}（Ask → Deny，
 *       文案 "Permission to use X has been denied because NexusAI is running in don't ask mode."）。</li>
 *   <li>{@code auto} = "Automatic mode (transcript classifier)."</li>
 * </ul>
 */
export const PERMISSION_MODE_DESCRIPTIONS: Record<PermissionMode, string> = {
  default: '每个敏感操作询问',
  plan: '只读探索，不执行修改',
  acceptEdits: '自动接受文件编辑',
  bypassPermissions: '绕过所有权限',
  dontAsk: '不弹窗；未预先批准的会被拒绝',
  auto: '自动判定（classifier）',
}

// ---- AppSettings（对齐后端 SettingsDto · GET/PUT /api/v1/settings）----
/** 后端 SettingsDto · 用户全局设置（singleton，未配置字段为 null） */
export interface AppSettings {
  theme: 'light' | 'dark' | 'auto' | null        // 后端 Theme 枚举
  fontSize: 'small' | 'medium' | 'large' | null  // 后端 FontSize 枚举
  accent: string | null
  animationsEnabled: boolean | null
  /** 界面/AI 回复语言（后端 settings.language 列 · 存语言显示名如「中文」或特殊值 auto=按本机时区自动解析） */
  language?: string | null
  // 0.4.0 契约：settings 存模型全名 providerName/modelName（V28 RENAME *_model_id → *_model_name）
  mainModelName: string | null
  fastModelName: string | null
  // provider-env 档位模型（后端 settings 表 weak/medium/strong/subagent_model_name 列）
  weakModelName: string | null
  mediumModelName: string | null
  strongModelName: string | null
  subagentModelName: string | null
  /** 压缩窗口上限（auto_compact_window 列）：留空=不限制；设置后 min(模型窗口, 值) 只缩不扩 */
  autoCompactWindow: number | null
  /** 输出 token 上限（V27 max_output_tokens）：>0 生效、> 模型上限封顶、null 用模型默认 */
  maxOutputTokens: number | null
  /** 回落模型全名（V28 RENAME fallback_model_name） */
  fallbackModelName: string | null
  // 0.4.0 新增档位（使用先不使用 · 后端 settings 列 multimodal/tts/asr_model_name）
  multimodalModelName: string | null
  ttsModelName: string | null
  asrModelName: string | null
  /** 自动记忆开关（settings.json 承载 · 后端提供读写 API） */
  autoMemoryEnabled: boolean | null
  /** 自动记忆目录路径（对齐 CC getAutoMemPathSetting · 留空用默认 ~/.claude/projects/.../memory） */
  autoMemoryDirectory: string | null
  /** 全局默认权限模式（环境配置可改 · 会话未覆盖时回落此值） */
  permissionMode?: PermissionMode | null
  // 0.4.4 契约：WebSearch 工具配置（对齐后端 SettingsDto 字段名）
  /** WebSearch 搜索引擎（默认 anysearch） */
  websearchEngine?: string | null
  /** WebSearch API key（空 → 内置默认兜底） */
  apiKey?: string | null
  /** WebSearch 代理（空 → 直连） */
  proxy?: string | null
  /** WebSearch 小模型开关（弱模型负责结果总结） */
  websearchUseSmallModel?: boolean | null
  /** WebSearch 引擎 Base URL（默认 https://api.anysearch.com） */
  websearchBaseUrl?: string | null
  /** WebSearch 域预检端点（空 → 跳过预检） */
  websearchDomainCheckUrl?: string | null
  /** Agent Swarms 功能开关（设置页「环境配置」模块写 · 后端 TaskSystemConfig.isAgentSwarmsEnabled 读） */
  agentSwarmsEnabled?: boolean | null
  /** 协调者模式开关（设置页「环境配置」模块写 · 后端 PromptAlignSettingsResolver.coordinatorModeEnabled 读；未配置/null → 关闭） */
  coordinatorModeEnabled?: boolean | null
  /** 权限分类器模型全名（V45 列 classifier_model；留空 → 主循环模型，对齐 CC getClassifierModel 兜底 getMainLoopModel） */
  classifierModel?: string | null
  // 0.5.x 契约：五层压缩开关（对齐后端 SettingsDto 新增 12 字段 camelCase · settings 表列承载）
  /** 主动压缩总开关（对齐 CC globalConfig.autoCompactEnabled） */
  autoCompactEnabled?: boolean | null
  /** 反应式压缩开关（对齐 CC reactiveCompactEnabled · 异步/被压制请求触发即时压缩） */
  reactiveCompactEnabled?: boolean | null
  /** 上下文折叠开关（对齐 CC contextCollapseEnabled · 管理性提示折叠入上下文） */
  contextCollapseEnabled?: boolean | null
  /** 历史裁剪开关（对齐 CC historySnipEnabled · 长对话 Snip 历史窗口） */
  historySnipEnabled?: boolean | null
  /** snip 提示「上下文剩余百分比」阈值（有效域 1..100），null = 用后端默认 30。判据口径 = ContextUsageCalculator.snapshot（窗口取 DB models.max_context_tokens，已用取真实 API usage） */
  snipNudgeThreshold?: number | null
  /** 会话记忆开关（对齐 CC smSessionMemoryEnabled · Session Memory 服务） */
  smSessionMemoryEnabled?: boolean | null
  /** 会话记忆压缩开关（对齐 CC smCompactEnabled） */
  smCompactEnabled?: boolean | null
  /** 缓存微压缩开关（对齐 CC cachedMicrocompactEnabled · cache_edits 微压缩） */
  cachedMicrocompactEnabled?: boolean | null
  /** 时间基准微压缩开关（对齐 CC timeBasedMcEnabled） */
  timeBasedMcEnabled?: boolean | null
  /** 时间基准微压缩间隔阈值（分钟 · 对齐 CC timeBasedMcGapMinutes） */
  timeBasedMcGapMinutes?: number | null
  /** 时间基准微压缩保留最近条数（对齐 CC timeBasedMcKeepRecent） */
  timeBasedMcKeepRecent?: number | null
  /** 禁用压缩总闸（对齐 CC disableCompact） */
  disableCompact?: boolean | null
  /** 禁用主动压缩总闸（对齐 CC disableAutoCompact） */
  disableAutoCompact?: boolean | null
  // V54 契约：settings 表新增 11 个压缩数值列（camelCase · 空=null 回落后端默认）
  /** 缓存微压缩触发阈值 · 活跃工具结果超过该阈值（默认 10）触发 cached-MC 删除 */
  cachedMicrocompactTriggerThreshold?: number | null
  /** 缓存微压缩保留数 · 触发时保留最近 N 个工具结果（默认 5） */
  cachedMicrocompactKeepRecent?: number | null
  /** SM 压缩保留尾段最小 token 数（默认 10000） */
  smMinTokens?: number | null
  /** SM 压缩最小正文消息条数（默认 5） */
  smMinTextBlockMessages?: number | null
  /** SM 压缩保留尾段最大 token 数（默认 40000） */
  smMaxTokens?: number | null
  /** 会话笔记提取：累计 token 达到该值才初始化会话笔记 */
  smMinimumMessageTokensToInit?: number | null
  /** 会话笔记提取：两次更新之间至少新增 token 数 */
  smMinimumTokensBetweenUpdate?: number | null
  /** 会话笔记提取：至少隔多少次工具调用更新一次 */
  smToolCallsBetweenUpdates?: number | null
  /** 自动压缩熔断阈值 · 连续失败 N 次（默认 3）后停止尝试 */
  maxConsecutiveAutocompactFailures?: number | null
  /** prompt-too-long（PTL）重试上限（默认 3） */
  maxPtlRetries?: number | null
  /** 压缩流式重试上限（默认 2） */
  maxCompactStreamingRetries?: number | null
  // V61 契约（2026-09-01）：插件配置 DB 化（后端 settings.enabled_plugins / plugin_claude_fallback 列）
  /** 插件启停映射（Record<插件ID, boolean> → settings.enabled_plugins JSON 文本 · 前端插件管理页写入；null = 未配置回落 ConfigStorage 读链） */
  enabledPlugins?: Record<string, boolean> | null
  /** 插件双读开关（settings.plugin_claude_fallback 0/1 · null = 回落默认 true：nexusai DB + CC settings.json 双读合并） */
  pluginClaudeFallback?: boolean | null
  // V72 契约（2026-09-12 provider-custom-headers D6）：提供商自定义请求头的运行时占位符总开关
  /** 按会话展开请求头占位符（settings.allow_dynamic_header_values · 后端列 NOT NULL DEFAULT 1，
   *  即默认**开**）：开 = 值里的会话 ID 占位符替换成当前会话 ID；关 = 统一发固定值 nexusai-static。
   *  null/undefined = 未见该字段（后端 DTO 尚未透出时即为该态），前端按默认开处理。 */
  allowDynamicHeaderValues?: boolean | null
  // appName 通道契约（2026-09-23 用户拍板「跟着 appName 走」）：GET /settings 的**只读**出站字段
  /** 当前应用名 = 自有根目录名去掉前导点（后端 spring.application.name ⇒ 如 nexusai / nexusai-scene）。
   *  ⚠️ **只读**：后端 PUT 的入参类型是 SettingsDto（无此字段），回传会被静默忽略 —— 前端不得把它当可写配置。
   *  用途：拼自有根目录标记（权限弹窗「编辑配置目录」档位）与启动页日志路径。 */
  readonly appName?: string | null
  /** 当前自有配置根绝对路径（后端 = `{user.home}/.{appName}`，如 C:\Users\x\.nexusai-scene）。
   *  ⚠️ **只读**：同 {@link appName}（PUT 入参无此字段，回传被忽略）。 */
  readonly configHome?: string | null
}

/** PUT /api/v1/settings 部分更新请求 · 后端 merge 策略：仅覆盖非 null 字段。
 *
 *  ⚠️ **不含**只读的 `appName` / `configHome`（GET 出站专用 · 后端 `SettingsController.update`
 *  的入参类型是 `SettingsDto`，回传会被静默忽略）—— 故在**类型层封死**，让「不小心把它当可写配置
 *  写回去」在编译期就报错，而不是静默被后端丢弃（F2）。 */
export type UpdateSettingsRequest = Partial<Omit<AppSettings, 'appName' | 'configHome'>>

// ---- /context analyze（后端 ContextAnalyzeController）----
export interface ContextAnalyzeRequest {
  customSystemPrompt?: string
  appendSystemPrompt?: string
}
export interface ContextSystemPromptSection { name: string; tokens: number }
export interface ContextMemoryFile { path: string; type: string; tokens: number }
export interface ContextAnalyzeCategory { name: string; tokens: number; color?: string | null }
export interface ContextAnalyzeSkills { totalSkills: number; includedSkills: number; tokens: number }
export interface ContextAnalyzeResponse {
  systemPromptTokens: number
  systemPromptSections: ContextSystemPromptSection[]
  claudeMdTokens: number
  memoryFiles: ContextMemoryFile[]
  builtInToolTokens: number
  mcpToolTokens: number
  /** 技能统计（totalSkills/includedSkills/tokens）· 可选 */
  skills?: ContextAnalyzeSkills | null
  /** 展示分类（name/tokens/color · builtInToolTokens 扣减值承载）· 可选 */
  categories?: ContextAnalyzeCategory[] | null
}

// ---- Workflow 运行（后端 WorkflowController · status 已小写归一）----
/** workflow run 进度（对齐后端 WorkflowRunDto · status 已小写归一） */
export interface WorkflowRunDto {
  runId: string
  workflowName: string
  status: 'running' | 'completed' | 'failed' | 'killed'   // 后端大写枚举已归一小写
  phases: Array<{ title: string; status: 'running' | 'done' }>
  declaredPhases: string[]
  currentPhase: string | null
  agents: Array<{
    id: number
    label: string | null
    phase: string | null
    status: 'running' | 'done'
    resultKind?: string      // ok | skipped | dead
    outputShape?: 'text' | 'object'
    model?: string
    tokenCount?: number
    toolCount?: number
  }>
  agentCount: number
  returnValue?: unknown
  error?: string
  startedAt: number
  description?: string
  updatedAt: number
}

// ---- Session（对齐后端 SessionDto） ----
export interface SessionDto {
  id: string
  model: ModelTag | null
  modelName: string | null
  title: string
  time: string | null
  group: SessionGroup | null
  tabId: string | null
  mainProjectId: string | null
  /** 会话级思考深度（V31 effort_level）· low/medium/high/xhigh/max */
  effortLevel: 'low' | 'medium' | 'high' | 'xhigh' | 'max' | null
  /** 会话级 ultracode 开关（V32）· ultracode = xhigh effort + workflows 编排（effortLevel 同步 xhigh） */
  ultracodeEnabled: boolean | null
  /** 会话级精简模式（V33 bare_mode）· 精简会话工具列表只显 [Bash, Read, Edit] */
  bareMode: boolean | null
  /** 会话级权限模式覆盖（null = 未覆盖，回落全局 settings.permissionMode） */
  permissionMode?: PermissionMode | null
  /** V58 main_thread_agent · 会话级主线程 agent（专家）· 整轮对话由指定 agent 驱动；null/空串 = 默认模式 */
  mainThreadAgent?: string | null
  messageCount: number | null
  createdAt?: string
  updatedAt?: string
  /** 会话累计花费（元 · sessions.total_cost_yuan V48 持久化）· 底部 token/金额汇总（F5 恢复源） */
  totalCostYuan?: number | null
  /** 会话累计 token（各模型 input+output · model_usage_json 求和）· 底部 token 汇总（F5 恢复源） */
  totalTokens?: number | null
  /** 会话级团队上下文（Team 协作 · GET /sessions/{id} 返回；无 team 为 null） */
  teamContext?: SessionTeamContext | null
  /** 会话级协调者模式覆盖（V75 coordinator_mode）· 三态：null = 未设置（回落全局「协调者模式」开关 →
   *  再回落 feature+env）；true = 本会话强制协调者；false = 本会话强制普通（压过全局开）。
   *  「A 会话是协调者、B 会话不是」的唯一载体（后端 PromptAlignSettingsResolver 逐次按 sessionId 读） */
  coordinatorMode?: boolean | null
}
export type SessionGroup = 'current' | 'today' | 'yesterday' | 'week'

// ---- 用量统计（S3 · 对齐后端 GET /api/v1/stats StatsResponse）----
/** 按天聚合单条（后端 StatsController byDay · date=yyyy-MM-dd · 会话创建日归组） */
export interface StatsByDay {
  date: string
  tokenCount: number
  costYuan: number
}
/** 按模型聚合单条（后端 StatsController byModel · model=模型全名 provider/model） */
export interface StatsByModel {
  model: string
  inputTokens: number
  outputTokens: number
  cacheReadInputTokens: number
  cacheCreationInputTokens: number
  costUSD: number
  /** 该模型是否 Anthropic provider（后端 ContextUsageCalculator.isAnthropic 判定）·
   *  true=total 按 4 项和（input 不含 cache hit）；false（deepseek input 已含 cache hit）= 仅 input+output，
   *  4 项和会双计 cache */
  anthropic: boolean
}
/** 全量总览（后端 StatsController totals · 全会话聚合） */
export interface StatsTotals {
  sessionCount: number
  tokenCount: number
  costYuan: number
}
/** GET /api/v1/stats 响应 */
export interface StatsResponse {
  totals: StatsTotals
  byDay: StatsByDay[]
  byModel: StatsByModel[]
}

// ---- Team 协作（/api/v1/teams · 对齐后端 TeamController）----
/** SessionDto.teamContext（会话绑定的团队上下文 · 无 team 为 null） */
export interface SessionTeamContext {
  teamName: string
  teamFilePath?: string | null
  leadAgentId: string
  /** 创建团队的会话 id（STOMP 订阅键 /topic/sessions/{leadSessionId}/team-*） */
  leadSessionId?: string | null
  /** teammates：以 leadAgentId 为键的成员名册 */
  teammates?: Record<string, SessionTeammate> | null
}
/** teamContext.teammates 单成员 */
export interface SessionTeammate {
  name: string
  agentType?: string | null
  cwd?: string | null
}
/** TeamDto.members 单成员（对齐后端 TeamMemberDto） */
export interface TeamMemberDto {
  agentId: string
  name: string
  agentType?: string | null
  model?: string | null
  color?: string | null
  mode?: string | null
  isActive?: boolean
  joinedAt?: string | null
  tmuxPaneId?: string | null
  cwd?: string | null
  backendType?: string | null
}
/** 团队详情（GET/POST/DELETE /api/v1/teams） */
export interface TeamDto {
  name: string
  description?: string | null
  leadAgentId: string
  leadSessionId?: string | null
  members: TeamMemberDto[]
  teammateStatuses?: unknown[] | null
}
/** 收件箱消息（GET /teams/{name}/inbox） */
export interface TeammateMessageDto {
  from: string
  text: string
  timestamp: string
  read?: boolean
  color?: string | null
  summary?: string | null
}
/** STOMP /topic/sessions/{leadSessionId}/team-status 载荷（方案3：按 lead 会话推送） */
export interface TeamStatusEvent {
  type: string
  teamName: string
  eventType: 'created' | 'deleted' | 'member_joined' | 'member_left'
  timestamp?: string
}
/** STOMP /topic/sessions/{leadSessionId}/team-messages 载荷（8 字段 · 方案3：按 lead 会话推送） */
export interface TeammateMessageEvent {
  type: string
  teamName: string
  from: string
  to: string
  text: string
  summary?: string | null
  color?: string | null
  timestamp: string
}
/** POST /api/v1/teams 创建请求 */
export interface CreateTeamRequest {
  teamName: string
  description?: string
  agentType?: string
  sessionId?: string
}
/** 会话创建请求 · mainProjectId 必填（后端 @NotBlank）：空串/null 一律 400，不产生「未绑定会话」 */
export interface SessionCreateRequest { title?: string; model?: ModelTag; modelName?: string; mainProjectId: string }
/** 会话更新（PATCH /sessions/{id} · null=不改动，mainThreadAgent 空串=清除） */
export interface SessionUpdateRequest { title?: string; model?: ModelTag; modelName?: string; mainProjectId?: string; bareMode?: boolean; permissionMode?: PermissionMode; /** V58 main_thread_agent · 会话级主线程 agent（专家）· null=不改动，空串=清除 */ mainThreadAgent?: string | null; /** V75 会话级协调者模式覆盖（coordinator_mode）· true/false=显式设置，不传=不改动 */ coordinatorMode?: boolean | null }
/** GET /agents/list?sessionId={sid} 单条（后端 AgentListDto · agentType 专家列表） */
export interface AgentListItem {
  agentType: string
  whenToUse: string
  source: string
  model?: string | null
  memory?: string | null
  tools?: string[] | null
  color?: string | null
}

// ---- 技能市场（GET/POST /api/market/* · 后端 MarketController 代理腾讯 workbuddy 市场 · remote=true 表示远端市场项）----
/** GET /api/market/expert?page&page_size 单条（后端 MarketExpertDto · 远端专家）
 *  字段对齐：marketId=市场源侧唯一 id；useCount 原始数字 / useCountDisplay 展示用文案（如「12.6万次使用」）。 */
export interface MarketExpert {
  /** 市场源侧唯一 id（POST use 路径段用） */
  marketId: string
  /** 专家 agentType（use 后端构造成本地 agent 后写入会话 mainThreadAgent 的值） */
  agentName?: string | null
  /** 展示名（卡片标题） */
  displayName?: string | null
  /** 头像/图标 URL（有则图，无则色块兜底） */
  icon?: string | null
  /** 专业领域/岗位（如「资深后端工程师」） */
  profession?: string | null
  /** 一句话介绍（卡片描述 2 行截断） */
  description?: string | null
  /** 标签（卡片底部 chips） */
  tags?: string[] | null
  /** 技能分类（聚合分类胶囊用） */
  categories?: string[] | null
  /** 使用次数原始值（展示用 useCountDisplay，排序可用它） */
  useCount?: number | null
  /** 展示用使用量文案（如「12.6万次使用」） */
  useCountDisplay?: string | null
  /** 是否已内置预装（远端列表也标「已安装」） */
  preinstalled?: boolean | null
  /** 是否精选/推荐置顶 */
  featured?: boolean | null
  /** 远端来源标识 */
  remote?: boolean
}
/** GET /api/market/skill 单条（后端 MarketSkillDto · 远端技能） */
export interface MarketSkill {
  marketId: string
  /** 技能命令名（如 todo-write） */
  name?: string | null
  displayName?: string | null
  icon?: string | null
  description?: string | null
  /** 技能分类（聚合「全部」胶囊过滤用） */
  categories?: string[] | null
  /** 使用示例（description 下方的示例短语，骨架未用可后续展示） */
  examples?: string[] | null
  /** 是否已内置预装 */
  preinstalled?: boolean | null
  remote?: boolean
}
/** GET /api/market/connector 单条（后端 MarketConnectorDto · 远端连接器） */
export interface MarketConnector {
  marketId: string
  /** 连接器名（如「Notion」「GitHub」） */
  name?: string | null
  /** 权限范围说明（description 合成用） */
  scope?: string | null
  /** 状态（如 available/installed） */
  status?: string | null
  /** 鉴权类型（如 OAuth2 / API Key） */
  authType?: string | null
  /** 是否已连接（「已安装」判定） */
  isConnected?: boolean | null
  remote?: boolean
}
/** POST /api/market/expert/{marketId}/use 响应（后端已构造本地 agent + 设会话 mainThreadAgent） */
export interface MarketUseExpertResult {
  /** 会话新主线程 agent（本地 expert agentType） */
  mainThreadAgent: string
  /** 展示名（toast「已使用 X 驱动会话」） */
  displayName?: string | null
}
/** GET /api/market/external 单插件（后端 ExternalPluginDto · 自建 MinIO 市场插件） */
export interface ExternalMarketPlugin {
  /** 插件英文标识（marketplace.json entry.name，安装定位用） */
  name: string
  /** 中文展示名 */
  displayName?: string | null
  /** 描述 */
  description?: string | null
  /** 版本号 */
  version?: string | null
  /** 分类（expert / skill / connector，前端分流到 Tab） */
  category?: string | null
  /** 标签 */
  tags?: string[] | null
  /** 创建时间（ISO 8601） */
  createdAt?: string | null
}
/** GET /api/market/external 响应（后端 ExternalMarketDto · 自建 MinIO 市场） */
export interface ExternalMarket {
  /** 市场名 */
  name: string
  /** 市场所有者 */
  owner?: string | null
  /** 市场描述 */
  description?: string | null
  /** 插件列表（category 分流） */
  plugins: ExternalMarketPlugin[]
}
/** POST /api/plugins/install 响应（后端 PluginInstallResult） */
export interface PluginInstallResult {
  /** 是否安装成功 */
  success: boolean
  /** 结果消息（中文；失败含原因） */
  message: string
  /** 已安装插件标识（name@marketplace） */
  pluginId?: string | null
  /** 插件名 */
  pluginName?: string | null
  /** 安装 scope */
  scope?: string | null
}

// ---- Todo 清单（TodoWrite 工具 · GET /sessions/{id}/todos + STOMP /topic/sessions/{sessionId}/todos）----
/** Todo 状态（对齐后端 TodoItem.status） */
export type TodoStatus = 'pending' | 'in_progress' | 'completed'
/** 单个 todo 项 */
export interface TodoItem {
  content: string
  status: TodoStatus
  /** 进行中文案（如「正在执行任务一」）；pending/completed 可空 */
  activeForm?: string | null
}
/** GET /sessions/{id}/todos 响应 · 后端 TodoStatusController.TodoSnapshotDto（单会话快照，非 Record） */
export interface TodoSnapshotDto {
  /** 主桶 todoKey（= 会话 id） */
  todoKey?: string | null
  /** 主桶 todo 数组（status 已小写） */
  todos?: TodoItem[] | null
  /** 读侧快照时刻（ms） */
  updatedAt?: number | null
  /** 全部桶键（含子 agent UUID 桶 · 前端仅渲染主桶） */
  availableTodoKeys?: string[] | null
}
/** 兼容：旧 Record 形态（STOMP 事件曾按此设计；现 REST 用 TodoSnapshotDto，此类型保留供历史引用） */
export type TodosByKey = Record<string, TodoItem[]>
// ---- 任务清单合并端点（GET /tasks/list · TaskCreate V2 + TodoWrite V1 互斥）----
/** TaskCreate 任务项（v2Tasks）· 对齐后端 TaskItem */
export interface TaskItem {
  id: string
  subject: string
  description?: string | null
  /** 进行中文案（如「正在执行任务一」） */
  activeForm?: string | null
  status: TodoStatus
  /** 阻塞该任务的任务 id 列表 */
  blocks?: string[]
  /** 该任务依赖的任务 id 列表 */
  blockedBy?: string[]
  metadata?: Record<string, unknown> | null
}
/** GET /tasks/list?sessionId 响应 · 任务清单合并快照（TaskCreate V2 + TodoWrite V1 · V1/V2 互斥，一方恒空） */
export interface TaskListSnapshotDto {
  /** 任务清单归属（= 会话 id） */
  taskListId: string
  /** TaskCreate 任务（V2 文件）· 与 v1Todos 互斥 */
  v2Tasks: TaskItem[]
  /** TodoWrite 任务（V1 sessions.todos）· 与 v2Tasks 互斥 */
  v1Todos: TodoItem[]
  /** 读侧快照时刻（ms） */
  updatedAt?: number | null
}
/** STOMP /topic/sessions/{sessionId}/todos 载荷（整体替换：收到事件直接 set，不增量） */
export interface TodoUpdateEvent {
  type: string
  sessionId?: string | null
  todoKey?: string | null
  todos?: TodoItem[] | null
  updatedAt?: number | null
}

// ---- 会话工具（GET/PATCH /sessions/{id}/tools · 会话级临时禁用）----
/** 单个会话工具状态（对齐后端 SessionToolDto · 被禁工具仍在列表，disabled=true 供恢复） */
export interface SessionToolDto {
  /** 工具名（如 Bash/Read/Agent · PATCH 路径段） */
  name: string
  /** 展示名（如「Bash（终端）」） */
  userFacingName: string
  /** 是否已禁用（bare 模式仅 Bash/Read/Edit 可见） */
  disabled: boolean
}
/** PATCH /sessions/{id}/tools/{toolName} 请求体（注意非 toggle，enabled=false 禁用 / true 恢复） */
export interface SessionToolPatchRequest {
  enabled: boolean
}

// ---- ChatMessage（对齐后端 ChatMessageDto，重构现有 mock 形状） ----
export type Role = 'user' | 'assistant' | 'system' | 'tool'
export type FinishReason = 'stop' | 'length' | 'tool_calls' | 'content_filter' | 'error'
export interface ToolCallDto {
  id: string | null
  name: string | null
  arguments: string | null   // JSON 字符串，前端 JSON.parse
  result: string | null
  isError: boolean | null
}
/** 压缩边界元数据（compact_boundary / microcompact_boundary 消息携带 · 后端透传）
 *
 *  <p>[死字段清理] 原声明的 `postTokens` / `summary` 已删：后端
 *  `CompactBoundaryMessage.toCompactMetadataMap` 从不 put 这两个 key（CC 的 compactMetadata
 *  也没有它们），前端读点结构上永取不到值。压缩摘要正文改由轨迹视图经「边界↔摘要配对」
 *  （`utils/compactPairing.ts`）读取。 */
export interface CompactBoundaryMetadata {
  /** 压缩前 tokens（CC original: compactMetadata.preTokens） */
  preTokens?: number | null
  /** IMP2-14 · 被总结的消息条数（CC original: compactMetadata.messagesSummarized）
   *  （后端 CompactBoundaryMessage.toCompactMetadataMap 有值才 put → 可能缺省） */
  messagesSummarized?: number | null
  [key: string]: unknown
}
/** snip 裁剪边界元数据（snip_boundary 消息携带 · 后端透传） */
export interface SnipBoundaryMetadata {
  /** 被 snip 移除的消息 id 列表（「已裁剪 · 移除 N 条」展示用） */
  removedUuids?: string[] | null
  [key: string]: unknown
}
/** [P2-15] Stop hook 摘要行载荷（前端实时行专用 · 后端 /topic/tasks 出站，不落 DB、不进模型上下文）。
 *  字段对齐 CC {@code SystemStopHookSummaryMessage}（utils/messages.ts:4838-4866）。 */
export interface StopHookSummaryPayload {
  /** 本轮 Stop 事件上执行的 hook 总数（CC hookCount） */
  hookCount: number
  /** hook 标签（CC hookLabel）；Stop/SubagentStop 生产形态为 null/空 → 非 label 摘要 */
  hookLabel?: string | null
  /** hook 错误列表（CC hookErrors · 非空才展示） */
  hookErrors: string[]
  /** 是否阻止继续（CC preventedContinuation · 真时展示 stopReason） */
  preventedContinuation: boolean
  /** 阻止继续的原因（CC stopReason · 仅 preventedContinuation=true 时有值） */
  stopReason?: string | null
}
export interface ChatMessageDto {
  id: string
  sessionId: string
  role: Role
  author: string | null
  content: string | null
  reasoning: string | null
  toolCalls: ToolCallDto[] | null
  finishReason: FinishReason | null
  inputTokens: number | null
  outputTokens: number | null
  /** 37 · thinking 耗时（ms）· GET /messages 每消息带出；user/tool 消息 null */
  reasoningDurationMs: number | null
  time: string | null
  createdAt?: string
  toolCallId: string | null
  assistantMessageId: string | null
  subtype: string | null
  isMeta: boolean
  /** IMP2-14 · compact 摘要 user 消息标记（CC original: isCompactSummary, messages.ts:465/480）：
   *  true = 该 user 消息是 compact 摘要正文（后端 CompactConversation.buildCompactSummaryMessage 产出，
   *  轨迹视图用它作为「已压缩」标记行的摘要详情来源）。可选：本地乐观构造的消息不带该字段。 */
  isCompactSummary?: boolean
  /** IMP2-14 · 「仅 transcript 可见」标记（CC original: isVisibleInTranscriptOnly, messages.ts:464/479）：
   *  true = 该消息仅在 UI/transcript 展示（shouldShowUserMessage, messages.ts:5115）并带 SDK isSynthetic
   *  事件标记；**严禁据此做模型面过滤** —— auto compact 摘要（compact.ts:643-650）带该标志但必须发给模型。
   *  后端 V70 is_visible_in_transcript_only 列读回；可选：本地乐观构造的消息不带该字段。 */
  isVisibleInTranscriptOnly?: boolean
  isApiErrorMessage: boolean
  apiError: string | null
  error: string | null
  errorDetails: string | null
  /** FNT-TC-01：classifier 自动批准规则（后端 ChatMessageDto.java:155 顶层出站，工具结果消息上携带） */
  matchedRule: string | null
  /** FE-11/F27/CHK-8：附件面（hook_stopped_continuation / tombstone / output_token_usage / background_task_notification 等）；可选避免破坏既有构造点 */
  attachments?: AttachmentMessageDto[] | null
  /** 前端乐观追加的图片附件（base64 直传图 · 发送后立即在用户消息显示缩略图；DB 重拉后端不出站该字段） */
  imageData?: { base64: string; mediaType: string }[] | null
  /** 后端出站图片 id 列表（对齐 ChatMessageDto.imagePasteIds · 重拉后按此批量拉图显示缩略图） */
  imagePasteIds?: string[] | null
  /** 所属 flow 的用户消息 id（user 消息=自身 id；assistant/tool=发起它的用户消息 id · 前端按此分组锚定消息链） */
  userMessageId?: string | null
  /** complete 事件透传：本轮真实 usage（snake_case · 无上报省略） */
  usage?: MessageUsageDto | null
  /** complete 事件透传：会话累计花费（元） */
  totalCostUsd?: number | null
  /** complete 事件透传：按模型 usage 快照（会话累计） */
  modelUsage?: Record<string, ModelUsageEntry> | null
  /** complete 事件透传：上下文已用 tokens（**快照口径** · 协议分派） */
  contextTokensUsed?: number | null
  /** complete 事件透传：**窗口相对**剩余百分比（快照口径 · 与 token_warning 的阈值相对口径不同源） */
  percentLeft?: number | null
  /** complete 事件透传：解码耗时（ms · usage.decode_ms 来源 · F4 t/s 速度显示） */
  decodeMs?: number | null
  /** complete 事件透传：模型上下文窗口（tokens · contextWindow 来源 · 模型原始 max_context_tokens） */
  contextWindow?: number | null
  /** 边界消息元数据（role=system + subtype=compact_boundary 携带 · 压缩分界线识别展示用） */
  compactMetadata?: CompactBoundaryMetadata | null
  /** 边界消息元数据（role=system + subtype=microcompact_boundary 携带 · 微压缩分界线识别展示用） */
  microcompactMetadata?: CompactBoundaryMetadata | null
  /** 边界消息元数据（role=system + subtype=snip_boundary 携带 · snip 裁剪分界线识别展示用） */
  snipMetadata?: SnipBoundaryMetadata | null
  /** [P2-15] Stop hook 摘要行载荷（subtype='stop_hook_summary' 的**实时行**携带 ·
   *  /topic/tasks 出站、不落库不进模型 → 仅本次会话存活，F5 后不再有此行） */
  stopHookSummary?: StopHookSummaryPayload | null
  /** 用户附件（PDF/Word/视频/音频/文件 · user 气泡内联胶囊展示 · 点击预览）。
   *  乐观追加带 base64（≤5MB 即时预览）/ path（local-read 大文件本地读）；F5 重拉后端出站 url（内容端点）+ contentId */
  userAttachments?: { type: string; filename: string; mediaType?: string | null; contentId?: string | null; url?: string | null; base64?: string | null; path?: string | null }[] | null
}
/** 附件契约（attachment-multimodal）：结构化附件。图片 base64 直传；大 PDF 先 upload 拿 contentId；
 *  local-read 模式（前后端同机）大文件传本地 path 由后端读盘，与 base64/contentId 三选一 */
export interface AttachmentRequest {
  type: 'image' | 'pdf' | 'video' | 'audio' | 'file'
  /** 后端缓存 id（上传/落盘后返回 · 附件表自增 id） */
  contentId?: string
  filename?: string
  mediaType?: string
  /** 图片/小文件直传（≤5MB） */
  base64?: string
  /** 本地绝对路径（local-read=true 前端 Tauri 直传后端同机读盘 · 不 upload） */
  path?: string
}
export interface SendMessageRequest {
  content: string
  modelName?: string
  useFastModel?: boolean
  attachments?: AttachmentRequest[]
  appendSystemPrompt?: string
  fallbackModel?: string
}
/** [批 A5] POST /sessions/{id}/queue/pop 响应 · 对齐 CC PopAllEditableResult
 *  （utils/messageQueueManager.ts:415-419 {text, cursorOffset, images}）的 web 等价物。
 *  - text = 全部【可编辑】排队项文本 + 输入框草稿，`\n` join（CC :455-456 逐字）；
 *  - attachments = 全部弹出项的附件（后端 QueueItem.attachments 原序展开，可直接并入待发 chip 重投）；
 *  无可弹出项 = {text: '', attachments: []}（不是 null / 空体 —— 前端统一按 JSON 解析）。 */
export interface QueuePopResponse {
  text: string
  attachments: AttachmentRequest[]
}
export interface MessageCreatedResponse { userMessageId: string; assistantMessageId: string; streamTopic: string; queued?: boolean }
export interface PartialCompactRequest { messageId: string; direction?: 'from' | 'up_to'; feedback?: string }
export interface PartialCompactResponse { messages: ChatMessageDto[]; conversationId: string }

// ---- STOMP 事件全集（基类 type/sessionId/userMessageId/ts） ----
export type StreamEventType =
  | 'message.chunk' | 'message.complete' | 'message.usage' | 'message.error' | 'message.cancelled' | 'message.user'
  | 'message.tool_call' | 'message.tool_result'   // 占位不发，仅类型占位
  | 'message.boundary'                             // [snip-persist] Snip 裁剪边界（removedUuids → 消息「已裁剪」角标）
  | 'session.status' | 'session.title'
  | 'permission.request'
  | 'api_retry' | 'files.changed' | 'token_warning'
  | 'task_started' | 'task_progress' | 'task_notification' | 'session_state_changed' | 'tool_use_summary'
  | 'hooks_start' | 'compact_start' | 'compact_end'   // §2 压缩 5 事件（CMP-5，后端事件已产生未出站，先备类型）
  | 'hook_progress'                                   // FE-08 hook 进度消息流（后端未出站，先备类型）
  | 'turn_duration'                                   // F34 turn 时长（后端未生产，先备类型）
  | 'message.tombstone'                               // F27 tombstone（后端未出站，先备类型）
  | 'mcp.connectivity'                                // FM-3 MCP 连接状态（后端未出站，先备类型）
  // 注：task_* 4 类为 /topic/tasks 后台任务事件鉴别名——运行时载荷 type 恒为 'system'（subtype 区分，
  //     见 TaskEventSubtype 与 TaskEvent 族）；tool_use_summary 为独立 type='tool_use_summary'。
  // 注：无 'notification' —— CC 通知是纯客户端 Zustand 状态机（context/notifications.tsx），
  // 无 server→client 推送通道；前端自建本地 useNotifications hook，不订阅后端 topic。

export interface StreamEventBase {
  type: string
  sessionId?: string | null
  userMessageId?: string | null
  ts?: number            // epoch 毫秒
}
export interface MessageChunkEvent extends StreamEventBase {
  type: 'message.chunk'
  assistantMessageId?: string | null
  delta?: string | null
  reasoning?: string | null
}
/** [snip-persist] Snip 裁剪边界事件 · 后端 replayAndPersist 落库 snip_boundary 时推送，
 *  removedUuids = 被裁剪消息 id 列表 → 前端把对应消息右上角标注「已裁剪」（实时）；F5 由
 *  GET /messages 返回的 boundary 消息（ChatMessageDto.snipMetadata.removedUuids）兜底 */
export interface MessageBoundaryEvent extends StreamEventBase {
  type: 'message.boundary'
  removedUuids?: string[] | null
  summary?: string | null
}
/** 后端推送的 user 消息（cron/Ask 后台落库 prompt · isMeta=true 前端占位不显示，保持 flow 顺序） */
export interface PushedUserMessageEvent extends StreamEventBase {
  type: 'message.user'
  id?: string | null
  content?: string | null
  isMeta?: boolean
}
/** complete 事件本轮 token usage（snake_case · 对齐 CC result.usage） */
export interface MessageUsageDto {
  input_tokens: number
  output_tokens: number
  cache_read_input_tokens?: number
  cache_creation_input_tokens?: number
  server_tool_use?: { web_search_requests?: number; web_fetch_requests?: number }
  service_tier?: string
  cache_creation?: { ephemeral_1h_input_tokens?: number; ephemeral_5m_input_tokens?: number }
  inference_geo?: string
  iterations?: string[]
  speed?: string
  /** 解码耗时（ms · 首 token → 完成 · 对齐后端 firstToken→complete 计时，F4 t/s 速度计算分母） */
  decode_ms?: number | null
}
/** complete 事件按模型 usage 快照（camelCase · 对齐 CC result.modelUsage） */
export interface ModelUsageEntry {
  inputTokens: number
  outputTokens: number
  cacheReadInputTokens: number
  cacheCreationInputTokens: number
  webSearchRequests: number
  costUSD: number
  contextWindow: number
  maxOutputTokens: number
}
export interface MessageCompleteEvent extends StreamEventBase {
  type: 'message.complete'
  assistantMessageId?: string | null
  content?: string | null
  reasoning?: string | null
  finishReason?: FinishReason | null
  inputTokens?: number | null
  outputTokens?: number | null
  /** 37 · thinking 耗时（ms）· 无 reasoning 时后端省略（NON_NULL），故可选 */
  reasoningDurationMs?: number | null
  /** CHK-8/FE-11：附件面（output_token_usage / tombstone / hook_stopped_continuation 等）；后端 complete 事件携带则前端透传 */
  attachments?: AttachmentMessageDto[] | null
  /** 本轮累计 usage（snake_case · 无上报时省略） */
  usage?: MessageUsageDto | null
  /** 会话累计花费（元 · 字段名对齐 CC，值非美元） */
  total_cost_usd?: number | null
  /** 按模型 usage 快照（会话累计 · camelCase） */
  modelUsage?: Record<string, ModelUsageEntry> | null
  /** 本轮耗时（Java turn 墙钟近似 · 仅展示） */
  duration_ms?: number | null
  /** 本轮轮数 */
  num_turns?: number | null
  /** 模型上下文窗口（tokens · 回落 1M） */
  contextWindow?: number | null
  /** 上下文已用（input + cache_read + cache_creation · 不含 output） */
  contextTokensUsed?: number | null
  /** 上下文剩余百分比（0-100 · 无 usage 时省略 · 负数 clamp 0） */
  percentLeft?: number | null
}
/** 消息级 usage 快照事件（后端每条 assistant 流式结束推 · 消息级完成、非 turn 终态）。
 *  携带该条 usage + 上下文快照 → 前端实时更新缓存%/上下文条；不得当 turn 终态退订（退订只在 message.complete）。
 *
 *  <p>{@code assistantMessageId == null} = **会话级快照覆盖**（后端 P3-b：手动 /compact 成功落库后推
 *  会话级归零快照 used=0/percentLeft=100）。⚠️ 该推送通道
 *  （{@code CompactCommand.registerPostCompactSnapshotPushContext}）当前**生产未接线**（无注册调用方）
 *  → 实际不会下发；前端压缩归零走「/compact 成功后重拉消息」（{@code App.runBuiltin}）而非本事件。 */
export interface MessageUsageEvent extends StreamEventBase {
  type: 'message.usage'
  /** 该条 assistant 消息 id；null = 会话级快照覆盖（见上） */
  assistantMessageId?: string | null
  usage?: MessageUsageDto | null
  /** 模型上下文窗口（tokens · 模型原始 max_context_tokens，未命中回落 1_048_576） */
  contextWindow?: number | null
  /** 上下文已用（协议分派：Anthropic input+cacheRead+cacheCreate / OpenAI·DeepSeek 仅 input） */
  contextTokensUsed?: number | null
  /** **窗口相对**剩余百分比（0-100 · 无 usage 时省略 · 负数 clamp 0） */
  percentLeft?: number | null
}
export interface MessageErrorEvent extends StreamEventBase {
  type: 'message.error'
  assistantMessageId?: string | null
  code?: string | null
  message?: string | null
}
/** 工具调用开始（契约 #6 · 后端回放推，assistantMessageId=发起轮 assistant id） */
export interface MessageToolCallEvent extends StreamEventBase {
  type: 'message.tool_call'
  assistantMessageId?: string | null
  toolCallId?: string | null
  toolName?: string | null
  /** 已解析参数对象（后端 Map → JSON 对象）；前端 JSON.stringify 转字符串对齐 ToolCallDto.arguments */
  arguments?: Record<string, unknown> | null
}
/** 工具调用结果（契约 #6 · 后端回放推；前端按 toolCallId 匹配卡片填 result/isError） */
export interface MessageToolResultEvent extends StreamEventBase {
  type: 'message.tool_result'
  assistantMessageId?: string | null
  toolCallId?: string | null
  result?: string | null
  isError?: boolean | null
}
export interface MessageCancelledEvent extends StreamEventBase {
  type: 'message.cancelled'
  assistantMessageId?: string | null
}
export interface SessionStatusEvent extends StreamEventBase {
  type: 'session.status'
  status?: 'thinking' | 'streaming' | 'idle'
}
export interface SessionTitleEvent extends StreamEventBase {
  type: 'session.title'
  title?: string
}

// ---- AskUserQuestion（对齐后端 AskUserQuestionTool inputSchema；wire 上承载于 toolInput.questions）----
/** AskUser 问题选项（后端 questionOptionSchema：label/description 必填，preview 可选） */
export interface AskUserQuestionOption {
  label: string
  description: string
  preview?: string
}
/** AskUser 问题（后端 questions item：question/header/options 必填，multiSelect 默认 false） */
export interface AskUserQuestion {
  question: string
  header: string
  options: AskUserQuestionOption[]
  multiSelect?: boolean
  /** 兼容 snake_case 模型输出（部分模型输出 multiple_selection/multi_select 而非 camelCase multiSelect） */
  multiple_selection?: boolean
  multi_select?: boolean
}
/** AskUser 答案：questionText → optionLabel（multi-select 逗号拼接，对齐 CC Record<string,string>） */
export type AskUserAnswers = Record<string, string>
/** AskUser 单问注解（对齐后端 annotationsSchema：preview/notes 可选） */
export interface AskUserAnnotation {
  preview?: string
  notes?: string
}
/** AskUser 注解：questionText → { preview?, notes? } */
export type AskUserAnnotations = Record<string, AskUserAnnotation>

// ---- 权限更新（PermissionUpdate）· 线格式 ----
//
// 后端 `PermissionUpdate` 的 wire 由单点序列化器产出，形状为 CC 的
// discriminatedUnion('type', ...)：
//   addRules / replaceRules / removeRules → {type, rules:[{toolName, ruleContent?}], behavior, destination}
//   setMode                               → {type, mode, destination}
//   addDirectories / removeDirectories    → {type, directories:[...], destination}
//
// 前端**只做原样透传**（弹窗回传 updatedPermissions）——不做任何结构转换，
// 否则等于引入第二份实现。
//
// 注意：`ruleContent` 缺省表示「整个工具」放行（不是空串）。

/** 更新落地位置（后端 Destination.ccLiteral 的逐字取值，大小写敏感） */
export type PermissionUpdateDestination =
  | 'userSettings'
  | 'projectSettings'
  | 'localSettings'
  | 'cliArg'
  | 'session'

/** 规则行为（后端 PermissionBehavior.ccLiteral） */
export type PermissionBehaviorLiteral = 'allow' | 'deny' | 'ask'

/** 权限模式（setMode 的 mode · 后端 ToolPermissionGate.modeToCcString） */
export type PermissionModeCcLiteral =
  | 'default' | 'plan' | 'acceptEdits' | 'bypassPermissions' | 'dontAsk' | 'auto'

/** 单条规则（ruleContent 缺省 = 整工具放行） */
export interface PermissionRuleValueWire {
  toolName: string
  ruleContent?: string
}

/** 规则型更新（addRules / replaceRules / removeRules 共用形状） */
export interface PermissionUpdateRulesWire {
  type: 'addRules' | 'replaceRules' | 'removeRules'
  rules: PermissionRuleValueWire[]
  behavior: PermissionBehaviorLiteral
  destination: PermissionUpdateDestination
}
/** 模式型更新 */
export interface PermissionUpdateSetModeWire {
  type: 'setMode'
  mode: PermissionModeCcLiteral
  destination: PermissionUpdateDestination
}
/** 目录型更新 */
export interface PermissionUpdateDirectoriesWire {
  type: 'addDirectories' | 'removeDirectories'
  directories: string[]
  destination: PermissionUpdateDestination
}
/** 权限更新（6 型判别联合 · 与后端线格式逐字段一致） */
export type PermissionUpdate =
  | PermissionUpdateRulesWire
  | PermissionUpdateSetModeWire
  | PermissionUpdateDirectoriesWire

export interface PermissionRequestEvent extends StreamEventBase {
  type: 'permission.request'
  requestId: string
  toolName: string
  toolInput?: unknown
  reason?: { type: string; detail: string } | null
  /** 危险命令警告（红/黄警示文案；空/缺省则正常弹窗） */
  warning?: string | null
  description?: string | null
  /** 一键授权建议（后端 PermissionPromptDetails.suggestions）· 原样回传 updatedPermissions */
  suggestions?: PermissionUpdate[] | null
  blockedPath?: string | null
  /** AskUser 问题（便捷访问；实际 wire 位置在 toolInput.questions，解析 toolInput 获得） */
  questions?: AskUserQuestion[]
}
/** 压缩警告抑制态（对齐 CC compactWarningStore/TokenWarning · STOMP token_warning）
 *
 *  <p>[P3-d 口径统一 2026-09-11] <b>本事件的 tokenUsage/contextWindow/percentLeft 是「阈值相对」口径，
 *  不是上下文快照口径</b>：后端 wire 字段名仍是 {@code percentLeft}（JSON 键 = AgentEvent.TokenWarning
 *  的 record 分量名，未随改名变化；改名的是 {@code CompactThresholdSystem.TokenWarningState} 的
 *  <b>访问器</b> {@code thresholdRelativePercentLeft}）——但语义 = {@code max(0, round((threshold − usage)
 *  / threshold × 100))}，分母 threshold = autoCompactThreshold（≠ 上下文窗口）；tokenUsage 还是**本地
 *  估算**（非服务端真实 usage）。**禁止当「上下文剩余 %」渲染**，要数字请走快照口径
 *  （{@code resolveCtxInfo}）。 */
export interface TokenWarningEvent extends StreamEventBase {
  type: 'token_warning'
  /** 压缩警告抑制态：压缩成功=true → 前端清除该会话告警；false（抑制位复位）**不等于显示** ——
   *  是否显示另看载荷有无真实用量数字（{@code tokenUsage>0} 才显示；后端抑制开关翻转时推的
   *  占位载荷 tokenUsage=0/contextWindow=0/percentLeft=null 仍不显示）。 */
  suppressed?: boolean
  /** 当前 token 用量（**本地估算** · 阈值判定用，非服务端真实 usage） */
  tokenUsage?: number
  /** 有效上下文窗口（= 窗口 − reserved；阈值口径分母，非模型原始 max_context_tokens） */
  contextWindow?: number
  /** 阈值相对百分比（分母 = autoCompactThreshold · 内部四态判定用；**不是**剩余百分比，禁止展示） */
  percentLeft?: number
}

/** 压缩进度事件（STOMP /topic/sessions/{sid}/compact-progress · 后端 CompactProgressState.toFrontendJson）。
 *  hooks_start{hookType} 各压缩 hook 前；compact_start 摘要请求前（进度条起步）；
 *  compact_progress{chars} 摘要流式已收字符（Java 扩展，前端驱动进度条蠕动）；compact_end 摘要结束（含 finally）。 */
export interface CompactProgressEventType {
  type: 'hooks_start' | 'compact_start' | 'compact_end' | 'compact_progress'
  /** hooks_start 的 hook 阶段：pre_compact / post_compact / session_start */
  hookType?: 'pre_compact' | 'post_compact' | 'session_start'
  /** compact_progress：已流式收到的摘要字符数（单调增） */
  chars?: number
}

export interface ApiRetryEvent extends StreamEventBase {
  type: 'api_retry'
  subtype?: string
  attempt?: number
  maxRetries?: number
  retryDelayMs?: number
  errorStatus?: number | null
  error?: string | null
  uuid?: string | null
}
export type StreamEvent =
  | MessageChunkEvent | MessageCompleteEvent | MessageUsageEvent | MessageErrorEvent | MessageCancelledEvent
  | MessageToolCallEvent | MessageToolResultEvent
  | SessionStatusEvent | SessionTitleEvent | PermissionRequestEvent | ApiRetryEvent | TokenWarningEvent
  | QueueChangedEvent | QueueDrainedEvent
  | StreamEventBase

// ---- 排队命令出站事件（B5 · STOMP /topic/sessions/{sid}/queue）----
/** 排队命令单条（queue.changed 的 commands[] / queue.drained 的 commands[]） */
export interface QueueCommandItem {
  uuid?: string
  content?: string
  mode?: string
  priority?: string
  isMeta?: boolean
}
/** queue.changed：入队/出队/清空后推会话级排队快照 */
export interface QueueChangedEvent extends StreamEventBase {
  type: 'queue.changed'
  sessionId?: string | null
  commands?: QueueCommandItem[]
}
/** queue.drained：CronIdleExecutor 消费 busy-queued 后推（排队行移除 + 注册新 streamTopic） */
export interface QueueDrainedEvent extends StreamEventBase {
  type: 'queue.drained'
  sessionId?: string | null
  drained?: (QueueCommandItem & { streamTopic?: string })[]
  commands?: QueueCommandItem[]
}

// ---- STOMP /topic/tasks 后台任务事件（对齐后端 SdkEventQueue / LlmAgentLoop，载荷扁平 snake_case）----
/** /topic/tasks 订阅主题（后台任务 SDK 事件通道） */
export const TASKS_TOPIC = '/topic/tasks'

/** 后台任务事件 subtype（drain 事件运行时 type 恒为 'system'，subtype 区分；
 *  stop_hook_summary = [P2-15] Stop hook 摘要（元数据型 · 非任务）同通道出站） */
export type TaskEventSubtype =
  | 'task_started' | 'task_progress' | 'task_notification' | 'session_state_changed'
  | 'stop_hook_summary'

/** 任务 usage 内部对象（对齐后端 SdkEventQueue.TaskUsage） */
export interface TaskEventUsage {
  total_tokens?: number
  tool_uses?: number
  duration_ms?: number
}

/** 任务事件统一基接口 · 载荷扁平：type/subtype/uuid/session_id 等 snake_case 字段平级（对齐 toFlatJsonNodes 展开） */
export interface TaskEventBase {
  type: 'system' | 'tool_use_summary'
  subtype?: TaskEventSubtype | null
  session_id?: string | null
  uuid?: string | null
}

/** 任务启动（对齐后端 TaskStartedEvent：type='system' + subtype='task_started'） */
export interface TaskStartedEvent extends TaskEventBase {
  type: 'system'
  subtype: 'task_started'
  task_id?: string | null
  tool_use_id?: string | null
  description?: string | null
  task_type?: string | null
  workflow_name?: string | null
  prompt?: string | null
}

/** 任务进度（对齐后端 TaskProgressEvent） */
export interface TaskProgressEvent extends TaskEventBase {
  type: 'system'
  subtype: 'task_progress'
  task_id?: string | null
  tool_use_id?: string | null
  description?: string | null
  usage?: TaskEventUsage | null
  last_tool_name?: string | null
  summary?: string | null
  workflow_progress?: unknown[] | null
}

/** 任务终态通知（对齐后端 TaskNotificationEvent；status='completed'|'failed'|'stopped'） */
export interface TaskNotificationEvent extends TaskEventBase {
  type: 'system'
  subtype: 'task_notification'
  task_id?: string | null
  tool_use_id?: string | null
  status?: string | null
  output_file?: string | null
  summary?: string | null
  usage?: TaskEventUsage | null
}

/**
 * 后端 d973edad 新增顶层 type='task.notification' 结构化事件（/topic/tasks · 后台任务每次终态
 * completed/failed/killed 都直推，含空闲路径）。wire 字段 = task_id（后端 TaskNotificationEvent
 * @JsonProperty("task_id")），且任务 id 同时落 StreamEvent 基类 userMessageId 槽位；sessionId 由
 * StreamEvent 承载。⚠️ 勿声明 taskId（camelCase）——wire 从不含该键，旧实现读 evt.taskId → 终态
 * addActivity 永不执行 → 子代理后台任务「运行中」虚高（2026-09-05 修复）。与既有
 * system+subtype=task_notification 并存 —— 前者是结构化载荷（前端按会话过滤 + 更新任务状态），
 * 后者是旧 type='system' 通知（其 task_id 已正确）。
 */
export interface TaskNotificationWireEvent {
  type: 'task.notification'
  sessionId?: string | null
  /** 任务 id（@JsonProperty("task_id")） */
  task_id?: string | null
  /** StreamEvent 基类槽位（后端把 taskId 同时放入 userMessageId 槽，作兜底） */
  userMessageId?: string | null
  status?: string | null
  summary?: string | null
}

/** 会话状态变更（对齐后端 SessionStateChangedEvent） */
export interface SessionStateChangedEvent extends TaskEventBase {
  type: 'system'
  subtype: 'session_state_changed'
  state?: string | null
}

/** 工具使用摘要（单对象，type='tool_use_summary'，对齐 LlmAgentLoop:6425-6434） */
export interface ToolUseSummaryEvent extends TaskEventBase {
  type: 'tool_use_summary'
  summary?: string | null
  preceding_tool_use_ids?: string[] | null
}

/**
 * [P2-15] Stop hook 摘要（单对象，type='system' + subtype='stop_hook_summary'）·
 * 对齐 CC {@code createStopHookSummaryMessage}（utils/messages.ts:4838-4866）经 stopHooks.ts:309-318
 * yield 进消息流。Java 出站点 {@code LlmAgentLoop.emitStopHookSummarySdkMessage} → /topic/tasks。
 *
 * <p><b>元数据型内容</b>（hook 计数 / 错误列表 / 阻止继续原因），<b>不是</b>对话正文 ——
 * 渲染必须走独立摘要行（对齐 CC SystemTextMessage.StopHookSummaryMessage），不得当普通气泡。
 *
 * <p>{@code hook_label}：CC 为 {@code string|undefined}；Stop/SubagentStop 生产形态为 null
 * （CC compact 摘要以外唯一生产者为 Pre/PostToolUse，本仓 Stop 摘要恒 null）。
 */
export interface StopHookSummaryEvent extends TaskEventBase {
  type: 'system'
  subtype: 'stop_hook_summary'
  hook_count?: number | null
  hook_infos?: string[] | null
  hook_errors?: string[] | null
  prevented_continuation?: boolean | null
  stop_reason?: string | null
  has_output?: boolean | null
  hook_label?: string | null
  total_duration_ms?: number | null
}

/** /topic/tasks 载荷（drain 出站为 JSON 数组）单条事件联合 */
export type TaskEvent =
  | TaskStartedEvent
  | TaskProgressEvent
  | TaskNotificationEvent
  | SessionStateChangedEvent
  | ToolUseSummaryEvent
  | StopHookSummaryEvent
  | TaskNotificationWireEvent

/** 子代理 transcript 单条消息（对齐后端 AgentMessage record · GET /sessions/{sid}/subagents/{agentId}/transcript） */
export interface AgentTranscriptMessage {
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  isApiError: boolean
  agentId: string | null
  isSidechain: boolean
  uuid: string | null
  parentUuid: string | null
  /** assistant 消息内 tool_use 块（id/name/arguments） */
  toolCalls?: { id: string; name: string; arguments: string }[] | null
  /** tool_result 消息的 tool_use id */
  toolCallId: string | null
}

/**
 * Hook 命令载荷 · 对齐后端 {@code HookCommand} sealed interface
 * （Jackson {@code @JsonTypeInfo} 按 type 字段多态：command/prompt/http/agent）。
 * 仅取 UI 展示所需字段（type/statusMessage/once + 各子类型的命令串）。
 */
export interface HookCommandConfig {
  /** CC 字面量：command / prompt / http / agent */
  type: 'command' | 'prompt' | 'http' | 'agent'
  /** 自定义状态文案（getHookDisplayText 优先用其做展示） */
  statusMessage?: string | null
  /** true = 执行一次后移除 */
  once?: boolean | null
  /** CommandHook.command */
  command?: string | null
  /** PromptHook.prompt / AgentHook.prompt */
  prompt?: string | null
  /** HttpHook.url */
  url?: string | null
}

/**
 * 单个 hook 配置 · 对齐后端 {@code IndividualHookConfig} record（五字段：
 * event/config/matcher/source/pluginName，hooksSettings.ts:22-28）。
 */
export interface HookItem {
  /** HookEventType 枚举名（如 "SESSION_START"） */
  event: string
  config: HookCommandConfig
  /** 匹配器字符串（如工具名 "Write"），无匹配器为 null */
  matcher?: string | null
  /** HookSource 枚举名（如 "USER_SETTINGS"） */
  source?: string | null
  /** 插件名（仅 PLUGIN_HOOK 来源有） */
  pluginName?: string | null
}

// ---- Branch / Export / Doctor（Phase 5 · 业务面板 · 对齐 nexusai-backend 各 Controller）----

/** GET /api/v1/branches 列表项（BranchController.list · git worktree list --porcelain 解析） */
export interface BranchWorktree {
  /** worktree 绝对路径 */
  path: string
  /** HEAD 提交 hash */
  head: string | null
  /** 分支名（refs/heads 简名；detached HEAD 时为 null） */
  branch: string | null
  /** detached HEAD 标记（porcelain 输出含 detached 时存在） */
  detached?: boolean
}

/** POST /api/v1/branches 创建/恢复分支响应（BranchController.create · status: created|resumed） */
export interface BranchCreateResponse {
  slug: string
  status: 'created' | 'resumed' | 'error' | 'fail'
  branch?: string | null
  path?: string | null
  gitRoot?: string | null
  error?: string | null
}

/** POST /{slug}/keep 与 DELETE /{slug} 响应（BranchController.keep/remove） */
export interface BranchActionResponse {
  slug: string
  status: 'kept' | 'removed' | 'error' | 'fail'
  discardChanges?: boolean
  error?: string | null
}

/** POST /api/v1/export/{sessionId}/copy 响应（ExportController.copy · 返回 markdown 字符数与消息数） */
export interface ExportCopyResponse {
  sessionId: string
  action: 'copied'
  chars: number
  messages: number
}

/** POST /api/v1/export/{sessionId}/share 响应（ExportController.share · expiresIn 固定 7d） */
export interface ExportShareResponse {
  sessionId: string
  shareUrl: string
  expiresIn: string
}

/** Doctor 单项检查（DoctorController.checks[i] · status: pass|fail|warn） */
export type DoctorCheckStatus = 'pass' | 'fail' | 'warn'
export interface DoctorCheck {
  name: string
  status: DoctorCheckStatus
  detail: Record<string, unknown>
}

/** GET /api/v1/doctor 诊断报告（DoctorController.diagnose · status: ok|degraded） */
export interface DoctorReport {
  status: 'ok' | 'degraded'
  checks: DoctorCheck[]
  warnings: string[]
}

// ---- 附件面（FE-11/F27/CHK-8 · 对齐后端 AttachmentMessageDto）----
/** 消息附件（type='attachment' 项；attachment.type 区分子类型：tombstone/hook_stopped_continuation/output_token_usage/background_task_notification 等） */
export interface AttachmentMessageDto {
  /** 附件承载消息 id（tombstone 时为 targetMessageId 来源）；后端部分附件 id=null */
  id?: string | null
  /** 消息角色名：'attachment' */
  type?: string | null
  /** 附件子类型（tombstone / hook_stopped_continuation / output_token_usage / background_task_notification …） */
  attachmentType?: string | null
  /** 附件内容（文本/XML/JSON 串，按 attachmentType 解析） */
  content?: string | null
  /** 工具使用 id（部分附件关联） */
  toolUseId?: string | null
  /** tombstone：要移除/标记的目标消息 id（F27） */
  targetMessageId?: string | null
  /** output_token_usage：本轮 token 用量（F37/CHK-8） */
  outputTokenTurn?: number | null
  /** output_token_usage：本会话累计 token 用量 */
  outputTokenSession?: number | null
  /** output_token_usage：预算上限 */
  outputTokenBudget?: number | null
}

// ---- 压缩进度事件（§2 / CMP-5 · 对齐 CC Tool.ts:150-156 + 后端 CompactProgressEvent sealed interface）----
/** 压缩 5 事件之一：hooks_start（hookType=pre_compact/session_start/post_compact） */
export interface CompactHooksStartEvent extends StreamEventBase {
  type: 'hooks_start'
  hookType?: 'pre_compact' | 'session_start' | 'post_compact' | string | null
}
/** 压缩开始 */
export interface CompactStartEvent extends StreamEventBase {
  type: 'compact_start'
}
/** 压缩结束（无论成败） */
export interface CompactEndEvent extends StreamEventBase {
  type: 'compact_end'
}
export type CompactProgressEvent = CompactHooksStartEvent | CompactStartEvent | CompactEndEvent

// ---- turn 时长 / 预算（F34 · 对齐 CC messages.ts:4433-4448）----
export interface TurnDurationEvent extends StreamEventBase {
  type: 'turn_duration'
  subtype?: 'turn_duration'
  durationMs?: number | null
  budgetTokens?: number | null
  budgetLimit?: number | null
  budgetNudges?: number | null
  messageCount?: number | null
}

// ---- tombstone（F27 · 对齐 CC query.ts:713-717）----
export interface MessageTombstoneEvent extends StreamEventBase {
  type: 'message.tombstone'
  targetMessageId: string
}

// ---- MCP 连接状态（FM-3 · 对齐 CC useMcpConnectivityStatus.tsx，后端未出站先备类型）----
export interface McpConnectivityEvent extends StreamEventBase {
  type: 'mcp.connectivity'
  server?: string | null
  status?: 'connected' | 'needs-auth' | 'failed' | string | null
  authUrl?: string | null
  authSuccess?: boolean | null
}

// ---- hook 进度消息流（FE-08 · 对齐 CC types/hooks.ts:234-241，后端未出站先备类型）----
export interface HookProgress extends StreamEventBase {
  type: 'hook_progress'
  hookEvent?: string | null
  hookName?: string | null
  command?: string | null
  promptText?: string | null
  statusMessage?: string | null
  toolUseId?: string | null
  parentToolUseId?: string | null
}

// ---- 工具元数据（§11.6 ToolMeta · 对齐 CC MCPToolListView.tsx，后端未出站先备类型）----
export interface ToolMeta {
  name: string
  type?: string | null
  server?: string | null
  userFacingName?: string | null
  isDestructive?: boolean | null
  isReadOnly?: boolean | null
  isOpenWorld?: boolean | null
  isResultTruncated?: boolean | null
}

// ---- 预测性输入建议（FE-07 · 对齐 CC usePromptSuggestion，后端未出站先备类型）----
export interface PromptSuggestion {
  suggestion: string
  promptId?: string | null
  generationRequestId?: string | null
}
