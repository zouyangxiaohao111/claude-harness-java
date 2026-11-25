import { useEffect, useMemo, useState } from 'react'
import { knowledgeApi, type KbFolderNode } from '@/api/knowledge'

/**
 * 目标文件夹选择器 · 对齐源站 `FolderPickerMenu.vue`（移动 / 批量移动 / 上传确认共用）。
 *
 * <p><b>⭐ 为什么要有「在此新建」</b>：本仓文件夹树是从 `kb_knowledge.folder_path` 派生的
 * 虚拟树，**空目录不存在**（见 `knowledge.ts:1201` 注释）。所以「新建文件夹」不是独立接口，
 * 而是「把文档移进一个还不存在的路径」——移动完成目录自然出现。源站同款语义。
 *
 * <p>⛔ 不在这里直接调 moveToFolder：调用方才知道要移哪些文档，本组件只负责「选一个路径」。
 */
export function FolderPicker({ baseId, title, onPick, onClose }: {
  baseId: string
  title: string
  /** 选中（或新建）一个路径；空串 = 移出文件夹（根目录）。 */
  onPick: (path: string) => void | Promise<void>
  onClose: () => void
}) {
  const [folders, setFolders] = useState<KbFolderNode[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  /** 正在新建的父路径（null = 未在新建；空串 = 在根目录新建） */
  const [newUnder, setNewUnder] = useState<string | null>(null)
  const [newName, setNewName] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let alive = true
    setLoading(true)
    knowledgeApi.listFolders(baseId)
      .then((r) => { if (alive) { setFolders(r.folders ?? []); setError(null) } })
      .catch((e) => { if (alive) setError(e instanceof Error ? e.message : String(e)) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [baseId])

  /** 展平成带深度的行（父在前、子在后）。 */
  const rows = useMemo(() => {
    const out: Array<{ node: KbFolderNode; depth: number }> = []
    const walk = (nodes: KbFolderNode[], depth: number) => {
      for (const n of nodes) {
        out.push({ node: n, depth })
        if (n.children?.length) walk(n.children, depth + 1)
      }
    }
    walk(folders, 0)
    return out
  }, [folders])

  const pick = async (path: string) => {
    if (busy) return
    setBusy(true)
    try {
      await onPick(path)
    } finally {
      setBusy(false)
    }
  }

  const submitNew = async (parent: string) => {
    const name = newName.trim().replace(/^\/+|\/+$/g, '')
    if (!name) return
    const path = parent ? `${parent}/${name}` : name
    setNewUnder(null)
    setNewName('')
    await pick(path)
  }

  const rowStyle = (depth: number): React.CSSProperties => ({
    display: 'flex', alignItems: 'center', gap: 6, padding: '6px 8px',
    paddingLeft: 8 + depth * 16, borderRadius: 6, cursor: 'pointer',
  })

  const newRow = (parent: string, depth: number) => (
    <div style={rowStyle(depth)}>
      <input
        autoFocus
        className="t-input"
        placeholder="新文件夹名"
        value={newName}
        style={{ flex: 1, minWidth: 0 }}
        onChange={(e) => setNewName(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') { e.preventDefault(); void submitNew(parent) }
          if (e.key === 'Escape') { e.stopPropagation(); setNewUnder(null); setNewName('') }
        }}
      />
      <button type="button" className="doc-filter-toggle" onClick={() => void submitNew(parent)}>
        确定
      </button>
      <button
        type="button"
        className="doc-filter-toggle"
        onClick={() => { setNewUnder(null); setNewName('') }}
      >
        取消
      </button>
    </div>
  )

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={title}
      style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.28)', zIndex: 1200,
        display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
      }}
      onClick={onClose}
    >
      <div
        style={{
          width: 420, maxWidth: '100%', maxHeight: '70vh', display: 'flex', flexDirection: 'column',
          background: 'var(--td-bg-color-container, #fff)',
          border: '1px solid var(--td-component-stroke, #e3e6ea)',
          borderRadius: 10, boxShadow: '0 12px 32px rgba(0,0,0,0.18)',
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <div style={{
          padding: '12px 16px', borderBottom: '1px solid var(--td-component-stroke, #e3e6ea)',
          fontSize: 15, fontWeight: 600,
        }}>
          {title}
        </div>

        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: 8 }}>
          {loading && <p style={{ padding: 8, color: '#646a73' }}>加载中…</p>}
          {!loading && error && <p style={{ padding: 8, color: '#d54941' }}>{error}</p>}
          {!loading && !error && (
            <>
              <div style={rowStyle(0)} onClick={() => void pick('')} title="移出文件夹（放到根目录）">
                <span style={{ flex: 1, minWidth: 0 }}>（根目录）</span>
                <button
                  type="button"
                  className="doc-filter-toggle"
                  title="在根目录新建文件夹"
                  onClick={(e) => { e.stopPropagation(); setNewUnder(''); setNewName('') }}
                >
                  ＋ 新建
                </button>
              </div>
              {newUnder === '' && newRow('', 1)}
              {rows.map(({ node, depth }) => (
                <div key={node.path}>
                  <div style={rowStyle(depth + 1)} onClick={() => void pick(node.path)} title={node.path}>
                    <span style={{
                      flex: 1, minWidth: 0, overflow: 'hidden',
                      textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                    }}>
                      {node.name}
                    </span>
                    <button
                      type="button"
                      className="doc-filter-toggle"
                      title={`在「${node.name}」下新建`}
                      onClick={(e) => { e.stopPropagation(); setNewUnder(node.path); setNewName('') }}
                    >
                      ＋ 新建
                    </button>
                  </div>
                  {newUnder === node.path && newRow(node.path, depth + 2)}
                </div>
              ))}
              {rows.length === 0 && (
                <p style={{ padding: '4px 8px', color: '#646a73' }}>
                  还没有文件夹，点「＋ 新建」建一个
                </p>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
