import { useState, type ReactNode } from 'react'
import { ApiError } from '@/api/rest'
import { exportApi, doctorApi } from '@/api/business'
import type { DoctorCheck, DoctorReport } from '@/api/types'

interface BusinessPanelProps {
  showToast: (msg: string, type?: 'success' | 'info') => void
}

/* ------------------------------------------------------------------ */
/*  局部 SVG 图标（跟随 DatabasePanel / SchedulesPanel 的局部图标风格）    */
/* ------------------------------------------------------------------ */

const ExportIcon = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 13, height: 13 }}>
    <path d="M7 1.5V8M4 5L7 8L10 5" />
    <path d="M2 10.5H12" />
  </svg>
)
const DoctorIcon = () => (
  <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 13, height: 13 }}>
    <path d="M1.5 8H3.5L5 3.5L7.5 10.5L9 7H12.5" />
  </svg>
)

/** 区块标题 · 对齐 fm-section-title 视觉（serif + 小字号），以 SVG 图标替代点号 */
function SectionHeader({ icon, title, hint }: { icon: ReactNode; title: string; hint?: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8 }}>
      <span style={{ color: 'var(--accent)', display: 'inline-flex' }}>{icon}</span>
      <span
        style={{
          fontSize: 10.5,
          color: 'var(--ink-tertiary)',
          fontWeight: 600,
          fontFamily: 'var(--font-serif)',
          letterSpacing: '0.4px',
        }}
      >
        {title}
      </span>
      {hint && (
        <span style={{ color: 'var(--ink-faint)', fontSize: 10.5, fontFamily: 'var(--font-mono)', marginLeft: 2 }}>
          {hint}
        </span>
      )}
    </div>
  )
}

const errMsg = (e: unknown) => (e instanceof ApiError ? e.userMessage() : String(e))

/* ------------------------------------------------------------------ */
/*  诊断结果                                                        */
/* ------------------------------------------------------------------ */

const CHECK_COLOR: Record<DoctorCheck['status'], string> = {
  pass: 'var(--success-dark)',
  fail: 'var(--error)',
  warn: 'var(--warning)',
}
const CHECK_LABEL: Record<DoctorCheck['status'], string> = {
  pass: '通过',
  fail: '失败',
  warn: '警告',
}

/** detail 键值展示：大数字按字节缩写、小数按百分比，其余原样 */
function fmtValue(v: unknown): string {
  if (v == null) return '—'
  if (typeof v === 'boolean') return v ? '是' : '否'
  if (typeof v === 'number') {
    if (v > 1024 * 1024) return `${(v / (1024 * 1024)).toFixed(1)} MB`
    if (v > 1024) return `${(v / 1024).toFixed(1)} KB`
    if (v > 0 && v < 1) return `${(v * 100).toFixed(0)}%`
    return String(v)
  }
  return String(v)
}

function DoctorReportView({ report }: { report: DoctorReport }) {
  const ok = report.status === 'ok'
  return (
    <div style={{ marginTop: 10 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <span
          className="db-status"
          style={
            ok
              ? { background: 'rgba(93,184,114,0.12)', color: 'var(--success-dark)' }
              : { background: 'rgba(232,172,71,0.14)', color: 'var(--warning)' }
          }
        >
          {ok ? '一切正常' : '部分降级'}
        </span>
        {report.warnings.length > 0 && (
          <span className="db-user">{report.warnings.length} 条警告</span>
        )}
      </div>

      {report.checks.map((c) => (
        <div key={c.name} className="db-row" style={{ marginBottom: 6, alignItems: 'flex-start' }}>
          <div className="db-info">
            <div className="db-name">{c.name}</div>
            <div className="db-detail" style={{ flexWrap: 'wrap', gap: '3px 10px' }}>
              {Object.entries(c.detail).map(([k, v]) => (
                <span key={k}>
                  <span className="db-user">{k}:</span> {fmtValue(v)}
                </span>
              ))}
            </div>
          </div>
          <div className="db-meta">
            <span className="db-status" style={{ background: 'var(--surface-2)', color: CHECK_COLOR[c.status] }}>
              {CHECK_LABEL[c.status]}
            </span>
          </div>
        </div>
      ))}

      {report.warnings.length > 0 && (
        <div style={{ fontSize: 11, color: 'var(--warning)', lineHeight: 1.5, marginTop: 6 }}>
          {report.warnings.map((w) => (
            <div key={w}>· {w}</div>
          ))}
        </div>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  业务面板 · Branch / Export / Doctor                              */
/* ------------------------------------------------------------------ */

/**
 * BusinessPanel · Phase 5 业务模块
 *
 * <p>分支：列出 worktree 分支，支持创建 / 保留 / 删除（对齐 BranchController）。
 * <p>导出：按会话 ID 触发 markdown 导出下载 / 复制 / 分享（对齐 ExportController）。
 * <p>诊断：运行 doctor 检查 git、路径、运行时环境并展示结果（对齐 DoctorController）。
 */
export function BusinessPanel({ showToast }: BusinessPanelProps) {
  const [sessionId, setSessionId] = useState('')
  const [exporting, setExporting] = useState(false)

  const [report, setReport] = useState<DoctorReport | null>(null)
  const [doctorLoading, setDoctorLoading] = useState(false)

  const onExport = async () => {
    const id = sessionId.trim()
    if (!id) {
      showToast('先填会话 ID', 'info')
      return
    }
    setExporting(true)
    try {
      const { filename, content } = await exportApi.downloadMarkdown(id)
      const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
      showToast(`已导出 ${filename}`, 'success')
    } catch (e) {
      showToast(`导出失败：${errMsg(e)}`, 'info')
    } finally {
      setExporting(false)
    }
  }

  const onCopy = async () => {
    const id = sessionId.trim()
    if (!id) {
      showToast('先填会话 ID', 'info')
      return
    }
    setExporting(true)
    try {
      const r = await exportApi.copy(id)
      showToast(`已复制到剪贴板（${r.messages} 条消息）`, 'success')
    } catch (e) {
      showToast(`复制失败：${errMsg(e)}`, 'info')
    } finally {
      setExporting(false)
    }
  }

  const onShare = async () => {
    const id = sessionId.trim()
    if (!id) {
      showToast('先填会话 ID', 'info')
      return
    }
    setExporting(true)
    try {
      const r = await exportApi.share(id)
      showToast(`分享链接已生成：${r.shareUrl}`, 'success')
    } catch (e) {
      showToast(`分享失败：${errMsg(e)}`, 'info')
    } finally {
      setExporting(false)
    }
  }

  const onDiagnose = async () => {
    setDoctorLoading(true)
    try {
      setReport(await doctorApi.diagnose())
    } catch (e) {
      setReport(null)
      showToast(`诊断失败：${errMsg(e)}`, 'info')
    } finally {
      setDoctorLoading(false)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      {/* 导出（worktree 分支管理已移入右侧「项目」tab，此处聚焦扫描/导出） */}
      <section>
        <SectionHeader icon={<ExportIcon />} title="导出" />
        <div className="settings-row-desc" style={{ marginBottom: 8 }}>
          把会话渲染为 Markdown，便于归档与分享
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <input
            className="fm-input"
            style={{ flex: 1, minWidth: 0 }}
            value={sessionId}
            placeholder="会话 ID，如 8f3a…"
            onChange={(e) => setSessionId(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') void onExport() }}
            disabled={exporting}
          />
          <button
            className="fm-btn primary"
            onClick={() => void onExport()}
            disabled={exporting || !sessionId.trim()}
            style={{ flexShrink: 0 }}
          >
            导出 Markdown
          </button>
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
          <button className="db-test" onClick={() => void onCopy()} disabled={exporting || !sessionId.trim()}>
            复制
          </button>
          <button className="db-test" onClick={() => void onShare()} disabled={exporting || !sessionId.trim()}>
            分享链接
          </button>
        </div>
      </section>

      <div className="fm-section-divider" style={{ margin: '0 -22px' }} />

      {/* 诊断 */}
      <section>
        <SectionHeader icon={<DoctorIcon />} title="诊断" />
        <div className="settings-row-desc" style={{ marginBottom: 8 }}>
          检查 git、工作目录与运行时环境，定位常见问题
        </div>
        <button className="fm-btn primary" onClick={() => void onDiagnose()} disabled={doctorLoading}>
          {doctorLoading ? '诊断中…' : '运行诊断'}
        </button>
        {report && <DoctorReportView report={report} />}
        {!report && !doctorLoading && (
          <div className="fm-model-empty" style={{ padding: '18px 12px', textAlign: 'center' }}>
            尚未运行诊断 · 点上方按钮开始
          </div>
        )}
      </section>
    </div>
  )
}
