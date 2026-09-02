import { useEffect, useMemo, useRef, useState } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
import { commandApi, type BuiltInCommandDto, type CommandDto } from '@/api/command'
import { ApiError } from '@/api/rest'

/* ------------------------------------------------------------------ */
/*  命令目录 · 静态维护                                                */
/*  对齐后端 BuiltInCommands 的 10 内置命令（DEC-9 web 子集）          */
/*  + CC COMMANDS 补充的 agents / color，执行统一走 executeBuiltin     */
/* ------------------------------------------------------------------ */

export interface CommandItem {
  name: string
  description: string
  aliases?: string[]
  /** 右侧快捷提示，缺省显示 /name */
  hint?: string
  /** 后端 builtins 类型（local/local-jsx/prompt · 渲染徽标 + 触发区分） */
  type?: BuiltInCommandDto['type']
  /** 所属插件名（如 zjkycode）· skillCommands 带插件信息时展示 */
  pluginName?: string
  /** CommandDto source（BUILTIN/BUNDLED/PLUGIN/USER/MCP…）· 无 type 时徽标降级为来源 */
  source?: string | null
}

export const COMMAND_ITEMS: CommandItem[] = [
  { name: 'clear', description: '清空会话历史，释放上下文', aliases: ['reset', 'new'] },
  { name: 'compact', description: '保留一份摘要，压缩当前会话' },
  { name: 'config', description: '打开配置面板', aliases: ['settings'] },
  { name: 'help', description: '查看帮助与可用命令' },
  { name: 'init', description: '生成 CLAUDE.md 初始上下文' },
  { name: 'memory', description: '编辑记忆文件' },
  { name: 'model', description: '切换当前会话的模型' },
  { name: 'output-style', description: '调整输出风格（已弃用，改用 /config）' },
  { name: 'resume', description: '恢复之前的对话', aliases: ['continue'] },
  { name: 'session', description: '查看远程会话链接与二维码', aliases: ['remote'] },
  { name: 'agents', description: '管理后台运行的智能体' },
  { name: 'color', description: '切换主题配色' },
  { name: 'chrome', description: '连接 NexusAI in Chrome 浏览器扩展', type: 'local-jsx', hint: '/chrome' },
]

/** name / 别名命中即视为已注册命令（slash 解析时直接执行，无需弹面板） */
export function isKnownCommand(name: string): boolean {
  const n = name.toLowerCase()
  return COMMAND_ITEMS.some((c) => c.name === n || (c.aliases ?? []).includes(n))
}

/** FNT-SUB-03：/resume 执行响应（后端 ResumeAgentResult：agentId/description/outputFile） */
interface ResumeResult { agentId?: string; description?: string; outputFile?: string }

type Feedback = { kind: 'success' | 'error'; text: string }

/* ------------------------------------------------------------------ */
/*  每命令一个 lucide 风格描边图标（stroke 1.5，与 icons.tsx 一致）     */
/* ------------------------------------------------------------------ */

const ICON_PATHS: Record<string, ReactNode> = {
  clear: (
    <>
      <path d="M3 4.5h8" />
      <path d="M5.5 4.5v-1a1 1 0 0 1 1-1h1a1 1 0 0 1 1 1v1" />
      <path d="M4.5 4.5l.6 6.7a1 1 0 0 0 1 .9h1.8a1 1 0 0 0 1-.9l.6-6.7" />
    </>
  ),
  compact: <path d="M3 4l4 4-4 4M11 4l-4 4 4 4" />,
  config: <path d="M2 4h7M11 4h1M2 8.5h3M7 8.5h5M2 13h6M10 13h2" />,
  help: (
    <>
      <circle cx="7" cy="7" r="5" />
      <path d="M7 9.2V7.5" />
      <path d="M7 5.4h.01" />
    </>
  ),
  init: (
    <path d="M7 2l1.2 3.2L11.5 6.5l-3.3 1.3L7 11l-1.2-3.2-3.3-1.3 3.3-1.3zM11.5 9.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z" />
  ),
  memory: (
    <>
      <ellipse cx="7" cy="4.5" rx="5" ry="2" />
      <path d="M2 4.5v5c0 1.1 2.2 2 5 2s5-.9 5-2v-5" />
      <path d="M2 9.5v5c0 1.1 2.2 2 5 2s5-.9 5-2v-5" />
    </>
  ),
  model: (
    <>
      <rect x="3.5" y="3.5" width="7" height="7" rx="1" />
      <path d="M7 2v1.5M7 10.5V12M2 7h1.5M10.5 7H12" />
    </>
  ),
  'output-style': (
    <>
      <rect x="2.5" y="3.5" width="9" height="7" rx="1" />
      <path d="M2.5 7h9M5.5 3.5v7" />
    </>
  ),
  resume: <path d="M4 3.5l6.5 3.5L4 10.5z" />,
  session: (
    <>
      <circle cx="7" cy="7" r="5" />
      <path d="M2 7h10M7 2c-2.2 2.2-2.2 7.8 0 10M7 2c2.2 2.2 2.2 7.8 0 10" />
    </>
  ),
  agents: (
    <>
      <circle cx="4.5" cy="4.5" r="2" />
      <path d="M1 9.5c0-1.5 1.6-2.5 3.5-2.5s3.5 1 3.5 2.5" />
      <circle cx="10.5" cy="5" r="1.5" />
      <path d="M8 9.3c.2-1 1.2-1.8 2.5-1.8 1.3 0 2.5.8 2.5 1.8" />
    </>
  ),
  color: <path d="M7 1.5C5 4 3 5.6 3 8.4a4 4 0 0 0 8 0c0-2.8-2-4.4-4-6.9z" />,
  chrome: (
    <>
      <rect x="1.5" y="3" width="11" height="8.5" rx="1.2" />
      <path d="M1.5 5.5h11" />
      <circle cx="7" cy="8.8" r="1.5" />
    </>
  ),
}

/** 未注册图标的命令（后端 skills / 新 bundled 命令）兜底：终端风格通用图标 */
const FALLBACK_ICON = (
  <>
    <path d="M1.5 2.5l11 4.5-11 4.5z" />
    <path d="M8.5 11.5h4" />
  </>
)

/** CommandDto source → 中文徽标（无 type 时降级显示来源，不臆造 prompt/local） */
const SOURCE_LABELS: Record<string, string> = {
  BUILTIN: '内置',
  BUNDLED: '捆绑',
  PLUGIN: '插件',
  USER: '用户',
  MCP: 'MCP',
  PROJECT_SETTINGS: '项目',
  LOCAL_SETTINGS: '本地',
  FLAG_SETTINGS: '标志',
  POLICY_SETTINGS: '策略',
}
function badgeFallback(item: CommandItem): string {
  if (item.pluginName) return `插件·${item.pluginName}`
  return item.source ? (SOURCE_LABELS[item.source] ?? '命令') : '命令'
}

/** Command.type → 中文徽标（对齐 CC types/command.ts:26 联合：prompt/local/local-jsx） */
const TYPE_LABELS: Record<string, string> = { prompt: '提示注入', local: '本地', 'local-jsx': '面板' }
function typeLabel(item: CommandItem): string | undefined {
  if (!item.type) return undefined
  return TYPE_LABELS[item.type] ?? item.type
}

function RowIcon({ name }: { name: string }) {
  return (
    <svg width={14} height={14} viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth={1.5}>
      {ICON_PATHS[name] ?? FALLBACK_ICON}
    </svg>
  )
}

/* ------------------------------------------------------------------ */
/*  组件 · Raycast 风格命令面板（自包含样式，贴合项目 backdrop 风格）   */
/* ------------------------------------------------------------------ */

interface CommandPaletteProps {
  onClose: () => void
  onExecute: (name: string) => void
}


export function CommandPalette({ onClose, onExecute }: CommandPaletteProps) {
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  // 后端 builtins（7 字段 · type 区分）：挂载拉取，失败静默回落本地 COMMAND_ITEMS
  const [builtins, setBuiltins] = useState<BuiltInCommandDto[]>([])
  useEffect(() => {
    let alive = true
    commandApi.builtins().then((b) => { if (alive) setBuiltins(b) }).catch(() => {})
    return () => { alive = false }
  }, [])
  // 后端技能命令（GET /api/command · 含 skills，如 /zjkycode）：合并进面板
  const [skillCommands, setSkillCommands] = useState<CommandDto[]>([])
  useEffect(() => {
    let alive = true
    commandApi.list().then((cs) => { if (alive) setSkillCommands(cs) }).catch(() => {})
    return () => { alive = false }
  }, [])
  // 合并命令目录：后端 builtins 优先（带 type），技能命令其次，本地 COMMAND_ITEMS 补充
  const items = useMemo<CommandItem[]>(() => {
    const map = new Map<string, CommandItem>()
    for (const b of builtins) {
      map.set(b.name, { name: b.name, type: b.type, description: b.description ?? '', aliases: b.aliases ?? undefined, hint: b.argumentHint ?? undefined })
    }
    for (const s of skillCommands) {
      if (!map.has(s.name)) map.set(s.name, {
        name: s.name,
        description: s.description ?? '',
        aliases: s.aliases ?? undefined,
        type: (s.type as BuiltInCommandDto['type'] | undefined) || undefined,
        pluginName: s.pluginName ?? undefined,
        source: s.source ?? null,
      })
    }
    for (const c of COMMAND_ITEMS) {
      if (!map.has(c.name)) map.set(c.name, { ...c })
    }
    return [...map.values()]
  }, [builtins, skillCommands])
  // FNT-SUB-03/05：/resume /color 就地反馈（fail loud），其余命令委托 onExecute
  const [feedback, setFeedback] = useState<Feedback | null>(null)

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return items
    return items.filter(
      (c) =>
        c.name.toLowerCase().includes(q) ||
        c.description.toLowerCase().includes(q) ||
        (c.aliases ?? []).some((a) => a.toLowerCase().includes(q)),
    )
  }, [query])

  // 查询变化时选中回到首项；挂载后自动聚焦搜索框
  useEffect(() => { setActive(0) }, [query])
  useEffect(() => { inputRef.current?.focus() }, [])

  const safeActive = filtered.length === 0 ? 0 : Math.min(active, filtered.length - 1)
  const selected = filtered[safeActive]

  const run = async (item: CommandItem) => {
    // FNT-SUB-03/05：/resume、/color 由面板直接执行并就地反馈。
    //   后端 builtins 无 color → 404 fail loud；resume 需 agentId 请求体（面板无选中 agent →
    //   400「resume 请求体需携带 agentId」，亦 fail loud 展示）。
    if (item.name !== 'resume' && item.name !== 'color') {
      onExecute(item.name)
      return
    }
    setFeedback(null)
    try {
      const resp = (await commandApi.executeBuiltin(item.name)) as ResumeResult
      if (item.name === 'resume') {
        const aid = resp.agentId
        setFeedback(aid ? { kind: 'success', text: `已恢复 ${aid}` } : { kind: 'success', text: '已执行 /resume' })
      } else {
        setFeedback({ kind: 'success', text: '已执行 /color' })
      }
    } catch (e) {
      setFeedback({ kind: 'error', text: e instanceof ApiError ? e.userMessage() : String(e) })
    }
  }

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (filtered.length > 0) setActive((i) => (i + 1) % filtered.length)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      if (filtered.length > 0) setActive((i) => (i - 1 + filtered.length) % filtered.length)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (selected) run(selected)
    } else if (e.key === 'Escape') {
      e.preventDefault()
      onClose()
    }
  }

  return (
    <div className="search-backdrop" onClick={onClose}>
      <style>{`
        .search-item.active { background: var(--surface-2); }
        .command-palette-run:disabled { opacity: 0.5; cursor: default; }
      `}</style>
      <div className="search-palette" onClick={(e) => e.stopPropagation()}>
        <div className="search-header">
          <svg className="search-icon" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth={1.5}>
            <circle cx="6" cy="6" r="4" />
            <path d="M10 10L13 13" />
          </svg>
          <input
            ref={inputRef}
            placeholder="搜索命令，回车执行所选"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={onKeyDown}
          />
          <kbd>esc</kbd>
        </div>
        <div className="search-results">
          {filtered.length === 0 ? (
            <div className="search-empty">没有匹配的命令，换个关键词试试</div>
          ) : (
            filtered.map((item, i) => (
              <div
                key={item.name}
                className={`search-item${i === safeActive ? ' active' : ''}`}
                onMouseEnter={() => setActive(i)}
                onClick={() => run(item)}
                style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px', cursor: 'pointer', borderRadius: 'var(--r-sm)' }}
              >
                <span style={{ color: 'var(--ink-subtle)', flexShrink: 0, display: 'flex' }}><RowIcon name={item.name} /></span>
                <div className="search-item-info">
                  <span className="search-item-title">{item.name}</span>
                  <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '10.5px', color: 'var(--ink-faint)', overflow: 'hidden' }}>
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{item.description}</span>
                    {item.pluginName && (
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: 9.5, color: 'var(--ink-muted)', background: 'var(--surface-2)', padding: '1px 5px', borderRadius: 'var(--r-xs)', flexShrink: 0 }}>{item.pluginName}</span>
                    )}
                  </span>
                </div>
                <span style={{ fontSize: 10, fontFamily: 'var(--font-mono)', color: 'var(--ink-subtle)', background: 'var(--surface-2)', padding: '2px 6px', borderRadius: 'var(--r-xs)', flexShrink: 0 }}>{typeLabel(item) ?? badgeFallback(item)}</span>
                <span style={{ fontSize: 10, fontFamily: 'var(--font-mono)', color: 'var(--ink-faint)', background: 'var(--surface-2)', padding: '2px 6px', borderRadius: 'var(--r-xs)', flexShrink: 0 }}>{item.hint ?? `/${item.name}`}</span>
              </div>
            ))
          )}
        </div>
        {feedback && (
          <div style={{ padding: '6px 16px', fontSize: 11, borderTop: '1px solid var(--hairline)' }}>
            <span style={{ color: feedback.kind === 'error' ? 'var(--error)' : 'var(--success)' }}>{feedback.text}</span>
          </div>
        )}
        <div className="search-footer">
          <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 10, color: 'var(--ink-faint)' }}>
            <kbd>↑</kbd><kbd>↓</kbd> 选择
            <kbd>↵</kbd> 执行
            <kbd>esc</kbd> 关闭
          </span>
          <button
            className="command-palette-run fm-btn primary"
            disabled={!selected}
            onClick={() => selected && run(selected)}
          >
            <svg width={10} height={10} viewBox="0 0 12 12" fill="currentColor">
              <path d="M3 2l7 4-7 4z" />
            </svg>
            执行
          </button>
        </div>
      </div>
    </div>
  )
}
