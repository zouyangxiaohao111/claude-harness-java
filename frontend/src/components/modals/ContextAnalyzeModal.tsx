import { useEffect, useState } from 'react'
import { contextApi } from '@/api/context'
import type { ContextAnalyzeResponse } from '@/api/types'

const fmt = (n: number) => (n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n))

/** /context analyze 展示 · 对齐 CC analyzeContextUsage 分类计数（system/memory/tools） */
export function ContextAnalyzeModal({ onClose }: { onClose: () => void }) {
  const [data, setData] = useState<ContextAnalyzeResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    contextApi.analyze()
      .then((d) => { if (!cancelled) setData(d) })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  return (
    <div className="ca-backdrop" onClick={onClose}>
      <div className="ca-modal" onClick={(e) => e.stopPropagation()}>
        <div className="ca-header">
          <span className="ca-title">上下文分析</span>
          <button className="ca-close" onClick={onClose} aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
              <path d="M2 2L10 10M10 2L2 10" />
            </svg>
          </button>
        </div>
        <div className="ca-body">
          {loading ? (
            <div className="right-empty">分析中…</div>
          ) : error ? (
            <div className="right-empty" style={{ color: 'var(--error)' }}>分析失败：{error}</div>
          ) : data ? (
            <>
              <div className="ca-total">
                系统提示词 <b>{fmt(data.systemPromptTokens)}</b> tokens
              </div>
              <div className="ca-section">
                <div className="ca-section-title">System Prompt 分节</div>
                {(data.systemPromptSections ?? []).map((s) => (
                  <div key={s.name} className="ca-row">
                    <span className="ca-name">{s.name}</span>
                    <span className="ca-tokens">{fmt(s.tokens)}</span>
                  </div>
                ))}
                {(data.systemPromptSections ?? []).length === 0 && <div className="right-empty">无分节数据</div>}
              </div>
              <div className="ca-section">
                <div className="ca-section-title">CLAUDE.md</div>
                <div className="ca-row"><span className="ca-name">CLAUDE.md</span><span className="ca-tokens">{fmt(data.claudeMdTokens)}</span></div>
              </div>
              <div className="ca-section">
                <div className="ca-section-title">记忆文件</div>
                {(data.memoryFiles ?? []).map((f) => (
                  <div key={f.path} className="ca-row">
                    <span className="ca-path">{f.path} <em>{f.type}</em></span>
                    <span className="ca-tokens">{fmt(f.tokens)}</span>
                  </div>
                ))}
                {(data.memoryFiles ?? []).length === 0 && <div className="right-empty">无记忆文件</div>}
              </div>
              <div className="ca-section">
                <div className="ca-section-title">工具</div>
                <div className="ca-row"><span className="ca-name">内置工具 builtInToolTokens</span><span className="ca-tokens">{fmt(data.builtInToolTokens)}</span></div>
                <div className="ca-row"><span className="ca-name">MCP 工具 mcpToolTokens</span><span className="ca-tokens">{fmt(data.mcpToolTokens)}</span></div>
              </div>
              {data.categories && data.categories.length > 0 && (
                <div className="ca-section">
                  <div className="ca-section-title">分类（categories · 扣减值承载）</div>
                  {data.categories.map((c) => (
                    <div key={c.name} className="ca-row">
                      <span className="ca-name" style={{ color: c.color ?? undefined }}>{c.name}</span>
                      <span className="ca-tokens">{fmt(c.tokens)}</span>
                    </div>
                  ))}
                </div>
              )}
              {data.skills && (
                <div className="ca-section">
                  <div className="ca-section-title">技能</div>
                  <div className="ca-row"><span className="ca-name">技能数 {data.skills.totalSkills} / {data.skills.includedSkills}</span><span className="ca-tokens">{fmt(data.skills.tokens)}</span></div>
                </div>
              )}
            </>
          ) : null}
        </div>
      </div>
    </div>
  )
}
