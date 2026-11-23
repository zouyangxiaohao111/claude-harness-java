/**
 * 输入框「应用选择」的数据面（P5 · 前端）。
 *
 * <p><b>最终口径（2026-11-23 终稿）</b>：
 * 应用层**只含 MCP**；浏览器（BR）/ 电脑操作（CU）已回「工具」面板各自开关，⛔ 不走应用指定。
 * 数据源优先 = 后端 `GET /api/v1/apps`；未就绪时回落 MCP server 列表（`kind:'mcp'`，
 * key = server name，后端按 `mcp__<name>__*` 前缀裁剪工具）。AppPicker 内再兜一层
 * `kind === 'mcp'` 过滤（后端后续也会移除静态条目）。
 *
 * <p><b>两个动作，两处落点</b>：
 * <ul>
 *   <li><b>启动/停止</b>（行内独立开关）→ 会话列 `sessions.enabled_apps`（V337），
 *       照 coordinatorMode 用 `PATCH /sessions/{id}` 保存；**启动才进工具 schema**。</li>
 *   <li><b>本轮强调</b>（点行/星标语义）→ 仅随聊天请求体带 `selectedApps`（per-turn），
 *       只影响给模型的强调通知文案，⛔ 不改 schema、不落会话列。</li>
 * </ul>
 * localStorage 只存「强调集合」的 UI 记忆（按 sessionId 隔离），⛔ 不是真相源。
 */
import { api } from './rest'
import { mcpApi } from './mcp'
import type { McpRuntimeStatus, McpServer } from './types'

/**
 * 应用类别。
 *
 * <p>当前**只有 `mcp` 走应用层**；`browser` / `computer-use` 已回工具面板开关
 * （保留枚举是为了兼容后端目录形状，AppPicker 会把这两类过滤掉）。
 */
export type AppKind = 'mcp' | 'browser' | 'computer-use'

/** 类别的中文徽章（面板行用） */
export const APP_KIND_LABEL: Record<AppKind, string> = {
  mcp: 'MCP',
  browser: '浏览器',
  'computer-use': '桌面',
}

/** 面板行数据（应用条目 · 当前只有 MCP 会渲染；kind 保留以兼容后端形状） */
export interface AppEntry {
  /** 选择键：MCP = server name（工具前缀 `mcp__<key>__*`） */
  key: string
  /** 类别（决定工具过滤判据） */
  kind: AppKind
  /** 人看的名字 */
  name: string
  description?: string
  /** 该应用名下的工具数（未知则不显示，⛔ 不臆造 0） */
  toolCount?: number
  /** 该应用名下的工具原始名（如 `mcp__codegraph__explore`）· 面板可展开查看；缺省 = 不可展开 */
  tools?: string[]
  /** 应用图标 URL（后端给；缺省时静态应用按 kind 用内置图标，MCP 回落到首字母） */
  icon?: string
  /** 后端给：该应用当前可被「启动」（未连接/已禁用也可能可启动；缺省 = 不可启动） */
  canStart?: boolean
  /** 后端 MCP server id（启动接口用它；缺省 = 无法启动） */
  serverId?: string
  runtimeStatus?: McpRuntimeStatus
  /** 预留：连接器授权类型（MCP server 恒缺省） */
  authType?: string
  /** false = 置灰但**不隐藏**（对齐「入口常驻」，用状态表达而不是用有无表达） */
  usable: boolean
  /** `usable=false` 时给用户看的原因（⛔ 不静默置灰；由后端给，前端不自造） */
  unusableReason?: string
  /**
   * 缺省（会话 `enabledApps === null`）时是否算「已启动」（后端字段 `defaultOpen`）。
   *
   * <p>MCP 回落口径 = 可用即可；后端给了就以它为准（⛔ 前端不猜 feature flag）。
   */
  defaultOpen: boolean
}

/**
 * 后端应用目录条目（`GET /api/v1/apps` · P5 后端契约）。
 *
 * <p>凡是「前端不知道就不能编」的字段都允许后端缺省：`usable` / `defaultOpen` 缺省时
 * 按保守值处理（usable 缺省视为可用；defaultOpen 缺省 = usable），⛔ 不塞假数据。
 */
export interface AppDto {
  key: string
  kind: AppKind
  name: string
  description?: string | null
  toolCount?: number | null
  /** 工具原始名清单（后端新字段；面板展开用） */
  tools?: string[] | null
  /** 应用图标（后端给；缺省 = 前端按 kind 用内置图标 / 首字母） */
  icon?: string | null
  /** 后端给：可被「启动」（未连接 / 已禁用时也能启动） */
  canStart?: boolean | null
  /** 后端 MCP server id（启动接口入参） */
  serverId?: string | null
  /** 缺省开启（最终口径）；`defaultEnabled` 为上一版字段名，仅作兼容回落 */
  defaultOpen?: boolean | null
  defaultEnabled?: boolean | null
  usable?: boolean | null
  /** 不可用原因（如「功能未开启」「未连接」），后端给什么显示什么 */
  disabledReason?: string | null
  runtimeStatus?: McpRuntimeStatus | null
  authType?: string | null
}

/** 仅在**接口缺失**（MCP 回落）时的兜底排序权重 · 正常路径按后端返回顺序渲染，⛔ 前端不重排 */
const KIND_RANK: Record<AppKind, number> = { 'computer-use': 0, browser: 1, mcp: 2 }

/** 不可用原因（只描述「现在为什么用不了」，不猜平台配置） */
function unusableReasonOf(s: McpServer): string | undefined {
  const rt = s.runtimeStatus
  if (rt === 'connected') return undefined
  if (rt === 'disabled' || s.enabled === false) return '应用已禁用'
  if (rt === 'needsAuth') return '需要先授权'
  if (rt === 'failed') return '连接失败'
  if (rt === 'pending') return '等待审批'
  // runtimeStatus 缺省（旧后端）→ 回落 status；运行中才算可用
  if (rt == null && s.status === 'running') return undefined
  return '未连接（启动后可用）'
}

/** McpServer → AppEntry（后端应用目录未就绪时的回落数据源） */
export function toAppEntry(s: McpServer): AppEntry {
  const reason = unusableReasonOf(s)
  const usable = reason == null
  return {
    key: s.name,
    kind: 'mcp',
    name: (s.userFacingName && s.userFacingName.trim()) || s.name,
    ...(s.runtimeStatus ? { runtimeStatus: s.runtimeStatus } : {}),
    usable,
    ...(reason ? { unusableReason: reason } : {}),
    // 会话 enabledApps 未设置时，可用的 MCP 服务默认算「已启动」（与旧行为等价）
    defaultOpen: usable,
  }
}

/** AppDto → AppEntry（后端应用目录 · 形状兼容静态条目，面板侧会过滤掉非 MCP） */
export function fromAppDto(d: AppDto): AppEntry {
  const usable = d.usable !== false
  const reason = usable ? undefined : (d.disabledReason ?? '当前不可用')
  return {
    key: d.key,
    kind: d.kind,
    name: (d.name && d.name.trim()) || d.key,
    ...(d.description ? { description: d.description } : {}),
    ...(typeof d.toolCount === 'number' ? { toolCount: d.toolCount } : {}),
    ...(Array.isArray(d.tools) && d.tools.length > 0 ? { tools: d.tools } : {}),
    ...(d.icon ? { icon: d.icon } : {}),
    ...(d.canStart ? { canStart: true } : {}),
    ...(d.serverId ? { serverId: d.serverId } : {}),
    ...(d.runtimeStatus ? { runtimeStatus: d.runtimeStatus } : {}),
    ...(d.authType ? { authType: d.authType } : {}),
    usable,
    ...(reason ? { unusableReason: reason } : {}),
    // 后端没给 => 跟随可用性（不可用的自然不算默认开）；`defaultEnabled` 是上一版字段名，仅兼容回落
    defaultOpen: d.defaultOpen ?? d.defaultEnabled ?? usable,
  }
}

/** 仅在**接口缺失**（MCP 回落）时使用的兜底排序：桌面 → 浏览器 → MCP，再按名字 */
function sortFallbackEntries(out: AppEntry[]): AppEntry[] {
  return out.sort((a, b) => {
    if (a.kind !== b.kind) return KIND_RANK[a.kind] - KIND_RANK[b.kind]
    return a.name.localeCompare(b.name, 'zh-Hans-CN')
  })
}

/**
 * 目录规范化（后端形状）：只去重（同 key 只留第一条），**保持后端返回顺序**。
 *
 * <p>⛔ 前端不重排：顺序是**后端的约定**（若后端仍返回静态条目，其顺序也照搬；面板只展示 MCP），
 * 前端重排会让两边口径漂移。
 */
export function normalizeAppEntries(list: AppDto[]): AppEntry[] {
  const seen = new Set<string>()
  const out: AppEntry[] = []
  for (const d of list) {
    if (!d?.key || seen.has(d.key)) continue
    seen.add(d.key)
    out.push(fromAppDto(d))
  }
  return out
}

/** 目录规范化（MCP 回落形状 · 仅在接口缺失时用，按 kind 兜底排序） */
export function normalizeMcpEntries(list: McpServer[]): AppEntry[] {
  const seen = new Set<string>()
  const out: AppEntry[] = []
  for (const s of list) {
    if (!s?.name || seen.has(s.name)) continue
    seen.add(s.name)
    out.push(toAppEntry(s))
  }
  return sortFallbackEntries(out)
}

/**
 * 拉应用目录（惰性：面板首次打开 / 有记忆选择时才调，⛔ 不在每次渲染调）。
 *
 * <p>数据源优先 = 后端 `GET /api/v1/apps`（**顺序由后端给**，前端不重排）；后端未就绪时回落到 MCP server 列表
 * （只给真实条目，⛔ 不伪造静态应用）。
 */
export async function fetchAppEntries(): Promise<AppEntry[]> {
  try {
    // 后端形状：数组 或 { apps: [...] } 都接受（契约落地时二者取其一即可）
    const res = await api<AppDto[] | { apps?: AppDto[] }>('/apps')
    const list = Array.isArray(res) ? res : res?.apps
    if (Array.isArray(list)) return normalizeAppEntries(list)
  } catch {
    // TODO(app-layer): 后端 GET /api/v1/apps 未就绪期间的兼容回落。
    //   只回落真实 MCP 列表 —— 浏览器 / 桌面的 usable 与 defaultOpen 只能由后端给，
    //   前端⛔ 不自造「功能未开启」之类的假条目（否则会出现点了没用的开关）。
  }
  return normalizeMcpEntries(await mcpApi.list())
}

/**
 * 启动一个应用对应的后端 MCP server（仅当后端给了 `canStart` + `serverId` 才可点）。
 *
 * <p>失败原样抛出（调用方展示真实原因，⛔ 不假成功）；成功后调用方应重拉目录刷新 usable。
 */
export async function startApp(serverId: string, sessionId?: string | null): Promise<void> {
  await mcpApi.start(serverId, sessionId ?? undefined)
}

// ---- 强调集合的 UI 记忆（localStorage · ⛔ 不是真相源 · 按 sessionId 隔离） ----

/**
 * 记忆键（v3 · **按会话隔离**）· 只作「刷新后恢复本轮强调」的初始 UI。
 *
 * <p>真相源 = 当前内存态（发送时随消息带 `selectedApps`）。⛔ 任何「界面显示以存储为准」
 * 的读法都会造成两套真源；这里只帮用户省一次手，且不同会话互不串味。
 */
export function appSelectionStorageKey(sessionId: string): string {
  return `nexusai.composer.selectedApps.v3:${sessionId}`
}

/** 读该会话上次的强调集合（null = 没有记忆 / 损坏 / 存储不可用 / 无会话） */
export function loadRememberedApps(sessionId: string | null | undefined): string[] | null {
  if (!sessionId) return null
  try {
    if (typeof localStorage === 'undefined') return null
    const raw = localStorage.getItem(appSelectionStorageKey(sessionId))
    if (raw == null) return null
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return null
    return [...new Set(parsed.filter((x): x is string => typeof x === 'string' && x.trim() !== ''))]
  } catch {
    return null
  }
}

/** 记住该会话的强调集合（失败静默：记忆是便利，不是功能前置） */
export function saveRememberedApps(sessionId: string | null | undefined, keys: string[]): void {
  if (!sessionId) return
  try {
    if (typeof localStorage === 'undefined') return
    localStorage.setItem(appSelectionStorageKey(sessionId), JSON.stringify(keys))
  } catch {
    /* 隐私模式 / 配额满：不影响本轮选择 */
  }
}

/** 清除该会话的记忆（强调集合清空时用） */
export function forgetRememberedApps(sessionId: string | null | undefined): void {
  if (!sessionId) return
  try {
    if (typeof localStorage === 'undefined') return
    localStorage.removeItem(appSelectionStorageKey(sessionId))
  } catch {
    /* 同上：静默 */
  }
}

/**
 * 多选切换（不可变；返回**完整新数组** —— 调用方按整表替换，⛔ 不要增量语义）。
 */
export function toggleAppKey(keys: string[], key: string): string[] {
  return keys.includes(key) ? keys.filter((k) => k !== key) : [...keys, key]
}
