/**
 * MCP 命令行快速解析 · 纯函数（无副作用）
 *
 * 后端 POST /api/v1/mcp 只接收结构化 CreateMcpRequest（无 commandLine 字段、不做命令行解析），
 * 故把 CC `claude mcp add` 一行命令 → 表单字段的解析放在前端。
 * 支持两种形态：
 *   形态 A：claude mcp add <name> [flags] [--] <command|url> [args...]（前缀 claude mcp add / add 可省略）
 *   形态 B：纯命令 npx -y @modelcontextprotocol/server-filesystem /tmp（name 由包名推导）
 */

type Transport = 'stdio' | 'sse' | 'http'
type Scope = 'local' | 'user' | 'project'

/** 解析结果可直接 setForm 到 MCPPanel 的 McpFormState（OAuth 残留字段由调用方清空） */
export type ParsedForm = {
  name: string
  type: Transport
  scope: Scope
  command: string
  argsStr: string
  envStr: string
  url: string
  headersJson: string
}

export type ParseResult =
  | { ok: true; form: ParsedForm }
  | { ok: false; error: string }

const isTransport = (v: string | undefined): v is Transport =>
  v === 'stdio' || v === 'sse' || v === 'http'
const isScope = (v: string | undefined): v is Scope =>
  v === 'local' || v === 'user' || v === 'project'

/**
 * shell 风格分词：正确处理空格分隔、单引号（内部空格不拆）、双引号（内部空格不拆 + `\"`/`\\` 转义）、
 * 反斜杠转义（`\ ` 为字面空格）、连续空格。纯函数。
 */
export function tokenizeShell(line: string): string[] {
  const tokens: string[] = []
  let cur = ''
  let inSingle = false
  let inDouble = false
  let active = false // 当前 token 是否有内容（含空引号 ''）
  let i = 0
  while (i < line.length) {
    const ch = line[i]
    if (inSingle) {
      if (ch === "'") inSingle = false
      else cur += ch
      active = true
      i++
      continue
    }
    if (inDouble) {
      if (ch === '"') inDouble = false
      else if (ch === '\\' && i + 1 < line.length && (line[i + 1] === '"' || line[i + 1] === '\\')) {
        cur += line[i + 1]
        i += 2
        continue
      } else cur += ch
      active = true
      i++
      continue
    }
    if (ch === '\\' && i + 1 < line.length) {
      cur += line[i + 1]
      active = true
      i += 2
      continue
    }
    if (ch === "'") { inSingle = true; active = true; i++; continue }
    if (ch === '"') { inDouble = true; active = true; i++; continue }
    if (ch === ' ' || ch === '\t' || ch === '\r' || ch === '\n') {
      if (active) { tokens.push(cur); cur = ''; active = false }
      i++
      continue
    }
    cur += ch
    active = true
    i++
  }
  if (active) tokens.push(cur)
  return tokens
}

/* ---- deriveMcpName 内部辅助 ---- */

// 绝对/相对路径开头（Windows 盘符、/、./、../、~/）
const PATH_START_RE = /^(?:[A-Za-z]:[\\/]|\/|\.\/|\.\.\/|~\/)/
// 常见文件扩展名（带扩展名视为文件而非包名）
const FILE_EXT_RE = /\.(?:js|json|ts|tsx|jsx|py|pyc|exe|bat|cmd|sh|bash|zsh|ps1|jar|war|dll|so|dylib|node|map|css|html|htm|png|jpg|jpeg|gif|svg|ico|toml|yaml|yml|lock|md|txt|zip|tar|gz|tgz|log|db|sqlite|db3|pem|key|crt|pub)$/i
// 协议 URL（https:// 等）
const URL_RE = /^[a-z][a-z0-9+.-]*:\/\//i

/** 是否为形如 @scope/pkg / pkg-name / 含 `.` 的包名 token（排除 flag、路径、带扩展名文件、URL） */
function isPackageNameToken(tok: string): boolean {
  if (!tok || tok.startsWith('-')) return false
  if (tok.includes('\\') || PATH_START_RE.test(tok)) return false // 路径（含 Windows 反斜杠分隔）
  if (URL_RE.test(tok)) return false
  if (FILE_EXT_RE.test(tok)) return false
  return tok.includes('/') || tok.includes('-') || tok.includes('.')
}

/** 包名 → 服务器短名：@scope/xxx → xxx；再剥 mcp-server- / server- 前缀；剥 @version 后缀 */
function shortName(pkg: string): string {
  let base = pkg.slice(pkg.lastIndexOf('/') + 1)
  base = base.replace(/@[^@]+$/, '') // pkg@1.2.3 → pkg
  const stripped = base.replace(/^(?:mcp-server|server)-/, '')
  return stripped || base || pkg
}

/**
 * 从命令/参数推导服务器名（取包名短名）。推导不出返回 null（UI toast 让用户手动填 name）。
 * - 在 args 里找形如 @scope/pkg / pkg-name / 含 `.` 的包名 token（跳过以 - 开头的 flag、路径、文件等）
 * - args 中无包名时回退看 command 本身是否像包名
 */
export function deriveMcpName(command: string, args: string[]): string | null {
  for (const a of args) {
    if (isPackageNameToken(a)) return shortName(a)
  }
  if (isPackageNameToken(command)) return shortName(command)
  return null
}

/* ---- parseMcpCommandLine 内部辅助 ---- */

/** 收集 --header 的 "K: V" 或 K=V 到 headers 对象；无合法分隔符的 token 丢弃 */
function addHeader(headers: Record<string, string>, raw: string): void {
  const colon = raw.indexOf(':')
  const eq = raw.indexOf('=')
  const sep = colon >= 0 && (eq < 0 || colon < eq) ? colon : eq
  if (sep <= 0) return
  const key = raw.slice(0, sep).trim()
  const value = raw.slice(sep + 1).trim()
  if (key) headers[key] = value
}

/**
 * 主入口：一行命令 → ParsedForm 或错误信息。
 * 形态 A（claude mcp add ...）：解析 name + flags（--transport/--scope/-s/--env/--header/--）+ command|url。
 * 形态 B（纯命令）：command=首 token，argsStr=其余，type=stdio、scope=project，name=deriveMcpName。
 */
export function parseMcpCommandLine(line: string): ParseResult {
  const trimmed = line.trim()
  if (!trimmed) return { ok: false, error: '请先粘贴一行命令' }
  const tokens = tokenizeShell(trimmed)
  if (tokens.length === 0) return { ok: false, error: '请先粘贴一行命令' }

  // 形态 A 前缀消费：`claude mcp add` 完整前缀，或省略后的 `add <name> ...`
  let isFormA = false
  let i = 0
  if (tokens[0] === 'claude' && tokens[1] === 'mcp' && tokens[2] === 'add') {
    isFormA = true
    i = 3
  } else if (tokens[0] === 'add' && tokens.length >= 2) {
    isFormA = true
    i = 1
  }

  if (!isFormA) {
    // 形态 B：纯命令，无 name
    const command = tokens[0]
    const args = tokens.slice(1)
    const name = deriveMcpName(command, args)
    if (!name) {
      return { ok: false, error: '无法推导服务器名，请补充 name（如：claude mcp add <name> ...）' }
    }
    return {
      ok: true,
      form: { name, type: 'stdio', scope: 'project', command, argsStr: args.join(' '), envStr: '', url: '', headersJson: '' },
    }
  }

  const rest = tokens.slice(i)
  if (rest.length === 0) return { ok: false, error: '缺少启动命令' }

  let type: Transport = 'stdio'
  let scope: Scope = 'project'
  const envLines: string[] = []
  const headers: Record<string, string> = {}
  const positionals: string[] = [] // [name, command|url, ...args]
  let cmdFound = false // 第二个位置参数（command/url）已落位后，未知 -flag 一律视为参数

  let j = 0
  while (j < rest.length) {
    const t = rest[j]

    // `--` 分隔符：其后全部原样作为 command/url + args（不再解析 flag）
    if (t === '--') {
      positionals.push(...rest.slice(j + 1))
      break
    }

    // 内联值 flag：--transport=value / --scope=value / --env=K=V / --header=...
    const mTransport = /^--transport=(.+)$/.exec(t)
    if (mTransport) {
      if (isTransport(mTransport[1])) type = mTransport[1]
      j++
      continue
    }
    const mScope = /^--scope=(.+)$/.exec(t)
    if (mScope) {
      if (isScope(mScope[1])) scope = mScope[1]
      j++
      continue
    }
    const mEnv = /^--env=(.+)$/.exec(t)
    if (mEnv) { envLines.push(mEnv[1]); j++; continue }
    const mHeader = /^--header=(.+)$/.exec(t)
    if (mHeader) { addHeader(headers, mHeader[1]); j++; continue }

    // 需取值的 flag（值缺失则跳过该 flag）
    if (t === '--transport' || t === '--scope' || t === '-s' || t === '--env' || t === '--header') {
      const val = rest[j + 1]
      if (val !== undefined) {
        if (t === '--transport' && isTransport(val)) type = val
        else if ((t === '--scope' || t === '-s') && isScope(val)) scope = val
        else if (t === '--env') envLines.push(val)
        else if (t === '--header') addHeader(headers, val)
      }
      j += 2
      continue
    }

    // 未知 flag：command/url 未落位前保守跳过（连同紧跟的非 flag 值）；已落位后视为命令参数
    if (t.startsWith('-')) {
      if (cmdFound) {
        positionals.push(t)
        j++
      } else {
        j++
        if (j < rest.length && !rest[j].startsWith('-') && rest[j] !== '--') j++
      }
      continue
    }

    // 位置参数
    positionals.push(t)
    if (positionals.length >= 2) cmdFound = true
    j++
  }

  if (positionals.length === 0) {
    return { ok: false, error: type === 'stdio' ? '缺少启动命令' : '远程 server 需要 URL' }
  }
  const name = positionals[0]
  const cmdOrUrl = positionals[1] ?? ''

  if (type === 'stdio') {
    if (!cmdOrUrl) return { ok: false, error: '缺少启动命令' }
    return {
      ok: true,
      form: {
        name,
        type,
        scope,
        command: cmdOrUrl,
        argsStr: positionals.slice(2).join(' '),
        envStr: envLines.join('\n'),
        url: '',
        headersJson: '',
      },
    }
  }

  // sse / http 远程：url 必须存在且不能是 flag
  if (!cmdOrUrl || cmdOrUrl.startsWith('--')) {
    return { ok: false, error: '远程 server 需要 URL' }
  }
  return {
    ok: true,
    form: { name, type, scope, command: '', argsStr: '', envStr: '', url: cmdOrUrl, headersJson: JSON.stringify(headers) },
  }
}
