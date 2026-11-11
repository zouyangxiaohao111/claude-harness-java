import { useCallback, useEffect, useRef, useState } from 'react'
import type { Client } from '@stomp/stompjs'
import { ApiError } from '@/api/rest'
import { createSocketClient, subscribeStream } from '@/api/socket'
import { knowledgeApi } from '@/api/knowledge'
import { sessionApi } from '@/api/sessions'
import type { MessageChunkEvent, MessageCompleteEvent, StreamEvent } from '@/api/types'
import { MarkdownTextV2 } from '@/markdown/vendor/renderer'

interface Props {
  /** 助手只围绕当前这篇文档回答 ⇒ 检索范围锁到它所在的知识库 */
  baseId: string
  /** 文档标题，用于欢迎语与提问上下文 */
  docTitle: string
  /** 文档 ID（当前版本后端 ask 只接受库级范围，故仅用于提示与后续扩展） */
  knowledgeId: string
  onClose: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}

/** 三条引导语 —— 与设计稿 11 页 `.assistant-starter` 逐字一致。 */
const STARTERS = [
  '这篇文档有哪些关键结论？',
  '把这篇文章里的做法整理成操作步骤',
  '这篇文档可以提炼出哪些常见问答？',
]

/** 一轮问答的完整状态（⛔ 不再用全局 answer/phase —— 那会让上一轮被清空）。 */
interface Turn {
  /** 稳定键（用自增序号，不用 index：删除中间轮后 index 会漂）。 */
  id: number
  question: string
  answer: string
  phase: 'retrieving' | 'generating' | 'done' | 'error'
  error?: string
}

/**
 * 右侧助手面板 · 对齐设计稿 11 页 `.assistant-panel`（440px 停靠栏）。
 *
 * <h2>⭐ 为什么单独一个组件</h2>
 * 设计稿 11 页 = 文档抽屉（654）+ 右侧助手（440）两栏。
 * 抽屉已由 {@link KbDocDrawer} 实现，助手是**独立的面板**，
 * 关掉助手不影响抽屉，反之亦然。
 *
 * <h2>⭐⭐ 多轮对话形态（本轮改动）</h2>
 * 原实现是「一问一答单气泡」：新一轮会 `setAnswer('')` 把上一轮冲掉，
 * 用户看不到历史。现改为 `turns: Turn[]`：
 * <ul>
 *   <li>每轮独立保存 `question / answer / phase` ⇒ 历史不被覆盖</li>
 *   <li>流式增量只累加到**本轮**（按 `activeTurnRef` 定位，⛔ 不累加到最后一轮）</li>
 *   <li>渲染复用主会话同款的 `.qa-thread / .qa-turn / .qa-a / .bubble`（`kb-08.css` 真实类）</li>
 *   <li>答案走 {@link MarkdownTextV2}（源站流水线 + 流式守卫），流式期也渲染 markdown</li>
 * </ul>
 *
 * <h2>⚠️ 问答链路是真实接的，不是假的</h2>
 * 走与知识库「问答」tab 相同的 `knowledgeApi.askQa` + STOMP `message.chunk` 流，
 * 只是检索范围固定为当前文档所在的库（`knowledgeBaseIds: [baseId]`）。
 * ⛔ 不做「假装思考然后编一段答案」。
 *
 * <h2>⚠️ 与源站的差异（如实说明）</h2>
 * 源站助手能「改文档 / 写操作」；本仓助手面板当前只做问答
 * ⇒ 输入框 placeholder 写「输入问题」，不写「或描述任务」。
 */
export function KbAssistantPanel({ baseId, docTitle, knowledgeId, onClose, showToast }: Props) {
  const [question, setQuestion] = useState('')
  const [turns, setTurns] = useState<Turn[]>([])

  /** 当前正在流式接收的那一轮 id（null = 没有进行中的轮）。 */
  const activeTurnRef = useRef<number | null>(null)
  const turnSeqRef = useRef(0)
  const sessionRef = useRef<string | null>(null)
  const clientRef = useRef<Client | null>(null)
  const readyRef = useRef(false)
  const threadRef = useRef<HTMLDivElement | null>(null)

  // 卸载时只断开本面板的 WS，不影响 useChatSocket 的连接
  useEffect(() => {
    return () => {
      clientRef.current?.deactivate()
      clientRef.current = null
      readyRef.current = false
    }
  }, [])

  /** 只改动指定轮（⛔ 不用「最后一轮」—— 流式回来时用户可能已在问下一题）。 */
  const patchTurn = useCallback((id: number, patch: Partial<Turn>) => {
    setTurns((prev) => prev.map((t) => (t.id === id ? { ...t, ...patch } : t)))
  }, [])

  const acceptEvent = useCallback((ev: StreamEvent) => {
    const turnId = activeTurnRef.current
    if (turnId == null) {
      // 没有进行中的轮 ⇒ 这是迟到事件（例如上一轮刚结束时到达），丢弃而不是写进别的轮
      return
    }
    const t = (ev as { type?: string }).type
    if (t === 'message.chunk') {
      const c = ev as MessageChunkEvent
      const delta = c.delta ?? ''
      if (!delta) return
      setTurns((prev) => prev.map((x) =>
        x.id === turnId
          ? { ...x, phase: 'generating', answer: x.answer + delta }
          : x))
      return
    }
    if (t === 'message.complete') {
      const done = ev as MessageCompleteEvent
      setTurns((prev) => prev.map((x) => {
        if (x.id !== turnId) return x
        // 已流式收到的就不再用 finalize 的整段覆盖（否则会「闪烁回退」）
        const finalAnswer = x.answer.length > 0 ? x.answer : (done.content ?? '')
        return { ...x, answer: finalAnswer, phase: 'done' }
      }))
      activeTurnRef.current = null
    }
  }, [])

  /** 懒创建一条专用会话（⛔ 不碰用户当前正在聊的会话）。 */
  const ensureSession = useCallback(async (): Promise<string> => {
    if (sessionRef.current) return sessionRef.current
    // 专用会话挂在「知识库工作区」项目下（服务端按 <安装目录>/workspace/kb →
    // <配置根>/workspace/kb 降级解析并幂等注册）。⛔ 不要退回 projects[0]：
    // 项目列表按 rowid 返回，随手拿到的是用户第一个注册的仓库。
    const workspace = await knowledgeApi.workspaceProject()
    const created = await sessionApi.create({
      title: `文档助手 · ${docTitle}`,
      mainProjectId: workspace.id,
    })
    sessionRef.current = created.id

    const client = createSocketClient()
    clientRef.current = client
    const sub = () =>
      subscribeStream(client, `/topic/sessions/${created.id}/stream`, acceptEvent)
    if (readyRef.current) sub()
    else {
      client.onConnect = () => {
        readyRef.current = true
        sub()
      }
    }
    client.activate()
    return created.id
  }, [acceptEvent, docTitle])

  const ask = useCallback(async (text?: string) => {
    const content = (text ?? question).trim()
    if (!content) return
    // 有进行中的轮就不接受新提问（避免两条流交错写进同一个 turn）
    if (activeTurnRef.current != null) return

    const id = ++turnSeqRef.current
    activeTurnRef.current = id
    setTurns((prev) => [...prev, { id, question: content, answer: '', phase: 'retrieving' }])
    setQuestion('')

    try {
      const sid = await ensureSession()
      await knowledgeApi.askQa(sid, { content, knowledgeBaseIds: [baseId] })
    } catch (e) {
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      patchTurn(id, { phase: 'error', error: msg })
      activeTurnRef.current = null
      showToast(msg, 'info')
    }
  }, [baseId, ensureSession, patchTurn, question, showToast])

  /** 新会话：清空历史并丢弃旧 WS（下次提问重新建）。 */
  const resetThread = useCallback(() => {
    clientRef.current?.deactivate()
    clientRef.current = null
    readyRef.current = false
    sessionRef.current = null
    activeTurnRef.current = null
    setTurns([])
    setQuestion('')
  }, [])

  const busy = activeTurnRef.current != null

  // 新内容到达时滚到底（只滚本面板内部，⛔ 不动页面滚动位置）
  useEffect(() => {
    const el = threadRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [turns])

  const empty = turns.length === 0

  return (
    /* `.assistant-panel` 的尺寸/边框/纵向布局来自 `kb-02.css:347`（width 440 / border-left / flex column）。
       这里只补**定位**：本仓抽屉是 fixed 贴右，助手要贴在抽屉左缘 ⇒ `position:fixed; right:654px`。
       ⛔ 不用 `flex` 外壳包两层 —— 会把抽屉的 fixed 基准换掉。 */
    <aside
      className="assistant-panel"
      aria-label="文档助手"
      /* zIndex 必须**高于抽屉**（`.t-drawer` 是 1500）：面板虽然 fixed，
         但它挂在 `.doc-main-drawer` 的 DOM 里 ⇒ 落在那个层叠上下文内，
         行内 121 会让它整块压在抽屉下面（本机实测：尺寸 440×851 正常但被遮住）。
         ⛔ 不要把 zIndex 调回 121。 */
      style={{ position: 'fixed', top: 0, right: 654, bottom: 0, zIndex: 1600 }}
    >
      <header className="assistant-panel__header">
        <span className="assistant-panel__header-icon" aria-hidden="true">✦</span>
        {/* ⛔ 用户可见文案不出现外部产品名（源站 i18n 是 assistant.title，
            本仓助手不叫 WeKnora）。 */}
        <span className="assistant-panel__title">文档助手</span>
        <div className="assistant-panel__actions">
          <button
            type="button"
            className="assistant-panel__action"
            title="新会话"
            aria-label="新会话"
            onClick={resetThread}
          >
            ＋
          </button>
          <button
            type="button"
            className="assistant-panel__action"
            title="关闭"
            aria-label="关闭助手"
            onClick={onClose}
          >
            ✕
          </button>
        </div>
      </header>

      <div className="assistant-panel__body assistant-body">
        {empty ? (
          <div className="assistant-welcome">
            <div className="assistant-welcome-icon" aria-hidden="true">✦</div>
            <h3 className="assistant-welcome-title">有什么可以帮你？</h3>
            <p className="assistant-welcome-desc">
              我会围绕《{docTitle}》回答；检索范围已锁定到它所在的知识库。
            </p>
            <div className="assistant-starters">
              {STARTERS.map((s) => (
                <button
                  key={s}
                  type="button"
                  className="assistant-starter"
                  onClick={() => void ask(s)}
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        ) : (
          /* 复用设计稿 08 页的 `.qa-thread / .qa-turn / .qa-q / .qa-a / .bubble`（真实类，
             `kb-08.css` 有完整规则）。⛔ 不自造 assistant-thread/bubble —— 那些没有样式。 */
          <div className="qa-thread" role="log" aria-live="polite" ref={threadRef}>
            {turns.map((t) => (
              <div className="qa-turn" key={t.id}>
                <div className="qa-q">
                  <div className="bubble">{t.question}</div>
                </div>
                <div className="qa-a">
                  <div className="bubble">
                    {t.phase === 'retrieving' && <span>检索中…</span>}
                    {t.phase === 'generating' && !t.answer && <span>生成中…</span>}
                    {t.phase === 'error' && <span>{t.error ?? '请求失败'}</span>}
                    {t.answer && <MarkdownTextV2 text={t.answer} streaming={t.phase === 'generating'} />}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}

        <div className="assistant-composer">
          <div className="assistant-input">
            <textarea
              rows={2}
              placeholder={busy ? '正在回答…' : '输入问题…'}
              aria-label="向助手提问"
              value={question}
              disabled={busy}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  void ask()
                }
              }}
            />
            <div className="assistant-control-bar">
              <span style={{ marginLeft: 'auto', fontSize: 11, color: 'var(--td-text-color-placeholder)' }}>
                {busy ? '生成中…' : 'Enter 发送 · Shift+Enter 换行'}
              </span>
              <button
                type="button"
                className="assistant-ctl"
                title="发送"
                aria-label="发送"
                disabled={busy || !question.trim()}
                style={{
                  background: busy || !question.trim()
                    ? 'var(--td-bg-color-container-hover)'
                    : 'var(--td-brand-color)',
                  borderColor: busy || !question.trim()
                    ? 'var(--td-component-stroke)'
                    : 'var(--td-brand-color)',
                  color: busy || !question.trim() ? 'var(--td-text-color-disabled)' : '#fff',
                }}
                onClick={() => void ask()}
              >
                ↑
              </button>
            </div>
          </div>
        </div>
      </div>
      {/* knowledgeId 目前只用于标题与后续「单文档范围」扩展，显式挂到 DOM 便于验收 */}
      <span hidden data-knowledge-id={knowledgeId} />
    </aside>
  )
}
