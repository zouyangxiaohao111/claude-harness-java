import { useState } from 'react'
import { ApiError } from '@/api/rest'
import { mcpApi } from '@/api/mcp'
import type { McpServer } from '@/api/types'
import { useOAuthStartPoll } from '@/hooks/useOAuthStartPoll'
import { openExternalUrl } from '@/utils/openExternal'

/**
 * MCP 行详情里的「OAuth 授权 / 断开」块。
 *
 * <p>与连接器抽屉共用 {@link useOAuthStartPoll}（start→系统浏览器→轮询），但**不复用 DOM**：
 * 这里是设置页 `cli-*` 风格，不引入市场抽屉的 `mc-` 组件。
 *
 * <p>断开会吊销 token 并清凭据（后端 `DELETE /mcp/{id}/oauth`），属破坏性动作 ⇒ 行内二次确认。
 */
export function McpOauthRow({ server, statusLabel, showToast, onChanged }: {
  server: McpServer
  /** 行摘要已有的状态中文（避免本组件重复维护一份状态文案表） */
  statusLabel: string
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 授权成功 / 断开后刷新列表（runtimeStatus 由后端现算） */
  onChanged: () => void | Promise<void>
}) {
  const name = server.userFacingName ?? server.name
  const [confirmDisc, setConfirmDisc] = useState(false)
  const [busy, setBusy] = useState(false)
  const [discErr, setDiscErr] = useState<string | null>(null)

  const oauth = useOAuthStartPoll({
    start: () => mcpApi.oauthStart(server.id),
    poll: (state) => mcpApi.oauthPoll(server.id, state),
    onAuthorized: async () => {
      await onChanged()
      showToast(`已授权：${name}`, 'success')
    },
    onOpenFailed: () => showToast('授权页未能自动打开，请点「打开授权页」', 'info'),
  })

  const copy = async (text: string) => {
    try {
      await navigator.clipboard?.writeText(text)
      showToast('已复制回调地址', 'success')
    } catch {
      showToast('复制失败，请手动选中复制', 'info')
    }
  }

  const disconnect = async () => {
    setBusy(true)
    setDiscErr(null)
    try {
      const r = await mcpApi.oauthLogout(server.id)
      if (!r.revoked) {
        setDiscErr('后端未确认断开，请重试')
        return
      }
      oauth.reset()
      setConfirmDisc(false)
      await onChanged()
      showToast(`已断开：${name}`, 'success')
    } catch (e) {
      setDiscErr(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setBusy(false)
    }
  }

  const waiting = oauth.phase === 'starting' || oauth.phase === 'waiting'

  return (
    <div className="mcp-detail-row" data-testid="mcp-oauth-row">
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="mcp-detail-label">OAuth 授权</div>
        <div className="mcp-detail-desc">
          当前状态：{statusLabel}
          {oauth.phase === 'ok' && ' · 本次授权已完成'}
        </div>

        {oauth.err && <div className="mcp-detail-error">✗ {oauth.err}</div>}
        {discErr && <div className="mcp-detail-error">✗ {discErr}</div>}

        {oauth.auth && (
          <>
            <div className="mcp-detail-desc" style={{ wordBreak: 'break-all', marginTop: 4 }}>
              授权页：{oauth.auth.authorizeUrl}
            </div>
            <div className="mcp-detail-desc" style={{ wordBreak: 'break-all', marginTop: 2 }} data-testid="mcp-oauth-callback">
              回调地址：<code>{oauth.auth.redirectUri}</code>
              <button
                className="cli-card-action"
                style={{ marginLeft: 6 }}
                onClick={() => void copy(oauth.auth!.redirectUri)}
                disabled={busy}
              >
                复制
              </button>
              <button
                className="cli-card-action"
                style={{ marginLeft: 6 }}
                onClick={() => void openExternalUrl(oauth.auth!.authorizeUrl)}
                disabled={busy}
              >
                打开授权页
              </button>
            </div>
          </>
        )}
      </div>

      <div style={{ display: 'flex', gap: 6, alignItems: 'flex-start' }}>
        <button className="cli-card-action" onClick={() => void oauth.begin()} disabled={waiting || busy}>
          {oauth.phase === 'starting' ? '发起中…' : oauth.phase === 'waiting' ? '等待授权…' : '授权'}
        </button>
        {confirmDisc ? (
          <>
            <button className="cli-card-action" style={{ color: 'var(--error)' }} onClick={() => void disconnect()} disabled={busy}>
              确认断开
            </button>
            <button className="cli-card-action" onClick={() => setConfirmDisc(false)} disabled={busy}>取消</button>
          </>
        ) : (
          <button className="cli-card-action" onClick={() => setConfirmDisc(true)} disabled={busy}>
            断开
          </button>
        )}
      </div>
    </div>
  )
}
