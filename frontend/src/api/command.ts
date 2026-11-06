import { api } from './rest'
import { API_BASE } from './base'

const CMD_BASE = `${API_BASE}/command`

export interface CommandDto {
  id: string
  name: string
  description?: string | null
  enabled: boolean
  /** 来源（BUILTIN/BUNDLED/USER/PLUGIN/MCP…）· 后端 CommandSource 枚举 */
  source?: string | null
  /** 所属插件名（如 zjkycode）· 前端 /插件名 提示其下技能 */
  pluginName?: string | null
  /** 命令类型（prompt/local/local-jsx）· 后端 Command.type（M23 起透出）；缺省时前端降级来源徽标 */
  type?: 'prompt' | 'local' | 'local-jsx' | string | null
  /** 命令 kind（后端 CommandBase.kind）· 无 type 字段时的降级信息源 */
  kind?: string | null
  aliases?: string[] | null
  argumentHint?: string | null
  builtin?: boolean
  isHidden?: boolean
  whenToUse?: string | null
  userInvocable?: boolean
}
/** 对齐后端 BuiltInCommandDto 7 字段：type 区分渲染/触发（local=本地命令 / local-jsx=面板 / prompt=提示注入） */
export interface BuiltInCommandDto {
  name: string
  type: 'local' | 'local-jsx' | 'prompt'
  description?: string | null
  aliases?: string[] | null
  argumentHint?: string | null
  isHidden?: boolean
  source: 'BUILTIN'
}

/**
 * prompt 型内置命令的执行结果（后端 `PromptExecuteResult`）。
 *
 * <p>`/init` 这类 `type=prompt` 命令的价值全在提示词正文 —— 后端把正文回传，
 * 前端须把它作为一条 user 消息发出（对齐 CC 把 `b.prompt` 注入本轮对话）。
 */
export interface PromptExecuteResult {
  /** 恒 'prompt'（前端据此分流：注入对话，而非只弹 toast） */
  type: 'prompt'
  name: string
  content: string
}

/**
 * 类型守卫：后端 `executeBuiltin` 的响应是否为 prompt 型执行结果。
 *
 * <p>通用端点的响应类型是 `Object`（resume → ResumeAgentResult、prompt → PromptExecuteResult、
 * 其余 → BuiltInCommandDto 元数据）⇒ 前端必须按 `type` 字段分流，否则会把元数据当正文发出去。
 */
export function isPromptExecuteResult(v: unknown): v is PromptExecuteResult {
  return !!v && typeof v === 'object'
    && (v as PromptExecuteResult).type === 'prompt'
    && typeof (v as PromptExecuteResult).content === 'string'
    && (v as PromptExecuteResult).content.length > 0
}

export const commandApi = {
  /**
   * 命令列表 · `sessionId` 可选（[TL-W1 P4] 后端已加 `?sessionId=` 查询参数）。
   *
   * <p>带 sessionId → 后端把会话注入 RequestContext，SkillRegistry 的 cwdSupplier 能在 REST 线程
   * 解析出**会话绑定项目** ⇒ 列表含该项目的 project 级技能 + workflow 命令；不带 → 后端无会话上下文
   * （旧行为：绑定项目的命令在前端列表里消失）。与 /skills 同源同语义。
   */
  list: (reload = false, sessionId?: string) => {
    const parts: string[] = []
    if (reload) parts.push('reload=true')
    if (sessionId) parts.push(`sessionId=${encodeURIComponent(sessionId)}`)
    return api<CommandDto[]>(parts.length ? `?${parts.join('&')}` : '', {}, CMD_BASE)
  },
  /** 单个命令详情 · GET /api/command/{id} */
  get: (id: string) => api<CommandDto>(`/${encodeURIComponent(id)}`, {}, CMD_BASE),
  builtins: () => api<BuiltInCommandDto[]>('/builtins', {}, CMD_BASE),
  // sessionId：**必传**（批 3a）。后端口已把 ?sessionId= 改成必填（缺 / 空白 ⇒ 400，不再回落 MDC）——
  //   旧实现「缺值 → 后端读 RequestContext.sessionId()」，而 MDC 第三态会读到上一请求残留的**别的
  //   会话** id ⇒ /clear 等会话级命令的副作用作用到别的会话上。TS 侧同步收紧为 required，让编译器
  //   在每个调用点强制把关（而非运行期才发现 400）。
  //
  // [2026-11-02 修 · /compact 报 JSON 解析异常] Accept 必须放宽。
  //   `POST /builtins/compact/execute` 在后端有两个入口，返回类型**不同**：
  //     · 通用入口（本函数默认走的 `/builtins/{name}/execute`）→ 返回 Command 元数据（JSON）；
  //     · `/compact` 的**字面端点** → `produces="text/plain;charset=UTF-8"`，返回纯文本。
  //   Spring 里字面路由优先，所以 /compact 实际命中纯文本端点 ⇒ `Accept: application/json`
  //   协商失败 ⇒ 406（错误体是 HTML）⇒ 前端 JSON.parse 抛 `Unexpected token 'W'`。
  //   ⇒ 这里统一发 `*/*`：不会收紧任何响应类型，两种入口都能正常接收。
  executeBuiltin: (name: string, sessionId: string, req?: unknown) =>
    api<unknown>(
      `/builtins/${encodeURIComponent(name)}/execute?sessionId=${encodeURIComponent(sessionId)}`,
      { method: 'POST', body: req ?? undefined, accept: '*/*' }, CMD_BASE),
  // [goal V253] /goal 独立端点 · 通用端点的请求体是 ResumeExecuteRequest（无 args 字段）
  //   ⇒ args 会被 Jackson 静默丢弃 ⇒ 条件文本丢失。故走字面端点（与 /compact 同款做法）。
  //   ⚠️ Accept 必须 '*/*'：该端点 produces=text/plain（字面路由优先于 {name} 路径变量），
  //   发 application/json 会 406（错误体是 HTML）⇒ JSON.parse 抛 'Unexpected token'。
  executeGoal: (sessionId: string, args?: string) =>
    api<string>(
      `/builtins/goal/execute?sessionId=${encodeURIComponent(sessionId)}`,
      { method: 'POST', body: args ? { args } : undefined, accept: '*/*' }, CMD_BASE),
}
