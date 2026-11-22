import { useSyncExternalStore } from 'react'
import { grammarLoadCount, highlightLines, subscribeGrammarLoaded } from '@/markdown/highlight.ts'
import type { AstEditPayload, AstGrepMatchDto, AstGrepPayload } from '@/api/types'

/**
 * AST 工具（AstGrep/AstEdit）工具卡正文 · 只吃后端 `AstUiPayload` 产出的结构化载荷。
 *
 * <p>载荷来源是 `tool.result`（JSON 串）：后端两条投放通道（实时 push / 落库重拉）都经
 * `AstUiPayload.serialize` 产出同一形态，前端只解析、不再猜文本。
 *
 * <p>渲染约束：
 * <ul>
 *   <li>每文件最多 10 条（超出显示 `… N more`）——DOM 密集，长列表会把会话卡住；</li>
 *   <li>单段文本超过 240 字符截断加 `…`（后端已截到 200，这里兜底旧数据/手工注入）；</li>
 *   <li>语法高亮复用 markdown 侧 `highlightLines`，语法未加载/未知扩展名回落纯文本（不阻塞）；</li>
 *   <li>`truncated=true` 时工具栏显示「另有 N 条未展示」——不让用户以为看到的是全部。</li>
 * </ul>
 */

const GREP_NAME_RE = /^ast[\s_-]?grep$/i
const EDIT_NAME_RE = /^ast[\s_-]?edit$/i

/** 每文件最多展示的条目数（match / change 同一口径）。 */
const PER_FILE_CAP = 10
/** 单段文本展示上限（字符）。 */
const TEXT_CAP = 240

/**
 * `tool.result` → AST 载荷；工具名/kind 不匹配或 JSON 非法 → null（调用方回落原有 IN/OUT 渲染）。
 */
export function parseAstPayload(
  name: string,
  result: string | null,
): AstGrepPayload | AstEditPayload | null {
  const isGrep = GREP_NAME_RE.test(name ?? '')
  const isEdit = EDIT_NAME_RE.test(name ?? '')
  if (!isGrep && !isEdit) return null
  if (!result) return null
  try {
    const parsed: unknown = JSON.parse(result)
    if (parsed == null || typeof parsed !== 'object' || !('kind' in parsed)) return null
    const kind = parsed.kind
    if (isGrep && kind !== 'ast_grep') return null
    if (isEdit && kind !== 'ast_edit') return null
    return parsed as AstGrepPayload | AstEditPayload
  } catch {
    return null
  }
}

/** 扩展名 → 高亮语言 id（`highlightLines` 只认 LANG_ALIASES 的键，未命中回落纯文本）。 */
function langOf(path: string): string | undefined {
  const ext = path.split('.').pop()?.toLowerCase() ?? ''
  const map: Record<string, string> = {
    js: 'javascript',
    jsx: 'javascript',
    ts: 'typescript',
    tsx: 'tsx',
    py: 'python',
    rs: 'rust',
    kt: 'kotlin',
    cs: 'csharp',
    sh: 'bash',
    md: 'markdown',
  }
  return map[ext] ?? (ext || undefined)
}

function clip(text: string): string {
  return text.length > TEXT_CAP ? `${text.slice(0, TEXT_CAP)}…` : text
}

/** 单行高亮（语法未就绪/未知语言 → 纯文本）。 */
function CodeLine({ text, lang }: { text: string; lang: string | undefined }) {
  // 懒加载语法就绪后重渲染（与 CodeBlock 同源）。
  const loaded = useSyncExternalStore(subscribeGrammarLoaded, grammarLoadCount, grammarLoadCount)
  const spans = (() => {
    void loaded
    return highlightLines(text, lang)
  })()
  if (spans === undefined || spans.length === 0) return <>{text}</>
  return (
    <>
      {spans[0]!.map((span, i) => (
        <span key={i} style={span.style}>{span.text}</span>
      ))}
    </>
  )
}

function metaText(meta: Record<string, string>): string {
  return Object.keys(meta)
    .sort()
    .map((k) => `${k}=${meta[k]}`)
    .join(', ')
}

/** AstGrep 正文：按文件分组、逐匹配 `行:列 正文`（多行匹配逐行渲染）+ meta。 */
function GrepBody({ payload }: { payload: AstGrepPayload }) {
  return (
    <div className="ast-body">
      <div className="ast-toolbar">
        <span className="ast-chip">{payload.pattern}</span>
        {payload.lang && <span className="ast-chip">{payload.lang}</span>}
        <span>{payload.files.length} files · {payload.totalMatches} matches</span>
        {payload.skip > 0 && <span>skip {payload.skip}</span>}
        {payload.limitReached && <span className="ast-badge">还有更多匹配</span>}
        {payload.truncated && (
          <span className="ast-more-note">
            {payload.more > 0 ? `另有 ${payload.more} 条未展示` : '匹配过多，结果已截断'}
          </span>
        )}
      </div>
      {payload.files.map((file) => {
        const lang = langOf(file.path)
        return (
          <div className="ast-file" key={file.path}>
            <div className="ast-file-head">
              {file.path}
              <span className="ast-file-lang">{file.language}</span>
            </div>
            {file.matches.slice(0, PER_FILE_CAP).map((m: AstGrepMatchDto, i) => {
              const lines = m.text.split('\n')
              return (
                <div key={`${m.line}:${m.column}:${i}`}>
                  {lines.map((line, li) => (
                    <div className="ast-match" key={li}>
                      <span className="ast-gutter">{li === 0 ? `${m.line}:${m.column}` : ''}</span>
                      <span className="ast-code"><CodeLine text={clip(line)} lang={lang} /></span>
                    </div>
                  ))}
                  {Object.keys(m.meta).length > 0 && (
                    <div className="ast-meta">meta: {metaText(m.meta)}</div>
                  )}
                </div>
              )
            })}
            {file.matches.length > PER_FILE_CAP && (
              <div className="ast-more">… {file.matches.length - PER_FILE_CAP} more</div>
            )}
          </div>
        )
      })}
    </div>
  )
}

/** AstEdit 正文：三态徽标 + 逐文件 `- before` / `+ after`。 */
function EditBody({ payload }: { payload: AstEditPayload }) {
  const badge = payload.totalReplacements === 0 ? '无变更' : payload.applied ? '已应用' : '未应用'
  return (
    <div className="ast-body">
      <div className="ast-toolbar">
        <span className={`ast-badge${payload.applied ? ' applied' : ''}`}>{badge}</span>
        <span>Total {payload.totalReplacements} replacements in {payload.filesTouched} files</span>
        {payload.truncated && <span className="ast-more-note">另有 {payload.more} 条未展示</span>}
      </div>
      {payload.files.map((file) => (
        <div className="ast-file" key={file.path}>
          <div className="ast-file-head">
            {file.path} ({file.changes.length} replacements)
          </div>
          {file.changes.slice(0, PER_FILE_CAP).map((c, i) => (
            <div key={`${c.line}:${c.column}:${i}`}>
              <div className="ast-diff-del">
                <span className="ast-diff-sign">-</span>
                <span className="ast-gutter">{c.line}:{c.column}</span>
                <span>{clip(c.before)}</span>
              </div>
              <div className="ast-diff-add">
                <span className="ast-diff-sign">+</span>
                <span className="ast-gutter">{c.line}:{c.column}</span>
                <span>{clip(c.after)}</span>
              </div>
            </div>
          ))}
          {file.changes.length > PER_FILE_CAP && (
            <div className="ast-more">… {file.changes.length - PER_FILE_CAP} more</div>
          )}
        </div>
      ))}
    </div>
  )
}

export function AstToolBody({ payload }: { payload: AstGrepPayload | AstEditPayload }) {
  return payload.kind === 'ast_grep' ? <GrepBody payload={payload} /> : <EditBody payload={payload} />
}
