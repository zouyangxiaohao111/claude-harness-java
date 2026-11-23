import { useEffect, useRef, useState } from 'react'
import { ApiError } from '@/api/rest'
import {
  connectorsApi,
  type ConnectorAuthType,
  type ConnectorDto,
  type ConnectorStatusResponse,
  type ConnectorTestResponse,
} from '@/api/connectors'
import { openExternalUrl } from '@/utils/openExternal'
import { normalizeToolList } from '@/utils/toolList'
import { useOAuthStartPoll } from '@/hooks/useOAuthStartPoll'
import { connectorLogo } from '@/assets/connectors'
import {
  AUTH_LABEL,
  CREDENTIAL_FIELDS,
  STATUS_LABEL,
  connectorTier,
  statusClass,
  type CredentialField,
} from './connectorMeta'
import './marketConnector.css'

const errMsg = (e: unknown) => (e instanceof ApiError ? e.userMessage() : String(e))

/** epoch millis → 本地时间文字（后端 `expiresAt` 是毫秒时间戳，⛔ 不是 ISO 字符串） */
function formatExpiry(v?: number | null): string | null {
  if (v == null || !Number.isFinite(v)) return null
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return null
  return d.toLocaleString('zh-CN', { hour12: false })
}

/**
 * 「测试连接」本次结果的文案与样式。
 *
 * <p>⛔ 不许 `ok ? '连接成功' : '连接失败'` 一刀切：**能连上 ≠ MCP 握手成功**。
 * 后端 `probeLevel` 三态：
 * <ul>
 *   <li>`mcp_initialize`：initialize + tools/list 成功（成功样式，可带工具数）；</li>
 *   <li>`connect_only`：能发起请求但握手失败（含 401）—— 非成功样式，必须说清「仅连通」；</li>
 *   <li>`none`：无可用凭据。</li>
 * </ul>
 * 缺省（老后端没这个字段）才回落 `ok` 二分。
 */
function describeTestResult(r: ConnectorTestResponse): { cls: 'ok' | 'fail'; text: string } {
  const level = r.probeLevel ?? (r.ok ? 'mcp_initialize' : 'none')
  const ms = `（${r.latencyMs}ms）`
  if (level === 'mcp_initialize') {
    if (!r.ok) return { cls: 'fail', text: `MCP 握手失败${ms}：${r.message}` }
    // 工具数：优先后端 toolCount，缺省用本次 tools[] 长度（修：原来只看 toolCount ⇒ 永不触发）
    const count = typeof r.toolCount === 'number' ? r.toolCount : (r.tools ?? []).length
    const tools = count > 0 ? ` · 工具 ${count} 个` : ''
    return { cls: 'ok', text: `MCP 握手成功${ms}${tools}：${r.message}` }
  }
  if (level === 'connect_only') {
    return { cls: 'fail', text: `仅连通，未完成 MCP 握手${ms}：${r.message}` }
  }
  return { cls: 'fail', text: `无可用凭据${ms}：${r.message}` }
}

/** 探测深度的人话（面板元信息行用） */
function probeLevelLabel(level?: string | null): string {
  if (level === 'mcp_initialize') return 'MCP 握手成功'
  if (level === 'connect_only') return '仅连通（未完成握手）'
  if (level === 'none') return '无可用凭据'
  return ''
}

function fieldInputs(fields: CredentialField[], form: Record<string, string>, setForm: (f: Record<string, string>) => void) {
  return fields.map((f) => (
    <div className="mc-frow" key={f.k}>
      <div className="mc-flab">{f.label}</div>
      <input
        className="mc-inp"
        type={f.secret ? 'password' : 'text'}
        value={form[f.k] ?? ''}
        placeholder={f.ph}
        data-field={f.k}
        onChange={(e) => setForm({ ...form, [f.k]: e.target.value })}
      />
    </div>
  ))
}

/**
 * 连接器授权抽屉。
 *
 * <p><b>流程（oauth2）</b>：点「开始授权」→ `POST /{id}/start` 拿 authorizeUrl/state/redirectUri →
 * 用<b>系统浏览器</b>打开授权页 → 按 `interval` 秒轮询 `GET /{id}/poll?state=` →
 * AUTHORIZED 后拉一次 `GET /{id}/status`（权威状态）回写列表。
 * 范式照 `ImChannelPage.tsx` 的 `OauthPanel`（`startPromiseRef` 去重 StrictMode 双跑 + 定时器清理）。
 *
 * <p><b>非 oauth 类型</b>：按 `CREDENTIAL_FIELDS` 渲染字段 → `POST /{id}/credentials`。
 * ⛔ 没有「假装已连接」的本地乐观态 —— 一切以 status 响应为准。
 *
 * <p>「测试连接」必须真调后端并把<b>本次</b>结果文字显示出来（⛔ 不许只变个颜色）。
 */
export function ConnectorAuthDrawer({ connector, onClose, onStatusChange, showToast }: {
  connector: ConnectorDto
  onClose: () => void
  /** 后端权威状态回写（技能市场列表用它刷新卡片） */
  onStatusChange: (id: string, st: ConnectorStatusResponse) => void
  showToast: (msg: string, type?: 'success' | 'info') => void
}) {
  const nm = connector.name || connector.key
  const tier = connectorTier(connector)
  const [live, setLive] = useState<ConnectorStatusResponse>({
    connected: !!connector.connected,
    authType: connector.authType,
    account: connector.account ?? null,
    expiresAt: connector.expiresAt ?? null,
    missingScopes: null,
  })
  const liveRef = useRef(live)
  liveRef.current = live
  const onStatusChangeRef = useRef(onStatusChange)
  onStatusChangeRef.current = onStatusChange

  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const applyStatus = (st: ConnectorStatusResponse) => {
    setLive(st)
    onStatusChangeRef.current(connector.id, st)
  }

  // ---- oauth2：start → 系统浏览器 → 轮询（与 MCP 面板共用 hook；DOM 仍用本抽屉）----
  const oauth = useOAuthStartPoll({
    start: () => connectorsApi.start(connector.id),
    poll: (state) => connectorsApi.poll(connector.id, state),
    onAuthorized: async () => {
      const st = await connectorsApi.status(connector.id)
      applyStatus(st)
      showToast(`已连接 ${nm}${st.account ? `（${st.account}）` : ''}`, 'success')
    },
    onOpenFailed: () => showToast('授权页未能自动打开，请点「打开授权页」', 'info'),
    initialPhase: live.connected ? 'ok' : 'idle',
  })
  const phase = oauth.phase
  const auth = oauth.auth
  const [showClient, setShowClient] = useState(false)
  const [clientForm, setClientForm] = useState<Record<string, string>>({})

  // ---- 非 oauth 凭证 ----
  // 表单优先级：① 后端 credentialFields → ② authMode 通用表单 → ③ 按 authType 的旧通用表
  const credAuthType = connector.authType && connector.authType !== 'oauth2' ? connector.authType : null
  const declaredFields: CredentialField[] = (connector.credentialFields ?? [])
    .filter((f) => !!f.key)
    .map((f) => ({
      k: f.key!,
      label: f.label || f.key!,
      ph: f.placeholder ?? '',
      secret: !!f.secret || f.fieldType === 'password',
      required: f.required !== false,
    }))
  const authMode = connector.authMode ?? null
  const headerName = connector.authHeaderName || connector.headerName || '自定义请求头'
  const modeFields: CredentialField[] = authMode === 'bearer'
    ? [{ k: 'token', label: '访问 Token', ph: '粘贴平台获取的 Token', secret: true }]
    : authMode === 'header'
      ? [{ k: 'token', label: `访问 Token（请求头 ${headerName}）`, ph: '粘贴平台获取的 Token', secret: true }]
      : authMode === 'query'
        ? [{ k: 'apiKey', label: 'API Key', ph: '粘贴平台获取的 API Key', secret: true }]
        : []
  const credFields = declaredFields.length > 0
    ? declaredFields
    : (modeFields.length > 0 ? modeFields : (credAuthType ? CREDENTIAL_FIELDS[credAuthType] : []))
  /** authMode 说明（header 显示头名；query 明示会拼到 URL） */
  const modeHint = authMode === 'bearer'
    ? '以 Authorization: Bearer <Token> 注入（服务端加密存储，不回显）。'
    : authMode === 'header'
      ? `通过请求头 ${headerName} 注入（服务端加密存储，不回显）。`
      : authMode === 'query'
        ? '该 Key 会作为查询参数拼到 MCP 地址上（请勿分享）。'
        : null
  const requiredKeys = credFields.filter((f) => f.required !== false).map((f) => f.k)
  const [credForm, setCredForm] = useState<Record<string, string>>({})

  // ---- 测试连接 / 断开 ----
  const [testRes, setTestRes] = useState<(ConnectorTestResponse & { at: string }) | null>(null)
  const [testing, setTesting] = useState(false)
  const [confirmDisc, setConfirmDisc] = useState(false)

  const drawerRef = useRef<HTMLElement | null>(null)
  /** 键盘可达：ESC 关闭；打开时把焦点送进抽屉（S9） */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    drawerRef.current?.focus()
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const logo = connectorLogo(connector.icon, connector.key)
  const reauth = connector.status === 'reauth_required'
  /** 凭据真正可用（reauth_required 即使 connected=true 也不算可用） */
  const usable = live.connected && !reauth
  const headerLabel = reauth ? '需重新授权' : (usable ? '已授权' : (STATUS_LABEL[connector.status] ?? connector.status))
  const headerCls = reauth ? 'reauth' : (usable ? 'connected' : statusClass(connector.status))


  const saveCredentials = async (
    authType: ConnectorAuthType, form: Record<string, string>, required: string[],
  ) => {
    const missing = required.filter((k) => !(form[k] ?? '').trim())
    if (missing.length > 0) {
      setErr(`请先填写：${missing.join('、')}`)
      return
    }
    setBusy(true)
    setErr(null)
    try {
      const payload: Record<string, string> = {}
      if (authType === 'customFields') {
        const name = (form.fieldName ?? '').trim()
        payload[name] = form.fieldValue ?? ''
      } else {
        for (const [k, v] of Object.entries(form)) payload[k] = v
      }
      const st = await connectorsApi.bindCredentials(connector.id, authType, payload)
      // 状态以服务端返回为准；「已授权」区块按 live.connected 渲染，不另置本地 phase
      applyStatus(st)
      if (st.connected) {
        showToast(`已保存 ${nm} 的凭证`, 'success')
      } else {
        showToast('凭证已提交，但连接未生效，请检查内容', 'info')
      }
    } catch (e) {
      setErr(errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  const runTest = async () => {
    setTesting(true)
    setErr(null)
    try {
      const r = await connectorsApi.test(connector.id)
      setTestRes({ ...r, at: new Date().toLocaleTimeString('zh-CN', { hour12: false }) })
    } catch (e) {
      setTestRes({ ok: false, message: errMsg(e), latencyMs: 0, at: new Date().toLocaleTimeString('zh-CN', { hour12: false }) })
    } finally {
      setTesting(false)
    }
  }

  const disconnect = async () => {
    setBusy(true)
    setErr(null)
    try {
      const r = await connectorsApi.disconnect(connector.id)
      if (!r.revoked) {
        setErr('后端未确认断开，请重试')
        return
      }
      applyStatus({ connected: false, authType: connector.authType, account: null, expiresAt: null, missingScopes: null })
      oauth.reset()
      setTestRes(null)
      setConfirmDisc(false)
      showToast(`已断开 ${nm}`, 'success')
    } catch (e) {
      setErr(errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  const copyCallback = async () => {
    if (!auth?.redirectUri) return
    try {
      await navigator.clipboard?.writeText(auth.redirectUri)
      showToast('已复制回调地址', 'success')
    } catch {
      showToast('复制失败，请手动选中复制', 'info')
    }
  }

  const expiryLabel = formatExpiry(live.expiresAt)
  const testView = testRes ? describeTestResult(testRes) : null
  const testTools = testRes ? normalizeToolList(testRes.tools) : []

  return (
    <>
      <div className="mc-scrim" onClick={onClose} />
      <aside
        className="mc-drawer"
        role="dialog"
        aria-modal="true"
        aria-label={`${nm} 授权`}
        data-testid="connector-drawer"
        tabIndex={-1}
        ref={drawerRef}
      >
        <div className="mc-dh">
          <span className="mc-dh-ico">
            {logo
              ? <img className="mc-logo" src={logo} alt="" />
              : <span className="mc-ico-fallback" style={{ background: connector.color || undefined }}>{nm.charAt(0).toUpperCase()}</span>}
          </span>
          <div className="mc-dh-titles">
            <h4>{nm}</h4>
            <span className={`mc-status ${headerCls}`} data-testid="drawer-status">
              <span className="mc-dot" />
              {headerLabel}
            </span>
          </div>
          {connector.official && <span className="mc-badge mc-badge-official">官方 MCP</span>}
          <span className="mc-badge">{AUTH_LABEL[connector.authType] ?? connector.authType}</span>
          <button className="mc-x" onClick={onClose} title="关闭（Esc）" aria-label="关闭">✕</button>
        </div>

        <div className="mc-db">
          {/* 授权提示（灰底一行；后端有才渲染，⛔ 不伪造） */}
          {connector.authHint && (
            <div className="mc-authhint" data-testid="connector-auth-hint" title={connector.authHint}>
              {connector.authHint}
            </div>
          )}
          {connector.docUrl && (
            <button className="mc-doclink" type="button" onClick={() => void openExternalUrl(connector.docUrl!)}>
              查看获取说明
            </button>
          )}

          {/* 说明/状态合并成一行（不再单独堆状态框） */}
          <div className="mc-line-hint" data-testid="connector-line-hint" title={connector.description ?? undefined}>
            {connector.description || '暂无说明'}
            {live.account ? ` · 账号 ${live.account}` : ''}
            {expiryLabel ? ` · 到期 ${expiryLabel}` : ''}
            {(live.missingScopes ?? []).length > 0 ? ` · 缺权限：${(live.missingScopes ?? []).join(', ')}` : ''}
            {tier === 'credentialOnly' ? ' · 运行需外部适配' : ''}
          </div>

          <div className="mc-sec">连接配置</div>
          {(connector.scopes ?? []).length > 0 && (
            <div className="mc-scopes">
              {(connector.scopes ?? []).map((s) => <span key={s} className="mc-scope">{s}</span>)}
            </div>
          )}

          {reauth && (
            <div className="mc-warn" data-testid="connector-reauth">
              授权已失效（token 过期或被吊销）。请重新授权，再点「测试连接」验证。
            </div>
          )}

          {(oauth.err ?? err) && <div className="mc-err">{oauth.err ?? err}</div>}

          {/* ---- oauth2：授权码 + loopback 回调 ---- */}
          {connector.authType === 'oauth2' && (
            <>
              {phase === 'ok' || usable ? (
                <div className="mc-configured-badge" data-testid="connector-authorized-badge">
                  已授权{live.account ? `：${live.account}` : ''}，可直接探测或保存
                </div>
              ) : (
                <>
                  {!auth && (
                    <div className="mc-hint" style={{ marginBottom: 12 }}>
                      点底部「{reauth ? '重新授权' : '开始授权'}」，用系统浏览器完成授权；完成后本页自动更新。
                    </div>
                  )}

                  {auth && (
                    <>
                      <div className="mc-frow">
                        <div className="mc-flab">授权页</div>
                        <div className="mc-cbrow">
                          <span className="mc-cburl" title={auth.authorizeUrl}>{auth.authorizeUrl}</span>
                          <button className="mc-btn" onClick={() => void openExternalUrl(auth.authorizeUrl)}>打开授权页</button>
                        </div>
                      </div>
                      <div className="mc-frow">
                        <div className="mc-flab">回调地址（在平台后台登记 / 核对）</div>
                        <div className="mc-cbrow">
                          <span className="mc-cburl" data-testid="connector-callback" title={auth.redirectUri}>{auth.redirectUri}</span>
                          <button className="mc-btn" onClick={() => void copyCallback()}>复制</button>
                        </div>
                      </div>
                      <div className="mc-hint" style={{ marginBottom: 12 }}>
                        已在浏览器打开授权页？完成授权后本页会自动更新（有效期 {auth.expiresIn}s）。
                      </div>
                    </>
                  )}
                </>
              )}

              {/* 平台未开放自动注册时，用户自备应用凭证 */}
              <div className="mc-frow">
                <button className="mc-btn" onClick={() => setShowClient((v) => !v)}>
                  {showClient ? '收起手动应用凭证' : '手动填写应用凭证（client_id / secret）'}
                </button>
              </div>
              {showClient && (
                <>
                  <div className="mc-hint" style={{ marginBottom: 8 }}>
                    平台不支持自动注册应用时，在此填入你在平台创建的 client_id / client_secret（加密存储，不回显）。
                  </div>
                  {fieldInputs([
                    { k: 'clientId', label: 'Client ID', ph: '平台的 Client ID' },
                    { k: 'clientSecret', label: 'Client Secret', ph: '平台的 Client Secret', secret: true },
                  ], clientForm, setClientForm)}
                  <div className="mc-frow">
                    <button
                      className="mc-btn"
                      disabled={busy}
                      onClick={() => void saveCredentials('oauth2', clientForm, ['clientId', 'clientSecret'])}
                    >
                      {busy ? '保存中…' : '保存应用凭证'}
                    </button>
                  </div>
                </>
              )}
            </>
          )}

          {/* ---- 非 oauth：直接落凭证（列表本轮只放 oauth2，分支代码保留待后续放开）---- */}
          {credAuthType && (
            <>
              <div className="mc-hint" style={{ marginBottom: 10 }}>
                {modeHint ?? `该连接器使用${AUTH_LABEL[credAuthType]}认证，凭证加密存储、界面不回显明文。`}
              </div>
              {fieldInputs(credFields, credForm, setCredForm)}
            </>
          )}
          {/* ---- 探测结果面板（Octop probeResult 结构：成功绿面板 / 失败红面板，都不只靠 toast）---- */}
          {testRes && testView && (
            <div className={`mc-probe${testView.cls === 'ok' ? '' : ' fail'}`} data-testid="connector-probe">
              <div className="mc-probe-hd">
                <span className="mc-probe-ico" aria-hidden="true">{testView.cls === 'ok' ? '✓' : '!'}</span>
                <div style={{ minWidth: 0 }}>
                  <div className="mc-probe-title">{testView.cls === 'ok' ? '探测成功' : '探测未通过'}</div>
                  <div className="mc-probe-sub">
                    {testView.cls === 'ok'
                      ? (testTools.length > 0
                        ? `连接正常，获取以下工具列表（共 ${testTools.length} 个）`
                        : '连接正常，但未发现可用工具')
                      : testView.text}
                  </div>
                </div>
              </div>
              <div className="mc-probe-meta">
                {testRes.latencyMs}ms · {testRes.at}
                {probeLevelLabel(testRes.probeLevel) ? ` · ${probeLevelLabel(testRes.probeLevel)}` : ''}
                {testView.cls === 'ok' && testRes.message ? ` · ${testRes.message}` : ''}
              </div>
              {testView.cls === 'ok' && testTools.length > 0 && (
                <ul className="mc-probe-list" data-testid="connector-probe-list">
                  {testTools.map((t, i) => (
                    <li className="mc-probe-item" key={`${t.name}-${i}`}>
                      <span className="mc-probe-idx">{i + 1}</span>
                      <div className="mc-probe-body">
                        <div className="mc-probe-name">{t.name}</div>
                        {t.description && <div className="mc-probe-desc">{t.description}</div>}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

        </div>

        <div className="mc-df">
          {/* 左侧：危险操作（断开，行内二次确认） */}
          <div className="mc-df-left">
            {live.connected && (
              confirmDisc ? (
                <span className="mc-confirmbar">
                  确定断开？
                  <button className="mc-btn danger" disabled={busy} onClick={() => void disconnect()}>确认断开</button>
                  <button className="mc-btn" onClick={() => setConfirmDisc(false)}>取消</button>
                </span>
              ) : (
                <button className="mc-btn danger" disabled={busy} onClick={() => setConfirmDisc(true)}>断开连接</button>
              )
            )}
          </div>
          {/* 右侧：测试 + 主 CTA + 关闭 */}
          <button
            className="mc-btn"
            disabled={testing || !usable}
            title={usable ? '真实调用后端测试连接' : '先完成授权后才能测试连接'}
            onClick={() => void runTest()}
          >
            {testing ? '测试中…' : '测试连接'}
          </button>
          {connector.authType === 'oauth2' && !usable && (
            <button
              className="mc-btn primary"
              disabled={phase === 'starting' || phase === 'waiting'}
              onClick={() => void oauth.begin()}
            >
              {phase === 'starting' ? '正在发起…' : phase === 'waiting' ? '等待授权…' : reauth ? '重新授权' : '开始授权'}
            </button>
          )}
          {credAuthType && (
            <button
              className="mc-btn primary"
              disabled={busy}
              onClick={() => void saveCredentials(credAuthType, credForm, requiredKeys)}
            >
              {busy ? '保存中…' : live.connected ? '更新凭证' : '保存并连接'}
            </button>
          )}
          <button className="mc-btn" onClick={onClose}>关闭</button>
        </div>
      </aside>
    </>
  )
}
