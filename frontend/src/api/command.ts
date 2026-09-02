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
  list: (reload = false) => api<CommandDto[]>(reload ? '?reload=true' : '', {}, CMD_BASE),
  /** 单个命令详情 · GET /api/command/{id} */
  get: (id: string) => api<CommandDto>(`/${encodeURIComponent(id)}`, {}, CMD_BASE),
  builtins: () => api<BuiltInCommandDto[]>('/builtins', {}, CMD_BASE),
  executeBuiltin: (name: string, req?: unknown) =>
    api<unknown>(`/builtins/${encodeURIComponent(name)}/execute`, { method: 'POST', body: req ?? undefined }, CMD_BASE),
}
