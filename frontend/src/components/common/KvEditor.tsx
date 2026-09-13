/**
 * KvEditor · 通用行式键值对编辑器（name / value 两列 + 增行 / 删行）。
 *
 * WHY 行式而非自由 JSON：这类配置（如提供商的额外请求头）用户往往只改一行，
 * 不该被迫面对整段 JSON 文本。内部用数组（Row[]）保序，对外转 `Record<string, string>`——
 * 与 `Provider.extraHeaders` 的形态对齐（`Record<string, string> | null`）。
 *
 * ⚠️ 本文件的所有校验判据都是**镜像**，单一判据源在后端：
 * `backend/src/main/java/com/nexusai/infra/llm/DynamicHeaderExpander.java`。
 * **改动需与 `DynamicHeaderExpander` 同步** —— 逐一对应四个判据：
 *   `FORBIDDEN_HEADER_NAMES` ↔ 本文件同名常量
 *   `isValidHeaderName`      ↔ `isValidHeaderToken` + `isForbiddenHeaderName`
 *   `isValidHeaderValue`     ↔ `isValidHeaderValue`
 *   `hasMalformedPlaceholder`↔ `hasMalformedPlaceholder`
 * 后端写侧命中会返回 400；这里只是把同一判据提前成行内提示，别等提交才报错。
 * **判据以后端为准**：两边不一致时改这里，不要改后端。
 *
 * 对外契约见 `KvEditorProps`。空 name 的行不入产物（与后端「跳过非法条目」同向）；
 * 重名按 `trim().toLowerCase()` 归一由**后行覆盖前行**（与后端 Map 语义一致），并在行内提示前一行将被覆盖。
 *
 * <p><b>全空产出 `{}`（不是 `null`）</b>：`{}` = 显式清空、缺字段/null = 不触碰，两者不可互换。
 */
import { useEffect, useRef, useState } from 'react'

/** 本批唯一占位符。大小写敏感（`String#includes` 语义），与后端 SESSION_ID_TOKEN 一致。 */
const SESSION_ID_TOKEN = '${session_id}'

/**
 * 禁止用户配置的请求头名（14 项 · 大小写不敏感）。
 * 镜像后端 `DynamicHeaderExpander.FORBIDDEN_HEADER_NAMES` —— 改动需与后端同步。
 * 两类：凭据类（SDK 依 apiKey 自动注入 / 属会话凭据，不允许被提供商级配置劫持）、
 * 报文完整性类（覆盖会直接破坏请求本身）。
 * 协议类头（anthropic-version / anthropic-beta / accept 等）**允许**，故不在此列。
 */
export const FORBIDDEN_HEADER_NAMES: readonly string[] = [
  // 凭据类
  'authorization', 'proxy-authorization', 'x-api-key', 'api-key', 'cookie', 'set-cookie',
  // 报文完整性类（expect：JDK HttpRequest 受限头，会让「测试连接」报出误导性失败）
  'content-length', 'content-type', 'host', 'transfer-encoding', 'connection', 'te', 'upgrade',
  'expect',
]

/** RFC 7230 token：`!#$%&'*+-.^_`|~` 与数字字母。镜像后端 VALID_HEADER_NAME。 */
const VALID_HEADER_TOKEN = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/

/** RFC 7230 field-value：可见 ASCII + 空格 / 水平制表，**不含 CR/LF**。镜像后端 VALID_HEADER_VALUE。 */
const VALID_HEADER_VALUE = /^[\x20-\x7E\t]*$/

/** 名称是否命中禁止清单（trim + 小写，与后端 isForbiddenHeaderName 同语义）。 */
export function isForbiddenHeaderName(name: string, forbiddenNames: readonly string[]): boolean {
  return forbiddenNames.includes(name.trim().toLowerCase())
}

/** 值里出现 `${` 但不是精确的占位符（拼写 / 大小写错误）→ true。镜像后端 hasMalformedPlaceholder。 */
export function hasMalformedPlaceholder(value: string): boolean {
  if (!value.includes('${')) return false
  // 与后端 String#replace（替换全部）等价：target 是 ES2020，无 String#replaceAll
  return value.split(SESSION_ID_TOKEN).join('').includes('${')
}

interface Row {
  /** 仅用于 React key，不参与产物。 */
  id: number
  name: string
  value: string
}

export interface KvEditorProps {
  value: Record<string, string> | null
  onChange: (next: Record<string, string> | null) => void
  /** 禁止的 header 名（大小写不敏感）；默认即后端 DynamicHeaderExpander 的同一份镜像清单 */
  forbiddenNames?: readonly string[]
  addLabel?: string
}

/**
 * 行 → 产物。
 *
 * <p>两条规则：
 * <ul>
 *   <li><b>空 name 的行被丢弃</b>（与后端「跳过非法条目」同向）。</li>
 *   <li><b>去重按 `trim().toLowerCase()` 归一</b>，保留**最后一行**的原始大小写作为键 —— 必须与
 *       {@link rowIssues} 里「名称重复，仅最后一行生效」的判据一致。否则 `X-A` / `x-a` 两行会
 *       既提示「仅最后一行生效」、又双双进入产物（HTTP 头名大小写不敏感，实为同一个头）——
 *       提示就成了假话。</li>
 * </ul>
 *
 * <p><b>全空产出 `{}` 而不是 `null`</b>（有意约定，非笔误）：这与「不发送该字段」是两种不同语义 ——
 * `{}` = 显式清空（后端写 SQL NULL），缺失/null = 不触碰。详见 `ProviderService.update` 的清空契约。
 * 若这里返回 null，「删光所有 header」就无法表达、会静默 no-op。
 */
function toRecord(rows: Row[]): Record<string, string> {
  // Map 保序；对同一归一键重复 set → 位置不变、值取最后一行（即「后行覆盖前行」）
  const byKey = new Map<string, { name: string; value: string }>()
  for (const r of rows) {
    const key = r.name.trim().toLowerCase()
    if (key === '') continue // 空 name 不入产物
    byKey.set(key, { name: r.name, value: r.value })
  }
  const out: Record<string, string> = {}
  for (const { name, value } of byKey.values()) out[name] = value
  return out
}

/**
 * 外部 value 与本组件当前产物是否等价（用于判断是否需要重建行）。
 * `null` 与 `{}` 视为同义（都是「没有 header」）—— 本组件全空时产出 `{}`，
 * 而外部可能仍传 `null`，不归一就会反复重建行、把用户正在敲的空行抹掉。
 */
function sameRecord(a: Record<string, string> | null, b: Record<string, string> | null): boolean {
  if (a === b) return true
  const ka = a === null ? [] : Object.keys(a)
  const kb = b === null ? [] : Object.keys(b)
  if (ka.length !== kb.length) return false
  const av = a as Record<string, string>
  const bv = b as Record<string, string>
  return ka.every((k) => av[k] === bv[k])
}

interface RowIssues {
  /** 行内提示文案（空 = 无问题）。 */
  messages: string[]
  /** 名称列是否标红（空名 / 非法 token / 命中禁止清单）。 */
  nameInvalid: boolean
  /** 值列是否标红（含换行 / 占位符拼写错误）。 */
  valueInvalid: boolean
}

/**
 * 单行的校验结果。`overridden` 由调用方按「后面还有同名行」算好传入。
 * 标红标志与提示文案在同一处产出 —— 避免两处判据各写一遍后漂移（本文件是后端判据的镜像，
 * 漂移的代价是用户看到提示却看不到标红，或反之）。
 */
function rowIssues(row: Row, forbiddenNames: readonly string[], overridden: boolean): RowIssues {
  const messages: string[] = []
  let nameInvalid = false
  let valueInvalid = false
  if (row.name.trim() === '') {
    nameInvalid = true
    messages.push('名称不能为空，该行不会被保存')
  } else {
    if (!VALID_HEADER_TOKEN.test(row.name)) {
      nameInvalid = true
      messages.push('名称含非法字符（不能有空格、冒号等）')
    }
    if (isForbiddenHeaderName(row.name, forbiddenNames)) {
      nameInvalid = true
      messages.push('该请求头由系统管理，不允许自定义')
    }
    if (overridden) {
      // 重名只是「前一行会被覆盖」的提示，不是非法输入 —— 不标红
      messages.push('名称重复，仅最后一行生效')
    }
  }
  if (row.value !== '') {
    if (!VALID_HEADER_VALUE.test(row.value)) {
      valueInvalid = true
      // 文案对齐后端 validateExtraHeaders 的完整说法：判据是 `[\x20-\x7E\t]`（可见 ASCII + 空格/制表），
      // 只说「换行」会让写中文的用户看不出该改什么
      messages.push('值不得包含换行符，且只能是可见 ASCII 字符（例如不能写中文）')
    }
    if (hasMalformedPlaceholder(row.value)) {
      valueInvalid = true
      messages.push('占位符区分大小写，正确写法是 ${session_id}')
    }
  }
  return { messages, nameInvalid, valueInvalid }
}

const ROW_STYLE = { display: 'flex', gap: 6, alignItems: 'flex-start', marginBottom: 4 } as const
const GROW_STYLE = { flex: 1, minWidth: 0 } as const

export function KvEditor({
  value,
  onChange,
  forbiddenNames = FORBIDDEN_HEADER_NAMES,
  addLabel = '+ 添加一行',
}: KvEditorProps) {
  const nextId = useRef(1)
  const fromRecord = (v: Record<string, string> | null): Row[] =>
    v === null ? [] : Object.entries(v).map(([name, value]) => ({ id: nextId.current++, name, value }))

  const [rows, setRows] = useState<Row[]>(() => fromRecord(value))
  const rowsRef = useRef(rows)
  rowsRef.current = rows

  useEffect(() => {
    // 仅当外部 value 与本组件当前产物**不等价**时才重建行。
    // 否则用户正在敲的空名行 / 重名行会被抹掉 —— 它们本就不在产物里，天然与 value 不等价。
    if (!sameRecord(value, toRecord(rowsRef.current))) {
      setRows(fromRecord(value))
    }
  }, [value])

  const commit = (next: Row[]) => {
    setRows(next)
    onChange(toRecord(next))
  }

  // 重名判定：同名（trim + 小写）行里只有最后一行生效，其余标记为「将被覆盖」
  const normalized = rows.map((r) => r.name.trim().toLowerCase())
  const lastIndexByName = new Map<string, number>()
  normalized.forEach((n, i) => {
    if (n !== '') lastIndexByName.set(n, i)
  })

  return (
    <div className="kv-editor">
      {rows.length === 0 && <div className="fm-field-hint">暂无自定义请求头</div>}
      {rows.map((row, i) => {
        const { messages, nameInvalid, valueInvalid } = rowIssues(
          row,
          forbiddenNames,
          lastIndexByName.get(normalized[i]) !== i,
        )
        return (
          <div key={row.id} style={ROW_STYLE}>
            <input
              className={`fm-input mono${nameInvalid ? ' invalid' : ''}`}
              style={GROW_STYLE}
              type="text"
              value={row.name}
              placeholder="请求头名称"
              spellCheck={false}
              onChange={(e) => commit(rows.map((r) => (r.id === row.id ? { ...r, name: e.target.value } : r)))}
            />
            <input
              className={`fm-input mono${valueInvalid ? ' invalid' : ''}`}
              style={GROW_STYLE}
              type="text"
              value={row.value}
              placeholder="值"
              spellCheck={false}
              onChange={(e) => commit(rows.map((r) => (r.id === row.id ? { ...r, value: e.target.value } : r)))}
            />
            <button
              type="button"
              className="icon-btn"
              title="删除此行"
              onClick={() => commit(rows.filter((r) => r.id !== row.id))}
            >
              <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
                <path d="M4 4L10 10M10 4L4 10" />
              </svg>
            </button>
            {messages.length > 0 && (
              <div className="fm-field-hint error" style={{ alignSelf: 'center' }}>
                {messages.join('；')}
              </div>
            )}
          </div>
        )
      })}
      <button
        type="button"
        className="fm-btn"
        onClick={() => commit([...rows, { id: nextId.current++, name: '', value: '' }])}
      >
        {addLabel}
      </button>
    </div>
  )
}
