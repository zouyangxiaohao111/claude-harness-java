import { useState } from 'react'
import type { CreateMcpRequest, McpOAuthRequest, McpServer } from '@/api/types'
import { parseMcpCommandLine } from '@/utils/mcpCliParse'

export type Transport = 'stdio' | 'sse' | 'http'
export type Scope = 'local' | 'user' | 'project'

export const TRANSPORT_TYPES: { value: Transport; label: string }[] = [
  { value: 'stdio', label: 'stdio' },
  { value: 'sse', label: 'sse' },
  { value: 'http', label: 'http' },
]

export const SCOPE_OPTIONS: { value: Scope; label: string }[] = [
  { value: 'local', label: 'local' },
  { value: 'user', label: 'user' },
  { value: 'project', label: 'project' },
]

/** 添加/编辑表单本地 state（传输类型切换联动 Command / URL + Headers/OAuth 行） */
export interface McpFormState {
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
  oauthScopes: string
  oauthAuthEndpoint: string
  oauthTokenEndpoint: string
  oauthXaa: boolean
  clientSecret: string
}

export const EMPTY_FORM: McpFormState = {
  name: '', type: 'stdio', scope: 'project',
  command: '', argsStr: '', envStr: '',
  url: '', headersJson: '',
  oauthClientId: '', oauthCallbackPort: '', oauthMetadataUrl: '',
  oauthScopes: '', oauthAuthEndpoint: '', oauthTokenEndpoint: '',
  oauthXaa: false,
  clientSecret: '',
}

/** 默认回调端口：与后端 `OauthPort` 的 fallback 3118 保持一致（未填 Callback Port 时展示它） */
const DEFAULT_CALLBACK_PORT = '3118'

export const isTransport = (t: string | undefined): t is Transport =>
  t === 'stdio' || t === 'sse' || t === 'http'
export const isScope = (s: string | null | undefined): s is Scope =>
  s === 'local' || s === 'user' || s === 'project'

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

/** `McpServer` → 表单初值（编辑态回显；OAuth 7 字段来自 `__mcp_oauth__` 反解后的 `oauth`）。 */
export function formStateFromServer(m: McpServer): McpFormState {
  const type: Transport = isTransport(m.type) ? m.type : 'stdio'
  const scope: Scope = isScope(m.scope) ? m.scope : 'project'
  const oauth = (m.oauth ?? null) as McpOAuthRequest | null
  return {
    name: m.userFacingName ?? m.name,
    type,
    scope,
    command: type === 'stdio' ? (m.command ?? '') : '',
    argsStr: m.args ? m.args.join(' ') : '',
    envStr: m.env ? Object.entries(m.env).map(([k, v]) => `${k}=${v}`).join('\n') : '',
    url: type === 'stdio' ? '' : (m.url ?? ''),
    headersJson: m.headers ? JSON.stringify(m.headers, null, 2) : '',
    oauthClientId: oauth?.clientId ?? '',
    oauthCallbackPort: oauth?.callbackPort != null ? String(oauth.callbackPort) : '',
    oauthMetadataUrl: oauth?.authServerMetadataUrl ?? '',
    oauthScopes: oauth?.scopes ?? '',
    oauthAuthEndpoint: oauth?.authorizationEndpoint ?? '',
    oauthTokenEndpoint: oauth?.tokenEndpoint ?? '',
    oauthXaa: !!oauth?.xaa,
    clientSecret: '',
  }
}

/**
 * MCP 服务器添加/编辑表单（**唯一实现**）。
 *
 * <p>设置里的 `MCPPanel` 与市场「自定义连接器」弹窗共用本组件，避免两套简化表单漂移。
 * DOM/类名沿用原 MCPPanel（`mcp-cli-row / mcp-add-form / mcp-add-row / mcp-add-actions`），
 * 既有 `MCPPanel.oauth` 测试与 CSS 都不受影响。
 */
export function McpServerForm({
  pending, editing = null, onSubmit, onCancel, showToast, submitLabel, cancelLabel, footerClass,
}: {
  pending: boolean
  /** 编辑态（null = 新增） */
  editing?: McpServer | null
  /** 校验通过后回调（父层决定 create / update） */
  onSubmit: (req: CreateMcpRequest) => void
  onCancel: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 提交按钮文案（缺省按编辑态推导） */
  submitLabel?: string
  /** 取消按钮文案（缺省「取消」） */
  cancelLabel?: string
  /** 底部按钮容器类名（设置页用 mcp-add-actions；自定义连接器弹窗用 mc-df） */
  footerClass?: string
}) {
  const [form, setForm] = useState<McpFormState>(() => (editing ? formStateFromServer(editing) : EMPTY_FORM))
  const [cliLine, setCliLine] = useState('')
  const set = (patch: Partial<McpFormState>) => setForm((p) => ({ ...p, ...patch }))

  /** 命令行快速添加：解析 `claude mcp add ...` / 纯命令 → 填入表单（失败 toast；成功清掉 OAuth 残留回到新建态） */
  const onCliParse = () => {
    const parsed = parseMcpCommandLine(cliLine)
    if (!parsed.ok) {
      showToast(parsed.error, 'info')
      return
    }
    setForm({
      ...parsed.form,
      oauthClientId: '', oauthCallbackPort: '', oauthMetadataUrl: '',
      oauthScopes: '', oauthAuthEndpoint: '', oauthTokenEndpoint: '',
      oauthXaa: false,
      clientSecret: '',
    })
    setCliLine('')
    showToast('已填入表单，确认后点添加服务器', 'success')
  }

  const buildOAuth = (): McpOAuthRequest | undefined => {
    const clientId = form.oauthClientId.trim()
    const scopes = form.oauthScopes.trim()
    const authz = form.oauthAuthEndpoint.trim()
    const token = form.oauthTokenEndpoint.trim()
    // falsy 门：所有 OAuth 字段都空 ⇒ 不下发 oauth（老行为完全不变）
    if (!clientId && !form.oauthCallbackPort.trim() && !form.oauthMetadataUrl.trim()
      && !scopes && !authz && !token && !form.oauthXaa) {
      return undefined
    }
    return {
      clientId: clientId || undefined,
      callbackPort: form.oauthCallbackPort.trim() || undefined,
      authServerMetadataUrl: form.oauthMetadataUrl.trim() || undefined,
      scopes: scopes || undefined,
      authorizationEndpoint: authz || undefined,
      tokenEndpoint: token || undefined,
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
      // 新增默认「启动时自动连接」（编辑时沿用原值，不回写覆盖；undefined 会被后端视为不改）
      autoStart: editing ? editing.autoStart : true,
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

  const submit = () => {
    const req = buildRequest()
    if (req) onSubmit(req)
  }

  /** 回调地址（未填端口时展示后端 `OauthPort` 的默认 fallback 3118） */
  const callbackUri = `http://localhost:${form.oauthCallbackPort.trim() || DEFAULT_CALLBACK_PORT}/callback`
  const copyCallback = async () => {
    try {
      await navigator.clipboard?.writeText(callbackUri)
      showToast('已复制回调地址', 'success')
    } catch {
      showToast('复制失败，请手动选中复制', 'info')
    }
  }

  return (
    <>
      {!editing && (
        <div className="mcp-cli-row">
          <input
            className="settings-input mcp-cli-input"
            value={cliLine}
            onChange={(e) => setCliLine(e.target.value)}
            placeholder="claude mcp add <name> -- <command> <args…>"
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
            data-field="name"
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
            {/* 回调地址：给用户去平台后台登记（默认端口与后端 fallback 一致） */}
            <div className="mcp-add-row">
              <label>回调地址</label>
              <code
                data-testid="mcp-callback-uri"
                style={{ flex: 1, minWidth: 0, fontSize: 12, wordBreak: 'break-all' }}
              >
                {callbackUri}
              </code>
              <button className="mcp-cli-btn" type="button" onClick={() => void copyCallback()} disabled={pending}>
                复制
              </button>
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
              <label>Scope</label>
              <input
                className="settings-input"
                value={form.oauthScopes}
                onChange={(e) => set({ oauthScopes: e.target.value })}
                placeholder="空格分隔，如 openid profile tasks:read"
                disabled={pending}
              />
            </div>
            <div className="mcp-add-row">
              <label>授权端点</label>
              <input
                className="settings-input"
                value={form.oauthAuthEndpoint}
                onChange={(e) => set({ oauthAuthEndpoint: e.target.value })}
                placeholder="可选，覆盖自动发现：https://.../authorize"
                disabled={pending}
              />
            </div>
            <div className="mcp-add-row">
              <label>令牌端点</label>
              <input
                className="settings-input"
                value={form.oauthTokenEndpoint}
                onChange={(e) => set({ oauthTokenEndpoint: e.target.value })}
                placeholder="可选，覆盖自动发现：https://.../token"
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
      <div className={footerClass ?? 'mcp-add-actions'}>
        <button className="mcp-add-btn ghost" onClick={onCancel} disabled={pending}>
          {cancelLabel ?? '取消'}
        </button>
        <button className="mcp-add-btn primary" onClick={submit} disabled={pending}>
          {submitLabel ?? (editing ? '保存修改' : '添加服务器')}
        </button>
      </div>
    </>
  )
}
