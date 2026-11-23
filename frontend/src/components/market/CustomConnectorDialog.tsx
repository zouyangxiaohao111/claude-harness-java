import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '@/api/rest'
import { mcpApi, type McpImportDiff } from '@/api/mcp'
import type { CreateMcpRequest, McpServer } from '@/api/types'
import { McpServerForm } from '@/components/mcp/McpServerForm'
import { McpServerRow } from '@/components/mcp/McpServerRow'
import './marketConnector.css'

type Mode = 'list' | 'form' | 'json'

const errMsg = (e: unknown) => (e instanceof ApiError ? e.userMessage() : String(e))

/**
 * 自定义连接器（统一入口）· 三模式都写 `mcp_servers`：
 * - 列表：现有 MCP server（排除由目录连接器授权产生的同名项，由 `catalogKeys` 传入）；
 * - 表单：新建 server（stdio/sse/http + OAuth 7 字段）；
 * - JSON：`GET /mcp/export` → 文本编辑 → `POST /mcp/import`（DB 唯一真源，非法 JSON 禁保存）。
 */
export function CustomConnectorDialog({ catalogKeys, onClose, showToast }: {
  /** 目录连接器 key（从「自定义」列表里排除，避免与内置连接器重复） */
  catalogKeys: Set<string>
  onClose: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}) {
  const [mode, setMode] = useState<Mode>('list')
  const [servers, setServers] = useState<McpServer[]>([])
  const [loading, setLoading] = useState(true)
  const [listErr, setListErr] = useState<string | null>(null)
  const [confirmDel, setConfirmDel] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  /** 展开的服务行（MCPPanel 行同款：同一时刻只展开一行） */
  const [openId, setOpenId] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    setListErr(null)
    try {
      setServers(await mcpApi.list())
    } catch (e) {
      setListErr(errMsg(e))
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => { void load() }, [])

  const customServers = useMemo(
    () => servers.filter((s) => !catalogKeys.has(s.name) && !catalogKeys.has(s.id)),
    [servers, catalogKeys],
  )

  const remove = async (s: McpServer) => {
    setBusy(true)
    try {
      await mcpApi.remove(s.id)
      setConfirmDel(null)
      await load()
      showToast(`已删除 ${s.userFacingName ?? s.name}`, 'success')
    } catch (e) {
      showToast(errMsg(e), 'info')
    } finally {
      setBusy(false)
    }
  }

  // ---------------- 表单（复用 MCPPanel 的 McpServerForm）----------------
  const toggleEnabled = async (s: McpServer) => {
    setBusy(true)
    try {
      await mcpApi.update(s.id, { enabled: !s.enabled, scope: s.scope ?? undefined })
      await load()
    } catch (e) {
      showToast(errMsg(e), 'info')
    } finally {
      setBusy(false)
    }
  }

  const submitForm = async (req: CreateMcpRequest) => {
    setBusy(true)
    try {
      await mcpApi.create(req)
      showToast(`已添加 ${req.name}`, 'success')
      setMode('list')
      await load()
    } catch (e) {
      showToast(errMsg(e), 'info')
    } finally {
      setBusy(false)
    }
  }

  // ---------------- JSON ----------------
  const [jsonText, setJsonText] = useState('')
  const [jsonErr, setJsonErr] = useState<string | null>(null)
  const [jsonLoaded, setJsonLoaded] = useState(false)
  const [deleteMissing, setDeleteMissing] = useState(false)
  const [diff, setDiff] = useState<McpImportDiff | null>(null)

  useEffect(() => {
    if (mode !== 'json' || jsonLoaded) return
    setJsonLoaded(true)
    void mcpApi.exportServers()
      .then((r) => setJsonText(JSON.stringify(r, null, 2)))
      .catch((e) => {
        // 旧后端未重启时 /mcp/export 404：给出可读错误，不静默
        setJsonErr(`导出接口不可用：${errMsg(e)}`)
      })
  }, [mode, jsonLoaded])

  /** JSON 合法性（非法即禁保存） */
  const parsedJson = useMemo((): { mcpServers: Record<string, Record<string, unknown>> } | null => {
    if (!jsonText.trim()) return null
    try {
      const v = JSON.parse(jsonText)
      if (!v || typeof v !== 'object' || Array.isArray(v)) return null
      const root = v as Record<string, unknown>
      if (!root.mcpServers || typeof root.mcpServers !== 'object' || Array.isArray(root.mcpServers)) return null
      return { mcpServers: root.mcpServers as Record<string, Record<string, unknown>> }
    } catch {
      return null
    }
  }, [jsonText])
  const jsonValid = !!parsedJson

  const saveJson = async () => {
    if (!parsedJson) { setJsonErr('JSON 不合法：需要 {"mcpServers": {...}}'); return }
    setBusy(true)
    setJsonErr(null)
    try {
      const r = await mcpApi.importJson({ mcpServers: parsedJson.mcpServers, deleteMissing })
      setDiff(r)
      showToast(`已导入：新增 ${r.added} / 更新 ${r.updated} / 删除 ${r.removed}`, 'success')
      await load()
    } catch (e) {
      setJsonErr(errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="mc-scrim" onClick={onClose} />
      <div className="mc-dlg" role="dialog" aria-modal="true" aria-label="自定义连接器" data-testid="custom-connector-dialog">
        <div className="mc-dh">
          <h4>自定义连接器</h4>
          <button className="mc-x" onClick={onClose} aria-label="关闭">✕</button>
        </div>

        <div className="mc-dlg-tabs">
          {([['list', '服务列表'], ['form', '表单添加'], ['json', 'JSON 导入导出']] as const).map(([v, l]) => (
            <button
              key={v}
              className={`mc-cat${mode === v ? ' active' : ''}`}
              onClick={() => setMode(v)}
            >
              {l}
            </button>
          ))}
        </div>

        <div className="mc-db">
          {mode === 'list' && (
            loading ? <div className="sm-loading">加载中…</div>
              : listErr ? <div className="mc-err">{listErr}</div>
              : customServers.length === 0
                ? <div className="sm-empty small">还没有自定义连接器 · 切到「表单添加」新建</div>
                : (
                  <div className="cli-list" data-testid="custom-connector-list">
                    {customServers.map((s) => (
                      <McpServerRow
                        key={s.id}
                        server={s}
                        testId="custom-connector-row"
                        open={openId === s.id}
                        pending={busy}
                        onToggleOpen={() => setOpenId(openId === s.id ? null : s.id)}
                        onToggleEnabled={() => void toggleEnabled(s)}
                      >
                        <div className="mcp-detail">
                          <div className="mcp-detail-cmd" title={(s.type === 'stdio' ? s.command : s.url) ?? undefined}>
                            {s.type === 'stdio' ? `Command: ${s.command ?? ''}` : `URL: ${s.url ?? ''}`}
                          </div>
                          <div className="mcp-detail-row">
                            <div>
                              <div className="mcp-detail-label">作用域</div>
                              <div className="mcp-detail-desc">{s.scope ?? 'project'} · {s.type ?? 'stdio'}</div>
                            </div>
                          </div>
                          <div className="mcp-detail-foot">
                            <span className="spacer" />
                            {confirmDel === s.id ? (
                              <>
                                <button className="cli-card-action" disabled={busy} onClick={() => void remove(s)}>确认删除</button>
                                <button className="cli-card-action" onClick={() => setConfirmDel(null)}>取消</button>
                              </>
                            ) : (
                              <button className="mcp-text-btn danger" onClick={() => setConfirmDel(s.id)}>删除</button>
                            )}
                          </div>
                        </div>
                      </McpServerRow>
                    ))}
                  </div>
                )
          )}

          {mode === 'form' && (
            <McpServerForm
              pending={busy}
              onSubmit={(req) => void submitForm(req)}
              onCancel={() => setMode('list')}
              showToast={showToast}
              submitLabel="添加"
              footerClass="mc-df"
            />
          )}

          {mode === 'json' && (
            <>
              <div className="mc-hint" style={{ marginBottom: 8 }}>
                与「设置 → MCP」同一份 DB 数据；导出已掩码，密钥字段留空/掩码表示保留原值。非法 JSON 无法保存。
              </div>
              <textarea
                className="mc-inp mc-json"
                data-testid="custom-connector-json"
                rows={14}
                value={jsonText}
                onChange={(e) => { setJsonText(e.target.value); setDiff(null); if (jsonErr) setJsonErr(null) }}
                spellCheck={false}
              />
              {jsonErr && <div className="mc-err" data-testid="custom-connector-json-err">{jsonErr}</div>}
              {!jsonErr && jsonText.trim() && !jsonValid && (
                <div className="mc-err" data-testid="custom-connector-json-err">JSON 格式不合法（需要 mcpServers 对象）</div>
              )}
              {diff && (
                <div className="mc-authhint" data-testid="custom-connector-diff">
                  新增 {diff.added} · 更新 {diff.updated} · 删除 {diff.removed} · 未变 {diff.unchanged}
                </div>
              )}
              <label className="mc-hint" style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                <input type="checkbox" checked={deleteMissing} onChange={(e) => setDeleteMissing(e.target.checked)} />
                删除 JSON 中不存在的 project/user/local server（危险）
              </label>
            </>
          )}
        </div>

        {mode !== 'form' && (
        <div className="mc-df">
          <button className="mc-btn" onClick={onClose}>关闭</button>
          {mode === 'json' && (
            <button
              className="mc-btn primary"
              data-testid="custom-connector-json-save"
              disabled={busy || !jsonValid}
              onClick={() => void saveJson()}
            >
              {busy ? '导入中…' : '导入'}
            </button>
          )}
        </div>
        )}
      </div>
    </>
  )
}
