// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PermissionBubble } from '@/components/center/PermissionBubble'
import { buildSuggestionOptions, formatSuggestionLabel } from '@/components/center/permissionSuggestionLabels'
import type { PermissionRequestItem } from '@/stores/chatStore'
import type { PermissionUpdate, PermissionUpdateDestination, PermissionUpdateRulesWire } from '@/api/types'

/**
 * [批 A3] 权限弹窗「一键授权」档位。
 *
 * <b>WHY 这组断言必须存在</b>：
 *   1. <b>措辞族由 destination 决定</b>（不是由工具决定）。session 档只活到会话结束、
 *      <b>不落盘</b>；若把它写成「不再询问」这种长期承诺，就是<b>骗用户</b>。这条规则
 *      没有编译期约束 —— 判据是「文案里有没有会话限定词」，只能靠断言钉住。
 *   2. <b>suggestions 为空不得渲染第三档</b>。空数组若被当成「有建议」渲染出一个空按钮，
 *      用户点下去会发出空 updatedPermissions —— 既无规则也无反馈。
 *   3. <b>点第三档必须原样回传该条建议</b>（不做结构转换）。转换 = 引入第二份实现。
 *
 * 环境写法照 markdown/__tests__/jsonBlock.test.tsx（jsdom + react-dom/client + act）。
 */

// ---- 后端真实产出的 suggestion 形态（file:line 见各用例注释） ----

/** Bash 复合命令聚合建议（BashCommandOperatorPermissions.java:265 → AddRules/LOCAL_SETTINGS） */
const bashPrefix = (prefix: string, destination: PermissionUpdateDestination = 'localSettings'): PermissionUpdate => ({
  type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: `${prefix}:*` }], behavior: 'allow', destination,
})
/** Read 目录建议（PermissionUpdates.createReadRuleSuggestion：绝对路径 → `//<abs>/**`） */
const readDir = (absDir: string, destination: PermissionUpdateDestination = 'session'): PermissionUpdate => ({
  type: 'addRules', rules: [{ toolName: 'Read', ruleContent: `/${absDir}/**` }], behavior: 'allow', destination,
})
/** 文件写建议（PermissionUpdates.generateSuggestions write/create 分支） */
const setModeAcceptEdits = (destination: PermissionUpdateDestination = 'session'): PermissionUpdate => ({
  type: 'setMode', mode: 'acceptEdits', destination,
})
const addDirs = (dirs: string[], destination: PermissionUpdateDestination = 'session'): PermissionUpdate => ({
  type: 'addDirectories', directories: dirs, destination,
})
/** MCP 整工具建议（McpServerTool.java:224 → 裸 toolName） */
const mcpWholeTool = (name: string): PermissionUpdate => ({
  type: 'addRules', rules: [{ toolName: name }], behavior: 'allow', destination: 'localSettings',
})

describe('[批 A3] 建议档位文案 · 措辞族由 destination 决定', () => {
  // 落盘三档（后端 PermissionUpdatePersister.java:112-114 判 true）
  const PERSISTING: PermissionUpdateDestination[] = ['userSettings', 'projectSettings', 'localSettings']
  // 不落盘两档（同处判 false）
  const IN_MEMORY: PermissionUpdateDestination[] = ['session', 'cliArg']

  // 覆盖全部 6 型 × 全部 destination 的组合矩阵
  const allUpdates: PermissionUpdate[] = [
    bashPrefix('npm run'),
    { type: 'addRules', rules: [{ toolName: 'Bash', ruleContent: 'ls -la' }], behavior: 'allow', destination: 'localSettings' },
    { type: 'addRules', rules: [{ toolName: 'PowerShell', ruleContent: 'Get-ChildItem' }], behavior: 'allow', destination: 'localSettings' },
    readDir('/home/u/proj'),
    { type: 'addRules', rules: [{ toolName: 'Edit', ruleContent: '/.claude/**' }], behavior: 'allow', destination: 'session' },
    { type: 'addRules', rules: [{ toolName: 'WebFetch', ruleContent: 'domain:example.com' }], behavior: 'allow', destination: 'localSettings' },
    { type: 'addRules', rules: [{ toolName: 'Skill', ruleContent: 'deploy' }], behavior: 'allow', destination: 'localSettings' },
    mcpWholeTool('mcp__fs__read'),
    setModeAcceptEdits(),
    addDirs(['/home/u/other']),
    { type: 'removeDirectories', directories: ['/home/u/other'], destination: 'session' },
    { type: 'replaceRules', rules: [{ toolName: 'Bash' }], behavior: 'allow', destination: 'localSettings' },
    { type: 'removeRules', rules: [{ toolName: 'Bash' }], behavior: 'allow', destination: 'localSettings' },
    // ⭐ 实测抓到的真实形态：Bash 默认 passthrough 推 cliArg + userSettings 两条**裸工具名**规则
    { type: 'addRules', rules: [{ toolName: 'Bash' }], behavior: 'allow', destination: 'cliArg' },
    { type: 'addRules', rules: [{ toolName: 'Bash' }], behavior: 'allow', destination: 'userSettings' },
  ]

  it('不落盘档（session / cliArg）的「授予」文案必带会话限定词「本次会话」', () => {
    // 只对「授予」型（addRules/addDirectories/setMode）要求限定词：
    //   撤销型（removeDirectories/removeRules）不承诺任何东西，无所谓限定词。
    const grants = allUpdates.filter(
      (u) => IN_MEMORY.includes(u.destination) && (u.type === 'addRules' || u.type === 'addDirectories' || u.type === 'setMode'),
    )
    expect(grants.length).toBeGreaterThan(0)
    for (const u of grants) {
      expect(formatSuggestionLabel(u, 'Bash'), `不落盘档缺「本次会话」: ${JSON.stringify(u)}`).toContain('本次会话')
    }
  })

  it('落盘档文案不得声称「本次会话」（否则用户会以为只活本次会话）', () => {
    const persisting = allUpdates.filter((u) => PERSISTING.includes(u.destination))
    expect(persisting.length).toBeGreaterThan(0)
    for (const u of persisting) {
      expect(formatSuggestionLabel(u, 'Bash'), `落盘档误称「本次会话」: ${JSON.stringify(u)}`).not.toContain('本次会话')
    }
  })

  it('⛔ cliArg 与 session 同类：都不得出现「不再询问」这种长期承诺', () => {
    // 这条是实测抓到的真缺陷：只按 session 判会把 cliArg 写成「不再询问」，
    //   而 PermissionUpdatePersister 对 cliArg 同样不落盘 ⇒ 骗用户。
    for (const destination of IN_MEMORY) {
      const label = formatSuggestionLabel({ type: 'addRules', rules: [{ toolName: 'Bash' }], behavior: 'allow', destination }, 'Bash')
      expect(label, `不落盘档不该承诺长期生效: ${destination}`).toContain('本次会话')
    }
  })

  it('同一 suggestion 换 destination ⇒ 文案必须换族（落盘 / 不落盘三态）', () => {
    const persisted = formatSuggestionLabel(bashPrefix('npm run', 'localSettings'), 'Bash')
    const session = formatSuggestionLabel(bashPrefix('npm run', 'session'), 'Bash')
    expect(persisted).not.toContain('本次会话')
    expect(session).toContain('本次会话')
    expect(session).not.toBe(persisted)

    // 实测形态：同一条裸工具名规则，cliArg 与 userSettings 文案必须不同
    const bare: Omit<PermissionUpdateRulesWire, 'destination'> = {
      type: 'addRules', rules: [{ toolName: 'Bash' }], behavior: 'allow',
    }
    const cli = formatSuggestionLabel({ ...bare, destination: 'cliArg' }, 'Bash')
    const user = formatSuggestionLabel({ ...bare, destination: 'userSettings' }, 'Bash')
    expect(cli).toContain('本次会话')
    expect(user).not.toContain('本次会话')
    expect(cli).not.toBe(user)
  })
})

describe('[批 A4c P3] 建议档位文案 · 作用域必须说清楚', () => {
  /** 不落盘两档（同 PermissionUpdatePersister.java:112-114 判 false） */
  const IN_MEMORY_DESTINATIONS: PermissionUpdateDestination[] = ['session', 'cliArg']
  const bare = (destination: PermissionUpdateDestination): PermissionUpdate => ({
    type: 'addRules', rules: [{ toolName: 'Bash' }], behavior: 'allow', destination,
  })

  it('用户级（userSettings）⇒ 明说「所有项目」（不得只说「不再询问」让用户以为只影响本项目）', () => {
    const label = formatSuggestionLabel(bare('userSettings'), 'Bash', 'nexusai')
    expect(label).toContain('所有项目')
    // 用户级不是「本项目」，也不带项目标识 —— 否则用户会以为只放行了这个项目
    expect(label).not.toContain('本项目（')
    expect(label).not.toContain('本次会话')
  })

  it('项目级（projectSettings / localSettings）⇒ 带项目标识', () => {
    for (const destination of ['projectSettings', 'localSettings'] as PermissionUpdateDestination[]) {
      const label = formatSuggestionLabel(bare(destination), 'Bash', 'nexusai')
      expect(label, `项目级缺项目标识: ${destination}`).toContain('本项目（nexusai）')
      expect(label, `项目级不得声称所有项目: ${destination}`).not.toContain('所有项目')
      expect(label, `项目级不得声称本次会话: ${destination}`).not.toContain('本次会话')
    }
  })

  it('项目标识取不到 ⇒ 退化为「在本项目中」，不编造项目名也不静默降级成「本次会话」', () => {
    for (const missing of [undefined, null, '']) {
      const label = formatSuggestionLabel(bare('localSettings'), 'Bash', missing)
      expect(label).toContain('在本项目中')
      expect(label).not.toContain('本次会话')
      expect(label).not.toContain('所有项目')
    }
  })

  it('会话级（session / cliArg）⇒ 仍是「本次会话内」，且不带任何项目作用域承诺', () => {
    for (const destination of IN_MEMORY_DESTINATIONS) {
      const label = formatSuggestionLabel(bare(destination), 'Bash', 'nexusai')
      expect(label, `会话级措辞被改坏: ${destination}`).toContain('本次会话内')
      expect(label).not.toContain('所有项目')
      expect(label).not.toContain('本项目')
    }
  })

  it('三档 scope 短语互不相同（同一句话在三处含义不同，文案必须能区分）', () => {
    const user = formatSuggestionLabel(bare('userSettings'), 'Bash', 'nexusai')
    const project = formatSuggestionLabel(bare('localSettings'), 'Bash', 'nexusai')
    const session = formatSuggestionLabel(bare('session'), 'Bash', 'nexusai')
    expect(new Set([user, project, session]).size).toBe(3)
  })

  it('作用域也进 addDirectories / setMode 档（不止 addRules）', () => {
    expect(formatSuggestionLabel(addDirs(['/home/u/other'], 'userSettings'), 'Edit', 'nexusai')).toContain('所有项目')
    expect(formatSuggestionLabel(addDirs(['/home/u/other'], 'localSettings'), 'Edit', 'nexusai')).toContain('本项目（nexusai）')
    expect(formatSuggestionLabel(setModeAcceptEdits('userSettings'), 'Edit', 'nexusai')).toContain('所有项目')
    expect(formatSuggestionLabel(setModeAcceptEdits('localSettings'), 'Edit', 'nexusai')).toContain('本项目（nexusai）')
  })
})

describe('[批 A3] 建议档位文案 · 分工具', () => {
  it('Bash 前缀规则 `npm run:*` ⇒ 显示前缀而非 `:*` 原文，且不再询问', () => {
    const label = formatSuggestionLabel(bashPrefix('npm run'), 'Bash')
    expect(label).toContain('npm run')
    expect(label).not.toContain(':*')
    expect(label).toContain('不再询问')
  })

  it('Bash 多规则聚合进一条 addRules ⇒ 一个档位聚合展示', () => {
    const update: PermissionUpdate = {
      type: 'addRules',
      rules: [{ toolName: 'Bash', ruleContent: 'npm run:*' }, { toolName: 'Bash', ruleContent: 'git status' }],
      behavior: 'allow', destination: 'localSettings',
    }
    const label = formatSuggestionLabel(update, 'Bash')
    expect(label).toContain('npm run')
    expect(label).toContain('git status')
  })

  it('命令列表过长 ⇒ 折叠为「多个命令」', () => {
    const long = Array.from({ length: 5 }, (_, i) => `averylongcommandname-${i}`)
    const update: PermissionUpdate = {
      type: 'addRules',
      rules: long.map((c) => ({ toolName: 'Bash', ruleContent: c })),
      behavior: 'allow', destination: 'localSettings',
    }
    expect(formatSuggestionLabel(update, 'Bash')).toContain('多个命令')
  })

  it('PowerShell 同走命令语义', () => {
    const update: PermissionUpdate = {
      type: 'addRules', rules: [{ toolName: 'PowerShell', ruleContent: 'Get-ChildItem' }], behavior: 'allow', destination: 'localSettings',
    }
    expect(formatSuggestionLabel(update, 'PowerShell')).toContain('Get-ChildItem')
  })

  it('Read 目录规则 `//<abs>/**` ⇒ 显示目录末段，去掉根锚点重复斜杠', () => {
    const label = formatSuggestionLabel(readDir('/home/u/proj'), 'Read')
    expect(label).toContain('proj/')
    expect(label).not.toContain('//home')
    expect(label).toContain('本次会话')
  })

  it('setMode acceptEdits ⇒ 「接受所有编辑」', () => {
    expect(formatSuggestionLabel(setModeAcceptEdits(), 'Edit')).toContain('接受所有编辑')
  })

  it('addDirectories ⇒ 显示目录末段', () => {
    expect(formatSuggestionLabel(addDirs(['/home/u/other']), 'Edit')).toContain('other/')
  })

  it('Edit `.claude/` 规则 ⇒ 配置目录特例文案', () => {
    const update: PermissionUpdate = {
      type: 'addRules', rules: [{ toolName: 'Edit', ruleContent: '/.claude/**' }], behavior: 'allow', destination: 'session',
    }
    expect(formatSuggestionLabel(update, 'Edit')).toContain('.claude/')
  })

  it('MCP 整工具（无 ruleContent）⇒ 不再询问该工具', () => {
    expect(formatSuggestionLabel(mcpWholeTool('mcp__fs__read'), 'mcp__fs__read')).toContain('mcp__fs__read')
  })

  it('Skill ⇒ 不再询问该技能', () => {
    const update: PermissionUpdate = {
      type: 'addRules', rules: [{ toolName: 'Skill', ruleContent: 'deploy' }], behavior: 'allow', destination: 'localSettings',
    }
    expect(formatSuggestionLabel(update, 'Skill')).toContain('deploy')
  })

  it('WebFetch domain 规则 ⇒ 显示主机名（后端暂无该建议，先钉住形状）', () => {
    const update: PermissionUpdate = {
      type: 'addRules', rules: [{ toolName: 'WebFetch', ruleContent: 'domain:example.com' }], behavior: 'allow', destination: 'localSettings',
    }
    expect(formatSuggestionLabel(update, 'WebFetch')).toContain('example.com')
  })

  it('未识别形态不静默：记 warn 并退回按规则原文渲染', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    // 混合形态：Read 规则 + 别的东西 ⇒ 走不到任何具名分支
    const update: PermissionUpdate = {
      type: 'addRules',
      rules: [{ toolName: 'Read', ruleContent: '/a/**' }, { toolName: 'WeirdTool', ruleContent: 'x' }],
      behavior: 'allow', destination: 'localSettings',
    }
    const label = formatSuggestionLabel(update, 'Read')
    expect(warn).toHaveBeenCalled()
    expect(label).toContain('WeirdTool')
    warn.mockRestore()
  })
})

describe('[批 A3] buildSuggestionOptions', () => {
  it('空 / null / undefined ⇒ 无档位', () => {
    expect(buildSuggestionOptions(null, 'Bash')).toEqual([])
    expect(buildSuggestionOptions(undefined, 'Bash')).toEqual([])
    expect(buildSuggestionOptions([], 'Bash')).toEqual([])
  })

  it('逐条成档，且 update 原样保留（不转换结构）', () => {
    const updates = [addDirs(['/home/u/other']), setModeAcceptEdits()]
    const opts = buildSuggestionOptions(updates, 'Edit')
    expect(opts).toHaveLength(2)
    expect(opts[0].update).toBe(updates[0])
    expect(opts[1].update).toBe(updates[1])
  })
})

// ---- 组件层 ----

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => root.unmount())
  container.remove()
})

function render(el: React.ReactElement) {
  act(() => root.render(el))
}

function req(over: Partial<PermissionRequestItem> = {}): PermissionRequestItem {
  return { kind: 'message', sessionId: 's1', requestId: 'r1', toolName: 'Bash', ...over }
}

function buttons(): HTMLButtonElement[] {
  return Array.from(container.querySelectorAll('button'))
}

describe('[批 A3] PermissionBubble 第三档渲染', () => {
  it('有 suggestions ⇒ 渲染第三档，文案与工具匹配，且在既有三档之外', () => {
    const onDecision = vi.fn()
    render(<PermissionBubble request={req({ suggestions: [bashPrefix('npm run')] })} onDecision={onDecision} />)

    const tier3 = container.querySelectorAll('.pb-suggestions button')
    expect(tier3).toHaveLength(1)
    expect(tier3[0].textContent).toContain('npm run')
    // 既有三档仍在（允许/拒绝 + 中止）
    expect(buttons().map((b) => b.textContent)).toEqual(expect.arrayContaining(['允许', '拒绝']))
  })

  it('suggestions 为空 / 缺省 ⇒ 第三档不出现（回归：既有两档不受影响）', () => {
    render(<PermissionBubble request={req({ suggestions: [] })} onDecision={vi.fn()} />)
    expect(container.querySelectorAll('.pb-suggestions button')).toHaveLength(0)
    render(<PermissionBubble request={req()} onDecision={vi.fn()} />)
    expect(container.querySelectorAll('.pb-suggestions button')).toHaveLength(0)
  })

  it('点第三档 ⇒ 只回传那一条建议（原样对象，同引用）', () => {
    const onDecision = vi.fn()
    const suggestion = bashPrefix('npm run')
    render(<PermissionBubble request={req({ suggestions: [suggestion] })} onDecision={onDecision} />)

    act(() => { (container.querySelector('.pb-suggestions button') as HTMLButtonElement).click() })

    expect(onDecision).toHaveBeenCalledTimes(1)
    const [id, decision, answers, annotations, updates] = onDecision.mock.calls[0]
    expect(id).toBe('r1')
    expect(decision).toBe('allow')
    expect(answers).toBeUndefined()
    expect(annotations).toBeUndefined()
    expect(updates).toHaveLength(1)
    expect(updates[0]).toBe(suggestion)
  })

  it('多条建议 ⇒ 逐条成档，点第 N 档只回传第 N 条', () => {
    const onDecision = vi.fn()
    const a = setModeAcceptEdits()
    const b = addDirs(['/home/u/other'])
    render(<PermissionBubble request={req({ toolName: 'Edit', suggestions: [a, b] })} onDecision={onDecision} />)

    const tier3 = container.querySelectorAll('.pb-suggestions button')
    expect(tier3).toHaveLength(2)
    act(() => { (tier3[1] as HTMLButtonElement).click() })
    expect(onDecision.mock.calls[0][4]).toEqual([b])
  })

  it('回归：「允许」仍只回传 2 参（不发任何权限更新 ⇒ 不产生规则）', () => {
    const onDecision = vi.fn()
    render(<PermissionBubble request={req({ suggestions: [bashPrefix('npm run')] })} onDecision={onDecision} />)

    const allow = buttons().find((b) => b.textContent === '允许')!
    act(() => allow.click())
    expect(onDecision).toHaveBeenCalledWith('r1', 'allow')
  })

  it('回归：「拒绝」仍只回传 2 参', () => {
    const onDecision = vi.fn()
    render(<PermissionBubble request={req({ suggestions: [bashPrefix('npm run')] })} onDecision={onDecision} />)

    const deny = buttons().find((b) => b.textContent === '拒绝')!
    act(() => deny.click())
    expect(onDecision).toHaveBeenCalledWith('r1', 'deny')
  })

  it('AskUser 提问弹窗不渲染第三档（答案走 answers，不是规则），且留 warn 不静默', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const onDecision = vi.fn()
    render(<PermissionBubble
      request={req({
        toolName: 'AskUserQuestion',
        toolInput: { questions: [{ question: 'q?', header: 'h', options: [{ label: 'a', description: 'd' }] }] },
        suggestions: [bashPrefix('npm run')],
      })}
      onDecision={onDecision}
    />)
    expect(container.querySelectorAll('.pb-suggestions button')).toHaveLength(0)
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })
})
