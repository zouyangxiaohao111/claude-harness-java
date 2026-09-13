import { api } from './rest'

const CMD_BASE = 'http://localhost:3458/api/command'

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
  executeBuiltin: (name: string, sessionId: string, req?: unknown) =>
    api<unknown>(
      `/builtins/${encodeURIComponent(name)}/execute?sessionId=${encodeURIComponent(sessionId)}`,
      { method: 'POST', body: req ?? undefined }, CMD_BASE),
}
