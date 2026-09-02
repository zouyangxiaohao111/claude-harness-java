import { useState } from 'react'
import { FormModal } from '@/components/ui/FormModal'
import { ApiError } from '@/api/rest'
import { mcpApi as mcpRestApi } from '@/api/mcp'
import type { CreateMcpRequest, McpOAuthRequest, McpServer, McpStatus } from '@/api/types'
import type { UseMcp } from '@/hooks/useMcp'
import { parseMcpCommandLine } from '@/utils/mcpCliParse'

interface MCPPanelProps {
  /** 真实后端 CRUD API */
  mcpApi: UseMcp
  showToast: (msg: string, type?: 'success' | 'info') => void
}

type Transport = 'stdio' | 'sse' | 'http'
type Scope = 'local' | 'user' | 'project'

const TRANSPORT_TYPES: { value: Transport; label: string }[] = [
  { value: 'stdio', label: 'stdio' },
  { value: 'sse', label: 'sse' },
  { value: 'http', label: 'http' },
]

const SCOPE_OPTIONS: { value: Scope; label: string }[] = [
  { value: 'local', label: 'local' },
  { value: 'user', label: 'user' },
  { value: 'project', label: 'project' },
]

/** 添加/编辑表单本地 state（传输类型切换联动 Command / URL + Headers/OAuth 行） */
interface McpFormState {
  name: string
  type: Transport
  scope: Scope
  command: string
  argsStr: string
  envStr: string
  url: string
  headersJson: string
  oauthClientId: string
  oauthCallbackPort: string
  oauthMetadataUrl: string
  oauthXaa: boolean
  clientSecret: string
}

const EMPTY_FORM: McpFormState = {
  name: '', type: 'stdio', scope: 'project',
  command: '', argsStr: '', envStr: '',
  url: '', headersJson: '',
  oauthClientId: '', oauthCallbackPort: '', oauthMetadataUrl: '', oauthXaa: false,
  clientSecret: '',
}

const isTransport = (t: string | undefined): t is Transport =>
  t === 'stdio' || t === 'sse' || t === 'http'
const isScope = (s: string | null | undefined): s is Scope =>
  s === 'local' || s === 'user' || s === 'project'

/** 审批态徽标（对齐 McpServerDto.approvalStatus） */
const APPROVAL_LABEL: Record<string, { cls: string; label: string }> = {
  approved: { cls: 'approved', label: 'approved' },
  rejected: { cls: 'rejected', label: 'rejected' },
  pending: { cls: 'pending', label: 'pending' },
}

/** 运行态徽标（对齐 McpServerDto.status：running/stopped/error） */
const STATUS_LABEL: Record<McpStatus, { cls: string; label: string }> = {
  running: { cls: 'running', label: '运行中' },
  stopped: { cls: 'stopped', label: '已停止' },
  error: { cls: 'error', label: '错误' },
  pending: { cls: 'pending', label: '待审批' },
}

/**
 * 连接状态徽标（设计稿「功能4」三态：connected 绿 / needs-auth 黄 / failed 红）· FM-3 预留。
 * <p>后端仅暴露运行时 status，暂无 needs-auth 分类（FM-8 半程，见 docs/后端待提供能力清单.md）——
 * 此处按设计稿做展示位映射：running → connected；error → failed；stopped 落入 needs-auth。
 * 待后端定案连接状态投递通道后替换为真实字段。
 */
const CONN_STATE: Record<McpStatus, { cls: 'connected' | 'needs-auth' | 'failed'; label: string }> = {
  running: { cls: 'connected', label: 'connected' },
  stopped: { cls: 'needs-auth', label: 'needs-auth' },
  error: { cls: 'failed', label: 'failed' },
  pending: { cls: 'needs-auth', label: 'needs-auth' },
}

const parseArgs = (raw: string): string[] => raw.split(/\s+/).filter(Boolean)

/** 把 "K=V\nK=V" 解析为对象；非法行（无 =）返回 null 触发 toast */
const parseKvLines = (raw: string): Record<string, string> | null => {
  const out: Record<string, string> = {}
  for (const line of raw.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed) continue
    const eq = trimmed.indexOf('=')
    if (eq <= 0) return null
    out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1)
  }
  return out
}

/** 解析 JSON 对象文本；空串返回 undefined（不发送），非法 JSON / 非对象返回 null 触发 toast */
const parseJsonObject = (raw: string): Record<string, string> | undefined | null => {
  if (!raw.trim()) return undefined
  try {
    const parsed = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null
    return parsed
  } catch {
    return null
  }
}

/**
 * MCPPanel · 按设计稿「功能4」+ 后端 MCP 契约（McpServerController / McpCreateRequest 11 字段）重构
 *
 * <p>顶部「添加新服务器」表单卡（橙底 #FFF3EB）：名称 + 传输类型 select（切换联动 Command /
 * URL+Headers+OAuth）+ 作用域。下方「已配置的服务器」列表：transport / scope / approvalStatus /
 * status / 连接状态（预留）徽标 + enabled 开关 + 启停/测试/去授权(预留)/编辑/删除。
 * <p>导入 .mcp.json + 待审批弹窗（approve/reject）。CRUD 复用 useMcp hook。
 */
export function MCPPanel({ mcpApi, showToast }: MCPPanelProps) {
  const { list, loading, error, refresh, createMcp, updateMcp, deleteMcp, toggleMcp } = mcpApi

  const [form, setForm] = useState<McpFormState>(EMPTY_FORM)
  const [editing, setEditing] = useState<McpServer | null>(null)
  const [cliLine, setCliLine] = useState('')
  const [pending, setPending] = useState(false)
  const [importing, setImporting] = useState(false)
  const [importDetail, setImportDetail] = useState<{ blocked: string[]; suppressed: string[] } | null>(null)
  const [approving, setApproving] = useState(false)
  const [selected, setSelected] = useState<string[]>([])

  const set = (patch: Partial<McpFormState>) => setForm((p) => ({ ...p, ...patch }))

  const wrap = async (fn: () => Promise<unknown>, successMsg: string) => {
    setPending(true)
    try {
      await fn()
      showToast(successMsg, 'success')
    } catch (e) {
      // G5-4：统一 toast · 后端 Problem detail 即友好文案（409 冲突/重复名 与 400 校验失败分类）
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      showToast(`${msg}`, 'info')
    } finally {
      setPending(false)
    }
  }

  const resetForm = () => {
    setForm(EMPTY_FORM)
    setEditing(null)
  }

  /** 命令行快速添加：解析 `claude mcp add ...` / 纯命令 → 填入表单（失败 toast；成功清掉 OAuth 残留回到新建态） */
  const onCliParse = () => {
    const parsed = parseMcpCommandLine(cliLine)
    if (!parsed.ok) {
      showToast(parsed.error, 'info')
      return
    }
    setForm({
      ...parsed.form,
      oauthClientId: '', oauthCallbackPort: '', oauthMetadataUrl: '', oauthXaa: false,
      clientSecret: '',
    })
    setEditing(null)
    setCliLine('')
    showToast('已填入表单，确认后点添加服务器', 'success')
  }

  const buildOAuth = (): McpOAuthRequest | undefined => {
    const clientId = form.oauthClientId.trim()
    if (!clientId && !form.oauthCallbackPort.trim() && !form.oauthMetadataUrl.trim() && !form.oauthXaa) {
      return undefined
    }
    return {
      clientId: clientId || undefined,
      callbackPort: form.oauthCallbackPort.trim() || undefined,
      authServerMetadataUrl: form.oauthMetadataUrl.trim() || undefined,
      xaa: form.oauthXaa || undefined,
    }
  }

  /** 表单 → 请求体：stdio 提交 command/args/env；sse/http 提交 url/headers/oauth/clientSecret。校验失败返回 null。 */
  const buildRequest = (): CreateMcpRequest | null => {
    const name = form.name.trim()
    if (!name) {
      showToast('请填写服务器名称', 'info')
      return null
    }
    const base: CreateMcpRequest = {
      name,
      type: form.type,
      scope: form.scope,
      enabled: editing ? editing.enabled : true,
    }
    if (form.type === 'stdio') {
      if (!form.command.trim()) {
        showToast('请填写 Command', 'info')
        return null
      }
      const env = parseKvLines(form.envStr)
      if (env === null) {
        showToast('环境变量格式错误：每行应为 KEY=VALUE', 'info')
        return null
      }
      return { ...base, command: form.command.trim(), args: parseArgs(form.argsStr), env }
    }
    if (!form.url.trim()) {
      showToast('请填写 URL', 'info')
      return null
    }
    const headers = parseJsonObject(form.headersJson)
    if (headers === null) {
      showToast('Headers 需为 JSON 对象，如 {"Authorization":"Bearer xxx"}', 'info')
      return null
    }
    const oauth = buildOAuth()
    const req: CreateMcpRequest = { ...base, url: form.url.trim(), headers, oauth }
    if (oauth?.clientId && form.clientSecret.trim()) req.clientSecret = form.clientSecret.trim()
    return req
  }

  const onAdd = () => {
    const req = buildRequest()
    if (!req) return
    void wrap(async () => {
      await createMcp(req)
      resetForm()
    }, `已添加: ${req.name}`)
  }

  const onSaveEdit = () => {
    const req = buildRequest()
    if (!req || !editing) return
    void wrap(async () => {
      await updateMcp(editing.id, req)
      resetForm()
    }, `已更新: ${req.name}`)
  }

  const startEdit = (m: McpServer) => {
    const type: Transport = isTransport(m.type) ? m.type : 'stdio'
    const scope: Scope = isScope(m.scope) ? m.scope : 'project'
    const oauth = (m.oauth ?? null) as McpOAuthRequest | null
    setForm({
      name: m.userFacingName ?? m.name,
      type,
      scope,
      command: type === 'stdio' ? m.command : '',
      argsStr: m.args ? m.args.join(' ') : '',
      envStr: m.env ? Object.entries(m.env).map(([k, v]) => `${k}=${v}`).join('\n') : '',
      url: type === 'stdio' ? '' : (m.url ?? ''),
      headersJson: m.headers ? JSON.stringify(m.headers, null, 2) : '',
      oauthClientId: oauth?.clientId ?? '',
      oauthCallbackPort: oauth?.callbackPort != null ? String(oauth.callbackPort) : '',
      oauthMetadataUrl: oauth?.authServerMetadataUrl ?? '',
      oauthXaa: !!oauth?.xaa,
      clientSecret: '',
    })
    setEditing(m)
  }

  // ── 每行操作：启停 / 测试 / 删除 / enabled 开关 ──
  const onToggle = (m: McpServer) => {
    const display = m.userFacingName ?? m.name
    void wrap(() => toggleMcp(m.id, !m.enabled), `${display} ${m.enabled ? '已停用' : '已启用'}`)
  }

  const onStart = (m: McpServer) => {
    void wrap(async () => {
      // start 端点有 enabled 门（disabled → 409）：未启用先 PATCH enabled=true（后端自动 start），已启用直接调 start
      if (!m.enabled) await toggleMcp(m.id, true)
      else await mcpRestApi.start(m.id)
      await refresh()
    }, `已启动: ${m.userFacingName ?? m.name}`)
  }

  const onStop = (m: McpServer) => {
    void wrap(async () => {
      await mcpRestApi.stop(m.id)
      await refresh()
    }, `已停止: ${m.userFacingName ?? m.name}`)
  }

  /** 测试连接：toast 展示 ok/latencyMs 或错误 message */
  const onTest = (m: McpServer) => {
    void (async () => {
      setPending(true)
      try {
        const res = await mcpRestApi.test(m.id)
        if (res.ok) showToast(`测试通过 · 延迟 ${res.latencyMs}ms`, 'success')
        else showToast(`测试失败: ${res.message}`, 'info')
      } catch (e) {
        const msg = e instanceof ApiError ? e.userMessage() : String(e)
        showToast(`${msg}`, 'info')
      } finally {
        setPending(false)
      }
    })()
  }

  const onRemove = (m: McpServer) => {
    const display = m.userFacingName ?? m.name
    if (!confirm(`删除 MCP server "${display}"？`)) return
    void wrap(() => deleteMcp(m.id), `已删除: ${display}`)
  }

  // ── 导入 .mcp.json + 待审批（FM-1：pending 行必须经 approve 才能 start） ──
  const pendingList = list.filter((m) => m.approvalStatus === 'pending')

  const parseScopeLines = (raw: string): Record<string, string> | null => {
    const out: Record<string, string> = {}
    for (const line of raw.split(/\r?\n/)) {
      const trimmed = line.trim()
      if (!trimmed) continue
      const eq = trimmed.indexOf('=')
      if (eq <= 0) return null
      out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim()
    }
    return out
  }

  const onImport = (form: { lines: string }) => {
    const files = parseScopeLines(form.lines)
    if (files === null) {
      showToast('格式错误：每行应为 scope=路径', 'info')
      return
    }
    setPending(true)
    ;(async () => {
      try {
        const res = await mcpRestApi.import({ files })
        setImporting(false)
        await refresh()
        setImportDetail(
          res.blocked.length > 0 || res.suppressed.length > 0
            ? { blocked: res.blocked, suppressed: res.suppressed }
            : null
        )
        const parts = [`已导入 ${res.imported} 个 MCP server`]
        if (res.blocked.length > 0) parts.push(`blocked(${res.blocked.length})`)
        if (res.suppressed.length > 0) parts.push(`suppressed(${res.suppressed.length})`)
        showToast(parts.join(' · '), 'success')
      } catch (e) {
        const msg = e instanceof ApiError ? e.userMessage() : String(e)
        showToast(`${msg}`, 'info')
      } finally {
        setPending(false)
      }
    })()
  }

  const toggleSelect = (id: string) =>
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))

  const onApprove = (ids: string[]) => {
    if (ids.length === 0) return
    void wrap(async () => {
      for (const id of ids) await mcpRestApi.approve(id)
      setApproving(false)
      await refresh()
    }, `已通过 ${ids.length} 个 MCP server`)
  }

  const onReject = (ids: string[]) => {
    if (ids.length === 0) return
    void wrap(async () => {
      for (const id of ids) await mcpRestApi.reject(id)
      setApproving(false)
      await refresh()
    }, `已拒绝 ${ids.length} 个 MCP server`)
  }

  const enabledCount = list.filter((m) => m.enabled).length

  return (
    <div className="mcp-list">
      {/* ── 顶部操作行 ── */}
      <div className="settings-row" style={{ borderBottom: 'none', paddingBottom: 4 }}>
        <div>
          <div className="settings-row-label">MCP 服务器</div>
          <div className="settings-row-desc">
            Model Context Protocol 工具接入 · {enabledCount}/{list.length} 已启用
            {pendingList.length > 0 && ` · ${pendingList.length} 个待审批`}
            {loading && ' · 加载中…'}
            {error && <span style={{ color: 'var(--error)', marginLeft: 8 }}>· {error}</span>}
          </div>
        </div>
        <div className="settings-row" style={{ gap: 6, marginLeft: 'auto' }}>
          {pendingList.length > 0 && (
            <button
              className="settings-add-btn"
              onClick={() => {
                setSelected(pendingList.map((m) => m.id))
                setApproving(true)
              }}
              disabled={pending}
            >
              待审批 ({pendingList.length})
            </button>
          )}
          <button className="settings-add-btn" onClick={() => setImporting(true)} disabled={pending}>
            导入 .mcp.json
          </button>
        </div>
      </div>

      {/* ── 添加 / 编辑 服务器表单卡（设计稿「功能4」· 橙底 #FFF3EB） ── */}
      <div className="mcp-add-card">
        <div className="mcp-add-title">{editing ? '编辑服务器' : '添加新服务器'}</div>
        {!editing && (
          <div className="mcp-cli-row">
            <input
              className="settings-input mcp-cli-input"
              value={cliLine}
              onChange={(e) => setCliLine(e.target.value)}
              placeholder="claude mcp add <name> -- npx -y @modelcontextprotocol/server-filesystem /tmp"
              onKeyDown={(e) => { if (e.key === 'Enter') onCliParse() }}
              disabled={pending}
            />
            <button className="mcp-cli-btn" onClick={onCliParse} disabled={pending}>
              解析填入
            </button>
          </div>
        )}
        <div className="mcp-add-form">
          <div className="mcp-add-row">
            <label>服务器名称</label>
            <input
              className="settings-input"
              value={form.name}
              onChange={(e) => set({ name: e.target.value })}
              placeholder="e.g. filesystem"
              disabled={pending}
            />
          </div>
          <div className="mcp-add-row">
            <label>传输类型</label>
            <select
              className="settings-input"
              value={form.type}
              onChange={(e) => set({ type: e.target.value as Transport, url: '', headersJson: '' })}
              disabled={pending}
            >
              {TRANSPORT_TYPES.map((t) => (
                <option key={t.value} value={t.value}>{t.label}</option>
              ))}
            </select>
            <label className="mcp-add-inline-label">作用域</label>
            <select
              className="settings-input"
              value={form.scope}
              onChange={(e) => set({ scope: e.target.value as Scope })}
              disabled={pending}
            >
              {SCOPE_OPTIONS.map((s) => (
                <option key={s.value} value={s.value}>{s.label}</option>
              ))}
            </select>
          </div>
          {form.type === 'stdio' ? (
            <>
              <div className="mcp-add-row">
                <label>Command</label>
                <input
                  className="settings-input"
                  value={form.command}
                  onChange={(e) => set({ command: e.target.value })}
                  placeholder="npx -y @modelcontextprotocol/server-filesystem /tmp"
                  disabled={pending}
                />
              </div>
              <div className="mcp-add-row">
                <label>Args</label>
                <input
                  className="settings-input"
                  value={form.argsStr}
                  onChange={(e) => set({ argsStr: e.target.value })}
                  placeholder="-y @modelcontextprotocol/server-fs /tmp"
                  disabled={pending}
                />
              </div>
              <div className="mcp-add-row">
                <label>Env</label>
                <textarea
                  className="settings-input mcp-add-textarea"
                  value={form.envStr}
                  onChange={(e) => set({ envStr: e.target.value })}
                  placeholder={'API_KEY=xxx\nLOG_LEVEL=info'}
                  rows={2}
                  disabled={pending}
                />
              </div>
            </>
          ) : (
            <>
              <div className="mcp-add-row">
                <label>URL</label>
                <input
                  className="settings-input"
                  value={form.url}
                  onChange={(e) => set({ url: e.target.value })}
                  placeholder="https://mcp.example.com/sse"
                  disabled={pending}
                />
              </div>
              <div className="mcp-add-row">
                <label>Headers JSON</label>
                <textarea
                  className="settings-input mcp-add-textarea"
                  value={form.headersJson}
                  onChange={(e) => set({ headersJson: e.target.value })}
                  placeholder='{ "Authorization": "Bearer xxxxx" }'
                  rows={2}
                  disabled={pending}
                />
              </div>
              <div className="mcp-add-subtitle">OAuth（可选）</div>
              <div className="mcp-add-row">
                <label>Client ID</label>
                <input
                  className="settings-input"
                  value={form.oauthClientId}
                  onChange={(e) => set({ oauthClientId: e.target.value })}
                  placeholder="e.g. my-app-client"
                  disabled={pending}
                />
              </div>
              <div className="mcp-add-row">
                <label>Callback Port</label>
                <input
                  className="settings-input"
                  value={form.oauthCallbackPort}
                  onChange={(e) => set({ oauthCallbackPort: e.target.value })}
                  placeholder="8080"
                  disabled={pending}
                />
              </div>
              <div className="mcp-add-row">
                <label>Metadata URL</label>
                <input
                  className="settings-input"
                  value={form.oauthMetadataUrl}
                  onChange={(e) => set({ oauthMetadataUrl: e.target.value })}
                  placeholder="https://auth.example.com/.well-known/openid-configuration"
                  disabled={pending}
                />
              </div>
              <div className="mcp-add-row">
                <label>XAA</label>
                <label className="settings-switch" style={{ marginTop: 3 }}>
                  <input
                    type="checkbox"
                    checked={form.oauthXaa}
                    onChange={(e) => set({ oauthXaa: e.target.checked })}
                    disabled={pending}
                  />
                  <span></span>
                </label>
              </div>
              <div className="mcp-add-row">
                <label>Client Secret</label>
                <input
                  className="settings-input"
                  value={form.clientSecret}
                  onChange={(e) => set({ clientSecret: e.target.value })}
                  placeholder="仅当填写 OAuth Client ID 时生效"
                  disabled={pending}
                />
              </div>
            </>
          )}
        </div>
        <div className="mcp-add-actions">
          {editing && (
            <button className="mcp-add-btn ghost" onClick={resetForm} disabled={pending}>
              取消
            </button>
          )}
          <button className="mcp-add-btn primary" onClick={editing ? onSaveEdit : onAdd} disabled={pending}>
            {editing ? '保存修改' : '添加服务器'}
          </button>
        </div>
      </div>

      {/* ── 已配置的服务器 列表 ── */}
      <div className="settings-row" style={{ borderBottom: 'none', paddingBottom: 4, marginTop: 6 }}>
        <div>
          <div className="settings-row-label">已配置的服务器</div>
          <div className="settings-row-desc">
            共 {list.length} 个 · 每行可启停 / 测试 / 编辑 / 删除
          </div>
        </div>
      </div>

      {importDetail && (importDetail.blocked.length > 0 || importDetail.suppressed.length > 0) && (
        <div className="fm-model-empty" style={{ padding: '12px 14px', textAlign: 'left' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
            <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--ink)' }}>导入明细</span>
            <button className="skill-remove" onClick={() => setImportDetail(null)} title="关闭">
              ×
            </button>
          </div>
          {importDetail.blocked.length > 0 && (
            <div style={{ marginBottom: importDetail.suppressed.length > 0 ? 8 : 0 }}>
              <div style={{ fontSize: 10.5, fontWeight: 500, color: 'var(--error-dark)' }}>
                blocked · 策略拦截未导入（{importDetail.blocked.length}）
              </div>
              <div style={{ fontFamily: 'var(--font-mono)', fontSize: 10.5, color: 'var(--ink-subtle)', marginTop: 3, wordBreak: 'break-all', lineHeight: 1.5 }}>
                {importDetail.blocked.join('、')}
              </div>
            </div>
          )}
          {importDetail.suppressed.length > 0 && (
            <div>
              <div style={{ fontSize: 10.5, fontWeight: 500, color: 'var(--ink-muted)' }}>
                suppressed · scope 去重已跳过（{importDetail.suppressed.length}）
              </div>
              <div style={{ fontFamily: 'var(--font-mono)', fontSize: 10.5, color: 'var(--ink-subtle)', marginTop: 3, wordBreak: 'break-all', lineHeight: 1.5 }}>
                {importDetail.suppressed.join('、')}
              </div>
            </div>
          )}
        </div>
      )}

      {list.length === 0 && !loading && !error && (
        <div className="fm-model-empty" style={{ padding: '28px 12px', textAlign: 'center' }}>
          暂无已配置的服务器 · 在上方表单添加
        </div>
      )}

      {list.map((m) => {
        const type = m.type ?? 'stdio'
        const remote = type !== 'stdio'
        const approval = APPROVAL_LABEL[m.approvalStatus ?? 'approved'] ?? APPROVAL_LABEL.approved
        const status = STATUS_LABEL[m.status]
        const conn = CONN_STATE[m.status]
        const desc = remote
          ? `URL: ${m.url ?? m.command}`
          : `Command: ${m.command}${m.args && m.args.length > 0 ? ' ' + m.args.join(' ') : ''}`
        const display = m.userFacingName ?? m.name
        return (
          <div key={m.id} className={`mcp-row ${m.enabled ? '' : 'disabled'}`}>
            <div className="mcp-info">
              <div className="mcp-name">
                <span>{display}</span>
                <span className={`mcp-badge transport ${remote ? 'remote' : 'stdio'}`}>{type}</span>
              </div>
              <div className="mcp-badges">
                <span className={`mcp-badge scope ${remote ? 'remote' : 'local'}`}>{remote ? 'Remote' : 'Local'}</span>
                <span className={`mcp-badge approval ${approval.cls}`}>{approval.label}</span>
                <span className={`mcp-badge status ${status.cls}`}>{status.label}</span>
                <span
                  className={`mcp-badge conn ${conn.cls}`}
                  title="连接状态（FM-3 预留，后端未定案；当前由运行态映射展示）"
                >
                  <span className="mcp-badge-dot" />
                  {conn.label}
                </span>
              </div>
              <div className="mcp-cmd">{desc}</div>
              {m.lastError && (
                <div className="mcp-env" style={{ color: 'var(--error)' }}>✗ {m.lastError}</div>
              )}
            </div>
            <div className="mcp-meta" style={{ flexWrap: 'wrap', justifyContent: 'flex-end' }}>
              <label className="settings-switch" title={m.enabled ? '停用' : '启用'}>
                <input type="checkbox" checked={m.enabled} onChange={() => onToggle(m)} disabled={pending} />
                <span></span>
              </label>
              {m.status === 'running' ? (
                <button className="mcp-text-btn" onClick={() => onStop(m)} disabled={pending}>
                  停止
                </button>
              ) : (
                <button
                  className="mcp-text-btn"
                  onClick={() => onStart(m)}
                  disabled={pending || m.status === 'pending'}
                  title={m.status === 'pending' ? '待审批，需先通过后才能启动' : '启动'}
                >
                  启动
                </button>
              )}
              <button className="mcp-text-btn" onClick={() => onTest(m)} disabled={pending}>
                测试
              </button>
              <button className="mcp-text-btn" disabled title="去授权（FM-4 预留，后端未定案）">
                去授权
              </button>
              <button className="mcp-text-btn" onClick={() => startEdit(m)} disabled={pending}>
                编辑
              </button>
              <button className="mcp-text-btn danger" onClick={() => onRemove(m)} disabled={pending}>
                删除
              </button>
            </div>
          </div>
        )
      })}

      {/* ── 导入 .mcp.json 弹窗 ── */}
      {importing && (
        <FormModal
          title="导入 .mcp.json"
          subtitle="每行一个 scope=文件路径（local/project/user/enterprise）"
          initial={{ lines: '' }}
          sections={[
            {
              title: '文件映射',
              fields: [
                { type: 'textarea', name: 'lines', label: 'scope=路径', placeholder: 'project=D:/myproj/.mcp.json', rows: 3, hint: '导入后 pending server 进入待审批，需手动确认' },
              ],
            },
          ]}
          onSave={onImport}
          onCancel={() => setImporting(false)}
        />
      )}

      {/* ── 待审批弹窗（FM-1） ── */}
      {approving && (
        <div className="fm-backdrop" onClick={() => setApproving(false)}>
          <div className="fm-modal" onClick={(e) => e.stopPropagation()}>
            <div className="fm-header">
              <span className="fm-title">MCP 审批</span>
              {pendingList.length > 0 && (
                <span className="fm-subtitle">导入产生的待确认 server · 共 {pendingList.length} 个</span>
              )}
            </div>
            <div className="fm-body">
              {pendingList.length === 0 ? (
                <div className="fm-model-empty">暂无待审批的 MCP server</div>
              ) : (
                pendingList.map((m) => (
                  <label
                    key={m.id}
                    className="approval-row"
                    style={{
                      display: 'flex', alignItems: 'center', gap: 10,
                      padding: '8px 0', cursor: 'pointer',
                      borderBottom: '1px solid rgba(0,0,0,.08)',
                    }}
                  >
                    <input type="checkbox" checked={selected.includes(m.id)} onChange={() => toggleSelect(m.id)} />
                    <span style={{ flex: 1 }}>{m.userFacingName ?? m.name}</span>
                    <code style={{ fontSize: 12, opacity: 0.7 }}>
                      {m.args && m.args.length > 0 ? `${m.command} ${m.args.join(' ')}` : m.command}
                    </code>
                  </label>
                ))
              )}
            </div>
            <div className="fm-footer">
              <button
                className="fm-btn danger"
                onClick={() => onReject(selected)}
                disabled={pending || selected.length === 0}
              >
                拒绝选中
              </button>
              <span className="spacer"></span>
              <button className="fm-btn" onClick={() => setApproving(false)}>关闭</button>
              <button
                className="fm-btn primary"
                onClick={() => onApprove(selected)}
                disabled={pending || selected.length === 0}
              >
                通过选中
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
