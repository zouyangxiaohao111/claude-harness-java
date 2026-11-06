import { useRef, useState } from 'react'
import { ApiError } from '@/api/rest'
import { knowledgeApi } from '@/api/knowledge'

interface Props {
  baseId: string
  /** 当前所在文件夹（新上传的文档会落到这里） */
  folderPath: string
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 上传完成回调（刷新列表/文件夹树） */
  onDone: () => void
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

  const fileRef = useRef<HTMLInputElement | null>(null)
  const dirRef = useRef<HTMLInputElement | null>(null)

  const toMessage = (e: unknown) =>
    e instanceof ApiError ? e.userMessage() : e instanceof Error ? e.message : String(e)

  const handleFiles = async (list: FileList | null) => {
    if (!list || list.length === 0) return
    setBusy(true)
    let ok = 0
    const failures: string[] = []
    try {
      for (const f of Array.from(list)) {
        try {
          await knowledgeApi.uploadKnowledge(baseId, f)
          ok++
        } catch (e) {
          failures.push(`${f.name}: ${toMessage(e)}`)
        }
      }
    } finally {
      if (fileRef.current) fileRef.current.value = ''
      setBusy(false)
      if (ok > 0) showToast(`已上传 ${ok} 个文件，正在解析`, 'success')
      if (failures.length > 0) showToast(failures.join('；'), 'info')
      onDone()
    }
  }

  const handleFolder = async (list: FileList | null) => {
    if (!list || list.length === 0) return
    setBusy(true)
    try {
      const r = await knowledgeApi.uploadFolder(baseId, Array.from(list), folderPath)
      if (r.ok > 0) showToast(`已上传 ${r.ok} 个文件（保留目录结构），正在解析`, 'success')
      if (r.failures.length > 0) showToast(r.failures.join('；'), 'info')
      onDone()
    } finally {
      if (dirRef.current) dirRef.current.value = ''
      setBusy(false)
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
