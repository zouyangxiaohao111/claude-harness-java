import { useCallback, useEffect, useRef, useState } from 'react'
import QRCode from 'qrcode'
import { openExternalUrl } from '@/utils/openExternal'
import { ApiError } from '@/api/rest'
import {
  imApi,
  type ImChat,
  type ImConfig,
  type ImInstance,
  type ImPlatform,
  type WechatLogProbe,
} from '@/api/im'
import './imChannel.css'

/**
 * IM 频道页（左侧边栏一级入口）。
 *
 * <h2>两个 tab 的边界</h2>
 * <ul>
 *   <li><b>频道</b> = 实例（连上了哪些账号）。实例不归属任务 —— 任何任务都能用它发消息。</li>
 *   <li><b>任务</b> = 聊天 ↔ 任务的接线（V305 聊天目录 + V255 绑定表）。</li>
 * </ul>
 *
 * <p>⛔ 旧的「扫描」tab（{@code im_scan_rule} 的扫描规则）已删除：
 * 那套机制是「定时筛本机微信记录 → 命中就唤醒 agent」，与新的两层结构不兼容
 * （消息扫描是**非 agent 任务**，见任务页的「系统任务」区块）。
 *
 * <h2>为什么对话不在这里</h2>
 * 用户裁定「对话肯定是现在的」⇒ 本页只做「管理面」；点「打开任务」切回既有对话页
 * （{@code onOpenSession}），复用现有 MessageList / Composer，不新写一套聊天界面。
 *
 * <h2>样式</h2>
 * 全部走 {@code imChannel.css}（{@code imc-} 前缀）。⛔ 不要改成复用
 * {@code globals.css} 里的 {@code .tab} / {@code .toast} —— 那两个类被全站别处用着。
 */

export const PLATFORM_LABEL: Record<ImPlatform, string> = {
  wechat: '微信',
  feishu: '飞书',
  wecom: '企业微信',
  dingtalk: '钉钉',
}
export const PLATFORM_CHAR: Record<ImPlatform, string> = {
  wechat: '微',
  feishu: '飞',
  wecom: '企',
  dingtalk: '钉',
}
const PLATFORM_DESC: Record<ImPlatform, string> = {
  wechat: '扫码创建机器人，接收并回复私聊消息（iLink Bot）',
  feishu: 'WebSocket 长连接，支持私聊与群聊（群内 @ 触发）',
  wecom: '智能机器人长连接，支持私聊与群聊（群内 @ 触发）',
  dingtalk: 'Stream 长连接，支持私聊与群聊（群内 @ 触发）',
}
const ALL_PLATFORMS: ImPlatform[] = ['wechat', 'feishu', 'dingtalk', 'wecom']

/** 凭证表单规格 —— 字段名与后端/平台文档一致，不做同义改写。 */
const CRED_SPEC: Partial<Record<ImPlatform, { title: string; hint: string; fields: Array<{ k: string; label: string; ph: string }> }>> = {
  dingtalk: {
    title: '添加钉钉',
    hint: '在钉钉开放平台 → 应用开发 → 机器人，开启「Stream 模式」后获取；无需公网地址。',
    fields: [
      { k: 'clientId', label: 'ClientID（AppKey）', ph: 'dingxxxxxxxxxxxxxxxx' },
      { k: 'clientSecret', label: 'ClientSecret（AppSecret）', ph: '••••••••••••••••' },
      { k: 'robotName', label: '机器人显示名', ph: 'NexusAI' },
    ],
  },
  wecom: {
    title: '添加企业微信',
    hint: '在企微管理后台 → 工作台 → 智能机器人 → 新建 → API 模式 → 长连接，复制 BotID 与 Secret。',
    fields: [
      { k: 'botId', label: 'BotID', ph: 'aibXXXXXXXXXXXX' },
      { k: 'secret', label: 'Secret', ph: '••••••••••••••••' },
      { k: 'botName', label: '机器人显示名', ph: 'NexusAI' },
    ],
  },
  feishu: {
    title: '添加飞书',
    // 飞书走长连接（程序主动连出到飞书），不需要公网地址；凭证只有 App ID + App Secret 两项必填。
    hint: '在飞书开放平台 → 开发者后台 → 你的应用 → 凭证与基础信息，复制 App ID 与 App Secret。长连接由程序主动连出，不用填任何网址。',
    fields: [
      { k: 'appId', label: 'App ID', ph: 'cli_xxxxxxxxxxxx' },
      { k: 'appSecret', label: 'App Secret', ph: '••••••••••••••••' },
    ],
  },
}

/** 「改接到…」弹窗里的哨兵值：选中表示「新建一个任务并接上」 */
const NEW_TASK_PICK = '__new_task__'


interface DrawerState {
  kind: 'cred' | 'security' | 'addChannel' | 'addFeishu' | 'qr' | 'channel' | 'oauth' | 'perms'
  platform?: ImPlatform
  instanceId?: string
}

/** 飞书添加流程的三个页面：先选分支，再进对应分支。 */
type FeishuBranch = 'choose' | 'existing' | 'create'

interface ConfirmState {
  kind: 'rebind' | 'approve' | 'remove'
  chatId?: string
  instanceId?: string
}

export interface ImChannelPageProps {
  showToast: (msg: string, type?: 'success' | 'info') => void
  /** 「打开任务」→ 切回既有对话页（对话本身不在这里实现） */
  onOpenSession: (sessionId: string) => void
  /** 可接线的任务列表（改接到选择器用） */
  sessions: Array<{ id: string; title: string }>
  /** 扫描授权状态（本机微信磁盘权限）—— 目前由设置页/后端能力位决定，默认已授权 */
  diskAuthorized?: boolean
}

export function ImChannelPage({ showToast, sessions }: ImChannelPageProps) {

  const [instances, setInstances] = useState<ImInstance[]>([])
  const [chats, setChats] = useState<ImChat[]>([])

  /**
   * 入站监听是否正在跑（instanceId → running），来自 {@code GET /im/inbound}。
   *
   * <p>为什么不能只看实例的 {@code status}：status 表达的是「长连接握手成功」，
   * 用户点过「启动连接」后即使还在握手/重连，监听也已经在跑 ⇒ 按钮必须显示「断开连接」，
   * 否则用户会以为没启动、反复点（或去查配置）。
   */
  const [inboundRunning, setInboundRunning] = useState<Record<string, boolean>>({})

  /**
   * 飞书实例的「以你本人名义发」授权状态（instanceId → 已授权）。
   *
   * <p>为什么不塞进 Instances 接口：token 相关字段不在 instance 的领域模型里（那是凭证层），
   * 而实例数量很少（通常 1 个飞书）⇒ 单独查几次比扩 DTO 更干净。
   * ⚠️ 查失败当「未授权」：显示成「未授权 · 可去管理页开通」而不是假装已授权。
   */
  const [feishuAuth, setFeishuAuth] = useState<Record<string, {
    authorized: boolean
    missing: number
    /** 后端报告的缺失 scope（用于「权限清单」逐项打勾） */
    missingScopes: string[]
    contacts?: boolean
  }>>({})

  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)


  const [drawer, setDrawer] = useState<DrawerState | null>(null)
  const [confirm, setConfirm] = useState<ConfirmState | null>(null)
  const [rebindPick, setRebindPick] = useState<string | null>(null)

  // 凭证表单
  const [credForm, setCredForm] = useState<Record<string, string>>({})
  /** 飞书添加流程当前页（选择 / 已有机器人 / 创建指引）。 */
  const [feishuBranch, setFeishuBranch] = useState<FeishuBranch>('choose')

  /**
   * 是否成功加载过至少一次。
   *
   * <p>为什么需要它：`loading` 决定整块内容是否被替换成「加载中…」。
   * 每次刷新都置 true 的话，页面会**整体闪一下**（内容→加载中→内容），
   * 而刷新通常是「同一份数据的新版本」，应该原地更新而不是重画。
   * ⇒ 只有首次（还没有任何数据）才显示加载态，之后静默刷新。
   */
  const loadedOnceRef = useRef(false)

  const refresh = useCallback(async () => {
    if (!loadedOnceRef.current) {
      setLoading(true)
    }
    setError(null)
    try {
      const [ins, ch, inbound] = await Promise.all([
        imApi.listInstances(),
        imApi.listChats().catch(() => [] as ImChat[]),
        // 入站运行态：查不到就当「都没在跑」（按钮回落到「启动连接」）
        imApi.listInbound().catch(() => [] as Array<{ instanceId: string; running: boolean }>),
      ])
      loadedOnceRef.current = true
      setInstances(ins)
      setChats(ch)
      setInboundRunning(Object.fromEntries(inbound.map((x) => [x.instanceId, x.running])))
      // 飞书卡片要标「已授权 · 你本人」——只对飞书实例查，其它平台没这个概念
      const feishuIds = ins.filter((i) => i.platform === 'feishu').map((i) => i.id)
      if (feishuIds.length > 0) {
        const pairs = await Promise.all(
          feishuIds.map(async (id) => {
            try {
              const st = await imApi.oauthStatus(id)
              // 通讯录权限单独查：它决定飞书对方能不能显示人名（不影响收发）
              let contacts = false
              try {
                contacts = (await imApi.contactsPermission(id)).granted
              } catch {
                contacts = false
              }
              const missingScopes = st.missingScopes ?? []
              return [id, {
                authorized: st.authorized,
                missing: missingScopes.length,
                missingScopes,
                contacts,
              }] as const
            } catch {
              return [id, { authorized: false, missing: 0, missingScopes: [], contacts: false }] as const
            }
          }),
        )
        setFeishuAuth(Object.fromEntries(pairs))
      }
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  /**
   * 握手结果是**异步回写**的（启动后 1~3 秒才变 connected/error），页面本身没有推送。
   * 不轮询的话会一直停在启动时写下的「正在连接飞书长连接…」——用户会以为没连上。
   * ⇒ 有「在跑但仍是 disconnected」的实例时，每 2 秒补拉一次；收到 connected/error 或
   * 停止后自然退出。上限 15 次（约 30 秒），避免异常时无限打接口。
   */
  const statusPollBudgetRef = useRef(0)
  useEffect(() => {
    const pending = instances.some((i) => inboundRunning[i.id] && i.status === 'disconnected')
    if (!pending) {
      statusPollBudgetRef.current = 0
      return
    }
    if (statusPollBudgetRef.current >= 15) return
    const t = window.setTimeout(() => {
      statusPollBudgetRef.current += 1
      void refresh()
    }, 2000)
    return () => window.clearTimeout(t)
  }, [instances, inboundRunning, refresh])

  /** 「在线」= 入站长连接正在跑（只对实现入站的平台有意义），与「已接入」两个口径分开 */
  const connectedCount = instances.filter((i) => i.status === 'connected').length

  /**
   * 启动某实例的入站长连接。
   *
   * <p>为什么需要按钮而不是建实例时自动连：连上就会真实收消息并触发 agent，
   * 用户可能只是想先把凭证存下来。启动结果以 DB 的 status 为准，前端不自行推断。
   */
  const startInbound = async (id: string) => {
    try {
      const r = await imApi.startInbound(id)
      showToast(r.started ? '已启动连接' : (r.reason ?? '未能启动连接'), r.started ? 'success' : 'info')
    } catch (e) {
      showToast(errMsg(e), 'info')
    }
    // 无论成败都刷新：状态由后端回写，前端只展示
    await refresh()
  }

  /** 断开连接（停止入站长连接）。与卡片上的「移除机器人」不同：那个是删除实例。 */
  const stopInbound = async (id: string) => {
    try {
      const r = await imApi.stopInbound(id)
      showToast(r.stopped ? '已断开连接' : '连接本来就没开', r.stopped ? 'success' : 'info')
    } catch (e) {
      showToast(errMsg(e), 'info')
    }
    await refresh()
  }
  // ── 打开抽屉时的初始化 ─────────────────────────────────────────
  const openCredDrawer = (platform: ImPlatform) => {
    // 飞书：先让用户选「已有机器人（填凭证）」还是「没有机器人（看创建指引）」。
    // 不做登录态自动探测 —— 飞书是长连接，凭证直接连即可（用户 2026-11-22 裁定）。
    if (platform === 'feishu') {
      setCredForm({})
      setFeishuBranch('choose')
      setDrawer({ kind: 'addFeishu' })
      return
    }
    // 企微 / 微信走「一键创建」；钉钉只能填凭证（平台没有授权流程）
    if (platform === 'wechat' || platform === 'wecom') {
      setDrawer({ kind: 'qr', platform })
      return
    }
    const spec = CRED_SPEC[platform]
    if (!spec) return
    setCredForm({})
    setDrawer({ kind: 'cred', platform })
  }

  /**
   * 校验平台凭证：返回第一条错误（null = 通过）。
   *
   * <p>飞书只校验两项必填 + App ID 前缀：飞书应用 ID 一律以 {@code cli_} 开头，
   * 把 App ID 与 App Secret 填反是常见错法，格式不对当场说清，别等建连失败。
   */
  const credentialError = (platform: ImPlatform): string | null => {
    const spec = CRED_SPEC[platform]
    if (!spec) return '该平台暂不支持填写凭证'
    const missing = spec.fields.filter((f) => !(credForm[f.k] ?? '').trim())
    if (missing.length > 0) return '请填完所有必填项'
    if (platform === 'feishu' && !/^cli_[A-Za-z0-9]+$/.test((credForm.appId ?? '').trim())) {
      return 'App ID 格式不对：应以 cli_ 开头（在飞书「凭证与基础信息」页复制）'
    }
    return null
  }

  /** 保存凭证并建实例（普通凭证抽屉与飞书「已有机器人」分支共用）。 */
  const saveCredential = async (platform: ImPlatform) => {
    const spec = CRED_SPEC[platform]
    if (!spec) return
    const err = credentialError(platform)
    if (err) {
      showToast(err, 'info')
      return
    }
    // 显示名只认明确的名字字段；飞书表单没有名字项 ⇒ 用平台名兜底。
    const nameKey = spec.fields.find((f) => f.k === 'botName' || f.k === 'robotName')?.k
    const displayName = (nameKey ? credForm[nameKey] : '') || PLATFORM_LABEL[platform]
    const credential = Object.fromEntries(
      spec.fields.map((f) => [f.k, (credForm[f.k] ?? '').trim()]),
    )
    setBusy(true)
    try {
      await imApi.createInstance({
        platform,
        displayName,
        credential: JSON.stringify(credential),
      })
      // 后端建实例后会自动启动连接（用户定）；这里只说「正在自动连接」，
      // 不让人以为还要再点一次。失败时卡片上仍有「启动连接」可手动重试。
      showToast(`${PLATFORM_LABEL[platform]} 已保存，正在自动连接…`, 'success')
      setDrawer(null)
      await refresh()
    } catch (e) {
      showToast(errMsg(e), 'info')
    } finally {
      setBusy(false)
    }
  }

  /**
   * 测试连接：用表单里**未保存**的凭证让后端真打一次换票（飞书）。
   *
   * <p>⛔ 不能再像旧实现那样直接弹「连接成功」——那会让人以为配好了。
   * 结果以飞书侧返回为准：成功给机器人名/可见群聊数，失败给平台码与解释。
   */
  const testCredential = async (platform: ImPlatform) => {
    const spec = CRED_SPEC[platform]
    if (!spec) return
    const err = credentialError(platform)
    if (err) {
      showToast(err, 'info')
      return
    }
    const credential = Object.fromEntries(
      spec.fields.map((f) => [f.k, (credForm[f.k] ?? '').trim()]),
    )
    setBusy(true)
    try {
      const r = await imApi.testConnection({ platform, credential: JSON.stringify(credential) })
      showToast(r.message || (r.ok ? '连接成功' : '连接失败'), r.ok ? 'success' : 'info')
    } catch (e) {
      showToast(errMsg(e), 'info')
    } finally {
      setBusy(false)
    }
  }

  /**
   * 同步会话：**默认按能力取最全**。
   *
   * <p>已授权本人身份且权限齐全 ⇒ 用 user token 拉「你本人所在的所有群」；
   * 否则退回「机器人所在的群」。两类都会把会话写进目录，区别只在范围。
   * ⛔ 飞书没有「列出我的单聊」接口，p2p 不会出现在结果里（硬限制）。
   */
  const syncChats = async (id: string) => {
    const st = feishuAuth[id]
    const canUser = !!st?.authorized && (st.missing ?? 1) === 0
    try {
      const r = canUser ? await imApi.syncFeishuUserChats(id) : await imApi.syncFeishuChats(id)
      const base = r.message || `已同步 ${r.total} 个会话（新建 ${r.created} / 更新 ${r.updated}）`
      const scope = canUser
        ? ''
        : '；当前只同步了机器人所在的群——授权本人身份后会改为同步你所在的所有群'
      const p2p = r.total === 0 ? '；飞书没有「列出我的单聊」接口，私聊要等对方先发一条消息' : ''
      showToast(`${base}${scope}${p2p}`, r.total > 0 ? 'success' : 'info')
      await refresh()
    } catch (e) {
      showToast(`同步会话失败：${errMsg(e)}`, 'info')
    }
  }

  /**
   * 授权入口（用户 2026-11-23 定）：点「去授权 / 补权限 / 已授权·重新授权」
   * **一律先打开权限清单页**，不要在入口处直接发起授权（不要默认打开浏览器）。
   * 真正的授权请求放在清单页内的「去授权页点同意」按钮上。
   * 清单页里同时有：用户身份 scope（点授权页同意即可）+ 管理员需要配置的项。
   */
  const openAuthorize = (id: string) => {
    setDrawer({ kind: 'perms', instanceId: id })
  }

  // ── 渲染 ──────────────────────────────────────────────────────
  return (
    <div className="imc-root im-channel-page">
      <div className="imc-head">
        <h3>IM 频道</h3>
        <span className="imc-sub">
          {/* ⚠️ 「已接入」= 已配置的实例数，不是「当前长连接在线数」。
              长连接只对企微/微信/飞书入站生效，且重启后未接线的实例会停在 disconnected；
              用 status==='connected' 计数会让「配了 3 个渠道」显示成「0 个已接入」。 */}
          {instances.length} 个已接入
          {connectedCount > 0 && ` · ${connectedCount} 个在线`}
        </span>
        <div className="imc-right">
          <button className="imc-btn sm" onClick={() => setDrawer({ kind: 'security' })}>
            安全设置
          </button>
          <button className="imc-btn sm pri" onClick={() => setDrawer({ kind: 'addChannel' })}>
            ＋ 添加渠道
          </button>
        </div>
      </div>

      <div className="imc-body">
        <div className="imc-wrap">
          {error && <div className="imc-error">{error}</div>}
          {loading ? (
            <div className="imc-loading">加载中…</div>
          ) : (
            <ChannelsTab instances={instances} feishuAuth={feishuAuth} inboundRunning={inboundRunning} onAdd={openCredDrawer} onStartInbound={startInbound} onStopInbound={stopInbound} onSyncChats={syncChats} onAuthorize={openAuthorize} onManage={(id) => { void refresh(); setDrawer({ kind: 'channel', instanceId: id }) }} onRemove={(id) => setConfirm({ kind: 'remove', instanceId: id })} />
          )}
        </div>
      </div>

      {/* ── 抽屉 ────────────────────────────────────────────── */}
      {drawer && (
        <>
          <div className="imc-scrim" onClick={() => setDrawer(null)} />
          <div className="imc-drawer" role="dialog" aria-modal="true">
            <div className="imc-dh">
              <h4>{drawerTitle(drawer)}</h4>
              <button className="imc-x" onClick={() => setDrawer(null)} aria-label="关闭">
                ×
              </button>
            </div>
            <div className="imc-db">
              {drawer.kind === 'cred' && drawer.platform && CRED_SPEC[drawer.platform] && (
                <CredForm platform={drawer.platform} form={credForm} setForm={setCredForm} />
              )}
              {drawer.kind === 'addFeishu' && feishuBranch === 'choose' && (
                <FeishuBranchPicker onPick={setFeishuBranch} />
              )}
              {drawer.kind === 'addFeishu' && feishuBranch === 'existing' && (
                <>
                  <CredForm platform="feishu" form={credForm} setForm={setCredForm} />
                  <button
                    className="imc-btn sm ghost"
                    style={{ marginTop: 12 }}
                    onClick={() => setFeishuBranch('create')}
                  >
                    还没有机器人？查看创建步骤
                  </button>
                </>
              )}
              {drawer.kind === 'addFeishu' && feishuBranch === 'create' && (
                <FeishuCreateGuide
                  onDone={() => setFeishuBranch('existing')}
                  // 一键创建 = 官方 device flow（新建应用并自动拿到凭证）；
                  // ⛔ 不是「探测已有应用」——那需要浏览器登录态，后端拿不到。
                  onAutoCreate={() => setDrawer({ kind: 'qr', platform: 'feishu' })}
                />
              )}
              {drawer.kind === 'addChannel' && <AddChannelList instances={instances} onPick={openCredDrawer} />}
              {drawer.kind === 'qr' && drawer.platform && <QrPanel platform={drawer.platform} onConnected={async (newInstanceId) => {
                showToast('已连接', 'success')
                setDrawer(null)
                await refresh()
                // 飞书：机器人建好后立即检查「能不能以你本人名义发」——
                // 没有 user token 就直接把授权抽屉推给用户（用户定：建/绑成功后触发）
                if (drawer.platform === 'feishu' && newInstanceId) {
                  try {
                    const st = await imApi.oauthStatus(newInstanceId)
                    if (!st.authorized) {
                      setDrawer({ kind: 'oauth', instanceId: newInstanceId })
                    }
                  } catch {
                    // 查不到就静默跳过：授权引导不该把「建渠道成功」这一步变成失败
                  }
                }
              }} />}
              {drawer.kind === 'security' && <SecurityPanel />}
              {drawer.kind === 'oauth' && drawer.instanceId && (
                <OauthPanel
                  instanceId={drawer.instanceId}
                  onAuthorized={() => { showToast('已授权：可以以你本人名义发消息了', 'success'); void refresh() }}
                />
              )}
              {drawer.kind === 'perms' && drawer.instanceId && (
                <FeishuPermissionPanel
                  instanceId={drawer.instanceId}
                  auth={feishuAuth[drawer.instanceId]}
                  onRecheck={() => { void refresh() }}
                  onAuthorize={() => setDrawer({ kind: 'oauth', instanceId: drawer.instanceId! })}
                />
              )}
              {drawer.kind === 'channel' && (
                <ChannelDetail
                  instance={instances.find((i) => i.id === drawer.instanceId) ?? null}
                  running={!!inboundRunning[drawer.instanceId ?? '']}
                />
              )}
            </div>
            <div className="imc-df">
              {drawer.kind === 'cred' && drawer.platform && (
                <>
                  <button className="imc-btn ghost" onClick={() => setDrawer(null)}>取消</button>
                  <button
                    className="imc-btn"
                    disabled={busy}
                    onClick={() => void testCredential(drawer.platform!)}
                  >
                    测试连接
                  </button>
                  <button
                    className="imc-btn pri"
                    disabled={busy}
                    onClick={() => void saveCredential(drawer.platform!)}
                  >
                    保存并连接
                  </button>
                </>
              )}
              {drawer.kind === 'addFeishu' && feishuBranch === 'choose' && (
                <button className="imc-btn ghost" onClick={() => setDrawer(null)}>关闭</button>
              )}
              {drawer.kind === 'addFeishu' && feishuBranch === 'existing' && (
                <>
                  <button className="imc-btn ghost" onClick={() => setDrawer(null)}>取消</button>
                  <button
                    className="imc-btn"
                    disabled={busy}
                    onClick={() => void testCredential('feishu')}
                  >
                    测试连接
                  </button>
                  <button
                    className="imc-btn pri"
                    disabled={busy}
                    onClick={() => void saveCredential('feishu')}
                  >
                    保存并连接
                  </button>
                </>
              )}
              {drawer.kind === 'addFeishu' && feishuBranch === 'create' && (
                <button className="imc-btn ghost" onClick={() => setFeishuBranch('choose')}>返 回</button>
              )}
              {drawer.kind === 'addChannel' && (
                <button className="imc-btn ghost" onClick={() => setDrawer(null)}>关闭</button>
              )}
              {drawer.kind === 'qr' && (
                <>
                  {/*
                    ⛔ 这里曾有「我已完成扫码」按钮，只弹一句硬编码提示、什么都不做（而且是错的平台文案）。
                    扫码抽屉里的 QrPanel 挂载即 startQr、并自动轮询到成功 ⇒ 不需要人点「我已完成」。
                  */}
                  <button className="imc-btn ghost" onClick={() => setDrawer(null)}>取消</button>
                </>
              )}
              {drawer.kind === 'oauth' && (
                <button className="imc-btn ghost" onClick={() => setDrawer(null)}>稍后</button>
              )}
              {drawer.kind === 'perms' && (
                <>
                  <button className="imc-btn ghost" onClick={() => setDrawer(null)}>关闭</button>
                  <button
                    className="imc-btn pri"
                    onClick={() => setDrawer({ kind: 'oauth', instanceId: drawer.instanceId! })}
                  >
                    {feishuAuth[drawer.instanceId ?? '']?.authorized ? '重新授权' : '去授权'}
                  </button>
                </>
              )}
              {drawer.kind === 'security' && (
                <>
                  <button className="imc-btn ghost" onClick={() => setDrawer(null)}>取消</button>
                  <button className="imc-btn pri" onClick={() => { setDrawer(null); showToast('安全设置已保存', 'success') }}>
                    保存
                  </button>
                </>
              )}
              {drawer.kind === 'channel' && drawer.instanceId && (
                <>
                  <button className="imc-btn ghost" onClick={() => setDrawer(null)}>关闭</button>
                  <button
                    className="imc-btn danger"
                    onClick={() => {
                      const id = drawer.instanceId!
                      setDrawer(null)
                      setConfirm({ kind: 'remove', instanceId: id })
                    }}
                  >
                    移除机器人
                  </button>
                  {/* 「断开连接」只关当前长连接，不删实例/凭证；样式与「移除机器人」同为红框 */}
                  {inboundRunning[drawer.instanceId] ? (
                    <button
                      className="imc-btn danger"
                      disabled={busy}
                      onClick={() => void stopInbound(drawer.instanceId!)}
                    >
                      断开连接
                    </button>
                  ) : (
                    <button
                      className="imc-btn pri"
                      disabled={busy}
                      onClick={() => void startInbound(drawer.instanceId!)}
                    >
                      启动连接
                    </button>
                  )}
                </>
              )}
            </div>
          </div>
        </>
      )}

      {/* ── 对话框 ──────────────────────────────────────────── */}
      {confirm && (
        <>
          <div className="imc-scrim" onClick={() => setConfirm(null)} />
          <div className="imc-dlg" role="dialog" aria-modal="true">
            <div className="imc-dlgh">{confirmTitle(confirm)}</div>
            <div className="imc-dlgb">
              {confirm.kind === 'rebind' && (
                <RebindPicker
                  chat={chats.find((c) => c.id === confirm.chatId) ?? null}
                  sessions={sessions}
                  pick={rebindPick}
                  onPick={setRebindPick}
                />
              )}
              {confirm.kind === 'remove' && (
                <>
                  移除机器人「{instances.find((i) => i.id === confirm.instanceId)?.displayName ?? ''}」？
                  <br />
                  <span className="imc-hint">
                    移除后这个机器人不再收消息，已接线的任务会变成「未接线」；凭证一起删除，
                    要再连需重新添加。只想临时断开、以后再连请用「断开连接」，别移除。
                  </span>
                </>
              )}
            </div>
            <div className="imc-dlgf">
              <button className="imc-btn ghost" onClick={() => setConfirm(null)}>取消</button>
              <button
                className={`imc-btn ${confirm.kind === 'remove' ? 'danger' : 'pri'}`}
                disabled={busy || (confirm.kind === 'rebind' && !rebindPick)}
                onClick={async () => {
                  setBusy(true)
                  try {
                    if (confirm.kind === 'rebind' && rebindPick && confirm.chatId) {
                      if (rebindPick === NEW_TASK_PICK) {
                        const r = await imApi.bindChatToNewTask(confirm.chatId)
                        showToast(`已新建任务「${r.sessionTitle}」并接到它`, 'success')
                      } else {
                        await imApi.bindChat(confirm.chatId, rebindPick, 'manual')
                        showToast(`已改接到「${sessions.find((s) => s.id === rebindPick)?.title ?? rebindPick}」`, 'success')
                      }
                      // [A 预热提示] 微信未激活 ⇒ 提示一次（绑定已生效，只告知手机上暂时收不到）
                      void imApi.listChatActivations().then((acts) => {
                        const a = acts.find((x) => x.chatId === confirm.chatId)
                        if (a?.activationRequired && !a.activated) {
                          showToast(a.hint ?? '微信渠道需先发一条消息激活，否则通知发不出去', 'info')
                        }
                      }).catch(() => { /* 查不到就不提示 */ })
                      await refresh()
                    } else if (confirm.kind === 'remove' && confirm.instanceId) {
                      await imApi.removeInstance(confirm.instanceId)
                      showToast('已移除机器人')
                      await refresh()
                    }
                    setConfirm(null)
                  } catch (e) {
                    showToast(errMsg(e), 'info')
                  } finally {
                    setBusy(false)
                  }
                }}
              >
                {confirm.kind === 'rebind'
                  ? rebindPick === NEW_TASK_PICK
                    ? '新建任务并接到它'
                    : rebindPick
                      ? `改接到「${sessions.find((s) => s.id === rebindPick)?.title ?? rebindPick}」`
                      : '请先选一个任务'
                  : confirm.kind === 'approve'
                    ? '确认并执行'
                    : '移除机器人'}
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  )
}

/* ===================== 子视图 ===================== */

function ChannelsTab({
  instances,
  feishuAuth,
  inboundRunning,
  onAdd,
  onStartInbound,
  onStopInbound,
  onSyncChats,
  onAuthorize,
  onManage,
  onRemove,
}: {
  instances: ImInstance[]
  /** 飞书实例的授权状态（instanceId → 是否已授权 + 缺哪些 scope + 通讯录） */
  feishuAuth: Record<string, {
    authorized: boolean
    missing: number
    missingScopes: string[]
    contacts?: boolean
  }>
  /** 入站监听运行态（instanceId → running）；按钮据此在「启动/断开连接」之间切换 */
  inboundRunning: Record<string, boolean>
  onAdd: (p: ImPlatform) => void
  /** 启动某实例的入站长连接；状态由后端回写后再刷新 */
  onStartInbound: (id: string) => Promise<void>
  /** 断开连接（停长连接，但保留实例） */
  onStopInbound: (id: string) => Promise<void>
  /** 拉飞书会话进聊天目录（已授权按本人全部群，否则机器人所在群） */
  onSyncChats: (id: string) => Promise<void>
  /** 授权入口：未授权/已齐全直接开授权页；已授权但缺 scope 则打开权限清单 */
  onAuthorize: (id: string) => void
  onManage: (id: string) => void
  /** 移除机器人（删除实例与凭证；与「断开连接」是两件事） */
  onRemove: (id: string) => void
}) {
  // ⚠️ error 也要留在「已接入」里：否则连接失败时卡片消失，用户看不到原因、
  // 也点不到「重新连接」——状态回写了却无法展示。
  const connected = instances.filter((i) =>
    i.status === 'connected' || i.status === 'disconnected' || i.status === 'error')
  const countOf = (p: ImPlatform) => connected.filter((i) => i.platform === p).length
  /** 正在启动的实例 id（按钮态，防止连点起两条连接）。 */
  const [startingId, setStartingId] = useState<string | null>(null)
  /** 正在断开连接的实例 id（按钮态）。 */
  const [stoppingId, setStoppingId] = useState<string | null>(null)
  /** 正在同步的实例 id（按钮态）。 */
  const [syncingId, setSyncingId] = useState<string | null>(null)
  return (
    <>
      <div className="imc-grp">
        <span className="imc-gt">已接入</span>
        <span className="imc-gc">{connected.length}</span>
      </div>
      {connected.length === 0 ? (
        <div className="imc-empty" style={{ padding: '28px 20px' }}>
          <div className="imc-eb">还没有接入任何渠道</div>
          <div className="imc-es">从下面挑一个平台，扫码或填凭证即可</div>
        </div>
      ) : (
        connected.map((c) => (
          <div className="imc-card" key={c.id}>
            <div className="imc-card-h">
              <span className={`imc-plat ${c.platform}`}>{PLATFORM_CHAR[c.platform]}</span>
              <span className="imc-t">{c.displayName}</span>
              <span className={`imc-st ${c.status === 'connected' ? 'ok' : c.status === 'error' ? 'err' : 'off'}`}>
                {c.status === 'connected' ? '已连接' : c.status === 'error' ? '异常' : '未连接'}
              </span>
            </div>
            <div className="imc-card-sub">
              {PLATFORM_LABEL[c.platform]} · {PLATFORM_DESC[c.platform]}
              {c.platform === 'feishu' && (
                feishuAuth[c.id]?.authorized ? (
                  feishuAuth[c.id]!.missing > 0 ? (
                    // 已授权但缺 scope：token 是「授权那一刻」的权限快照，
                    // 我们后来加的能力它不会有 ⇒ 必须明确告诉用户「要重新授权」，
                    // 否则表现成「某功能莫名不可用」，用户完全不知道该做什么。
                    <span className="imc-st wait" style={{ marginLeft: 8 }} title="授权时拿到的权限是快照；控制台后加的权限需重新授权一次">
                      需重新授权（缺 {feishuAuth[c.id]!.missing} 项）
                    </span>
                  ) : (
                    <span className="imc-st ok" style={{ marginLeft: 8 }} title="可以以你本人名义发消息（对方看到的是你的头像）">
                      已授权 · 你本人
                    </span>
                  )
                ) : (
                  <span className="imc-st off" style={{ marginLeft: 8 }} title="控制台「已开通」只代表应用拥有该权限；还要你在浏览器授权页点「同意」，本人身份才生效">
                    本人身份未授权（需浏览器同意）
                  </span>
                )
              )}
            </div>
            <div className="imc-card-meta">
              {c.lastActiveAt ? <span>最后活跃 <b>{formatTime(c.lastActiveAt)}</b></span> : <span>尚未活跃</span>}
              {c.statusDetail && <span>{c.statusDetail}</span>}
            </div>
            {/* 飞书专有：「对方名字能不能显示」取决于**应用身份**通讯录权限。
                ⚠️ 这是与 OAuth scope 完全不同的另一套权限 —— 用户把授权页点穿也不会生效。
                没开通不影响收发，只是对方显示成 ou_xxx。 */}
            {c.platform === 'feishu' && (
              <div className="imc-card-meta">
                {feishuAuth[c.id]?.contacts ? (
                  <span title="能把飞书对方换成真实姓名">通讯录权限 已开通</span>
                ) : (
                  // 该权限只决定「对方显示名」，收发消息完全不受影响 ⇒ 中性说明即可。
                  // ⛔ 不要再放「去开通」：飞书开放平台的权限页在 WebView 里打不开，
                  // 点了没反应比没有按钮更糟（用户 2026-11-22 报的就是这个）。
                  <span title="不影响收发消息，只影响飞书对方的显示名">对方会显示为 ou_xxx（通讯录权限未开通，不影响收发）</span>
                )}
              </div>
            )}
            <div className="imc-card-acts">
              {/* 连接开关：以「入站监听是否在跑」为准（不是握手的 status）。
                  已启动 ⇒ 显示「断开连接」；否则显示「启动连接 / 重新连接」。
                  ⚠️ 与右侧「移除机器人」不同：那个是删除整个实例。 */}
              {inboundRunning[c.id] ? (
                <button
                  className="imc-btn sm danger"
                  disabled={stoppingId === c.id}
                  onClick={async () => {
                    setStoppingId(c.id)
                    try {
                      await onStopInbound(c.id)
                    } finally {
                      setStoppingId(null)
                    }
                  }}
                >
                  {stoppingId === c.id ? '断开中…' : '断开连接'}
                </button>
              ) : (
                <button
                  className="imc-btn sm pri"
                  disabled={startingId === c.id}
                  onClick={async () => {
                    setStartingId(c.id)
                    try {
                      await onStartInbound(c.id)
                    } finally {
                      setStartingId(null)
                    }
                  }}
                >
                  {startingId === c.id ? '启动中…' : c.status === 'error' ? '重新连接' : '启动连接'}
                </button>
              )}
              {/* 飞书：授权「以你本人身份」发消息 / 读群与历史（OAuth）。
                  未授权 ⇒ 直接开授权页；已授权但缺 scope ⇒ 打开权限清单（同一个入口，不重复放按钮）。 */}
              {c.platform === 'feishu' && (
                <button className="imc-btn sm" onClick={() => onAuthorize(c.id)}>
                  {!feishuAuth[c.id]?.authorized
                    ? '去授权（浏览器同意）'
                    : (feishuAuth[c.id]?.missing ?? 0) > 0
                      ? '补权限 / 重新授权'
                      : '已授权 · 重新授权'}
                </button>
              )}
              {/* 飞书：同步会话进目录。已授权且权限齐全时自动按「本人所在全部群」同步；
                  否则退回「机器人所在的群」——不额外放第二个按钮（用户 2026-11-23 要求合并）。
                  ⚠️ 私聊不会出现在列表里（飞书不提供单聊列表接口）。 */}
              {c.platform === 'feishu' && (
                <button
                  className="imc-btn sm"
                  disabled={syncingId === c.id}
                  title={
                    feishuAuth[c.id]?.authorized && (feishuAuth[c.id]?.missing ?? 1) === 0
                      ? '读取你本人所在的所有群并拉历史消息（不含单聊：飞书没有单聊列表接口）'
                      : '未授权时只能同步机器人所在的群；授权本人身份后会改为同步你所在的所有群'
                  }
                  onClick={async () => {
                    setSyncingId(c.id)
                    try {
                      await onSyncChats(c.id)
                    } finally {
                      setSyncingId(null)
                    }
                  }}
                >
                  {syncingId === c.id ? '同步中…' : '同步会话'}
                </button>
              )}
              <button className="imc-btn sm" onClick={() => onManage(c.id)}>管理</button>
              <button className="imc-btn sm danger" onClick={() => onRemove(c.id)}>移除机器人</button>
            </div>
          </div>
        ))
      )}

      <div className="imc-grp">
        <span className="imc-gt">可添加 / 再加一个</span>
      </div>
      {ALL_PLATFORMS.map((p) => {
        const n = countOf(p)
        return (
          <div className="imc-card click" key={p} onClick={() => onAdd(p)}>
            <div className="imc-card-h">
              <span className={`imc-plat ${n > 0 ? p : 'none'}`}>{PLATFORM_CHAR[p]}</span>
              <span className="imc-t">{PLATFORM_LABEL[p]}</span>
              <span className={`imc-st ${n > 0 ? 'ok' : 'off'}`}>{n > 0 ? `已接入 ${n} 个` : '未配置'}</span>
              <span style={{ marginLeft: 'auto', fontSize: 12, color: 'var(--accent)' }}>
                {n > 0 ? '再加一个' : '添加'}
              </span>
            </div>
            <div className="imc-card-sub">{PLATFORM_DESC[p]}</div>
          </div>
        )
      })}
      <div className="imc-grp">
        <span className="imc-gt">本机微信聊天记录</span>
      </div>
      <LocalWechatLogCard />
      <div className="imc-hint">
        同一平台可添加多个实例（两个微信号、两个飞书租户）。实例不归属任何任务 —— 任何任务都能用它发消息。
      </div>
    </>
  )
}

/**
 * 本机微信聊天记录同步 —— 与上面的「微信渠道」是两条链。
 *
 * 渠道（iLink Bot）只能收发给机器人的新消息；历史记录只能读本机已登录微信的数据库。
 * 取密钥可能需要一次管理员授权（读微信进程内存）。后端会按真实分支
 * （版本未适配 / 扫描预算不足 / 取消提权 / 真的无权限 …）返回 error + remedy，
 * 这里原样展示 —— 不能写死成「需要管理员权限」，那会把版本问题误导成权限问题。
 */
function LocalWechatLogCard() {
  const [probe, setProbe] = useState<WechatLogProbe | null>(null)
  const [root, setRoot] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const p = await imApi.wechatLogProbe()
      setProbe(p)
      setRoot((prev) => prev || p.configuredRoot || p.root || '')
    } catch (e) {
      setError(errMsg(e))
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const saveRoot = async () => {
    setBusy(true)
    setError(null)
    setDone(null)
    try {
      const r = await imApi.setWechatLogRoot(root.trim())
      if (r.error) {
        setError(r.error)
        return
      }
      setDone(`已切换到 ${r.root}`)
      await load()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  const sync = async () => {
    setBusy(true)
    setError(null)
    setDone(null)
    try {
      const r = await imApi.wechatLogSync()
      if (r.error) {
        setError(r.error)
        return
      }
      const bad = r.accounts.filter((a) => a.status === 'error')
      if (bad.length > 0) {
        setError(bad
          .map((a) => `${a.wxid}：${a.error ?? '同步失败'}${a.remedy ? `\n${a.remedy}` : ''}`)
          .join('\n\n'))
      } else {
        setDone(`同步完成，写入索引 ${r.totalIndexed} 行`)
      }
      await load()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  const accounts = probe?.accounts ?? []
  return (
    <div className="imc-card">
      <div className="imc-card-h">
        <span className="imc-plat wechat">记</span>
        <span className="imc-t">本机聊天记录</span>
        <span className={`imc-st ${accounts.length > 0 ? 'ok' : 'off'}`}>
          {accounts.length > 0 ? `${accounts.length} 个账号` : '未发现账号'}
        </span>
      </div>
      <div className="imc-card-sub">
        读取本机已登录微信的聊天记录（历史全量）。与上面的扫码机器人是两条链 ——
        扫码只能收新消息，拿不到历史。
      </div>

      {accounts.length === 0 && (
        <div className="imc-frow" style={{ marginTop: 12 }}>
          <div className="imc-flab">微信数据目录</div>
          <input
            className="imc-inp"
            value={root}
            onChange={(e) => setRoot(e.target.value)}
            placeholder="D:\wechat\chat\xwechat_files"
          />
          <div className="imc-hint" style={{ marginTop: 6 }}>
            微信把数据存到自定义位置时程序找不到。填写含 <code>xwechat_files</code> 的那一级目录。
          </div>
          <button
            className="imc-btn sm"
            style={{ marginTop: 8 }}
            disabled={busy || !root.trim()}
            onClick={() => void saveRoot()}
          >
            使用此目录
          </button>
        </div>
      )}

      {accounts.length > 0 && (
        <div className="imc-card-meta" style={{ marginTop: 8 }}>
          {accounts.map((a) => (
            <span key={a.wxid}>{a.wxid} · v{a.version} · {a.encryptedDbs} 个库</span>
          ))}
        </div>
      )}

      {error && (
        <div className="imc-hint" style={{ color: 'var(--danger, #c0392b)', whiteSpace: 'pre-wrap', marginTop: 8 }}>
          {error}
        </div>
      )}
      {done && (
        <div className="imc-hint" style={{ color: 'var(--ok, #1e8e3e)', marginTop: 8 }}>{done}</div>
      )}

      <div className="imc-card-acts">
        <button
          className="imc-btn sm"
          disabled={busy || accounts.length === 0}
          onClick={() => void sync()}
        >
          {busy ? '同步中…' : '同步聊天记录'}
        </button>
        <button className="imc-btn sm ghost" disabled={busy} onClick={() => void load()}>
          重新检测
        </button>
      </div>
      <div className="imc-hint">
        取密钥可能需要一次管理员授权（读取微信进程内存），点「是」即可；之后使用缓存密钥，
        不再提示。取消授权不会影响其它功能，具体原因以本次同步的错误说明为准。
      </div>
    </div>
  )
}

function CredForm({
  platform,
  form,
  setForm,
}: {
  platform: ImPlatform
  form: Record<string, string>
  setForm: (f: Record<string, string>) => void
}) {
  const spec = CRED_SPEC[platform]!
  return (
    <>
      <div className="imc-crednote">{spec.hint}</div>
      {spec.fields.map((f) => (
        <div className="imc-frow" key={f.k} style={{ marginTop: 14 }}>
          <div className="imc-flab">{f.label}</div>
          <input className="imc-inp" value={form[f.k] ?? ''} placeholder={f.ph}
            onChange={(e) => setForm({ ...form, [f.k]: e.target.value })} />
        </div>
      ))}
      <div className="imc-hint">凭证加密存储，界面上不回显明文。同一平台可添加多个实例。</div>
    </>
  )
}

function AddChannelList({ instances, onPick }: { instances: ImInstance[]; onPick: (p: ImPlatform) => void }) {
  return (
    <>
      {ALL_PLATFORMS.map((p) => {
        const n = instances.filter((i) => i.platform === p && i.status !== 'error').length
        return (
          <div className="imc-pick" key={p} onClick={() => onPick(p)}>
            <span className={`imc-plat sm ${n > 0 ? p : 'none'}`}>{PLATFORM_CHAR[p]}</span>
            <div className="imc-pb">
              <div className="imc-pn1">{PLATFORM_LABEL[p]}{n > 0 ? ` · 已接入 ${n} 个` : ''}</div>
              <div className="imc-pn2">{PLATFORM_DESC[p]}</div>
            </div>
            <span className="imc-btn sm">{n > 0 ? '再加一个' : '添加'}</span>
          </div>
        )
      })}
      <div className="imc-hint" style={{ marginTop: 10 }}>
        企业微信与钉钉需要机器人凭证（企微：BotID + Secret；钉钉：ClientID + ClientSecret）。
      </div>
    </>
  )
}

/**
 * 微信扫码面板。
 *
 * <p>视觉按原型（二维码 + 三步说明 + 警示），但二维码是**占位图** ——
 * 真正的码要由后端调 iLink `get_bot_qrcode` 返回 URL 再渲染。
 * ⛔ 不要在这里硬编码一个假 URL 让用户去扫：扫了也连不上，比"没有"更糟。
 */
/**
 * 扫码创建机器人（企微 / 微信）。
 *
 * <p>为什么企微也要浏览器：企微的展示页是 SPA（`/ai/qc/gen?scode=`），终端 ASCII 二维码扫不出来；
 * 所以直接把 `auth_url` 丢进浏览器（一键打开），用户在手机上确认后这里自动继续。
 *
 * <p>凭证不经过前端：轮询到 SCANNED 时后端已经把实例建好（加密落库），本组件只负责关抽屉。
 */
function QrPanel({ platform, onConnected }: { platform: ImPlatform; onConnected: (instanceId?: string) => void }) {
  const [sess, setSess] = useState<{ sessionId: string; qrContent: string | null; hint: string | null } | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [state, setState] = useState<'PENDING' | 'SCANNED' | 'EXPIRED' | 'UNSUPPORTED'>('PENDING')
  const [msg, setMsg] = useState<string | null>(null)
  /** 把 qrContent 编码成二维码图（微信的 qrcode_img_content 是个 H5 页面 URL，不是图片） */
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null)
  const qrDoneRef = useRef(false)
  /** 轮询失败次数：静默吞掉会让用户以为"扫了没反应"，必须可见 */
  const [pollErr, setPollErr] = useState(0)
  /**
   * onConnected 是父组件 JSX 里的内联箭头 ⇒ 每次父渲染都是新引用。
   * 直接放进轮询 effect 的依赖，会让定时器在 2s 到点前被反复清理重建 ⇒ 轮询永不触发。
   */
  const onConnectedRef = useRef(onConnected)
  useEffect(() => { onConnectedRef.current = onConnected }, [onConnected])

  // 发起扫码
  useEffect(() => {
    let alive = true
    imApi.startQr(platform)
      .then((v) => {
        if (!alive) return
        setSess(v)
        // ⛔ 不自动开浏览器：二维码就是给人手机扫的，自动弹系统浏览器反而打断。
        //    想用电脑打开的话，弹窗里有「在浏览器打开」按钮。
      })
      .catch((e) => { if (alive) setErr(errMsg(e)) })
    return () => { alive = false }
  }, [platform])

  // 把授权 URL 编码成二维码（后端给的只是一串 URL；微信那条是 liteapp 页面，必须扫）
  useEffect(() => {
    if (!sess?.qrContent) return
    if (qrDoneRef.current) return
    qrDoneRef.current = true
    let alive = true
    QRCode.toDataURL(sess.qrContent, { width: 340, margin: 1, errorCorrectionLevel: 'M' })
      .then((url) => { if (alive) setQrDataUrl(url) })
      .catch(() => { /* 失败就退化成显示链接 */ })
    return () => { alive = false }
  }, [sess?.qrContent])

  // 轮询（依赖只有 sess / platform —— onConnected 走 ref，见上）
  useEffect(() => {
    if (!sess) return
    let alive = true
    let fails = 0
    const tick = async () => {
      try {
        const r = await imApi.pollQr(platform, sess.sessionId)
        if (!alive) return
        fails = 0
        setPollErr(0)
        setState(r.state)
        setMsg(r.message ?? null)
        if (r.state === 'SCANNED') {
          window.clearInterval(t)
          // 把新实例 id 带出去 —— 飞书要在这一步判断「要不要引导用户授权」
          onConnectedRef.current(r.instanceId)
        } else if (r.state === 'EXPIRED' || r.state === 'UNSUPPORTED') {
          window.clearInterval(t)
        }
      } catch (e) {
        if (!alive) return
        fails += 1
        setPollErr(fails)
        // 连续失败要在界面上看得见，不要静默
        if (fails === 1) setMsg(errMsg(e))
      }
    }
    // 立刻拉一次（不等 2s），再按 2s 轮询
    void tick()
    const t = window.setInterval(tick, 2000)
    return () => { alive = false; window.clearInterval(t) }
  }, [sess, platform])

  if (err) {
    return (
      <div className="imc-hint" style={{ color: 'var(--danger, #c0392b)' }}>
        发起扫码失败：{err}
      </div>
    )
  }
  if (!sess) return <div className="imc-hint">正在生成二维码…</div>

  const isUrl = !!sess.qrContent && /^https?:\/\//.test(sess.qrContent)
  /** 微信那条是「用手机扫的页面 URL」⇒ 必须自己编码成二维码；企微/飞书是授权页（开浏览器） */
  const scanOnly = platform === 'wechat'

  return (
    <div style={{ textAlign: 'center' }}>
      <div className="imc-qrbox">
        {qrDataUrl ? (
          <img src={qrDataUrl} alt="扫码" style={{ width: 170, height: 170 }} />
        ) : (
          <div style={{ fontSize: 12.5, color: 'var(--ink-subtle)', lineHeight: 1.6, textAlign: 'center' }}>
            {isUrl ? '用手机扫码，或点下方按钮\n在电脑浏览器打开授权页' : '用手机扫码'}
          </div>
        )}
      </div>
      {isUrl && !scanOnly && (
        <div style={{ marginTop: 12 }}>
          <button className="imc-btn sm" onClick={() => void openExternalUrl(sess.qrContent!)}>
            在浏览器打开
          </button>
          <div className="imc-hint mono" style={{ marginTop: 8, wordBreak: 'break-all', fontSize: 11 }}>
            {sess.qrContent}
          </div>
        </div>
      )}
      <div className="imc-steps">
        <div className="imc-s now"><i>1</i>{scanOnly ? '手机扫码' : '扫码或打开授权页'}</div>
        <div className="imc-s"><i>2</i>手机上确认</div>
        <div className="imc-s"><i>3</i>这里自动完成</div>
      </div>
      {sess.hint && <div className="imc-hint" style={{ marginTop: 12 }}>{sess.hint}</div>}
      <div className="imc-hint" style={{ marginTop: 10 }}>
        {state === 'PENDING' && (pollErr > 0 ? `轮询失败 ${pollErr} 次，正在重试…` : '等待扫码…')}
        {state === 'EXPIRED' && (msg || '这个二维码已失效，请关闭重试')}
        {state === 'UNSUPPORTED' && (msg || '该平台不支持扫码')}
      </div>
      {platform === 'wechat' && (
        <div className="imc-hint" style={{ color: 'var(--warning)', marginTop: 10 }}>
          注意：扫码会创建一个新机器人，旧机器人的登录态会立即失效。
        </div>
      )}
      <div className="imc-hint" style={{ marginTop: 10 }}>
        凭证由后端直接加密保存，不会出现在这个页面上。
      </div>
    </div>
  )
}

type SecuritySwitchKey =
  | 'readAll' | 'autoBind' | 'aiDirect' | 'existingOnly' | 'takeoverConfirm' | 'audit'
  | 'allowUserIdentity'

/**
 * IM 安全与权限（设计稿「IM 安全与权限」抽屉）。
 *
 * <p>7 个开关落 {@code im_config} 单行（V307），PUT 整体覆盖。
 * 点开关时先本地翻转（网络往返期间手感不能卡），失败则<b>回滚</b>并报错 ——
 * 否则界面显示"已开启"但库里没开，用户会以为设置生效了。
 */
function SecurityPanel() {
  const [cfg, setCfg] = useState<ImConfig | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let alive = true
    imApi.getConfig()
      .then((c) => { if (alive) setCfg(c) })
      .catch((e) => { if (alive) setErr(errMsg(e)) })
    return () => { alive = false }
  }, [])

  const toggle = async (key: SecuritySwitchKey) => {
    if (!cfg || saving) return
    const prev = cfg
    const next = { ...cfg, [key]: !cfg[key] }
    setCfg(next)
    setSaving(true)
    setErr(null)
    try {
      setCfg(await imApi.updateConfig(next))
    } catch (e) {
      setCfg(prev)
      setErr(errMsg(e))
    } finally {
      setSaving(false)
    }
  }

  const row = (id: SecuritySwitchKey, label: string, desc: string) => (
    <div className="imc-card" key={id}>
      <div className="imc-card-h">
        <span className="imc-t" style={{ fontSize: 13 }}>{label}</span>
        <button className={`imc-sw${cfg?.[id] ? ' on' : ''}`}
          onClick={() => toggle(id)}
          disabled={!cfg || saving}
          data-switch={id}
          aria-label={label}>
          <i />
        </button>
      </div>
      <div className="imc-card-sub">{desc}</div>
    </div>
  )

  if (!cfg) {
    return err
      ? <div className="imc-hint">读取安全设置失败：{err}</div>
      : <div className="imc-hint">正在读取安全设置…</div>
  }

  return (
    <>
      <div className="imc-grp" style={{ marginTop: 0 }}><span className="imc-gt">读取</span></div>
      {row('readAll', '任何任务都能读 IM', '允许所有任务调用 ChannelList / ChannelRead。关闭后仅接线到该任务的聊天可见。')}
      <div className="imc-grp"><span className="imc-gt">发送</span></div>
      {row('autoBind', '发送自动接线', '从任何任务首次给某个聊天发消息时，自动把该聊天接到这个任务。')}
      {row('aiDirect', 'AI 可直接发送（免确认）', '开启后 AI 调 ChannelSend 不弹确认卡。对面是真人，建议保持关闭。')}
      {row('existingOnly', '仅允许发给已有聊天', 'AI 只能回复已有会话，不能给新联系人发消息。')}
      {row('takeoverConfirm', '接管别人的接线需确认', '目标聊天已接在别的任务时，发送前弹确认，可选「只发这次不改接线」。')}
      {row('allowUserIdentity', '允许以我的名义发消息', '开启后 AI 可以以你的名义在 IM 里发言（对方看到的是你本人说的）。默认关闭。')}
      <div className="imc-grp"><span className="imc-gt">扫描</span></div>
      <div className="imc-card" style={{ borderColor: 'var(--accent)' }}>
        <div className="imc-card-h"><span className="imc-t" style={{ fontSize: 13 }}>扫描推送受「AI 可直接发送」约束</span></div>
        <div className="imc-card-sub">
          推送审批单不算「对外发消息」，仍可发出；但审批通过后 agent 的对外动作要走确认。
        </div>
      </div>
      {row('audit', '审计日志', '记录每次由哪个任务、哪位用户触发、发给了谁、内容摘要。')}
      {err && <div className="imc-hint" style={{ color: 'var(--danger, #c0392b)' }}>保存失败：{err}</div>}
      <div className="imc-hint">
        说明：以上开关已存入 IM 配置表（{cfg.updatedAt ? `最近更新 ${formatTime(cfg.updatedAt)}` : '尚未改动'}）。
        其中「AI 可直接发送」「仅允许发给已有聊天」会在发送链路接入后生效。
      </div>
    </>
  )
}

function ChannelDetail({ instance, running }: { instance: ImInstance | null; running: boolean }) {
  if (!instance) return <div className="imc-hint">实例不存在</div>
  return (
    <>
      <div className="imc-frow">
        <div className="imc-flab">连接状态</div>
        <div className="imc-card" style={{ margin: 0 }}>
          <div className="imc-card-h">
            {/* 「已连接」= 入站监听在跑（与握手结果 status 分开）；没跑才会显示已停止 */}
            <span className={`imc-st ${running ? 'ok' : 'off'}`}>
              {running ? '已连接' : '已停止'}
            </span>
            {instance.status === 'error' && (
              <span className="imc-st err">连接异常</span>
            )}
            {instance.lastActiveAt && (
              <span style={{ fontSize: 12, color: 'var(--ink-tertiary)' }}>
                {formatTime(instance.lastActiveAt)}
              </span>
            )}
          </div>
          {instance.statusDetail && <div className="imc-card-sub" style={{ marginTop: 6 }}>{instance.statusDetail}</div>}
        </div>
      </div>
      <div className="imc-frow">
        <div className="imc-flab">平台</div>
        <div style={{ fontSize: 13, color: 'var(--ink-muted)' }}>
          {PLATFORM_LABEL[instance.platform]} · {PLATFORM_DESC[instance.platform]}
        </div>
      </div>
      <div className="imc-frow">
        <div className="imc-flab">凭证</div>
        <div className="imc-hint" style={{ margin: 0 }}>
          已加密存储，界面上不回显明文。要更换凭证请重新扫码或重新填写。
        </div>
      </div>
    </>
  )
}

function RebindPicker({
  chat,
  sessions,
  pick,
  onPick,
}: {
  chat: ImChat | null
  sessions: Array<{ id: string; title: string }>
  pick: string | null
  onPick: (id: string) => void
}) {
  if (!chat) return <div className="imc-hint">聊天不存在</div>
  const current = chat.boundSessionId
    ? sessions.find((s) => s.id === chat.boundSessionId)?.title ?? chat.boundSessionId
    : null
  return (
    <>
      <div className="imc-hint" style={{ marginTop: 0, marginBottom: 10 }}>
        来源 <b>{chat.peerName ?? chat.peerId}</b>
        {current ? ` · 当前接到「${current}」` : ' · 尚未接线'}
      </div>
      {sessions.map((s) => (
        <div key={s.id} className={`imc-pick${pick === s.id ? ' sel' : ''}`} onClick={() => onPick(s.id)}>
          <span className="imc-cb">{pick === s.id ? '✓' : ''}</span>
          <div className="imc-pb">
            <div className="imc-pn1">{s.title}</div>
            <div className="imc-pn2">
              {chat.boundSessionId === s.id ? '当前已接到这里' : pick === s.id ? '将改接到这里' : '任务'}
            </div>
          </div>
        </div>
      ))}
      {/* 不只绑已有 —— 也可以为这个聊天开一个全新任务（用户 2026-11-17 要求） */}
      <div
        className={`imc-pick${pick === NEW_TASK_PICK ? ' sel' : ''}`}
        data-role="new-task"
        onClick={() => onPick(NEW_TASK_PICK)}
      >
        <span className="imc-cb">{pick === NEW_TASK_PICK ? '✓' : ''}</span>
        <div className="imc-pb">
          <div className="imc-pn1">＋ 新建任务并接到它</div>
          <div className="imc-pn2">为这个聊天开一个新任务，落到 IM 工作区目录</div>
        </div>
      </div>
      <div className="imc-hint">
        一个任务可以接多个 IM 聊天；同一个聊天只能接一个任务。换到别的任务后，原任务不再收到它的消息。
      </div>
    </>
  )
}

/* ===================== 工具 ===================== */

/** 后端返回 {bindings:[...]} 或裸数组，两种都接住。 */
function formatTime(v: string | null): string {
  if (!v) return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return v
  const now = Date.now()
  const diff = now - d.getTime()
  if (diff >= 0 && diff < 60_000) return '刚刚'
  if (diff >= 0 && diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  return d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function errMsg(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof Error) return e.message
  return String(e)
}

function drawerTitle(d: DrawerState): string {
  switch (d.kind) {
    case 'cred':
      return d.platform ? CRED_SPEC[d.platform]?.title ?? '添加渠道' : '添加渠道'
    case 'addChannel':
      return '添加渠道'
    case 'addFeishu':
      return '添加飞书'
    case 'security':
      return 'IM 安全与权限'
    case 'qr':
      return d.platform ? `添加${PLATFORM_LABEL[d.platform] ?? ''}` : '扫码接入'
    case 'channel':
      return '渠道详情'
    case 'oauth':
      return '授权本人身份'
    case 'perms':
      return '飞书权限清单'
    default:
      return ''
  }
}

function confirmTitle(c: ConfirmState): string {
  switch (c.kind) {
    case 'rebind':
      return '改接到哪个任务'
    case 'approve':
      return '确认并执行'
    case 'remove':
      return '移除机器人'
    default:
      return ''
  }
}

/**
 * 「以你本人名义发消息」的授权面板（飞书 OAuth device flow）。
 *
 * <h2>为什么挂在「建/绑机器人成功之后」而不是一个常驻开关</h2>
 * 用户定（2026-11-18）：授权是**能力**，不是偏好 —— 让它在拿到机器人的那一刻就问到，
 * 比让人去设置页翻一个开关更自然。开关本身（`allowUserIdentity`）仍在，
 * 只是这里把「是否允许」隐含在「是否点了授权」里。
 *
 * <h2>⚠️ 后端已经自动开浏览器</h2>
 * `start` 返回 `browserOpened`；我们仍然把 userCode 与链接展示出来 ——
 * 浏览器被拦、或者用户当场关掉了，还能手动打开。这是「不能只靠一个自动动作」的兜底。
 */
function OauthPanel({ instanceId, onAuthorized }: { instanceId: string; onAuthorized: () => void }) {
  const [state, setState] = useState<'starting' | 'waiting' | 'ok' | 'err'>('starting')
  const [userCode, setUserCode] = useState('')
  const [url, setUrl] = useState('')
  const [msg, setMsg] = useState<string | null>(null)
  const onOkRef = useRef(onAuthorized)
  onOkRef.current = onAuthorized

  /**
   * 复用同一次 start 请求：React StrictMode 会把 effect 跑两遍，
   * 每次都发一遍 ⇒ 后端开两次授权会话、弹两次浏览器（用户 2026-11-23 实测）。
   * 用 ref 缓存 Promise，两遍 effect 等同一个结果，只发一次。
   */
  const startPromiseRef = useRef<Promise<Awaited<ReturnType<typeof imApi.oauthStart>>> | null>(null)
  /** 授权页只在拿到 URL 后自动打开一次（用户仍可用按钮再开）。 */
  const openedRef = useRef(false)

  useEffect(() => {
    let alive = true
    let timer: number | undefined
    const run = async () => {
      try {
        if (!startPromiseRef.current) {
          startPromiseRef.current = imApi.oauthStart(instanceId)
        }
        const r = await startPromiseRef.current
        if (!alive) return
        setUserCode(r.userCode)
        setUrl(r.verificationUriComplete)
        setState('waiting')
        // 用户要求：用**系统默认浏览器**打开授权页（不要 Tauri 应用内窗口）
        if (!openedRef.current) {
          openedRef.current = true
          void openExternalUrl(r.verificationUriComplete)
        }
        const intervalMs = Math.max(3, r.interval) * 1000
        const tick = async () => {
          try {
            const p = await imApi.oauthPoll(instanceId, r.deviceCode)
            if (!alive) return
            if (p.state === 'AUTHORIZED') {
              setState('ok')
              onOkRef.current()
              return
            }
            if (p.state === 'ERROR') {
              setState('err')
              setMsg(p.message ?? '授权失败')
              return
            }
            timer = window.setTimeout(tick, intervalMs)
          } catch (e) {
            if (alive) { setState('err'); setMsg(errMsg(e)) }
          }
        }
        timer = window.setTimeout(tick, intervalMs)
      } catch (e) {
        if (alive) { setState('err'); setMsg(errMsg(e)) }
      }
    }
    void run()
    return () => { alive = false; if (timer) window.clearTimeout(timer) }
  }, [instanceId])

  if (state === 'ok') {
    return <div className="imc-hint">✅ 已授权，现在可以以你本人名义发消息了。</div>
  }
  return (
    <>
      <div className="imc-hint" style={{ marginBottom: 12 }}>
        想让 AI 以<strong>你本人</strong>的名义发消息（对方看到的是你的头像），需要你授权一次。
        已用默认浏览器打开授权页；没有弹出就点下面的按钮。
      </div>
      {userCode && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
          <span className="imc-hint" style={{ margin: 0 }}>用户码</span>
          <b style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: 18, letterSpacing: 1 }}>{userCode}</b>
        </div>
      )}
      {url && (
        <>
          {/* 用户要求：用系统默认浏览器打开（不用 Tauri 应用内窗口）。
              ⛔ 不要用 <a target="_blank"> / window.open（桌面 WebView 里打不开）。 */}
          <button className="imc-btn pri" onClick={() => void openExternalUrl(url)}>
            打开授权页（默认浏览器）
          </button>
          <div className="imc-hint mono" style={{ marginTop: 8, wordBreak: 'break-all', fontSize: 11 }}>
            {url}
          </div>
        </>
      )}
      <div className="imc-hint" style={{ marginTop: 12 }}>
        {state === 'starting' ? '正在申请授权链接…' : '等待你在飞书页面点「同意」…'}
      </div>
      {/* ⚠️ 失败只在**下方**提示，不整页替换 —— 用户要保留上面的链接/用户码，
          且 authorization_pending 属于正常中间态（后端已按 pending 处理，不走这里）。 */}
      {state === 'err' && (
        <div className="imc-hint" style={{ color: 'var(--danger, #c0392b)', marginTop: 10 }}>
          授权失败：{msg}
          <div style={{ marginTop: 6, fontSize: 12, color: 'var(--ink-tertiary)' }}>
            如果是「权限不足」，先在权限清单里确认管理员已开通对应权限，再重新授权。
          </div>
        </div>
      )}
    </>
  )
}

/* ------------- 飞书添加：两个分支（已有机器人 / 没有机器人） ------------- */

/**
 * 分支选择：用户手上有没有现成的飞书机器人。
 *
 * <p>为什么不在这里做「登录自动探测」：用户 2026-11-22 明确裁定
 * 「飞书我们就是长连接，不用探测了」⇒ 只留「填凭证」与「看怎么建」两条路，
 * 自动探测记进 TODO，不留半截入口。
 */
function FeishuBranchPicker({ onPick }: { onPick: (b: FeishuBranch) => void }) {
  return (
    <>
      <div className="imc-crednote">
        先确认一下：你手上有没有已经建好的飞书机器人？飞书用长连接，程序会主动连出去，不需要公网地址。
      </div>
      <div className="imc-pick" style={{ marginTop: 14 }} onClick={() => onPick('existing')} data-branch="existing">
        <div className="imc-pb">
          <div className="imc-pn1">已有机器人</div>
          <div className="imc-pn2">手上有 App ID 和 App Secret，直接填进来就能连</div>
        </div>
        <span className="imc-btn sm pri">去填写</span>
      </div>
      <div className="imc-pick" onClick={() => onPick('create')} data-branch="create">
        <div className="imc-pb">
          <div className="imc-pn1">我没有机器人</div>
          <div className="imc-pn2">按步骤去飞书开放平台建一个，建好后回来填凭证</div>
        </div>
        <span className="imc-btn sm">看步骤</span>
      </div>
      <div className="imc-hint" style={{ marginTop: 10 }}>
        两种方式建出来的机器人都一样用；「没有机器人」只是把创建过程写清楚，不会自动替你创建。
      </div>
    </>
  )
}

/** 创建指引的六步。每步一句话；chips 是界面上真正要点的词，点一下可复制。 */
const FEISHU_STEPS: Array<{ title: string; detail: string; chips?: string[] }> = [
  {
    title: '建一个应用',
    detail: '打开飞书开放平台（open.feishu.cn），用你的飞书账号登录，点「创建企业自建应用」，填个名字即可。',
    chips: ['创建企业自建应用'],
  },
  {
    title: '打开机器人能力',
    detail: '进入这个应用，在「添加应用能力」里勾选「机器人」。',
    chips: ['机器人'],
  },
  {
    title: '开通消息权限',
    detail: '在「权限管理」里搜索 im:message，把收发单聊、群聊消息的权限都打开；群里要 @ 机器人它才会收到。',
    chips: ['im:message'],
  },
  {
    title: '事件订阅选「长连接」',
    detail:
      '在「事件与回调」→「事件配置」里，订阅方式选「长连接」——这一步不用填任何网址，也不用内网穿透；' +
      '然后添加事件「接收消息 v2.0」（让飞书把新消息推给这个应用）。',
    chips: ['长连接', '接收消息 v2.0'],
  },
  {
    title: '发布并设置可用范围',
    detail: '在「版本管理与发布」里创建版本并申请发布，可用范围至少包括你自己；企业管理员通过后就生效。',
  },
  {
    title: '复制凭证',
    detail: '回到「凭证与基础信息」，复制 App ID（cli_ 开头）和 App Secret —— 下一步就是填这两个。',
    chips: ['App ID', 'App Secret'],
  },
]

function FeishuCreateGuide({ onDone, onAutoCreate }: { onDone: () => void; onAutoCreate: () => void }) {
  return (
    <>
      <div className="imc-crednote">
        最快的方式：点下面按钮，浏览器会打开飞书授权页 —— 登录并点「同意」后，
        应用自动建好、凭证自动存进来，不用手抄。
      </div>
      <button className="imc-btn pri" style={{ marginTop: 10 }} onClick={onAutoCreate}>
        一键创建应用（自动获取凭证）
      </button>
      <div className="imc-hint" style={{ margin: '12px 0 0' }}>
        注意：程序无法读取你浏览器里已登录的开发者后台，也无法列出你已有的应用
        （飞书没有这类接口，密钥只能从控制台复制）。
        <br />
        如果确实没有机器人，用上面的按钮新建；想自己建就按下面步骤来：
      </div>
      {FEISHU_STEPS.map((s, i) => (
        <div className="imc-card" key={s.title} style={{ marginTop: 10 }}>
          <div className="imc-card-h">
            <span className="imc-plat sm feishu">{i + 1}</span>
            <span className="imc-t" style={{ fontSize: 13 }}>{s.title}</span>
          </div>
          <div className="imc-card-sub">{s.detail}</div>
          {s.chips && (
            <div className="imc-chips">
              {s.chips.map((c) => <CopyChip key={c} text={c} />)}
            </div>
          )}
        </div>
      ))}
      <div className="imc-hint" style={{ marginTop: 10 }}>
        点上面的小标签可以复制那一步要点的词。建好后点下面的按钮回来填凭证。
      </div>
      <button className="imc-btn pri" style={{ marginTop: 12 }} onClick={onDone}>
        我已建好，回去填凭证
      </button>
    </>
  )
}

/** 可复制的小标签：没有剪贴板权限时静默失败，不影响主流程。 */
function CopyChip({ text }: { text: string }) {
  return (
    <button
      type="button"
      className="imc-chip pick"
      title={`复制「${text}」`}
      aria-label={`复制 ${text}`}
      onClick={() => {
        // 剪贴板在非安全上下文 / 旧 WebView 里可能不存在 ⇒ 可选链 + 吞掉拒绝
        void Promise.resolve(navigator.clipboard?.writeText(text)).catch(() => undefined)
      }}
    >
      {text}
    </button>
  )
}

/* ------------- 飞书「本人身份」权限清单（路线 B：程序不能代开） ------------- */

/**
 * 飞书本授权所需 scope。授权页会一次申请齐，但**开启**需要管理员在开放平台点。
 *
 * <p>⛔ 不要写成「一键开通」：飞书没有让应用自己加权限的接口。
 */
const FEISHU_SCOPES: Array<{ scope: string; desc: string }> = [
  // 官方要求：以用户身份发消息必须同时开通 im:message + im:message.send_as_user
  // （open.feishu.cn/document/server-docs/im-v1/message/create）
  { scope: 'im:message', desc: '获取与发送单聊、群组消息（用户身份发送的前置）' },
  { scope: 'im:message.send_as_user', desc: '以你本人名义发送消息（须与 im:message 同时开通）' },
  { scope: 'im:message.group_msg:get_as_user', desc: '读取群历史消息（用户身份）' },
  { scope: 'im:message.p2p_msg:get_as_user', desc: '读取单聊历史消息（用户身份）' },
  { scope: 'im:chat:read', desc: '查看群信息（列群 / 会话信息）' },
  { scope: 'search:message', desc: '按关键词搜索消息' },
]

/**
 * 权限清单抽屉。
 *
 * <p>为什么要有它：飞书要求管理员在开放平台「权限管理」里开启这些权限，程序**不能代开**。
 * 这里逐条列出 scope（可复制）+ 人话说明 + 控制台入口，并提供「重新检测」按后端
 * {@code missingScopes} 更新状态；全部满足时明确提示「权限已就绪」。
 */
function FeishuPermissionPanel({
  instanceId,
  auth,
  onRecheck,
  onAuthorize,
}: {
  instanceId: string
  auth?: { authorized: boolean; missing: number; missingScopes: string[]; contacts?: boolean }
  onRecheck: () => void
  /** 打开授权页（用户点「同意」即可完成本人授权） */
  onAuthorize: () => void
}) {
  /** 重新检测后的最新状态（优先于父级 refresh 的快照，点完立即有反馈） */
  const [checked, setChecked] = useState<{ authorized: boolean; missingScopes: string[] } | null>(null)
  const [checking, setChecking] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  /** 重新检测的可见反馈（用户报「重新检测没用」：必须有结果文字，而不是静默） */
  const [notice, setNotice] = useState<string | null>(null)

  const cur = checked ?? {
    authorized: auth?.authorized ?? false,
    missingScopes: auth?.missingScopes ?? [],
  }
  const missing = new Set(cur.missingScopes)
  const allReady = cur.authorized && missing.size === 0

  const recheck = async () => {
    setChecking(true)
    setErr(null)
    setNotice(null)
    try {
      const r = await imApi.oauthStatus(instanceId)
      const next = { authorized: r.authorized, missingScopes: r.missingScopes ?? [] }
      setChecked(next)
      const n = next.missingScopes.length
      setNotice(
        !next.authorized
          ? '已重新检测：本人身份仍未授权（需去授权页点「同意」）'
          : n === 0
            ? '已重新检测：权限已就绪'
            : `已重新检测：仍缺 ${n} 项（控制台已开的话，需要重新授权一次让新权限进入 token）`,
      )
      onRecheck()
    } catch (e) {
      setErr(errMsg(e))
    } finally {
      setChecking(false)
    }
  }

  const statusOf = (scope: string): { text: string; cls: string } => {
    if (!cur.authorized) return { text: '待你点同意', cls: 'off' }
    if (missing.has(scope)) return { text: '缺少', cls: 'err' }
    if (allReady) return { text: '已就绪', cls: 'ok' }
    // 后端只检测功能必需项；其余项无法断言，不假装通过
    return { text: '以后端检测为准', cls: 'wait' }
  }

  return (
    <>
      <div className="imc-crednote">
        这里有两类，别混：
        <br />
        <strong>① 控制台开通（管理员）</strong>：决定「应用能不能拥有这些权限」。你截图里显示「已开通」的，就不用再开。
        <br />
        <strong>② 本人授权（浏览器点「同意」）</strong>：决定「你本人是否把权限授给应用」。
        控制台开了但还没点同意，界面仍会显示未授权——这是正常的下一步，不是没配好。
      </div>

      <div className="imc-grp" style={{ marginTop: 12 }}>
        <span className="imc-gt">第一步 · 控制台开通（管理员）</span>
      </div>
      <div className="imc-hint" style={{ marginTop: 4 }}>
        需要管理员在开放平台「权限管理」开启；程序不能代开（飞书没有让应用自己加权限的接口）。
        已开通过就跳过这一步。
      </div>
      <div className="imc-card-acts">
        <button
          className="imc-btn sm"
          onClick={() => void openExternalUrl('https://open.feishu.cn/app')}
        >
          打开权限管理页
        </button>
        <button className="imc-btn sm" disabled={checking} onClick={() => void recheck()}>
          {checking ? '检测中…' : '重新检测'}
        </button>
      </div>
      {notice && (
        <div className="imc-hint" style={{ marginTop: 6 }}>{notice}</div>
      )}

      <div className="imc-grp" style={{ marginTop: 12 }}>
        <span className="imc-gt">管理员需要在控制台开启的（应用身份，不是下面自动申请的）</span>
      </div>
      {[
        {
          t: 'contact:user.base:readonly',
          s: auth?.contacts ? '已开通' : '待授权',
          cls: auth?.contacts ? 'ok' : 'off',
          why: '获取用户基本信息，把发送者显示成人名（还要在通讯录权限范围里选可见范围）',
        },
        { t: '版本管理与发布：提交并审核通过', s: '待确认', cls: 'wait', why: '程序检测不到，请在控制台确认' },
        { t: '事件与回调：长连接 + im.message.receive_v1', s: '待确认', cls: 'wait', why: '程序检测不到，请在控制台确认' },
      ].map((it) => (
        <div className="imc-card" key={it.t} style={{ marginTop: 6 }}>
          <div className="imc-card-h">
            <span className="imc-card-sub" style={{ marginTop: 0, flex: 1 }}>{it.t}</span>
            <span className={`imc-st ${it.cls}`}>{it.s}</span>
          </div>
          <div className="imc-hint" style={{ margin: '4px 0 0' }}>{it.why}</div>
        </div>
      ))}

      <div className="imc-grp" style={{ marginTop: 12 }}>
        <span className="imc-gt">第二步 · 本人授权（浏览器点「同意」即可）</span>
      </div>
      <div className="imc-hint" style={{ marginTop: 4 }}>
        下面 6 项是<strong>用户身份</strong>权限，打开授权页时会一次性申请：你点「同意」即可，不用逐项手动开。
        若应用还没在控制台开通其中某项，授权后这里会显示「缺少」，需管理员补开后再同意一次。
        <br />
        {!cur.authorized
          ? '还没在浏览器里点过「同意」⇒ 点下面的按钮打开授权页，确认同意后回来点「重新检测」。'
          : allReady
            ? '权限已就绪，可以以你本人身份调用。'
            : `上次授权缺少 ${missing.size} 项 ⇒ 需要重新授权一次并同意。`}
      </div>
      <button className="imc-btn sm pri" style={{ marginTop: 8 }} onClick={onAuthorize}>
        {cur.authorized ? '重新授权（浏览器同意）' : '去授权页点同意'}
      </button>

      {FEISHU_SCOPES.map((s) => {
        const st = statusOf(s.scope)
        return (
          <div className="imc-card" key={s.scope} style={{ marginTop: 8 }}>
            <div className="imc-card-h">
              <span className="imc-code">{s.scope}</span>
              <span className={`imc-st ${st.cls}`}>{st.text}</span>
              <CopyChip text={s.scope} />
            </div>
            <div className="imc-card-sub">{s.desc}</div>
          </div>
        )
      })}

      <div className="imc-hint" style={{ marginTop: 8 }}>
        状态来自你<strong>授权那一刻</strong>拿到的权限快照；控制台后加的权限不会自动进旧授权，需重新点一次同意。
        <br />
        硬限制：飞书<strong>没有</strong>「列出我的单聊」接口，私聊会话不会自动出现；
        要预置机器人会话，只能等本人先给机器人发一条消息。
      </div>
      {err && <div className="imc-hint" style={{ color: 'var(--danger, #c0392b)' }}>检测失败：{err}</div>}
    </>
  )
}
