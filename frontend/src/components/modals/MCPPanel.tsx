import { useEffect, useState } from 'react'
import { FormModal } from '@/components/ui/FormModal'
import { Icon } from '@/icons'
import { ApiError } from '@/api/rest'
import { doctorApi } from '@/api/doctor'
import type { RuntimeEnvResponse } from '@/api/doctor'
import { mcpApi as mcpRestApi } from '@/api/mcp'
import type { CreateMcpRequest, McpOAuthRequest, McpServer } from '@/api/types'
import type { UseMcp } from '@/hooks/useMcp'
import { parseMcpCommandLine } from '@/utils/mcpCliParse'
import { McpOauthRow } from './McpOauthRow'
import { CONN_STATE, RUNTIME_LABEL } from './mcpStatus'

interface MCPPanelProps {
  /** 真实后端 CRUD API */
  mcpApi: UseMcp
  showToast: (msg: string, type?: 'success' | 'info') => void
  /**
   * 当前活动会话 id · **启动 server 时必传**（后端 POST /mcp/{id}/start 的 `?sessionId=` 必填；
   * 缺值返 400 "sessionId is required"）。无会话（设置页在未选中会话时打开）→ null，此时启动按钮
   * 显式报错而不是发一个必 400 的请求。
   */
  sessionId?: string | null
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
  oauthScopes: string
  oauthAuthEndpoint: string
  oauthTokenEndpoint: string
  oauthXaa: boolean
  clientSecret: string
}

const EMPTY_FORM: McpFormState = {
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

const isTransport = (t: string | undefined): t is Transport =>
  t === 'stdio' || t === 'sse' || t === 'http'
const isScope = (s: string | null | undefined): s is Scope =>
  s === 'local' || s === 'user' || s === 'project'

/** 审批态徽标（对齐 McpServerDto.approvalStatus） */
const APPROVAL_LABEL: Record<string, { cls: string; label: string }> = {
  approved: { cls: 'approved', label: '已通过' },
  rejected: { cls: 'rejected', label: '已拒绝' },
  pending: { cls: 'pending', label: '待审批' },
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
 * MCPPanel · 版式对齐「CLI 设置」页：
 * 单列卡片列表（默认只显示一行摘要，点哪行才展开哪行），添加/编辑表单在同一处展开，
 * 避免一进页面就被一张常驻表单抢注意力。
 *
 * <p>「启动时自动连接」是每个服务器自己的开关（新增默认开启），跟着列表行走，没有全局总闸。
 */
export function MCPPanel({ mcpApi, showToast, sessionId = null }: MCPPanelProps) {
  const { list, loading, error, refresh, createMcp, updateMcp, deleteMcp, toggleMcp } = mcpApi

  const [form, setForm] = useState<McpFormState>(EMPTY_FORM)
  const [editing, setEditing] = useState<McpServer | null>(null)
  const [cliLine, setCliLine] = useState('')
  const [pending, setPending] = useState(false)
  const [importing, setImporting] = useState(false)
  const [importDetail, setImportDetail] = useState<{ blocked: string[]; suppressed: string[] } | null>(null)
  const [approving, setApproving] = useState(false)
  const [selected, setSelected] = useState<string[]>([])
  /** 当前展开（聚焦）的服务器行；同一时刻只展开一行 */
  const [openId, setOpenId] = useState<string | null>(null)
  /** 「添加新服务器」表单是否展开 */
  const [formOpen, setFormOpen] = useState(false)
  /** Node / Python 探测结果：缺失时提示，不阻塞任何操作 */
  const [runtimeEnv, setRuntimeEnv] = useState<RuntimeEnvResponse | null>(null)
  /** 最近一次「测试」结果（按 server id）· tools[] 是后端 tools/list 的真实工具名 */
  const [lastTest, setLastTest] = useState<Record<string, { ok: boolean; message: string; latencyMs: number | null; tools: string[] }>>({})

  useEffect(() => {
    let alive = true
    doctorApi.runtime()
      .then((v) => { if (alive) setRuntimeEnv(v) })
      .catch(() => { /* 老后端无该端点 → 不显示运行时提示 */ })
    return () => { alive = false }
  }, [])

  const refreshRuntime = () => {
    doctorApi.runtime()
      .then(setRuntimeEnv)
      .catch(() => { /* 探测失败静默：不把 MCP 面板拖进错误态 */ })
  }

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

  /** 关闭添加/编辑表单（编辑时回到该行的详情态） */
  const closeForm = () => {
    resetForm()
    setFormOpen(false)
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
      oauthClientId: '', oauthCallbackPort: '', oauthMetadataUrl: '',
      oauthScopes: '', oauthAuthEndpoint: '', oauthTokenEndpoint: '',
      oauthXaa: false,
      clientSecret: '',
    })
    setEditing(null)
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

  const onAdd = () => {
    const req = buildRequest()
    if (!req) return
    void wrap(async () => {
      await createMcp(req)
      resetForm()
      setFormOpen(false)
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

  const startAdd = () => {
    resetForm()
    setCliLine('')
    setFormOpen(true)
    setOpenId(null)
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
      oauthScopes: oauth?.scopes ?? '',
      oauthAuthEndpoint: oauth?.authorizationEndpoint ?? '',
      oauthTokenEndpoint: oauth?.tokenEndpoint ?? '',
      oauthXaa: !!oauth?.xaa,
      clientSecret: '',
    })
    setEditing(m)
    setFormOpen(false)
    setOpenId(m.id)
  }

  // ── 每行操作：启停 / 测试 / 删除 / enabled 开关 ──
  const onToggle = (m: McpServer) => {
    const display = m.userFacingName ?? m.name
    void wrap(async () => {
      const saved = await toggleMcp(m.id, !m.enabled)
      // 正在编辑同一台时同步 editing，避免「编辑」表单里的自启行用旧的 enabled 置灰
      setEditing((prev) => (prev && prev.id === saved.id
        ? { ...prev, enabled: saved.enabled, autoStart: saved.autoStart }
        : prev))
    }, `${display} ${m.enabled ? '已停用' : '已启用'}`)
  }

  /** [V295] 启动自启开关（PATCH autoStart；enabled 总开关关闭时后端归一化为 false）。 */
  const onToggleAutoStart = (m: McpServer) => {
    const display = m.userFacingName ?? m.name
    const next = !m.autoStart
    // 显式带 scope：后端 McpCreateRequest 对 scope 有 "project" 缺省，单传 autoStart 会把原有
    // user/local scope 覆盖成 project（既有 PATCH 契约缺陷，此处规避）。
    void wrap(async () => {
      const saved = await updateMcp(m.id, { autoStart: next, scope: m.scope ?? undefined })
      // 编辑表单里的自启行读的是 editing，同步一次否则勾选态要等重新点编辑才刷新
      setEditing((prev) => (prev && prev.id === saved.id ? { ...prev, autoStart: saved.autoStart } : prev))
    }, `${display} 启动自启${next ? '已开启' : '已关闭'}`)
  }

  const onStart = (m: McpServer) => {
    // sessionId 可选：有活动会话就带上（声明 claude/channel 的 server 才用得到它，决定 channel 白名单）；
    // 没有会话也能启动 —— 后端对该次连接 fail-closed 跳过 channel handler，工具照常可用。
    void wrap(async () => {
      // start 端点有 enabled 门（disabled → 409）：未启用先 PATCH enabled=true（后端自动 start），已启用直接调 start
      if (!m.enabled) await toggleMcp(m.id, true)
      else await mcpRestApi.start(m.id, sessionId)
      await refresh()
    }, `已启动: ${m.userFacingName ?? m.name}`)
  }

  const onStop = (m: McpServer) => {
    void wrap(async () => {
      await mcpRestApi.stop(m.id)
      await refresh()
    }, `已停止: ${m.userFacingName ?? m.name}`)
  }

  /** 测试连接：toast 展示 ok/latencyMs 或错误 message，并在行详情里保留本次结果与工具清单 */
  const onTest = (m: McpServer) => {
    void (async () => {
      setPending(true)
      try {
        const res = await mcpRestApi.test(m.id)
        setLastTest((prev) => ({
          ...prev,
          [m.id]: { ok: res.ok, message: res.message, latencyMs: res.latencyMs, tools: res.tools ?? [] },
        }))
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
    void wrap(async () => {
      await deleteMcp(m.id)
      setOpenId((cur) => (cur === m.id ? null : cur))
    }, `已删除: ${display}`)
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

  /** 添加 / 编辑共用的表单主体（在卡片内展开，不常驻页面顶部） */
  const renderForm = () => (
    <>
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
      <div className="mcp-add-actions">
        <button className="mcp-add-btn ghost" onClick={closeForm} disabled={pending}>
          取消
        </button>
        <button className="mcp-add-btn primary" onClick={editing ? onSaveEdit : onAdd} disabled={pending}>
          {editing ? '保存修改' : '添加服务器'}
        </button>
      </div>
    </>
  )

  return (
    <div className="mcp-list cli-panel">
      <section className="cli-section">
        <div className="cli-group-head" style={{ marginBottom: 8 }}>
          <h4>MCP 服务器（{list.length}）</h4>
          <div className="mcp-head-actions">
            {pendingList.length > 0 && (
              <button
                className="cli-ghost-btn"
                onClick={() => {
                  setSelected(pendingList.map((m) => m.id))
                  setApproving(true)
                }}
                disabled={pending}
              >
                待审批 ({pendingList.length})
              </button>
            )}
            <button className="cli-ghost-btn" onClick={() => setImporting(true)} disabled={pending}>
              导入 .mcp.json
            </button>
            <button className="cli-primary-btn" onClick={startAdd} disabled={pending}>
              添加服务器
            </button>
          </div>
        </div>

        <p className="cli-hint">
          Model Context Protocol 工具接入 · {enabledCount}/{list.length} 已启用
          {loading && ' · 加载中…'}
          {error && <span style={{ color: 'var(--error)' }}> · {error}</span>}
        </p>

        {/* ── 运行时缺失提示（Node / Python / uv）· 只提醒，不静默安装 ── */}
        {runtimeEnv && runtimeEnv.missingForMcp.length > 0 && (
          <div className="mcp-warn">
            <div style={{ flex: 1 }}>
              <div className="mcp-detail-label">缺少 MCP 运行时</div>
              <div className="mcp-detail-desc">
                未检测到 {runtimeEnv.missingForMcp.join(' / ')}，npx / uvx 类型的服务器无法启动。安装后点「重新检测」。
              </div>
              <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 4 }}>
                {runtimeEnv.missingForMcp.map((name) => {
                  const info = runtimeEnv.runtimes[name]
                  if (!info?.installCommand) return null
                  return (
                    <div key={name} style={{ fontSize: 10.5, color: 'var(--ink-subtle)' }}>
                      <span style={{ fontFamily: 'var(--font-mono)' }}>{info.installCommand}</span>
                      {info.installUrl && (
                        <>
                          {' · '}
                          <a href={info.installUrl} target="_blank" rel="noreferrer">下载页</a>
                        </>
                      )}
                    </div>
                  )
                })}
              </div>
            </div>
            <button className="cli-ghost-btn" onClick={refreshRuntime}>重新检测</button>
          </div>
        )}

        {importDetail && (importDetail.blocked.length > 0 || importDetail.suppressed.length > 0) && (
          <div className="mcp-import-detail">
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
              <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--ink)' }}>导入明细</span>
              {/* 「关闭明细」是中性动作，不是破坏性操作 ⇒ 用普通 icon-btn + 关闭图标，
                  不复用 .skill-remove（那是删除语义 · hover 红色警示）。 */}
              <button className="icon-btn" onClick={() => setImportDetail(null)} title="关闭">
                <Icon.CloseLg />
              </button>
            </div>
            {importDetail.blocked.length > 0 && (
              <div style={{ marginBottom: importDetail.suppressed.length > 0 ? 8 : 0 }}>
                <div style={{ fontSize: 10.5, fontWeight: 500, color: 'var(--error-dark)' }}>
                  blocked · 策略拦截未导入（{importDetail.blocked.length}）
                </div>
                <div className="mcp-import-path">{importDetail.blocked.join('、')}</div>
              </div>
            )}
            {importDetail.suppressed.length > 0 && (
              <div>
                <div style={{ fontSize: 10.5, fontWeight: 500, color: 'var(--ink-muted)' }}>
                  suppressed · scope 去重已跳过（{importDetail.suppressed.length}）
                </div>
                <div className="mcp-import-path">{importDetail.suppressed.join('、')}</div>
              </div>
            )}
          </div>
        )}

        {list.length === 0 && !loading && !error && !formOpen && (
          <div className="cli-empty">暂无已配置的服务器 · 点右上角「添加服务器」</div>
        )}

        <div className="cli-list" style={{ marginTop: list.length > 0 || formOpen ? 8 : 0 }}>
          {/* 添加新服务器：点右上角按钮才展开 */}
          {formOpen && !editing && (
            <div className="cli-card mcp-add-card-cli is-open">
              <div className="cli-config">
                <div className="cli-config-head">
                  <span className="cli-config-name">添加新服务器</span>
                </div>
                <div className="mcp-form-wrap">{renderForm()}</div>
              </div>
            </div>
          )}

          {list.map((m) => {
            const type = m.type ?? 'stdio'
            const remote = type !== 'stdio'
            const approval = APPROVAL_LABEL[m.approvalStatus ?? 'approved'] ?? APPROVAL_LABEL.approved
            // [V295] 运行时状态优先（后端现算，不落库）；老后端无 runtimeStatus → 回落三态映射
            const conn = m.runtimeStatus ? RUNTIME_LABEL[m.runtimeStatus] : (CONN_STATE[m.status] ?? CONN_STATE.stopped)
            const isConnected = m.runtimeStatus
              ? m.runtimeStatus === 'connected'
              : m.status === 'running'
            const desc = remote
              ? `URL: ${m.url ?? m.command}`
              : `Command: ${m.command}${m.args && m.args.length > 0 ? ' ' + m.args.join(' ') : ''}`
            const display = m.userFacingName ?? m.name
            const scopeText = m.scope ?? (remote ? 'remote' : 'local')
            const isOpen = openId === m.id
            const editingThis = editing?.id === m.id
            const approvalNote = m.approvalStatus === 'pending'
              ? '待审批 · '
              : m.approvalStatus === 'rejected' ? '已拒绝 · ' : ''
            return (
              <div
                key={m.id}
                className={`cli-card mcp-card${isOpen ? ' is-open' : ''}${m.enabled ? '' : ' is-off'}`}
              >
                <div
                  role="button"
                  tabIndex={0}
                  className="cli-card-hit"
                  aria-expanded={isOpen}
                  onClick={() => setOpenId(isOpen ? null : m.id)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      setOpenId(isOpen ? null : m.id)
                    }
                  }}
                >
                  <span className="mcp-card-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
                         strokeLinecap="round" strokeLinejoin="round">
                      <rect x="3.5" y="4.5" width="17" height="6" rx="2" />
                      <rect x="3.5" y="13.5" width="17" height="6" rx="2" />
                      <path d="M7 7.5h.01M7 16.5h.01" />
                    </svg>
                  </span>
                  <span className="cli-card-body">
                    <span className="cli-card-name">
                      <span className="cli-card-title">{display}</span>
                      <span className="cli-card-divider" aria-hidden="true">·</span>
                      <span className="cli-card-tagline">{type} · {scopeText}</span>
                      {isConnected && <span className="cli-dot" title="已连接" aria-hidden="true" />}
                    </span>
                    <span className="cli-card-meta">
                      {approvalNote}{m.enabled && m.autoStart ? '自启 · ' : ''}{conn.label} · {desc}
                    </span>
                  </span>
                  <svg
                    className="cli-card-chevron"
                    viewBox="0 0 12 12"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.6"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    aria-hidden="true"
                  >
                    <path d="M3 4.5L6 7.5L9 4.5" />
                  </svg>
                </div>

                {/* 启用开关放在点击区外：它是独立动作，嵌在整行点击区里容易误触 */}
                <div className="mcp-card-tail">
                  <span className="mcp-switch-label">启用</span>
                  <label className="settings-switch" title={m.enabled ? '停用' : '启用'}>
                    <input
                      type="checkbox"
                      checked={m.enabled}
                      onChange={() => onToggle(m)}
                      disabled={pending}
                    />
                    <span></span>
                  </label>
                </div>

                {isOpen && (
                  <div className="cli-config">
                    {editingThis ? (
                      <>
                        <div className="cli-config-head">
                          <span className="cli-config-name">编辑服务器</span>
                        </div>
                        <div className="mcp-form-wrap">{renderForm()}</div>
                      </>
                    ) : (
                      <>
                        <div className="cli-config-head">
                          <span className="mcp-detail-badges">
                            {m.approvalStatus && m.approvalStatus !== 'approved' && (
                              <span className={`mcp-badge approval ${approval.cls}`}>{approval.label}</span>
                            )}
                            <span
                              className={`mcp-badge conn ${conn.cls}`}
                              title={m.runtimeStatus
                                ? '运行时连接状态（后端现算，不落库）'
                                : '连接状态（后端未返回 runtimeStatus，按三态映射）'}
                            >
                              <span className="mcp-badge-dot" />
                              {conn.label}
                            </span>
                          </span>
                          <div className="mcp-card-actions">
                            {isConnected ? (
                              <button className="cli-card-action" onClick={() => onStop(m)} disabled={pending}>
                                停止
                              </button>
                            ) : (
                              <button
                                className="cli-card-action"
                                onClick={() => onStart(m)}
                                disabled={pending || m.status === 'pending'}
                                title={m.status === 'pending' ? '待审批，需先通过后才能启动' : '启动'}
                              >
                                启动
                              </button>
                            )}
                            <button className="cli-card-action" onClick={() => onTest(m)} disabled={pending}>
                              测试
                            </button>
                          </div>
                        </div>
                        <div className="mcp-detail">
                          {m.lastError && <div className="mcp-detail-error">✗ {m.lastError}</div>}
                          <div className="mcp-detail-cmd">{desc}</div>
                          {/* 最近一次测试结果 + 真实工具清单（后端 /test 的 tools: string[]） */}
                          {lastTest[m.id] && (
                            <div className="mcp-detail-row" data-testid="mcp-test-result">
                              <div style={{ flex: 1, minWidth: 0 }}>
                                <div className="mcp-detail-label">最近一次测试</div>
                                <div className="mcp-detail-desc">
                                  {lastTest[m.id].ok
                                    ? `通过${lastTest[m.id].latencyMs != null ? ` · ${lastTest[m.id].latencyMs}ms` : ''}：${lastTest[m.id].message}`
                                    : `失败：${lastTest[m.id].message}`}
                                </div>
                                {lastTest[m.id].tools.length > 0 && (
                                  <ul
                                    data-testid="mcp-test-tools"
                                    style={{ listStyle: 'none', margin: '4px 0 0', padding: 0, display: 'flex', flexDirection: 'column', gap: 3 }}
                                  >
                                    {lastTest[m.id].tools.map((t) => (
                                      <li key={t} style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5, color: 'var(--ink-subtle)' }}>
                                        {t}
                                      </li>
                                    ))}
                                  </ul>
                                )}
                              </div>
                            </div>
                          )}
                          {/* 远程 server 的 OAuth 授权/断开（start→浏览器→轮询；行内二次确认） */}
                          {remote && (
                            <McpOauthRow
                              server={m}
                              statusLabel={conn.label}
                              showToast={showToast}
                              onChanged={refresh}
                            />
                          )}
                          <div className="mcp-detail-row">
                            <div>
                              <div className="mcp-detail-label">启动时自动连接</div>
                              <div className="mcp-detail-desc">
                                开启后，应用启动时会自动连上这个服务；关闭则每次手动启动。
                                {!m.enabled && '（先启用该服务器）'}
                              </div>
                            </div>
                            <label
                              className="settings-switch"
                              title={m.enabled ? '启动时自动连接此服务（全局总闸关闭时仍不会连）' : '先启用该服务器才能设置启动自启'}
                            >
                              <input
                                type="checkbox"
                                checked={!!m.autoStart && m.enabled}
                                onChange={() => onToggleAutoStart(m)}
                                disabled={pending || !m.enabled}
                              />
                              <span></span>
                            </label>
                          </div>
                          <div className="mcp-detail-foot">
                            <button className="cli-card-action" onClick={() => startEdit(m)} disabled={pending}>
                              编辑
                            </button>
                            <span className="spacer" />
                            <button className="mcp-text-btn danger" onClick={() => onRemove(m)} disabled={pending}>
                              删除
                            </button>
                          </div>
                        </div>
                      </>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </section>

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
