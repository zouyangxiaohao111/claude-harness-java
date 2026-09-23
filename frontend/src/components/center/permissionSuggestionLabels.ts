/**
 * 权限弹窗「一键授权」档位的文案生成。
 *
 * 输入 = 后端随权限请求推来的 `suggestions`（`PermissionUpdate[]`，线格式见
 * `api/types.ts`）；输出 = 该条建议对应的按钮文案。
 *
 * ## 两条不可违反的规则
 *
 * 1. **措辞族由 `destination` 决定**（不是由工具决定）：只有会写进设置文件的 destination
 *    才可以说「不再询问」；**不落盘**的 destination 必须说「本次会话」。
 *    判据来源 = 后端 `PermissionUpdatePersister.java:112-114`（唯一真源）：
 *    ```
 *    case USER_SETTINGS, PROJECT_SETTINGS, LOCAL_SETTINGS -> true;  // 落盘
 *    case CLI_ARG, SESSION -> false;                                // 只在内存里生效
 *    ```
 *    ⚠️ **`cliArg` 与 `session` 一样不落盘** —— 只按 session 判会把 cliArg 写成「不再询问」，
 *    同样是骗用户（实测踩过：Bash 默认 passthrough 推的正是 cliArg + userSettings 两条）。
 *
 * 2. **只承诺规则真正授予的东西**。文案里的目录/工具/命令一律**从规则内容自身**取，
 *    不从会话 cwd 推断 —— 因为 `Bash(<prefix>:*)` / `Skill(<name>)` / 裸工具名这类规则
 *    **本身不带目录条件**，写成「仅限 <cwd>」会过度承诺。
 *
 * 3. **[批 A4c P3] 作用域必须说清楚**：同一句「不再询问」，落在用户级配置
 *    （`~/.nexusai/settings.json`）是**所有项目**生效，落在项目级
 *    （`<项目>/.nexusai/settings.local.json`）只对本项目生效。用户看不出区别就只能猜。
 *    单点 = {@link scopePhrase}；项目标识由调用方传入（见 `PermissionBubble.projectLabel`）。
 */

import { PERMISSION_MODE_LABELS, type PermissionUpdate, type PermissionUpdateRulesWire, type PermissionRuleValueWire } from '@/api/types'

/** 走「命令」语义的工具（规则内容 = 命令前缀 / 命令原文） */
const SHELL_TOOLS = new Set(['Bash', 'PowerShell'])
/** 走「文件路径」语义的工具（规则内容 = 路径 glob） */
const FILE_TOOLS = new Set(['Edit', 'Write', 'Read', 'Glob', 'Grep', 'NotebookEdit'])
/** Skill 规则前缀形态（`/^(.+):\*$/`） */
const RULE_PREFIX_RE = /^(.+):\*$/

/**
 * 配置目录标记 → 展示名（编辑类规则的特例）。
 *
 * <p>`/.claude/**` 一类规则（含 `~/.claude/**`）落到「配置目录」文案；本仓自有根
 * `.nexusai` 同理 —— 后端「编辑自有设置（本会话）」档产出的 ruleContent 形如
 * `~/.nexusai/**` / `/.nexusai/**`（后端 `PermissionUpdates.selfConfigRootRuleSuggestion`），
 * 不登记就会掉进「未识别形态」留痕 + 按规则原文渲染。
 *
 * <p>⚠️ 标记必须带尾斜杠：`Edit(~/.nexusai/**)` 命中 `.nexusai/`；不带尾斜杠会让
 * `<某个叫 .nexusai2 的目录>` 这类规则误判成配置目录。
 */
const CONFIG_DIR_LABELS: ReadonlyArray<readonly [string, string]> = [
  ['.claude/', '.claude/'],
  ['.nexusai/', '.nexusai/'],
]

/** 会**写进设置文件**的 destination · 单一真源 = 后端 `PermissionUpdatePersister.java:112-114`
 *  （`case USER_SETTINGS, PROJECT_SETTINGS, LOCAL_SETTINGS -> true; case CLI_ARG, SESSION -> false;`）。
 *  ⚠️ `cliArg` 与 `session` 都不落盘 —— 漏掉 cliArg 会把它写成「不再询问」（实测踩过）。 */
const PERSISTING_DESTINATIONS = new Set<PermissionUpdate['destination']>([
  'userSettings', 'projectSettings', 'localSettings',
])

/**
 * 作用域短语 —— 「这条授权在**哪里**生效」。
 *
 * <p>为什么必须写进文案：`destination` 决定规则落到哪个设置文件，而同一句「不再询问」在
 * **用户级**（`~/.nexusai/settings.json`）与**项目级**（`<项目>/.nexusai/settings.local.json`）
 * 下含义完全不同 —— 前者对**所有项目**生效，后者只对本项目生效。用户看不出区别就只能靠猜，
 * 猜错的代价是「以为只放行了这个项目的命令，其实全局放行了」。
 *
 * <p>对齐 CC `shellPermissionHelpers.tsx:151`：`Yes, and don't ask again for <cmd> commands in
 * <getOriginalCwd()>` —— 把**项目路径**写进档位文案，而不是只写「不再询问」。
 */
function scopePhrase(destination: PermissionUpdate['destination'], projectLabel?: string | null): string {
  switch (destination) {
    case 'userSettings':
      return '在所有项目中'
    case 'projectSettings':
    case 'localSettings':
      return projectLabel ? `在本项目（${projectLabel}）中` : '在本项目中'
    default:
      if (PERSISTING_DESTINATIONS.has(destination)) {
        // 后端新增了落盘 destination 却没登记作用域文案 ⇒ 会静默降级成「本次会话内」= 骗用户。留痕。
        console.warn('[perm] 落盘 destination 未登记作用域文案，按会话级渲染，请补 scopePhrase', { destination })
      }
      // session / cliArg —— 不落盘 ⇒ 只活到本次会话结束
      return '本次会话内'
  }
}

/** 取路径末段（posix/win 分隔符都认；全分隔符 → 回退原串） */
function baseName(p: string): string {
  const trimmed = p.replace(/[\\/]+$/, '')
  if (!trimmed) return p
  const i = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
  return i >= 0 ? trimmed.slice(i + 1) : trimmed
}

/** 目录展示：末段 + `/`（规则里的路径是 posix 归一形态） */
function dirLabel(p: string): string {
  return `${baseName(p)}/`
}

/** 路径列表拼接（1 / 2 / 3+ 三态） */
function formatPathList(paths: string[]): string {
  const names = paths.map(dirLabel)
  if (names.length === 0) return ''
  if (names.length === 1) return names[0]
  if (names.length === 2) return `${names[0]} 和 ${names[1]}`
  return `${names[0]}、${names[1]} 等 ${names.length} 个目录`
}

/** 命令列表拼接（1 / 2 / 3+ 三态） */
function formatCommandList(commands: string[]): string {
  if (commands.length === 0) return ''
  if (commands.length === 1) return commands[0]
  if (commands.length === 2) return `${commands[0]} 和 ${commands[1]}`
  return `${commands.slice(0, -1).join('、')} 和 ${commands[commands.length - 1]}`
}

/** 命令列表过长 → 折叠（对齐 CC 的 50 字符阈值） */
function formatCommandListTruncated(commands: string[]): string {
  return commands.join(', ').length > 50 ? '多个命令' : formatCommandList(commands)
}

/** 规则内容 → 展示用命令（`npm run:*` → `npm run`；无 `:*` 后缀 → 原文） */
function ruleContentToCommand(ruleContent: string): string {
  const m = RULE_PREFIX_RE.exec(ruleContent)
  return m ? m[1] : ruleContent
}

/** 规则内容（路径 glob）→ 展示用目录（去掉 `/**` 尾部与根锚点重复的 `/`） */
function ruleContentToDir(ruleContent: string): string {
  const stripped = ruleContent.endsWith('/**') ? ruleContent.slice(0, -3) : ruleContent
  // 绝对路径在 createReadRuleSuggestion 里前置了一个 `/` 形成 `//abs/path/**` → 展示时去掉根锚点
  return stripped.startsWith('//') ? stripped.slice(1) : stripped
}

/** 规则列表 → `工具名(内容)` 展示（无 ruleContent = 整工具放行） */
function formatRules(rules: PermissionRuleValueWire[]): string {
  return rules
    .map((r) => (r.ruleContent ? `${r.toolName}(${r.ruleContent})` : r.toolName))
    .join('、')
}

/** 兜底文案：只描述规则本身，不做任何额外承诺 */
function fallbackLabel(update: PermissionUpdate): string {
  switch (update.type) {
    case 'setMode': {
      const mode = PERMISSION_MODE_LABELS[update.mode] ?? update.mode
      return `切换到${mode}模式`
    }
    case 'addDirectories':
      return `允许访问 ${formatPathList(update.directories ?? [])}`
    case 'removeDirectories':
      return `撤销对 ${formatPathList(update.directories ?? [])} 的访问授权`
    case 'removeRules':
      return `撤销规则：${formatRules(update.rules)}`
    case 'replaceRules':
      return `替换规则：${formatRules(update.rules)}`
    case 'addRules':
      return `允许：${formatRules(update.rules)}`
  }
}

/** addRules 型建议的文案（rules 已确定为规则数组，无需再做类型收窄） */
function addRulesLabel(update: PermissionUpdateRulesWire, toolName: string, scope: string): string {
  /** 作用域由 {@link scopePhrase} 单点决定（用户级 = 所有项目 / 项目级 = 本项目 / 会话级 = 本次会话内） */
  const askAgain = (what: string) => `允许，且${scope}不再询问 ${what}`
  const rules = update.rules

  // 命令类：规则内容 = 命令前缀 / 命令原文
  if (SHELL_TOOLS.has(toolName)) {
    const commands = [...new Set(
      rules
        .filter((r) => SHELL_TOOLS.has(r.toolName) && r.ruleContent)
        .map((r) => ruleContentToCommand(r.ruleContent!)),
    )]
    if (commands.length > 0) return askAgain(`${formatCommandListTruncated(commands)} 命令`)
  }

  // 文件读取类：规则内容 = 路径 glob（全部规则都是 Read 才走此文案）
  const readDirs = rules
    .filter((r) => r.toolName === 'Read' && r.ruleContent)
    .map((r) => ruleContentToDir(r.ruleContent!))
  if (readDirs.length > 0 && readDirs.length === rules.length) {
    return `允许，且${scope}可读取 ${formatPathList(readDirs)}`
  }

  // 配置文件目录（`.claude/` 与本仓自有根 `.nexusai/`）：编辑类规则的特例
  for (const [marker, label] of CONFIG_DIR_LABELS) {
    if (rules.every((r) => FILE_TOOLS.has(r.toolName) && r.ruleContent?.includes(marker))) {
      return `允许，且${scope}编辑配置目录 ${label}`
    }
  }

  // WebFetch：规则内容 = `domain:<host>`
  const domains = rules
    .filter((r) => r.ruleContent?.startsWith('domain:'))
    .map((r) => r.ruleContent!.slice('domain:'.length))
  if (domains.length > 0 && domains.length === rules.length) {
    return askAgain(domains.join('、'))
  }

  // Skill：规则内容 = 技能名
  if (rules.every((r) => r.toolName === 'Skill' && r.ruleContent)) {
    const skills = [...new Set(rules.map((r) => ruleContentToCommand(r.ruleContent!)))]
    return askAgain(`技能 ${skills.join('、')}`)
  }

  // 整工具放行（MCP / 未登记工具）——规则不带内容 = 对该工具全局放行
  if (rules.every((r) => !r.ruleContent)) {
    const names = [...new Set(rules.map((r) => r.toolName))]
    return askAgain(names.join('、'))
  }

  // 走到这里 = 规则形态本仓尚未见过（后端新增了建议形态？）—— 不静默：留痕后按规则原文渲染
  console.warn('[perm] 未识别的建议规则形态，退回按规则原文渲染', { toolName, update })
  return fallbackLabel(update)
}

/**
 * 单条 suggestion → 按钮文案。
 *
 * @param update 后端推来的权限更新（原样，不转换）
 * @param toolName 本次请求的工具名（后端 `Tool.name()`）· 用于分工具选措辞
 * @param projectLabel 当前会话绑定项目的展示名（cwd 的等价物）· 项目级档位的项目标识；
 *                     缺省/取不到时退化为「在本项目中」（不猜、不编造项目名）
 */
export function formatSuggestionLabel(
  update: PermissionUpdate,
  toolName: string,
  projectLabel?: string | null,
): string {
  // ⭐ 作用域短语单点：项目级带项目标识 / 用户级明说「所有项目」/ 会话级「本次会话内」。
  //   判据 = 后端 PermissionUpdatePersister.java:112-114（cliArg / session 都不落盘）。
  const scope = scopePhrase(update.destination, projectLabel)

  switch (update.type) {
    case 'addRules':
      return addRulesLabel(update, toolName, scope)
    case 'addDirectories': {
      const dirs = update.directories ?? []
      if (dirs.length === 0) return fallbackLabel(update)
      return `允许，且${scope}可访问 ${formatPathList(dirs)}`
    }
    case 'setMode':
      // acceptEdits = 「接受所有编辑」（文件类工具的主模式建议）
      if (update.mode === 'acceptEdits') {
        return `允许，且${scope}接受所有编辑`
      }
      return fallbackLabel(update)
    case 'removeDirectories':
    case 'replaceRules':
    case 'removeRules':
      return fallbackLabel(update)
  }
}

/**
 * 把 suggestions 逐条转成可渲染档位。
 *
 * 每条 suggestion 一个独立档位：点第 N 档**只授予第 N 条建议**，文案与该条规则一一对应
 * （后端会把多条规则打包进同一条 addRules —— 那种情况仍是一个档位，文案聚合展示）。
 *
 * @param projectLabel 当前会话绑定项目的展示名（见 {@link formatSuggestionLabel}）
 */
export function buildSuggestionOptions(
  suggestions: PermissionUpdate[] | null | undefined,
  toolName: string,
  projectLabel?: string | null,
): { label: string; update: PermissionUpdate }[] {
  if (!suggestions || suggestions.length === 0) return []
  return suggestions.map((update) => ({ label: formatSuggestionLabel(update, toolName, projectLabel), update }))
}
