/**
 * IM 频道 REST 端点封装（V302~V304）。
 *
 * <p><b>与 /channels 的分工</b>：
 * <ul>
 *   <li>本文件 {@code /im/instances · /im/scan-channels} = 实例 / 消息扫描的渠道开关；</li>
 *   <li>{@code /channels/bindings}（既有 V255）= 渠道 ↔ 任务 的接线关系。</li>
 * </ul>
 * 实例不归属任务，所以两套端点不合并（任何任务都能用任一实例发消息）。
 *
 * <p><b>凭证永不回传</b>：后端 {@code ImInstanceEntry} 本身不含 credential 字段，
 * 前端也<b>不定义</b>该字段 —— 双向都不给它出现的机会。
 */
import { api } from './rest'

export type ImPlatform = 'wechat' | 'feishu' | 'wecom' | 'dingtalk'

export interface ImInstance {
  id: string
  platform: ImPlatform
  displayName: string
  status: 'connected' | 'disconnected' | 'error'
  statusDetail: string | null
  lastActiveAt: string | null
  configJson: string | null
  createdAt: string
  updatedAt: string
}

/**
 * 机器人身份（接入页 / 渠道选择器展示用）。
 *
 * <p>飞书：botName=app_name，chatVisible=可见群数；企微：botId + authorizerId（whoami）；
 * 微信：只有 message（登录账号即机器人）。⛔ 不含任何凭证。
 */
export interface ImInstanceIdentity {
  ok: boolean
  platform?: ImPlatform
  botName?: string
  botId?: string
  authorizerId?: string
  chatVisible?: string
  message?: string
}

/**
 * IM 聊天（V305）· 「任务」tab 的真实数据源。
 *
 * <p>⚠️ 与 {@link ImBinding} 不是一回事：binding 是**渠道级**（wechat → 任务），
 * chat 是**聊天级**（张三 → 任务）。界面要显示"谁在跟我说话"就必须用 chat。
 */
export interface ImChat {
  id: string
  instanceId: string
  platform: ImPlatform
  peerId: string
  peerName: string | null
  /** V319 · 平台给的昵称（对方改过的名字）；null = 该平台拿不到（微信永远为空） */
  nickName?: string | null
  chatKind: 'p2p' | 'group'
  memberCount: number | null
  lastMessagePreview: string | null
  lastMessageAt: string | null
  boundSessionId: string | null
  bindMode: 'auto' | 'manual' | null
  boundAt: string | null
  /**
   * V335 · 机器人是否在该会话里。
   * null/undefined = 尚未判定（按可达处理）；false = 确认机器人不在该群
   * （飞书「本人所在群」同步进来的会话：@ 它不会推事件、拉历史也会被拒）。
   */
  botPresent?: boolean | null
  createdAt: string
  updatedAt: string
}

/**
 * [A 预热提示] 某个聊天「能否主动发消息」的状态（只读·非敏感）。
 *
 * <p>微信必须先收到对方来信（拿到 context_token）才能主动发；没激活时权限通知会
 * 被策略层 SKIPPED。⛔ 这里只有布尔，**没有**令牌本身。
 */
export interface ImChatActivation {
  chatId: string
  platform: ImPlatform
  instanceId: string
  /** 该平台是否要求「对方先说话」（目前只有微信 true） */
  activationRequired: boolean
  activated: boolean
  /** 未激活时给用户看的一句话；已激活/无此约束 ⇒ null */
  hint: string | null
}

/** 本机微信会话（扫描规则「监控会话」选择器的数据源 · 只读）。 */export interface WechatConversationOption {
  conversationId: string
  displayName: string
  group: boolean
  messageCount: number
  recentCount: number
  lastMessageTime: number | null
}

/** 微信账号同步状态（「微信同步状态」面板）。 */
export interface WechatAccountStat {
  wxid: string
  conversationCount: number
  messageCount: number
  lastSyncedAt: number | null
}
export interface WechatAccountsResp {
  accounts: WechatAccountStat[]
  paths: { store: string; keys: string }
}

/** 渠道 ↔ 任务 接线（V255 · 与「频道」tab 的实例是两件事）。 */export interface ImBinding {
  id: string
  channelId: string
  serverName: string
  sessionId: string
  boundAt: string
}

export interface CreateInstanceRequest {
  platform: ImPlatform
  displayName: string
  /** 平台凭证 JSON 字符串；后端加密落库，永不回传 */
  credential: string
  configJson?: string
}

/** IM 安全与权限配置（单行 · 7 个开关）。 */
export interface ImConfig {
  /** 任何任务都能读 IM（false = 仅接线到该任务的聊天可见） */
  readAll: boolean
  /** 首次从某任务给聊天发消息时，自动把该聊天接到这个任务 */
  autoBind: boolean
  /** AI 可直接发送（免确认）；对面是真人，默认关闭 */
  aiDirect: boolean
  /** AI 只能回复已有会话，不能给新联系人发消息 */
  existingOnly: boolean
  /** 目标聊天已接在别的任务时，发送前弹确认 */
  takeoverConfirm: boolean
  /** 记录每次对外动作的来源 / 对象 / 内容摘要 */
  audit: boolean
  /**
   * 允许 AI 以**用户本人名义**在 IM 发消息（对方看到的是你本人说的）。
   *
   * <p>默认关闭：这条开关决定 agent 是否拿到 `SendImAsUser` 工具 ——
   * 与 `SendUserMessage`（机器人通知你）是两件不同的事，模型必须分得清。
   *
   * <p>⚠️ 本字段必须在类型里出现：`updateConfig` 是**全量 PUT**，
   * 少一个字段 ⇒ 保存别的开关时会把这一项静默写回默认值。
   */
  allowUserIdentity: boolean
  updatedAt: string | null
}

export const imApi = {
  // ── 实例 ──────────────────────────────────────────────
  listInstances: () => api<ImInstance[]>('/im/instances'),
  /** 机器人身份（飞书 bot/v3/info；企微 identity whoami）；refresh=true 跳过 10 分钟缓存。 */
  instanceIdentity: (id: string, refresh = false) =>
    api<ImInstanceIdentity>(
      `/im/instances/${encodeURIComponent(id)}/identity${refresh ? '?refresh=true' : ''}`),
  createInstance: (req: CreateInstanceRequest) =>
    api<ImInstance>('/im/instances', { method: 'POST', body: req }),
  updateInstance: (id: string, req: { displayName?: string; configJson?: string }) =>
    api<ImInstance>(`/im/instances/${encodeURIComponent(id)}`, { method: 'PUT', body: req }),
  rotateCredential: (id: string, credential: string) =>
    api<{ rotated: boolean }>(`/im/instances/${encodeURIComponent(id)}/credential`, {
      method: 'PUT',
      body: { credential },
    }),
  removeInstance: (id: string) =>
    api<{ removed: boolean }>(`/im/instances/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  /**
   * 开始接收某实例的消息（启动入站长连接）。
   *
   * <p>后端刻意不做「建实例即自动连」：连上会真实收消息、真实触发 agent。
   * 用户在渠道卡片上明确点「启动连接」才连；失败时 started=false + reason。
   */
  startInbound: (id: string) =>
    api<{ instanceId: string; started: boolean; reason?: string }>(
      `/im/inbound/${encodeURIComponent(id)}/start`,
      { method: 'POST' },
    ),

  /** 正在收消息的实例（入站监听运行态，按钮据此在「启动/断开连接」之间切换）。 */
  listInbound: () =>
    api<Array<{ instanceId: string; platform: string; running: boolean }>>('/im/inbound'),

  /** 断开连接（停止入站长连接，幂等）。 */
  stopInbound: (id: string) =>
    api<{ instanceId: string; stopped: boolean }>(
      `/im/inbound/${encodeURIComponent(id)}/stop`,
      { method: 'POST' },
    ),

  /**
   * 测试连接（不落库）：后端用未保存的凭证真打一次平台换票。
   * 飞书成功时回 message（含机器人名/可见群聊数）；失败回飞书 code + 中文解释。
   */
  testConnection: (req: { platform: ImPlatform; credential: string }) =>
    api<{ ok: boolean; code: string; message: string; botName?: string; chatVisible?: string }>(
      '/im/instances/test-connection',
      { method: 'POST', body: req },
    ),

  /**
   * 已保存实例的凭证自检（飞书换票）。
   *
   * <p>一键创建后立刻调用：创建成功 ≠ 飞书肯换票（豆包账号会回 {@code 21001}）。
   * ⛔ 响应只含 ok/code/message（+ 成功的机器人名/群数），不含凭证与 token。
   */
  probeSaved: (instanceId: string) =>
    api<{ ok: boolean; code?: string | null; message?: string | null; botName?: string | null; chatVisible?: string | null }>(
      `/im/instances/${encodeURIComponent(instanceId)}/probe`,
      { method: 'POST' },
    ),

  /** 拉飞书「机器人所在的群」进聊天目录（私聊不会出现在列表里，仍需先发一条消息）。 */
  syncFeishuChats: (instanceId: string) =>
    api<{
      instanceId: string
      total: number
      created: number
      updated: number
      skipped: number
      message: string | null
    }>('/im/chats/sync/feishu', { method: 'POST', body: { instanceId } }),

  /**
   * 以**你本人**身份（user_access_token）读取「本人所在的所有群」并按 chat_id 拉历史落库。
   *
   * <p>⚠️ 与 {@link syncFeishuChats} 的区别：那个只拉**机器人所在的群**。
   * <p>⛔ 飞书没有「列出我的单聊」接口 ⇒ 私聊不会出现在结果里。
   * <p>契约与 syncFeishuChats 一致（后端接口由另一会话提供）。
   */
  syncFeishuUserChats: (instanceId: string) =>
    api<{
      instanceId: string
      total: number
      created: number
      updated: number
      skipped: number
      message: string | null
    }>('/im/chats/sync/feishu-user', { method: 'POST', body: { instanceId } }),

  // ── 聊天目录（V305 · 任务 tab 的数据源）────────────────
  listChats: () => api<ImChat[]>('/im/chats'),
  listChatsBySession: (sessionId: string) =>
    api<ImChat[]>(`/im/chats/by-session/${encodeURIComponent(sessionId)}`),
  /** [A] 全部聊天的「能否主动发」状态（微信未激活 = 通知发不出去） */
  listChatActivations: () => api<ImChatActivation[]>('/im/chats/activation'),
  /** 改接：把聊天接到某任务（一个聊天只接一个 ⇒ 覆盖式） */
  bindChat: (chatId: string, sessionId: string, mode: 'auto' | 'manual' = 'manual') =>
    api<ImChat>(`/im/chats/${encodeURIComponent(chatId)}/bind`, {
      method: 'PUT',
      body: { sessionId, mode },
    }),
  /**
   * 快速绑定：把该渠道的「机器人默认会话」接到当前会话（后端挑：私聊优先→最近活跃→企微授权人）。
   * 没有会话时后端回可执行提示，前端直接 toast。
   */
  bindDefaultChat: (instanceId: string, sessionId: string) =>
    api<{ sessionId: string; chatId: string; chatName: string; bound: ImChat }>(
      '/im/chats/bind-default',
      { method: 'POST', body: { instanceId, sessionId } }),
  /**
   * 快速绑定前的预览（不写库）：将要绑哪个会话、它现在接在哪个任务上。
   * 前端据此在「已被别的任务占用」时先确认，避免静默抢接线。
   */
  previewDefaultChat: (instanceId: string) =>
    api<{
      found: boolean
      chatId?: string
      chatName?: string
      boundSessionId?: string
      boundSessionTitle?: string
      message?: string
    }>('/im/chats/bind-default/preview', { method: 'POST', body: { instanceId } }),
  /** 新建一个任务并把它接到该聊天上（落 IM 工作区目录） */
  bindChatToNewTask: (chatId: string) =>
    api<{ sessionId: string; sessionTitle: string; projectPath: string }>(
      `/im/chats/${encodeURIComponent(chatId)}/bind-new`, { method: 'POST' }),
  unbindChat: (chatId: string) =>
    api<{ unbound: boolean }>(`/im/chats/${encodeURIComponent(chatId)}/bind`, { method: 'DELETE' }),

  /** 可监控的微信会话（本机同步产物，表空时返回 []） */
  listWechatConversations: () => api<WechatConversationOption[]>('/im/wechat/conversations'),
  /** 账号同步状态 + 数据位置（表空 ⇒ accounts 为空） */
  listWechatAccounts: () => api<WechatAccountsResp>('/im/wechat/accounts'),

  // ── 扫码 / 一键创建（企业微信、微信；飞书待 auth_method）────────────
  /** 发起扫码：返回 {sessionId, qrContent(URL 或二维码内容), hint} */
  startQr: (platform: ImPlatform, displayName?: string) =>
    api<{ sessionId: string; qrContent: string | null; expiresAt: string | null; hint: string | null }>(
      `/im/qr/${platform}/start`, { method: 'POST', body: { displayName } }),
  /** 轮询；SCANNED 时后端已把实例建好（凭证不经前端），返回 instanceId */
  pollQr: (platform: ImPlatform, sessionId: string) =>
    api<{ state: 'PENDING' | 'SCANNED' | 'EXPIRED' | 'UNSUPPORTED'; instanceId?: string; displayName?: string; message?: string | null }>(
      `/im/qr/${platform}/${encodeURIComponent(sessionId)}/poll`),

  // ── 以用户本人名义发（飞书 OAuth device flow）────────────
  /**
   * 发起授权：返回 deviceCode + 授权链接，**后端会直接用系统命令打开浏览器**。
   *
   * <p>前端仍要展示 `userCode`/`verificationUriComplete` 兜底 ——
   * 浏览器打不开、或用户当时关掉了，还能手动打开。
   */
  oauthStart: (instanceId: string) =>
    api<{
      deviceCode: string; userCode: string; verificationUriComplete: string
      interval: number; expiresIn: number; browserOpened: boolean
    }>(`/im/oauth/${encodeURIComponent(instanceId)}/start`, { method: 'POST' }),
  /** 轮询授权结果；按 start 返回的 interval 调 */
  oauthPoll: (instanceId: string, deviceCode: string) =>
    api<{ state: 'PENDING' | 'AUTHORIZED' | 'ERROR'; openId: string | null; message: string | null }>(
      `/im/oauth/${encodeURIComponent(instanceId)}/poll?deviceCode=${encodeURIComponent(deviceCode)}`),
  /** 该实例有没有可用的用户令牌 */
  oauthStatus: (instanceId: string) =>
    api<{
      authorized: boolean
      openId: string | null
      /**
       * 这张 token **缺**哪些 scope（空数组 = 齐全，或无法判断的老数据）。
       *
       * <p>为什么要它：我们后续会给默认 scope 加权限（读历史消息、搜索等），
       * 但 user token 带的是「授权那一刻」的集合 ⇒ 旧 token 不会自动获得。
       * 有值就意味着「去重新授权一次才能用新能力」。
       */
      missingScopes?: string[]
    }>(`/im/oauth/${encodeURIComponent(instanceId)}/status`),
  /**
   * 飞书**通讯录读取权限**是否开通（决定 {@code ou_xxx} 能不能换成人名）。
   *
   * <p>⚠️ 这是**应用身份权限**，与 {@link oauthStatus} 里的 scope 是两套 ——
   * 用户 OAuth 授权点了也不会让它生效。没开通**不影响任何功能**，只是显示 id。
   */
  contactsPermission: (instanceId: string) =>
    api<{
      granted: boolean
      openId?: string | null
      missingScope?: string
      /** 直达开放平台的申请链接（后端拼好，前端不要自己拼） */
      applyUrl?: string
      error?: string
    }>(`/im/oauth/${encodeURIComponent(instanceId)}/contacts-permission`),
  /** 取消授权 */
  oauthRevoke: (instanceId: string) =>
    api<{ revoked: boolean }>(`/im/oauth/${encodeURIComponent(instanceId)}`, { method: 'DELETE' }),

  // ── 消息扫描（非 agent 任务 · 系统内置，用户只能配「扫哪些渠道」）────
  /**
   * 全部渠道 + 是否在扫 + 上次扫描结果。
   *
   * <p>⚠️ 这是**系统任务**的配置，不能新建/删除任务本身 —— 所以只有 list + 整体设置两个端点。
   * <p>{@code paused} 是**任务级**开关（V318）：暂停时渠道勾选原样保留。
   */
  listScanChannels: () =>
    api<{
      channels: Array<{
        instanceId: string
        platform: ImPlatform
        displayName: string
        enabled: boolean
        lastScanAt?: string | null
        lastChatCount?: number | null
        lastNewCount?: number | null
        lastError?: string | null
      }>
      enabledCount: number
      /** 整个扫描任务是否已暂停（与 enabledCount 相互独立） */
      paused: boolean
    }>('/im/scan-channels'),
  /**
   * 整体设置要扫哪些渠道（覆盖式）。
   *
   * <p>为什么是覆盖而不是增量：界面是复选框 + 保存，用户提交的是**意图全集**；
   * 增量式无法表达「取消勾选」，而那是最常用的操作。
   */
  setScanChannels: (instanceIds: string[]) =>
    api<{ enabled: string[]; disabled: string[]; ignored?: string[] }>(
      '/im/scan-channels', { method: 'PUT', body: { instanceIds } }),

  /**
   * 暂停 / 恢复整个「消息扫描」系统任务（任务级开关，V318）。
   *
   * <p>⛔ 与 {@link setScanChannels} 分开：暂停是一次点击即时生效，
   * 不参与「勾完一批再保存」的覆盖式设置；暂停也不会动渠道勾选。
   */
  setScanPaused: (paused: boolean) =>
    api<{ paused: boolean }>('/im/scan-channels/paused', { method: 'PUT', body: { paused } }),

  /** 安全与权限开关（GET 拿 6 个，PUT 整体覆盖 —— 不是逐字段 patch） */
  getConfig: () => api<ImConfig>('/im/config'),
  updateConfig: (cfg: Omit<ImConfig, 'updatedAt'>) =>
    api<ImConfig>('/im/config', { method: 'PUT', body: cfg }),

  // ── 接线（复用 V255）──────────────────────────────────
  // ⚠️ 列表走 `/channels`（root），不是 `/channels/bindings` ——
  //    后者只支持 POST（ChannelBindingController 的写法），GET 会 405/500。
  listBindings: () => api<ImBinding[]>('/channels'),
  bind: (req: { channelId: string; serverName: string; sessionId: string; force?: boolean }) =>
    api<{ binding: ImBinding } | { error: string }>('/channels/bindings', { method: 'POST', body: req }),
  unbind: (channelId: string, sessionId?: string) =>
    api<{ removed: boolean }>(`/channels/bindings/${encodeURIComponent(channelId)}`, {
      method: 'DELETE',
      body: sessionId ? { sessionId } : undefined,
    }),

  // ── 本机微信聊天记录（不是 iLink 渠道）──────────────────
  // 解密本机 PC 微信数据库 → 写 wechat_message；首次需要管理员权限取密钥。
  wechatLogProbe: () => api<WechatLogProbe>('/wechat-log/probe'),
  wechatLogSync: () =>
    api<{ accounts: WechatLogAccountResult[]; totalIndexed: number; error?: string }>(
      '/wechat-log/sync', { method: 'POST' }),
  setWechatLogRoot: (root: string) =>
    api<{ root?: string; accounts?: WechatLogAccount[]; error?: string }>(
      '/wechat-log/root', { method: 'PUT', body: { root } }),
}

/** 本机微信账号（来自数据目录扫描，不含任何凭证）。 */
export interface WechatLogAccount {
  wxid: string
  version: number
  dir: string
  encryptedDbs: number
}

export interface WechatLogProbe {
  root: string | null
  configuredRoot: string | null
  candidates: string[]
  accounts: WechatLogAccount[]
}

export interface WechatLogAccountResult {
  wxid: string
  status: 'ok' | 'error'
  messages?: number
  indexed?: number
  error?: string
  /** 权限/SIP 等前置条件不满足时的可执行修复步骤 */
  remedy?: string
}

/** JSON 数组字符串 → 数组（库里是字符串，解析失败回退空数组，不抛）。 */
export function parseJsonArray(raw: string | null | undefined): string[] {
  if (!raw) return []
  try {
    const v = JSON.parse(raw)
    return Array.isArray(v) ? v.map(String) : []
  } catch {
    return []
  }
}
