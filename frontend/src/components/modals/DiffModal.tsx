import { useEffect, useRef, useState } from 'react'
import type { DiffFile, DiffHunk } from '@/types'
import { monaco, monacoLangOf, MONACO_COMMON_OPTS } from '@/utils/monaco'

interface DiffModalProps {
  diff: DiffFile | null
  close: () => void
}

/** 从 hunks 重建 original/modified 两侧全文（Monaco Diff Editor 需要完整两侧文本） */
function rebuildSides(hunks: DiffHunk[]): { original: string; modified: string } {
  const o: string[] = []
  const m: string[] = []
  for (const hunk of hunks) {
    for (const line of hunk.lines) {
      if (line.type === 'ctx') { o.push(line.text); m.push(line.text) }
      else if (line.type === 'del') o.push(line.text)
      else if (line.type === 'add') m.push(line.text)
    }
  }
  return { original: o.join('\n'), modified: m.join('\n') }
}

/** 文件 diff 查看（Monaco Diff Editor · 并排/统一切换 · 行内高亮 + 行号 + 折叠导航） */
export function DiffModal({ diff, close }: DiffModalProps) {
  const boxRef = useRef<HTMLDivElement>(null)
  const diffRef = useRef<monaco.editor.IStandaloneDiffEditor | null>(null)
  const [sideBySide, setSideBySide] = useState(true)

  useEffect(() => {
    if (!boxRef.current || !diff) return
    const { original, modified } = rebuildSides(diff.hunks)
    const ed = monaco.editor.createDiffEditor(boxRef.current, {
      readOnly: true,
      renderSideBySide: true,
      ...MONACO_COMMON_OPTS,
    })
    ed.setModel({
      original: monaco.editor.createModel(original, monacoLangOf(diff.path)),
      modified: monaco.editor.createModel(modified, monacoLangOf(diff.path)),
    })
    diffRef.current = ed
    return () => { ed.dispose(); diffRef.current = null }
  }, [diff])

  useEffect(() => {
    diffRef.current?.updateOptions({ renderSideBySide: sideBySide })
  }, [sideBySide])

  if (!diff) return null
  return (
    <div className="diff-backdrop" onClick={close}>
      <div className="diff-modal" onClick={(e) => e.stopPropagation()}>
        <div className="diff-header">
          <div className="diff-file-info">
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '14px', height: '14px' }}>
              <path d="M2 2H8L12 6V12H2Z" />
              <path d="M8 2V6H12" />
            </svg>
            <span className="diff-file-name">{diff.name}</span>
            <span className="diff-file-path">{diff.path}</span>
            {diff.isNew && <span className="diff-new-badge">NEW</span>}
          </div>
          <div className="diff-stats">
            <span className="diff-adds">+{diff.adds}</span>
            <span className="diff-dels">−{diff.dels}</span>
          </div>
          <button className="diff-close" onClick={close}>
            <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '14px', height: '14px' }}>
              <path d="M3 3L11 11M11 3L3 11" />
            </svg>
          </button>
        </div>
        <div className="diff-toolbar">
          <div className="diff-toolbar-group">
            <button className={`diff-tbtn${sideBySide ? ' active' : ''}`} onClick={() => setSideBySide(true)} title="并排对比">
              <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '10px', height: '10px' }}>
                <path d="M1 1H5V11H1ZM7 1H11V11H7Z" />
              </svg>
              并排
            </button>
            <button className={`diff-tbtn${!sideBySide ? ' active' : ''}`} onClick={() => setSideBySide(false)} title="统一视图">
              <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: '10px', height: '10px' }}>
                <path d="M1 1H11V11H1Z" />
              </svg>
              统一
            </button>
          </div>
          <div className="diff-toolbar-spacer"></div>
        </div>
        <div className="diff-body monaco-diff-body">
          <div ref={boxRef} className="monaco-diff-box" />
        </div>
        <div className="diff-footer">
          <span>Esc 关闭</span>
          <span>{diff.path}</span>
        </div>
      </div>
    </div>
  )
}
