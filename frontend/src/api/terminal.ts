import { api } from './rest'

/**
 * 集成终端 REST 客户端（后端 `TerminalController` · `/api/v1/sessions/{sessionId}/terminals`）。
 *
 * <p>写入（input / resize）与生命周期（create / close）走 REST，输出走 STOMP
 * `/topic/sessions/{sessionId}/terminal/{terminalId}`（见 {@link TerminalOutputEvent}）。
 */
export interface TerminalInfo {
  /** 终端 ID（`term-xxxxxxxx`） */
  id: string
  /** 实际启动的 shell（白名单内可执行文件名，如 `powershell.exe`） */
  shell: string
  /** 启动工作目录（会话项目目录；解析失败回落用户主目录） */
  cwd: string
  cols: number
  rows: number
  /** false = 进程已退出（缓冲仍可读） */
  alive: boolean
  /** 启动时刻（ISO-8601） */
  startedAt: string
}

/** STOMP 输出帧：数据帧 `{terminalId, data, closed:false}`；退出帧 `{terminalId, closed:true, exitCode}`。 */
export interface TerminalOutputEvent {
  terminalId: string
  data?: string | null
  closed?: boolean
  exitCode?: number | null
}

export interface CreateTerminalRequest {
  shell?: string
  cols?: number
  rows?: number
}

/** 本机可用 shell：`value` = 后端白名单原值（可直接回传 create），`label` = 友好名 */
export interface TerminalShellOption {
  value: string
  label: string
}

/** `GET /terminals/shells` 响应；`default` = 当前探测的默认 shell */
export interface TerminalShells {
  shells: TerminalShellOption[]
  default: string
}

const base = (sessionId: string) => `/sessions/${encodeURIComponent(sessionId)}/terminals`
const one = (sessionId: string, terminalId: string) => `${base(sessionId)}/${encodeURIComponent(terminalId)}`

export const terminalApi = {
  list: (sessionId: string) => api<TerminalInfo[]>(base(sessionId)),
  /** 新建终端（body 全字段可缺省 ⇒ 后端默认 shell + 80x24） */
  create: (sessionId: string, req?: CreateTerminalRequest) =>
    api<TerminalInfo>(base(sessionId), { method: 'POST', body: req ?? {} }),
  input: (sessionId: string, terminalId: string, data: string) =>
    api<void>(`${one(sessionId, terminalId)}/input`, { method: 'POST', body: { data } }),
  resize: (sessionId: string, terminalId: string, cols: number, rows: number) =>
    api<void>(`${one(sessionId, terminalId)}/resize`, { method: 'POST', body: { cols, rows } }),
  close: (sessionId: string, terminalId: string) =>
    api<void>(one(sessionId, terminalId), { method: 'DELETE' }),
  /** 最近 N 行（后端已剥离 ANSI 转义）· 首次 attach 回放用 */
  snapshot: (sessionId: string, terminalId: string, lines = 200) =>
    api<{ lines: string }>(`${one(sessionId, terminalId)}/snapshot?lines=${lines}`),
  /** 本机可用 shell（基础设置「默认终端」下拉）· 只读、无副作用 */
  shells: () => api<TerminalShells>('/terminals/shells'),
}
