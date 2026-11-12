import { useEffect, useRef, useState } from 'react'
import { ApiError } from '@/api/rest'
import {
  UPLOAD_CONCURRENCY,
  buildUploadFileName,
  isParseCompleted,
  knowledgeApi,
  needsParsePolling,
  pollParseStatuses,
  type UploadManyItem,
  type UploadSettledItem,
} from '@/api/knowledge'

interface Props {
  baseId: string
  /** 当前所在文件夹（新上传的文档会落到这里） */
  folderPath: string
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 上传完成回调（刷新列表/文件夹树） */
  onDone: () => void
}

/** 面板里一条文件的状态（`uploading` 之外的都已经有结局） */
type TrackState = 'uploading' | 'uploaded' | 'failed' | 'duplicate' | 'cancelled'

interface TrackedUpload {
  /** 面板内稳定的行键（⛔ 不用文件名：目录上传可能重名） */
  key: string
  name: string
  /** 原始 item —— item 级重试直接把它喂回并发池 */
  item: UploadManyItem
  state: TrackState
  message?: string
  knowledgeId?: string
}

/**
 * 上传来源下拉 · 对齐源侧 `KbUploadSourceDropdown.vue`。
 *
 * <h2>源侧支持四种来源</h2>
 * <ol>
 *   <li><b>文件</b>（多选）</li>
 *   <li><b>文件夹</b>（`webkitdirectory`，保留目录结构 ⇒ 子文件夹的唯一来源）</li>
 *   <li><b>URL 导入</b>（后端 `POST /knowledge-bases/{id}/knowledge/url`）</li>
 *   <li><b>手工录入</b>（后端 `POST /knowledge-bases/{id}/knowledge/manual`）</li>
 * </ol>
 *
 * <h2>⚠️ 上次实现漏了后两种</h2>
 * 上一版只有「上传文件」和「上传文件夹」两个按钮，URL 导入与手工录入完全没做 ——
 * 而后端端点早就存在（`KnowledgeExtController`）。这里补齐。
 */
export function KbUploadSource({ baseId, folderPath, showToast, onDone }: Props) {
  const [open, setOpen] = useState(false)
  const [urlOpen, setUrlOpen] = useState(false)
  const [url, setUrl] = useState('')
  const [manualOpen, setManualOpen] = useState(false)
  const [manualTitle, setManualTitle] = useState('')
  const [manualContent, setManualContent] = useState('')
  const [busy, setBusy] = useState(false)
  /** 当前批次进度 */
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null)
  /**
   * 本批文件逐条状态（含失败原因 / 重复 / 已取消 / 解析结果）。
   *
   * <p>为什么不是「失败清单」：item 级重试要拿到**那一条**的原始 item，
   * 而重复项要单列（⛔ 不能混进失败）。一个数组三种视图，判据在 `state`。
   */
  const [tracked, setTracked] = useState<TrackedUpload[]>([])
  /** 知识 id → parse_status（轮询每轮回填） */
  const [parseStatuses, setParseStatuses] = useState<Record<string, string>>({})
  /** 是否还有解析在轮询（面板据此显示解析进度） */
  const [polling, setPolling] = useState(false)

  const fileRef = useRef<HTMLInputElement | null>(null)
  const dirRef = useRef<HTMLInputElement | null>(null)
  /** 当前库 id 的最新值：上传可能跨分钟，结束时要比对「结果还属不属于当前库」 */
  const baseIdRef = useRef(baseId)
  baseIdRef.current = baseId
  /** 「取消全部」用：中断在飞的 uploadMany 请求 */
  const uploadAbortRef = useRef<AbortController | null>(null)
  /** 解析轮询的取消句柄（新批次/切库/关闭面板时停掉上一轮） */
  const pollAbortRef = useRef<AbortController | null>(null)
  /** 本批上传成功的知识 id（跨多次重试累积） */
  const parseIdsRef = useRef<Set<string>>(new Set())
  const parseStatusesRef = useRef<Record<string, string>>({})
  const keySeqRef = useRef(0)

  const toMessage = (e: unknown) =>
    e instanceof ApiError ? e.userMessage() : e instanceof Error ? e.message : String(e)

  const failed = tracked.filter((t) => t.state === 'failed')
  const duplicates = tracked.filter((t) => t.state === 'duplicate')
  const cancelled = tracked.filter((t) => t.state === 'cancelled')
  /** 有文件正在传（URL 导入/手工录入也算 busy，但没有「取消全部」） */
  const inFlight = tracked.some((t) => t.state === 'uploading')
  /** 面板只要还有行就显示（含上传完成后的解析阶段） */
  const showResult = tracked.length > 0

  /** 本批成功上传的知识 id（解析完成度只统计这一批） */
  const uploadedIds = tracked
    .filter((t): t is TrackedUpload & { knowledgeId: string } => t.state === 'uploaded' && !!t.knowledgeId)
    .map((t) => t.knowledgeId)
  const parseDone = uploadedIds.filter((id) => isParseCompleted(parseStatuses[id])).length

  // 切换知识库时丢掉上一库的整批状态 —— 否则「重试」会把旧库的文件传进新库，
  // 解析轮询也会继续问旧库的 id
  useEffect(() => {
    pollAbortRef.current?.abort()
    pollAbortRef.current = null
    parseIdsRef.current = new Set()
    parseStatusesRef.current = {}
    setTracked([])
    setProgress(null)
    setParseStatuses({})
    setPolling(false)
  }, [baseId])

  // 卸载时停掉在飞的请求与轮询（否则组件没了还在占后端连接）
  useEffect(() => () => {
    uploadAbortRef.current?.abort()
    pollAbortRef.current?.abort()
  }, [])

  /**
   * 启动解析状态轮询（全部 settled 后自动停）。
   *
   * <p>每次上传/重试后调一次：先停上一轮（避免同一批 id 被两条循环各问一遍），
   * 再把「还没到终态」的 id 交给 {@link pollParseStatuses} —— 3000ms 间隔、
   * 40 个 id 一批、≤4 条并发，收敛即停。
   */
  const startParsePolling = () => {
    const ids = [...parseIdsRef.current].filter((id) => needsParsePolling(parseStatusesRef.current[id]))
    if (ids.length === 0) return
    pollAbortRef.current?.abort()
    const controller = new AbortController()
    pollAbortRef.current = controller
    const runFor = baseIdRef.current
    const applyUpdate = (statuses: Record<string, string>) => {
      if (controller.signal.aborted || baseIdRef.current !== runFor) return
      parseStatusesRef.current = { ...parseStatusesRef.current, ...statuses }
      setParseStatuses(parseStatusesRef.current)
      setPolling(Object.keys(statuses).some((id) => needsParsePolling(statuses[id])))
    }
    setPolling(true)
    void pollParseStatuses(ids, { signal: controller.signal, onUpdate: applyUpdate })
      .then(applyUpdate)
      .finally(() => {
        if (controller.signal.aborted || baseIdRef.current !== runFor) return
        setPolling(false)
      })
  }

  /** 把一条 settled 结果合到面板行上 */
  const applySettled = (row: TrackedUpload, s: UploadSettledItem): TrackedUpload => ({
    ...row,
    state: s.state,
    message: s.message,
    knowledgeId: s.knowledgeId ?? row.knowledgeId,
  })

  /**
   * 并发上传一批文件 · 多选文件与文件夹都走这里。
   *
   * <p>对齐源站：最多 {@link UPLOAD_CONCURRENCY}（=3）条 POST 同时在飞；
   * 单个失败不中断其它文件，失败清单留在面板里可重试。
   */
  const runUpload = async (items: UploadManyItem[], keys: string[] | null) => {
    if (items.length === 0) return
    const runFor = baseId
    const keySet = keys === null ? null : new Set(keys)
    const others = keySet === null ? [] : tracked.filter((t) => !keySet.has(t.key))
    /** 重试前已经是终态的行数（进度基数的起点） */
    const baseDone = others.filter((t) => t.state !== 'uploading').length
    const total = keySet === null ? items.length : tracked.length
    if (keySet === null) {
      setTracked(items.map((item) => ({
        key: `u-${++keySeqRef.current}`,
        name: item.fileName || item.file.name,
        item,
        state: 'uploading' as const,
      })))
    } else {
      setTracked((prev) => prev.map((t) => (
        keySet.has(t.key) ? { ...t, state: 'uploading', message: undefined } : t
      )))
    }
    setProgress({ done: baseDone, total })
    setBusy(true)
    const controller = new AbortController()
    uploadAbortRef.current = controller
    try {
      const r = await knowledgeApi.uploadMany(runFor, items, {
        concurrency: UPLOAD_CONCURRENCY,
        signal: controller.signal,
        onProgress: (done) => {
          if (baseIdRef.current === runFor) setProgress({ done: baseDone + done, total })
        },
      })
      // 上传期间切了库：结果属于旧库，不能显示在当前库的面板上（否则重试会传错库）
      if (baseIdRef.current !== runFor) return
      // `settled` 与 items 同序；重试时 keys 也按同一顺序构造 ⇒ 按下标对位回填
      if (keySet === null) {
        setTracked((prev) => prev.map((t, i) => (r.settled[i] ? applySettled(t, r.settled[i]) : t)))
      } else {
        let cursor = 0
        setTracked((prev) => prev.map((t) => (
          keySet.has(t.key) && r.settled[cursor] ? applySettled(t, r.settled[cursor++]) : t
        )))
      }
      setProgress({ done: baseDone + r.settled.length, total })
      for (const s of r.settled) {
        if (s.state === 'uploaded' && s.knowledgeId) parseIdsRef.current.add(s.knowledgeId)
      }
      startParsePolling()
      const uploadedNow = r.settled.filter((s) => s.state === 'uploaded').length
      if (uploadedNow > 0) showToast(`已上传 ${uploadedNow} 个文件，正在解析`, 'success')
      if (r.duplicates.length > 0) {
        showToast(`${r.duplicates.length} 个文件已存在（重复），已跳过`, 'info')
      }
      if (r.cancelled.length > 0) {
        showToast(`已取消 ${r.cancelled.length} 个未传完的文件`, 'info')
      }
      if (r.failures.length > 0) {
        showToast(`${r.failures.length} 个文件上传失败，可在上传结果里重试`, 'info')
      }
    } catch (e) {
      // 逐文件失败已收进 failures；能走到这里的是批次级异常
      showToast(toMessage(e), 'info')
    } finally {
      if (uploadAbortRef.current === controller) uploadAbortRef.current = null
      setBusy(false)
      onDone()
    }
  }

  /** 「取消全部」：中断在飞请求；已上传成功的不回滚 */
  const cancelAll = () => {
    uploadAbortRef.current?.abort()
  }

  /** item 级重试：只把这一条原样喂回并发池（其余行状态不变） */
  const retryOne = async (key: string) => {
    const target = tracked.find((t) => t.key === key)
    if (!target) return
    await runUpload([target.item], [key])
  }

  /** 重试全部失败项（保留上一批的能力） */
  const retryFailed = async () => {
    if (failed.length === 0) return
    await runUpload(failed.map((f) => f.item), failed.map((f) => f.key))
  }

  /** 关闭结果面板（同时停掉解析轮询） */
  const closeResult = () => {
    pollAbortRef.current?.abort()
    pollAbortRef.current = null
    parseIdsRef.current = new Set()
    parseStatusesRef.current = {}
    setPolling(false)
    setParseStatuses({})
    setTracked([])
    setProgress(null)
  }

  const handleFiles = async (list: FileList | null) => {
    if (!list || list.length === 0) return
    try {
      await runUpload(Array.from(list).map((f) => ({ file: f })), null)
    } finally {
      if (fileRef.current) fileRef.current.value = ''
    }
  }

  const handleFolder = async (list: FileList | null) => {
    if (!list || list.length === 0) return
    try {
      // ⭐ 目录名仍逐个拼进 fileName（后端据此拆 folder_path）⇒ 保留目录结构
      await runUpload(
        Array.from(list).map((f) => ({
          file: f,
          fileName: buildUploadFileName(f as File & { webkitRelativePath?: string }, folderPath),
        })),
        null
      )
    } finally {
      if (dirRef.current) dirRef.current.value = ''
    }
  }

  const submitUrl = async () => {
    const u = url.trim()
    if (!u) return
    setBusy(true)
    try {
      await knowledgeApi.importUrl(baseId, u)
      showToast('已提交，正在抓取与解析', 'success')
      setUrlOpen(false)
      setUrl('')
      onDone()
    } catch (e) {
      showToast(toMessage(e), 'info')
    } finally {
      setBusy(false)
    }
  }

  const submitManual = async () => {
    const title = manualTitle.trim()
    const content = manualContent.trim()
    if (!title || !content) {
      showToast('标题与正文都不能为空', 'info')
      return
    }
    setBusy(true)
    try {
      await knowledgeApi.createManual(baseId, { title, content })
      showToast('已创建手工知识', 'success')
      setManualOpen(false)
      setManualTitle('')
      setManualContent('')
      onDone()
    } catch (e) {
      showToast(toMessage(e), 'info')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="kb-upload">
      <input ref={fileRef} type="file" multiple hidden onChange={(e) => void handleFiles(e.target.files)} />
      <input
        ref={dirRef}
        type="file"
        multiple
        hidden
        // @ts-expect-error 非标准属性，Chrome/Edge/Safari 均支持
        webkitdirectory=""
        directory=""
        onChange={(e) => void handleFolder(e.target.files)}
      />

      <button
        type="button"
        className="kb-btn primary"
        disabled={busy}
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
      >
        {busy ? '处理中…' : '＋ 添加文档'}
      </button>

      {showResult && (
        <div className="kb-upload-result" role="status" aria-live="polite">
          <div className="kb-upload-result-head">
            <span>
              {progress ? `已完成 ${progress.done} / ${progress.total}` : '上传结果'}
              {uploadedIds.length > 0 ? `（解析完成 ${parseDone}）` : ''}
              {polling ? ' · 解析中…' : ''}
              {failed.length > 0 ? ` · ${failed.length} 个失败` : ''}
              {duplicates.length > 0 ? ` · ${duplicates.length} 个重复` : ''}
            </span>
            <div className="kb-upload-result-actions">
              {inFlight && (
                <button
                  type="button"
                  className="kb-upload-result-cancel"
                  onClick={cancelAll}
                >
                  取消全部
                </button>
              )}
              {!busy && (
                <button
                  type="button"
                  className="kb-upload-result-close"
                  aria-label="关闭上传结果"
                  onClick={closeResult}
                >
                  ✕
                </button>
              )}
            </div>
          </div>
          {failed.length > 0 && (
            <>
              {/* ⚠️ 失败清单必须逐条列出（名字 + 原因）：只弹一句「失败」用户无从下手 */}
              <ul className="kb-upload-failures">
                {failed.map((f) => (
                  <li key={f.key} title={f.message}>
                    <div className="kb-upload-fail-main">
                      <b>{f.name}</b>
                      <span>{f.message}</span>
                    </div>
                    <button
                      type="button"
                      className="kb-upload-item-retry"
                      disabled={busy}
                      onClick={() => void retryOne(f.key)}
                    >
                      重试
                    </button>
                  </li>
                ))}
              </ul>
              <button
                type="button"
                className="kb-btn ghost kb-upload-retry"
                disabled={busy}
                onClick={() => void retryFailed()}
              >
                重试失败项（{failed.length}）
              </button>
            </>
          )}
          {duplicates.length > 0 && (
            <>
              {/* 重复不是失败：后端 409 判重 ⇒ 单列「重复（已存在）」，⛔ 不给重试入口 */}
              <ul className="kb-upload-duplicates">
                {duplicates.map((d) => (
                  <li key={d.key} title={d.message}>
                    <b>{d.name}</b>
                    <span>重复（已存在）</span>
                  </li>
                ))}
              </ul>
            </>
          )}
          {cancelled.length > 0 && (
            <p className="kb-upload-cancelled-tip">已取消 {cancelled.length} 个未传完的文件</p>
          )}
        </div>
      )}

      {open && (
        <>
          <div className="kb-upload-mask" onClick={() => setOpen(false)} />
          <div className="kb-upload-menu" role="menu">
            <button
              type="button"
              role="menuitem"
              onClick={() => { setOpen(false); fileRef.current?.click() }}
            >
              <span className="ic">📄</span>
              <span>
                <b>上传文件</b>
                <em>pdf / word / excel / ppt / md / txt</em>
              </span>
            </button>
            <button
              type="button"
              role="menuitem"
              onClick={() => { setOpen(false); dirRef.current?.click() }}
            >
              <span className="ic">📁</span>
              <span>
                <b>上传文件夹</b>
                <em>保留目录结构</em>
              </span>
            </button>
            <button
              type="button"
              role="menuitem"
              onClick={() => { setOpen(false); setUrlOpen(true) }}
            >
              <span className="ic">🔗</span>
              <span>
                <b>URL 导入</b>
                <em>抓取网页正文入库</em>
              </span>
            </button>
            <button
              type="button"
              role="menuitem"
              onClick={() => { setOpen(false); setManualOpen(true) }}
            >
              <span className="ic">✎</span>
              <span>
                <b>手工录入</b>
                <em>直接写标题与正文</em>
              </span>
            </button>
          </div>
        </>
      )}

      {urlOpen && (
        <div className="kb-mini-modal" role="dialog" aria-modal="true" aria-label="URL 导入">
          <div className="kb-mini-head">
            <strong>URL 导入</strong>
            <button type="button" className="kb-mini-x" onClick={() => setUrlOpen(false)}>✕</button>
          </div>
          <div className="kb-mini-body">
            <label className="kb-mini-label">网页地址</label>
            <input
              className="kb-input"
              placeholder="https://example.com/article"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') void submitUrl() }}
              autoFocus
            />
            <p className="kb-mini-tip">会抓取该页正文并作为一条知识入库；需要该站点允许抓取。</p>
          </div>
          <div className="kb-mini-foot">
            <button type="button" className="kb-btn ghost" onClick={() => setUrlOpen(false)}>取消</button>
            <button type="button" className="kb-btn primary" disabled={busy || !url.trim()} onClick={() => void submitUrl()}>
              导入
            </button>
          </div>
        </div>
      )}

      {manualOpen && (
        <div className="kb-mini-modal kb-mini-lg" role="dialog" aria-modal="true" aria-label="手工录入">
          <div className="kb-mini-head">
            <strong>手工录入</strong>
            <button type="button" className="kb-mini-x" onClick={() => setManualOpen(false)}>✕</button>
          </div>
          <div className="kb-mini-body">
            <label className="kb-mini-label">标题</label>
            <input
              className="kb-input"
              placeholder="例如：运维值班制度"
              value={manualTitle}
              onChange={(e) => setManualTitle(e.target.value)}
              autoFocus
            />
            <label className="kb-mini-label" style={{ marginTop: 12 }}>正文</label>
            <textarea
              className="kb-input kb-mini-textarea"
              placeholder="粘贴或输入正文；保存后会重新切块并重建索引"
              value={manualContent}
              onChange={(e) => setManualContent(e.target.value)}
              rows={10}
            />
          </div>
          <div className="kb-mini-foot">
            <button type="button" className="kb-btn ghost" onClick={() => setManualOpen(false)}>取消</button>
            <button
              type="button"
              className="kb-btn primary"
              disabled={busy || !manualTitle.trim() || !manualContent.trim()}
              onClick={() => void submitManual()}
            >
              保存
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
