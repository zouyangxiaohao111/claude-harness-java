import { api } from './rest'

/** 单个外部运行时的探测结果（GET /api/v1/subagents/runtimes） */
export interface SubagentRuntimeInfo {
  /** 运行时 id（与 agents/*.md 里 runtime: 对齐） */
  runtime: string
  displayName: string
  /** 给用户看的一句话说明 */
  notes: string
  /** 该 CLI 认的「配置目录」环境变量（null = 不支持覆盖） */
  configDirEnv: string | null
  /** 该 CLI 认的「API base URL」环境变量（null = 按 provider 走配置文件） */
  baseUrlEnv: string | null
  /** 该 CLI 认的「API key」环境变量（null = 用自己的登录态） */
  apiKeyEnv: string | null
  /** 本机 PATH 上是否找到可执行文件（⚠️ 可探测 ≠ 能用：还要有 key/登录） */
  available: boolean
  /** 解析到的绝对路径（未找到为 null） */
  resolvedPath: string | null
  /** 需要的环境变量是否已设置 */
  apiKeyPresent: boolean
}

/** 本会话可派发的外部 agent（含内置预设与用户 md 写的 profile） */
export interface SubagentExternalAgent {
  /** 真正的 subagent_type 值，塞进设置的就是它 */
  agentType: string
  runtime: string
  description: string
  /** 继承自哪个内置预设（null = 全量手写） */
  baseRuntime: string | null
  streamFormat: string
  /** preset = 内置模板；custom = 用户在 md 里配的（含继承） */
  source: 'preset' | 'custom'
  available: boolean
}

/** 当前默认配置（三态：configured=false 表示用户从未配置过） */
export interface SubagentRuntimeCurrent {
  /**
   * 默认外部运行时 · 值域是 **runtime id**（claude / codex / opencode / pi）。
   *
   * ⚠️ **不是 agentType**（codex-external 那种）—— 后端 `settings.subagentDefaultRuntime`
   * 只认 runtime id，传 agentType 一律 400；派发侧也是按 runtime id 去 registry 反查 agentType。
   */
  defaultRuntime: string | null
  /** 与 defaultRuntime 同值（保留字段名兼容旧调用点）· 值域同样是 runtime id */
  defaultAgentType: string | null
  externalEnabled: boolean | null
  configured: boolean
}

export interface SubagentRuntimesResponse {
  runtimes: SubagentRuntimeInfo[]
  agents: SubagentExternalAgent[]
  current: SubagentRuntimeCurrent
  availableCount: number
}

/**
 * 子代理外部运行时 · 自动感知。
 *
 * 为什么放后端探测：可执行文件是否存在于**服务进程**的 PATH 里，前端无法自己判断。
 * 传 sessionId 才能拿到「本会话实际可派发的 agent」（agent 定义是按会话项目加载的）。
 */
export const subagentRuntimeApi = {
  list: (sessionId?: string | null) =>
    api<SubagentRuntimesResponse>(
      `/subagents/runtimes${sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''}`,
    ),
}

/** 推理强度如何落到 argv（后端 ExternalRuntimeCatalog.ReasoningStyle）。 */
export type ReasoningStyle = 'NONE' | 'CODEX_CONFIG' | 'PI_THINKING' | 'OPENCODE_VARIANT'

/**
 * 单个外部运行时的完整配置与能力位（GET /api/v1/subagents/runtime-configs）。
 *
 * 能力位（supportsReasoning / supportsModelList / apiKeyEnvs）决定页面渲染哪些控件
 * —— ⛔ 不支持的就不显示，而不是显示一个点了没用的灰控件。
 */
export interface CliRuntimeConfig {
  runtime: string
  displayName: string
  /** 列表行紧跟名称的短描述（如「Anthropic 官方 CLI」） */
  tagline: string | null
  /** 是否支持从页面覆盖连接信息 */
  configurable: boolean
  /** 三项各自会被写成哪个环境变量（null = 该 CLI 不认这个通用变量，前端置灰） */
  configDirEnv: string | null
  baseUrlEnv: string | null
  /** 该 CLI 认的 key 变量名（**可多个**：claude/codex 各两个）；空 = 用自身登录态 */
  apiKeyEnvs: string[]
  /** 自定义可执行路径对应的环境变量（如 CLAUDE_BIN） */
  binEnv: string | null

  /** 已保存值（key 只有掩码，永不含明文） */
  configDir: string | null
  baseUrl: string | null
  /** 变量名 → 掩码，如 { ANTHROPIC_AUTH_TOKEN: 'sk-****d23d' } */
  secrets: Record<string, string>
  apiKeySet: boolean
  bin: string | null
  model: string | null
  reasoning: string | null
  /** 任一配置项有值 */
  configured: boolean
  updatedAt: string | null

  /** 能力位 */
  supportsReasoning: boolean
  /** 支持的推理强度档位 id（不含 default，页面自己补） */
  reasoningOptions: string[]
  reasoningStyle: ReasoningStyle
  /** 是否支持实时拉取模型列表（false = 只能用内置列表） */
  supportsModelList: boolean
  /** 内置模型列表（实时拉取失败或刷新前展示） */
  fallbackModels: string[]

  /** 本机探测 */
  available: boolean
  resolvedPath: string | null
}

/** 一个模型及其自己支持的推理档位 */
export interface CliModelInfo {
  id: string
  /** 该模型支持的推理档位（空 = 不支持/未知，前端退回预设档位） */
  reasoningOptions: string[]
}

/** 模型列表响应（GET /{runtime}/models） */
export interface CliRuntimeModels {
  runtime: string
  resolvedPath: string | null
  /** true = 来自 CLI 的实时列表；false = 内置列表（reason 说明为什么） */
  live: boolean
  models: string[]
  /**
   * 与 models 同序的详细信息。**推理档位按模型给** —— codex 每个模型支持
   * 的档位不同（gpt-6-luna 到 max、gpt-6.1-sol 到 ultra），用全局表会选出 CLI 不认的档位。
   */
  modelInfos?: CliModelInfo[]
  reason?: string
}

/** 版本响应（GET /{runtime}/version） */
export interface CliRuntimeVersion {
  runtime: string
  available: boolean
  resolvedPath: string | null
  version: string | null
}

/** 测试连接结果（POST /{runtime}/test）· ⚠️ 会真的消耗一次模型调用 */
export interface CliRuntimeTestResult {
  runtime: string
  ok: boolean
  elapsedMs: number
  sample: string | null
  error: string | null
}

/**
 * 保存请求 · 各项均「缺省 = 不改，空串 = 清空」。
 * ⚠️ 前端只在用户真的编辑过某项时才带上它 —— 否则「打开页面点保存」会把原值清掉。
 */
export interface SaveCliRuntimeConfigRequest {
  configDir?: string | null
  baseUrl?: string | null
  bin?: string | null
  model?: string | null
  reasoning?: string | null
  /** 变量名 → 明文 key。null = 不改；空对象 = 清空全部 */
  secrets?: Record<string, string> | null
}

/**
 * 子代理运行时的配置（配置目录 / base URL / API key / 自定义 bin / 模型 / 推理强度）。
 *
 * 语义（与后端一致）：
 *  1. 不配置 ⇒ 不注入任何环境变量，CLI 用自己的默认配置/登录态；
 *  2. 配了某项 ⇒ 只用该项对应的环境变量覆盖，其余仍走默认。
 */
export const cliRuntimeConfigApi = {
  list: () => api<CliRuntimeConfig[]>('/subagents/runtime-configs'),
  save: (runtime: string, req: SaveCliRuntimeConfigRequest) =>
    api<{ ok: boolean; runtime: string; secrets: Record<string, string>; apiKeySet: boolean }>(
      `/subagents/runtime-configs/${encodeURIComponent(runtime)}`,
      { method: 'PUT', body: req },
    ),
  /** 跑一次 `--version` 取版本号 */
  version: (runtime: string) =>
    api<CliRuntimeVersion>(`/subagents/runtime-configs/${encodeURIComponent(runtime)}/version`),
  /** 跑一次模型子命令取实时列表（拉不到回落内置） */
  models: (runtime: string) =>
    api<CliRuntimeModels>(`/subagents/runtime-configs/${encodeURIComponent(runtime)}/models`),
  /** ⚠️ 真的发一次极小提示词（消耗一次模型调用） */
  test: (runtime: string) =>
    api<CliRuntimeTestResult>(`/subagents/runtime-configs/${encodeURIComponent(runtime)}/test`, {
      method: 'POST',
    }),
}
