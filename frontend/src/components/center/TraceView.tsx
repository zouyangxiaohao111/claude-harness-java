import type { ChatMessageDto, ToolCallDto } from '@/api/types'

/** 轨迹视图 · dsh 式记录列表：从会话消息历史派生 user/assistant/tool 记录（按 turn 分组）。
 *  数据源：chatStore.messages[activeSessionId]（真实后端消息历史，含 toolCalls）。 */

/** 工具类型 → 色条 class（对齐设计稿 v7：bash/edit/read/write/glob/task） */
function toolKind(name: string): string {
  const n = name.toLowerCase()
  if (n.includes('bash') || n.includes('shell') || n.includes('exec')) return 'tool-bash'
  if (n.includes('edit') || n.includes('write')) return 'tool-edit'
  if (n.includes('glob') || n.includes('grep') || n.includes('search')) return 'tool-glob'
  if (n.startsWith('read') || n.includes('read_file')) return 'tool-read'
  if (n.includes('task') || n.includes('agent') || n.includes('subagent')) return 'tool-task'
  return 'tool-other'
}

/** 工具名清理（复用 MessageList 的 FM-14 规则：剥 mcp__server__ 前缀等） */
function cleanToolName(name: string | null): string {
  let n = (name ?? 'tool').trim()
  n = n.replace(/^\s*-\s*/, '').trim()
  if (n.startsWith('mcp__')) {
    const rest = n.slice('mcp__'.length)
    const sep = rest.indexOf('__')
    n = (sep === -1 ? rest : rest.slice(sep + 2)).trim()
  }
  if (n.endsWith('(MCP)')) n = n.slice(0, -'(MCP)'.length).trim()
  return n
}

interface TraceRecord {
  kind: 'user' | 'assistant' | 'tool'
  toolClass: string
  toolName: string
  txt: string
  time: string
}

/** 单条消息 → 轨迹记录数组（user 1 条 / assistant 1 条 + 每条 toolCall 1 条，保持顺序） */
function toRecords(msg: ChatMessageDto): TraceRecord[] {
  const time = msg.time ?? msg.createdAt ?? ''
  if (msg.role === 'user') {
    return [{ kind: 'user', toolClass: '', toolName: '', txt: msg.content ?? '', time }]
  }
  if (msg.role === 'system') {
    // fallback / 系统消息：归为 assistant 类别，避免空行
    return [{ kind: 'assistant', toolClass: '', toolName: '', txt: msg.content ?? '', time }]
  }
  const records: TraceRecord[] = []
  for (const tc of msg.toolCalls ?? []) {
    records.push({
      kind: 'tool',
      toolClass: toolKind(tc.name ?? ''),
      toolName: cleanToolName(tc.name),
      txt: summarizeArgs(tc),
      time,
    })
  }
  if (msg.content) {
    records.push({
      kind: 'assistant',
      toolClass: '',
      toolName: '',
      txt: msg.content.length > 60 ? `${msg.content.slice(0, 60)}…` : msg.content,
      time,
    })
  }
  return records
}

/** 工具参数摘要：取 arguments 前 40 字（JSON 截断到可读） */
function summarizeArgs(tc: ToolCallDto): string {
  const args = tc.arguments ?? ''
  const cleaned = args.replace(/\s+/g, ' ').trim()
  return cleaned.length > 40 ? `${cleaned.slice(0, 40)}…` : cleaned
}

interface TraceViewProps {
  messages: ChatMessageDto[]
}

export function TraceView({ messages }: TraceViewProps) {
  const visible = messages.filter((m) => !m.isMeta)
  if (visible.length === 0) {
    return (
      <div className="trace-view">
        <div className="trace-empty">该会话暂无轨迹</div>
      </div>
    )
  }

  // 按 turn 分组：每条 user 消息开新 turn；无 user 起始时兜底为单 turn
  const turns: { num: number; title: string; records: TraceRecord[] }[] = []
  let cur: { num: number; title: string; records: TraceRecord[] } | null = null
  for (const msg of visible) {
    const recs = toRecords(msg)
    if (recs.length === 0) continue
    if (msg.role === 'user') {
      cur = { num: turns.length + 1, title: recs[0].txt.slice(0, 30), records: [] }
      turns.push(cur)
    } else if (cur === null) {
      cur = { num: turns.length + 1, title: '会话', records: [] }
      turns.push(cur)
    }
    cur.records.push(...recs)
  }
  if (turns.length === 0) {
    return <div className="trace-view"><div className="trace-empty">该会话暂无轨迹</div></div>
  }

  return (
    <div className="trace-view">
      {turns.map((t) => (
        <div key={t.num} className="trace-turn">
          <div className="trace-turn-header"><span className="turn-num">#{t.num}</span> {t.title}</div>
          {t.records.map((r, i) => (
            <div key={i} className={`trace-record ${r.kind === 'user' ? 'user-rec' : r.kind === 'assistant' ? 'assistant-rec' : r.toolClass}`}>
              <span className={`kind ${r.kind}`}>{r.kind}</span>
              <span className="content">
                {r.kind === 'tool' ? (
                  <span className={`tool-name ${r.toolClass.replace('tool-', '')}`}>{r.toolName}</span>
                ) : r.kind === 'user' ? (
                  <span className="user-txt">{r.txt}</span>
                ) : (
                  r.txt
                )}
              </span>
              <span className="time">{r.time}</span>
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}
