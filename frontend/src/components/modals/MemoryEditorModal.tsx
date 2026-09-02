import { useCallback, useEffect, useMemo, useState } from 'react'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { listMemoryFiles, saveMemoryFile } from '@/api/memory'
import type { MemoryFileEntry } from '@/api/memory'
import { ApiError } from '@/api/rest'

interface MemoryEditorModalProps {
  onClose: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
  sessionId?: string
}

type ViewMode = 'edit' | 'preview' | 'split'

const VIEW_TABS: { id: ViewMode; label: string }[] = [
  { id: 'edit', label: '编辑' },
  { id: 'preview', label: '预览' },
  { id: 'split', label: '分屏' },
]

/** type 分组顺序（下拉 optgroup）：User / Project / Managed（只读） */
const TYPE_GROUPS: MemoryFileEntry['type'][] = ['User', 'Project', 'Managed']

/** option 唯一键 = `type|file`（file 为相对路径，不再用绝对 path 作键） */
const entryKey = (f: MemoryFileEntry) => `${f.type}|${f.file}`

/** 记忆编辑器 · 对齐设计稿 960px 弹窗（mem-* 类）。工具栏下拉按 type 分组选文件，编辑/预览/分屏三视图，
 *  底部保存走 PUT type 语义覆盖写（无 upsert；editable=false 只读禁用保存）。 */
export function MemoryEditorModal({ onClose, showToast, sessionId }: MemoryEditorModalProps) {
  const [files, setFiles] = useState<MemoryFileEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selectedKey, setSelectedKey] = useState('')
  const [draft, setDraft] = useState('')
  const [mode, setMode] = useState<ViewMode>('split')
  const [saving, setSaving] = useState(false)

  // 挂载/切换会话时拉取记忆文件列表（type 三档视图）；失败 fail loud（error 态 + 关闭按钮仍可用）
  const load = useCallback(() => {
    let cancelled = false
    setLoading(true)
    setError('')
    listMemoryFiles(sessionId)
      .then((list) => {
        if (cancelled) return
        setFiles(list)
        // 默认选第一个已存在的文件载入 draft；全为缺失槽位则取首项
        const first = list.length > 0 ? (list.find((f) => f.exists) ?? list[0]) : null
        setSelectedKey(first ? entryKey(first) : '')
        setDraft(first?.content ?? '')
      })
      .catch((e) => {
        if (cancelled) return
        setError(e instanceof ApiError ? e.userMessage() : String(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sessionId])

  useEffect(() => load(), [load])

  // 选中项（按 type|file 键反查）；只读判定：Managed 恒只读（契约明示）；User 主文件恒可编辑（用户拍板）；
  // Project 依赖后端 editable
  const selected = files.find((f) => entryKey(f) === selectedKey) ?? null
  const readOnly = !!selected && (selected.type === 'Managed' || (selected.type === 'Project' && !selected.editable))

  // 预览 = marked 渲染 + DOMPurify 清洗（防 XSS）；marked.parse 同步路径返回 string，cast 兼容 async 联合类型
  const previewHtml = useMemo(() => DOMPurify.sanitize(marked.parse(draft) as string), [draft])

  const handlePick = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const key = e.target.value
    setSelectedKey(key)
    const file = files.find((f) => entryKey(f) === key)
    setDraft(file?.content ?? '')
  }

  const handleSave = async () => {
    if (!selected || readOnly || saving) return
    setSaving(true)
    try {
      await saveMemoryFile({ type: selected.type, file: selected.file, content: draft, sessionId })
      showToast('记忆已保存', 'success')
      onClose()
    } catch (e) {
      // 403/404 均 fail loud：Managed 只读 / exists=false 槽位无 upsert → 后端错误直接提示
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="mem-backdrop" onClick={onClose}>
      <div className="mem-modal" onClick={(e) => e.stopPropagation()}>
        <div className="mem-header">
          <span className="mem-title">记忆编辑</span>
          <span className="mem-badge">.md</span>
          <span className="mem-subtitle">全局记忆文件 · 用于指导 AI 的长期行为与偏好</span>
          <button className="mem-close" onClick={onClose} aria-label="关闭">✕</button>
        </div>
        <div className="mem-toolbar">
          <select
            className="mem-file-picker"
            value={selectedKey}
            onChange={handlePick}
            disabled={files.length === 0}
            aria-label="选择记忆文件"
          >
            {TYPE_GROUPS.map((type) => {
              const group = files.filter((f) => f.type === type)
              if (group.length === 0) return null
              return (
                <optgroup key={type} label={type}>
                  {group.map((f) => (
                    <option key={entryKey(f)} value={entryKey(f)}>
                      {f.file}
                      {f.exists ? '' : ' (new)'}
                      {f.type === 'Managed' || (f.type === 'Project' && !f.editable) ? ' (只读)' : ''}
                    </option>
                  ))}
                </optgroup>
              )
            })}
          </select>
          <div className="view-tabs">
            {VIEW_TABS.map((t) => (
              <button
                key={t.id}
                className={`view-tab${mode === t.id ? ' active' : ''}`}
                onClick={() => setMode(t.id)}
              >
                {t.label}
              </button>
            ))}
          </div>
          <span className="mem-toolbar-info">{readOnly ? '只读文件 · 保存已禁用' : '支持 Markdown 格式'}</span>
        </div>
        <div className="mem-body">
          {loading ? (
            <div className="mem-state">加载中…</div>
          ) : error ? (
            <div className="mem-state mem-state-error">
              <span>加载失败：{error}</span>
              <button className="mem-retry-btn" onClick={load}>重试</button>
            </div>
          ) : files.length === 0 ? (
            <div className="mem-state">未发现记忆文件</div>
          ) : (
            <>
              <div className={`editor-pane${mode === 'preview' ? ' hidden' : ''}`}>
                <textarea
                  className="mem-textarea"
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  spellCheck={false}
                  disabled={readOnly}
                />
              </div>
              <div className={`preview-pane${mode === 'edit' ? ' hidden' : ''}`}>
                <div className="md-preview" dangerouslySetInnerHTML={{ __html: previewHtml }} />
              </div>
            </>
          )}
        </div>
        <div className="mem-footer">
          <span className="mem-footer-note">💾 修改将持久化到本地数据库</span>
          <div className="mem-footer-actions">
            <button className="mem-cancel-btn" onClick={onClose}>取消</button>
            <button className="mem-save-btn" disabled={saving || !selected || readOnly} onClick={handleSave}>保存修改</button>
          </div>
        </div>
      </div>
    </div>
  )
}
