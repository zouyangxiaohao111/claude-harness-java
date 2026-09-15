import { useEffect, useState } from 'react'
import { api } from '@/api/rest'
import { API_BASE } from '@/api/base'

/**
 * 子代理面板（FNT-SUB-02）· 调 GET /api/agents（text/plain，后端 AgentsHandler 暴露）。
 * 展示后端格式化的 agent 列表（"{N} active agents" + 分组行），整块原样呈现。
 * craft 风格：SVG 图标 + 1 条提示文案；复用 fm-* 弹窗结构。
 *
 * <p>传当前活动会话 sessionId ⇒ 后端走**会话视图**（该会话冻结启动目录下的 agent-defs，
 * 对齐 CC getAgentDefinitionsWithOverrides(cwd)）；不传 ⇒ 进程默认（workspaceDir）+
 * 后端 ≥WARN（多项目部署下可能与当前会话不一致）。
 */
const AGENTS_BASE = API_BASE

export function AgentsPanel({ onClose, sessionId }: { onClose: () => void; sessionId?: string | null }) {
  const [text, setText] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setError(null)
    // ⭐ 有活动会话就**总是传**（用户裁定：「理论上前端不该不传递 sessionId」）——
    //   不传则后端只能给进程默认（workspaceDir）视图，多项目部署下与当前会话不一致。
    //   ⚠️ 后端该参数仍**可选**（缺参 ⇒ 进程默认 + ≥WARN，不 400）：该端点还有非前端调用方
    //      （curl / `claude agents` 等价命令），CC 那边这命令本来就是无会话的 ⇒ 前端总传 + 后端容缺。
    const qs = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
    api<string>(`/agents${qs}`, {}, AGENTS_BASE)
      .then((t) => { if (!cancelled) setText(t) })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)) })
    return () => { cancelled = true }
  }, [sessionId])

  return (
    <div className="fm-backdrop" onClick={onClose}>
      <div className="fm-modal" style={{ width: 520 }} onClick={(e) => e.stopPropagation()}>
        <div className="fm-header">
          <svg width={16} height={16} viewBox="0 0 14 14" fill="none" stroke="var(--accent)" strokeWidth={1.5} style={{ flexShrink: 0 }}>
            <circle cx="4.5" cy="4.5" r="2" />
            <path d="M1 9.5c0-1.5 1.6-2.5 3.5-2.5s3.5 1 3.5 2.5" />
            <circle cx="10.5" cy="5" r="1.5" />
            <path d="M8 9.3c.2-1 1.2-1.8 2.5-1.8 1.3 0 2.5.8 2.5 1.8" />
          </svg>
          <div className="fm-title">子代理</div>
          <span className="fm-subtitle">/agents</span>
        </div>
        <div className="fm-body">
          <div className="fm-field-hint">列出当前配置的子代理，以及它们的模型与记忆规模。</div>
          {error ? (
            <div className="fm-field-hint error">{error}</div>
          ) : text === null ? (
            <div className="fm-field-hint">加载中…</div>
          ) : (
            <pre
              style={{
                margin: 0,
                padding: '12px 14px',
                background: 'var(--surface-2)',
                border: '1px solid var(--hairline)',
                borderRadius: 'var(--r-md)',
                fontFamily: 'var(--font-mono)',
                fontSize: 12,
                lineHeight: 1.6,
                color: 'var(--ink-muted)',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
              }}
            >
              {text}
            </pre>
          )}
        </div>
        <div className="fm-footer" style={{ justifyContent: 'flex-end' }}>
          <button className="fm-btn" onClick={onClose}>关闭</button>
        </div>
      </div>
    </div>
  )
}
