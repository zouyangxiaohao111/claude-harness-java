import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Client } from '@stomp/stompjs'
import { ApiError } from '@/api/rest'
import { createSocketClient, subscribeStream } from '@/api/socket'
import { knowledgeApi } from '@/api/knowledge'
import type { KbCitation, KnowledgeBase } from '@/api/knowledge'
import { sessionApi } from '@/api/sessions'
import type { StreamEvent, MessageChunkEvent, MessageCompleteEvent } from '@/api/types'

interface KbQaPanelProps {
  /** 当前知识库（默认检索范围） */
  baseId: string
  baseName?: string
  /** 全部知识库（跨库问答多选用） */
  bases: KnowledgeBase[]
  showToast: (msg: string, type?: 'success' | 'info') => void
}

/** 网页来源（没有 chunk，不落 kb_citation）。 */
interface WebSource {
  title: string
  url: string
}

/** STOMP `citations` 事件的原始形状（`@JsonProperty` 钉死的 snake_case）。 */
interface RawCitationsEvent {
  citations?: Array<{
    ref_index?: number; chunk_id?: string; knowledge_id?: string
    knowledge_base_id?: string; knowledge_title?: string
    snippet?: string; chunk_index?: number | null
  }>
  webSources?: WebSource[]
  web_sources?: WebSource[]
}

/**
 * 把 `citations` 事件归一化成 REST 实体的驼峰形状。
 *
 * <p><b>WHY 必须显式转</b>：事件侧是 `@JsonProperty` 钉死的 snake_case，
 * REST 实体走 Jackson 默认 getter ⇒ 驼峰。两处同源但形态不同，直接当同一类型用
 * 会让每一栏都变成 `undefined`（页面上表现为「引用标题全空」——静默、不报错）。
 *
 * @param raw 事件载荷（可能缺字段/缺整块）
 * @returns 引用列表（永不 null）
 */
export function toCitations(raw: RawCitationsEvent | null | undefined): KbCitation[] {
  if (!raw || !Array.isArray(raw.citations)) return []
  return raw.citations.map((c) => ({
    refIndex: c.ref_index ?? 0,
    chunkId: c.chunk_id ?? '',
    knowledgeId: c.knowledge_id ?? '',
    knowledgeBaseId: c.knowledge_base_id ?? '',
    knowledgeTitle: c.knowledge_title ?? '',
    snippet: c.snippet ?? '',
    chunkIndex: c.chunk_index ?? null,
  }))
}

/** 从事件里取网页来源（兼容两种键名）。 */
export function toWebSources(raw: RawCitationsEvent | null | undefined): WebSource[] {
  if (!raw) return []
  const ws = raw.webSources ?? raw.web_sources
  return Array.isArray(ws) ? ws : []
}

/**
 * 切换检索范围里的一个库。
 *
 * <p>⭐ 最后一个是**删不掉**的：范围为空会被后端解释成「全部可检索库」，
 * 用户以为自己只查一个库，实际查了全部 —— 那是最难发现的一类越权。
 *
 * @param prev 当前范围
 * @param id   要切换的库
 * @returns 新范围（至少保留 1 个）
 */
export function toggleScopeIds(prev: string[], id: string): string[] {
  if (prev.includes(id)) {
    return prev.length <= 1 ? prev : prev.filter((x) => x !== id)
  }
  return [...prev, id]
}

/**
 * 知识库「问答」tab · QA-13。
 *
 * <h2>⭐ 这条链与主对话是两条执行路径</h2>
 * 本组件走 `POST /api/v1/knowledge-chat/{sid}` → `KbQaTurnService` → `KbQaPipeline`
 * （12 阶段 RAG）。**⛔ 不经过 `LlmAgentLoop`**、不参与工具/权限/skill。
 *
 * <h2>为什么自己开一个 STOMP client</h2>
 * `useChatSocket` 绑定在 chat workspace 的会话流上；知识库页是**独立 workspace**
 * （`workspacePage === 'knowledge'`），不复用它。两条链共用同一个 topic 与事件类，
 * 所以这里只需要「订阅 + 读事件」，不需要第二套事件解析。
 *
 * <h2>会话从哪来</h2>
 * RAG 轮要落 `messages` 行，必须有 `sessionId`。首次提问时懒创建一个专用会话
 * （标题「知识库问答」）—— ⛔ 不动用户当前正在聊的会话，避免把两个话题混进同一轮。
 *
 * <h2>阶段提示的诚实边界</h2>
 * 后端阶段进度只写日志（`[问答链] ①…⑫`），**没有推 STOMP 阶段事件**。
 * 因此这里只显示客户端可观测的两种状态：「检索中」（POST 已受理、尚未收到首块）
 * 与「生成中」（已收到 `message.chunk`）。⛔ 不假装逐阶段进度。
 *
 * <h2>⚠️ 类名依据</h2>
 * 结构取自设计稿 08（`.qa-wrap > .qa-main > .qa-scope / .qa-thread / .qa-compose`，
 * 引用块 `.qa-sources > .src-item > .src-idx + .src-body > .src-title + .src-snip`）。
 * 源站仓库里**没有知识库问答组件**（`grep -rl "qa-thread" D:/tmp/WeKnora/frontend/src` 0 命中，
 * 中文「问答」只命中 agent 编辑器注释），`styles/kb/*.css` 里 `.qa-*` / `.src-*` / `.bubble`
 * 同样是 0 条规则 ⇒ 这批类目前无样式。按派单要求仍落真类名、不自造 CSS；
 * 只有最外层两条 flex 容器用行内兜底，避免绝对无布局。
 */
export function KbQaPanel({ baseId, baseName, bases, showToast }: KbQaPanelProps) {
  const [scopeIds, setScopeIds] = useState<string[]>([baseId])
  const [question, setQuestion] = useState('')
  /**
   * 本次会话已提过的问题（设计稿 08 页右栏 `.qa-side-card > .qa-hist > .qa-hist-item`）。
   * ⚠️ 只记录**真正发出去的提问**（点发送 / Ctrl+Enter 那一刻追加），不是输入框草稿 ——
   * 否则用户边打字边改历史。
   */
  const [askedHistory, setAskedHistory] = useState<string[]>([])
  /** 检索设置三开关（设计稿 08 右栏）。⚠️ 本仓后端 `askQa` 无对应参数 ⇒ 见下方注释。 */
  const [qaOpts, setQaOpts] = useState({ hybrid: true, rerank: true, showCitations: true })
  const [answer, setAnswer] = useState('')
  const [citations, setCitations] = useState<KbCitation[]>([])
  const [webSources, setWebSources] = useState<WebSource[]>([])
  const [phase, setPhase] = useState<'idle' | 'retrieving' | 'generating' | 'done' | 'error'>('idle')
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [openCitation, setOpenCitation] = useState<number | null>(null)

  const clientRef = useRef<Client | null>(null)
  const clientReadyRef = useRef(false)
  const sessionRef = useRef<string | null>(null)

  // 切库 ⇒ 检索范围回到「只看当前库」（跨库选择是显式动作，不跨越库切换保留）
  useEffect(() => {
    setScopeIds([baseId])
  }, [baseId])

  useEffect(() => { sessionRef.current = sessionId }, [sessionId])

  /** 处理一帧流事件（只关心 RAG 轮需要的三类）。 */
  const acceptEvent = useCallback((evt: StreamEvent) => {
    const t = evt.type
    if (t === 'message.chunk') {
      const e = evt as MessageChunkEvent
      if (e.delta) {
        setAnswer((prev) => prev + e.delta)
        setPhase((p) => (p === 'generating' ? p : 'generating'))
      }
      return
    }
    if (t === 'citations') {
      const raw = evt as unknown as RawCitationsEvent
      const items = toCitations(raw)
      if (items.length > 0) setCitations(items)
      const ws = toWebSources(raw)
      if (ws.length > 0) setWebSources(ws)
      return
    }
    if (t === 'message.complete') {
      const e = evt as MessageCompleteEvent
      // 收口事件带全文：用它对齐最终文本（流式可能丢末段，前端以 complete 为准）
      if (e.content) setAnswer(e.content)
      const failed = e.finishReason === 'error'
      setPhase(failed ? 'error' : 'done')
      // 权威引用走 REST（事件只是增量通知；refresh/回放同理）
      if (e.assistantMessageId) {
        void knowledgeApi
          .listCitations(e.assistantMessageId)
          .then((list) => { if (list.length > 0) setCitations(list) })
          .catch(() => { /* 引用是附加证据，查询失败不改变答案 */ })
      }
      return
    }
    if (t === 'message.error') {
      const e = evt as unknown as { message?: string }
      setPhase('error')
      setErrorMsg(e.message ?? '问答失败')
      return
    }
    if (t === 'message.cancelled') {
      // ⚠️ 取消不是 `finishReason` 的取值（类型里没有 `cancelled`）⇒ 单独一帧
      setPhase('done')
      setErrorMsg('已停止')
    }
  }, [])

  /** 懒建连接（只在第一次提问时连，避免知识库页一打开就占一条 WS）。 */
  const ensureClient = useCallback((): Client => {
    if (clientRef.current) return clientRef.current
    const client = createSocketClient()
    clientRef.current = client
    client.onConnect = () => {
      clientReadyRef.current = true
      // 首次连上时若已有会话（重复提问场景），补订阅
      const sid = sessionRef.current
      if (sid) subscribeStream(client, `/topic/sessions/${sid}/stream`, acceptEvent)
    }
    client.activate()
    return client
  }, [acceptEvent])

  /** 拿到本轮可用的 sessionId：优先复用本 panel 建的，否则新建一个专用会话。 */
  const ensureSession = useCallback(async (): Promise<string> => {
    if (sessionRef.current) return sessionRef.current
    // 专用会话挂在「知识库工作区」项目下（服务端按 <安装目录>/workspace/kb →
    // <配置根>/workspace/kb 降级解析并幂等注册）。⛔ 不要退回 projects[0]：
    // 项目列表按 rowid 返回，随手拿到的是用户第一个注册的仓库。
    const workspace = await knowledgeApi.workspaceProject()
    const created = await sessionApi.create({
      title: `知识库问答 · ${baseName ?? '当前库'}`,
      mainProjectId: workspace.id,
    })
    sessionRef.current = created.id
    setSessionId(created.id)
    // 等连接可用后再订阅（client 可能还没 onConnect）
    const client = ensureClient()
    const sub = () => subscribeStream(client, `/topic/sessions/${created.id}/stream`, acceptEvent)
    if (clientReadyRef.current) sub()
    else client.onConnect = () => { clientReadyRef.current = true; sub() }
    return created.id
  }, [acceptEvent, baseName, ensureClient])

  const ask = useCallback(async () => {
    const content = question.trim()
    if (!content) return
    setAnswer('')
    setCitations([])
    setWebSources([])
    setErrorMsg(null)
    setPhase('retrieving')
    // 记录到右栏「本次会话」历史（设计稿 08 页 `.qa-hist-item`）
    setAskedHistory((prev) => (prev[prev.length - 1] === content ? prev : [...prev, content]))
    try {
      const sid = await ensureSession()
      await knowledgeApi.askQa(sid, {
        content,
        knowledgeBaseIds: scopeIds.length > 0 ? scopeIds : null,
      })
    } catch (e) {
      setPhase('error')
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      setErrorMsg(msg)
      showToast(msg, 'info')
    }
  }, [ensureSession, question, scopeIds, showToast])

  const stop = useCallback(async () => {
    const sid = sessionRef.current
    if (!sid) return
    try {
      await knowledgeApi.stopQa(sid)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [showToast])

  // 卸载：断开本 panel 的 WS（不影响 useChatSocket 的连接）
  useEffect(() => {
    return () => {
      clientRef.current?.deactivate()
      clientRef.current = null
      clientReadyRef.current = false
    }
  }, [])

  const scopeLabel = useMemo(() => {
    if (scopeIds.length === 0) return '全部知识库'
    if (scopeIds.length === 1) {
      const one = bases.find((b) => b.id === scopeIds[0])
      return one?.name ?? '已选 1 个库'
    }
    return `已选 ${scopeIds.length} 个库`
  }, [scopeIds, bases])

  const toggleScope = (id: string) => {
    setScopeIds((prev) => toggleScopeIds(prev, id))
  }

  const busy = phase === 'retrieving' || phase === 'generating'

  return (
    /* ⚠️ `.qa-*` / `.src-*` / `.bubble` / `.cite` 在 `styles/kb/*.css` 与 `globals.css` 中
       **0 条规则**时靠 `.qa-wrap` 的纵向 flex 兜底 —— 现已由 `kb-08.css` 提供
       （设计稿 08 页的内联 `<style>` 已被提取脚本并入），行内只留最外层 `flex:1`。 */
    <div className="qa-wrap">
      <div className="qa-main">
        {/* 检索范围（跨库问答入口） */}
        <div className="qa-scope">
          <span className="qa-scope-label">检索范围：{scopeLabel}</span>
          {bases.map((b) => (
            <button
              key={b.id}
              type="button"
              className={`scope-chip${scopeIds.includes(b.id) ? ' on' : ''}`}
              aria-pressed={scopeIds.includes(b.id)}
              onClick={() => toggleScope(b.id)}
              title={b.description || b.name}
            >
              <svg width={12} height={12} viewBox="0 0 20 20" aria-hidden="true" style={{ verticalAlign: '-2px', marginRight: 4 }}><use href="#t-icon-kb-resource" /></svg>
              {b.name}
            </button>
          ))}
        </div>

        {/* 对话流：源站/设计稿把「问」与「答」放在同一个 .qa-turn 里。
            ⚠️ 本仓没有「已提交问题」的独立状态（`question` 是输入框草稿，发送后不清空），
            拿它当气泡会让用户边打字边改掉历史提问 ⇒ 只渲染「答」这一侧。 */}
        {phase !== 'idle' && (
          <div className="qa-thread">
            <div className="qa-turn">
              <div className="qa-a">
                <div className="bubble">
                  {/* 阶段提示（客户端可观测的两态，⛔ 不假装逐阶段进度）*/}
                  <div className="qa-tips" data-phase={phase}>
                    {phase === 'retrieving' && '检索中…（改写 → 检索 → 重排 → 合并）'}
                    {phase === 'generating' && '生成中…'}
                    {phase === 'done' && '已完成'}
                    {phase === 'error' && (errorMsg || '失败')}
                  </div>

                  {/* 答案 */}
                  {answer && <p style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{answer}</p>}

                  {/* 引用面板 */}
                  {qaOpts.showCitations && (citations.length > 0 || webSources.length > 0) && (
                    <div className="qa-sources">
                      <div className="t">引用来源 {citations.length + webSources.length}</div>
                      {citations.map((c) => (
                        <div key={`${c.chunkId}-${c.refIndex}`}>
                          <button
                            type="button"
                            className="src-item"
                            aria-expanded={openCitation === c.refIndex}
                            onClick={() => setOpenCitation(openCitation === c.refIndex ? null : c.refIndex)}
                          >
                            <span className="src-idx">[{c.refIndex}]</span>
                            <span className="src-body">
                              <span className="src-title">
                                {c.knowledgeTitle || '未命名文档'}{c.chunkIndex != null ? ` · #${c.chunkIndex}` : ''}
                              </span>
                              <span className="src-snip">{c.snippet || '（无片段）'}</span>
                            </span>
                          </button>
                          {openCitation === c.refIndex && (
                            <pre style={{ margin: '4px 0 0', whiteSpace: 'pre-wrap', fontSize: 12, color: 'var(--td-text-color-secondary)' }}>
                              {c.snippet || '（无片段）'}
                            </pre>
                          )}
                        </div>
                      ))}
                      {webSources.map((w) => (
                        <a
                          key={w.url}
                          className="src-item"
                          href={w.url}
                          target="_blank"
                          rel="noreferrer noopener"
                        >
                          <span className="src-idx">🌐</span>
                          <span className="src-body">
                            <span className="src-title">{w.title || w.url}</span>
                          </span>
                        </a>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>
        )}

        {/* 提问 */}
        <div className="qa-compose">
          <div className="qa-input-row">
            <textarea
              className="qa-input"
              placeholder="向知识库提问…（Ctrl/⌘ + Enter 发送）"
              value={question}
              rows={2}
              style={{ flex: 1, minWidth: 0 }}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                  e.preventDefault()
                  void ask()
                }
              }}
            />
            {busy ? (
              <button
                type="button"
                className="t-button t-button--variant-outline t-button--theme-default t-size-m"
                onClick={() => void stop()}
              >
                <span className="t-button__text">停止</span>
              </button>
            ) : (
              <button
                type="button"
                className="t-button t-button--variant-base t-button--theme-primary t-size-m"
                disabled={!question.trim()}
                onClick={() => void ask()}
              >
                <span className="t-button__text">提问</span>
              </button>
            )}
          </div>
        </div>
      </div>

      {/* ── 右栏（设计稿 08 页 `.qa-side`）────────────────────────────
          两张卡：本次会话历史 + 检索设置。
          ⚠️ 检索设置三开关：本仓 `knowledgeApi.askQa` 的后端契约只有
          `{content, knowledgeBaseIds}`，**没有** hybrid / rerank / showCitations 参数
          ⇒ 开关会真实改变前端呈现（`showCitations` 控制引用区是否渲染），
          但 hybrid / rerank 不传后端，点击时如实提示「本仓后端未开放该参数」，
          ⛔ 不假装生效。 */}
      <aside className="qa-side">
        <div className="qa-side-card">
          <h4>本次会话</h4>
          <div className="qa-hist">
            {askedHistory.length === 0 ? (
              <div className="qa-hist-item" aria-disabled="true" style={{ opacity: 0.5 }}>
                还没有提问
              </div>
            ) : (
              askedHistory.map((h, i) => (
                <div
                  key={`${i}-${h}`}
                  className="qa-hist-item"
                  role="button"
                  tabIndex={0}
                  title={h}
                  onClick={() => setQuestion(h)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      setQuestion(h)
                    }
                  }}
                >
                  {h}
                </div>
              ))
            )}
          </div>
        </div>

        <div className="qa-side-card">
          <h4>检索设置</h4>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {([
              ['hybrid', '混合检索（向量 + 关键词）', false],
              ['rerank', '启用重排', false],
              ['showCitations', '显示引用来源', true],
            ] as const).map(([key, label, wired]) => (
              <div
                key={key}
                className="row"
                style={{ justifyContent: 'space-between', fontSize: 12, color: 'var(--td-text-color-secondary)' }}
              >
                <span>{label}</span>
                <button
                  type="button"
                  className={`t-switch t-size-s${qaOpts[key] ? ' t-is-checked' : ''}`}
                  role="switch"
                  aria-checked={qaOpts[key]}
                  aria-label={label}
                  onClick={() => {
                    const next = !qaOpts[key]
                    setQaOpts((o) => ({ ...o, [key]: next }))
                    if (!wired) {
                      showToast(`${label}：本仓后端 askQa 未开放该参数，开关仅记录在本页`, 'info')
                    }
                  }}
                >
                  <span className="t-switch__handle" />
                </button>
              </div>
            ))}
          </div>
          <p className="hint mt16">设置仅影响本次会话，不写回知识库配置。</p>
        </div>
      </aside>
    </div>
  )
}
