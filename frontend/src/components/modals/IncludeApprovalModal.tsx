import { useState } from 'react'
import { approveInclude } from '@/api/claudeMd'
import { ApiError } from '@/api/rest'

interface IncludeApprovalModalProps {
  /** 待审批的外部 @import 文件路径列表 */
  files: string[]
  /** 审批结果回传（true=确认允许 / false=拒绝）· 仅 API 成功后调用 */
  onApprove: (approved: boolean) => void
  onClose: () => void
}

/** 文档 icon（每行文件名左侧）· 对齐设计稿「功能3：外部 include 审批」file-item */
function DocIcon() {
  return (
    <svg className="ia-file-icon" viewBox="0 0 12 14" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M2 1.2C2 0.9 2.2 0.7 2.5 0.7H7.5L10 3.2V12.8C10 13.1 9.8 13.3 9.5 13.3H2.5C2.2 13.3 2 13.1 2 12.8V1.2Z" />
      <path d="M7.5 0.7V3.2H10" />
    </svg>
  )
}

/** 关闭 icon（右上角）· 与其他 modal 统一 */
function CloseIcon() {
  return (
    <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
      <path d="M2 2L10 10M10 2L2 10" />
    </svg>
  )
}

/**
 * CLAUDE.md 外部 include 审批弹窗（功能3）。
 * 结构对齐设计稿：overlay / modal / icon(📄) / title / desc / file-list(file-item) / btn-group(btn-reject · btn-allow)。
 * 底部按钮：拒绝灰底、确认允许橙渐变 #FF7A3D。
 * 提交走 POST /api/v1/claude-md/include-approval；失败 fail loud（ApiError.userMessage() 内联展示，弹窗不关闭）。
 */
export function IncludeApprovalModal({ files, onApprove, onClose }: IncludeApprovalModalProps) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const submit = async (approved: boolean) => {
    if (submitting) return
    setSubmitting(true)
    setError('')
    try {
      await approveInclude(approved)
      onApprove(approved)
      onClose()
    } catch (e) {
      // fail loud：审批失败停留弹窗，展示后端错误（400 approved 缺失 / 500 引擎未接线）
      setError(e instanceof ApiError ? e.userMessage() : String(e))
      setSubmitting(false)
    }
  }

  return (
    <div className="ia-backdrop" onClick={onClose}>
      <div className="ia-modal" onClick={(e) => e.stopPropagation()}>
        <div className="ia-header">
          <span className="ia-icon">📄</span>
          <span className="ia-title">外部文件引用审批</span>
          <button className="ia-close" onClick={onClose} aria-label="关闭">
            <CloseIcon />
          </button>
        </div>
        <div className="ia-desc">
          CLAUDE.md 尝试引用以下外部文件。请确认是否允许 AI 读取并加载这些内容？
        </div>
        <div className="ia-file-list">
          {files.length === 0 ? (
            <div className="ia-file-empty">无待审批的外部文件</div>
          ) : (
            files.map((f) => (
              <div key={f} className="ia-file-item">
                <DocIcon />
                <span className="ia-file-name" title={f}>{f}</span>
              </div>
            ))
          )}
        </div>
        {error && <div className="ia-error">{error}</div>}
        <div className="ia-btn-group">
          <button className="ia-btn-reject" disabled={submitting} onClick={() => submit(false)}>拒绝</button>
          <button className="ia-btn-allow" disabled={submitting} onClick={() => submit(true)}>确认允许</button>
        </div>
      </div>
    </div>
  )
}
