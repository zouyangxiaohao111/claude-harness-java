import type { ConnectorAuthType, ConnectorState } from '@/api/connectors'

/** 授权方式中文名（卡片徽章 / 抽屉标题用） */
export const AUTH_LABEL: Record<ConnectorAuthType, string> = {
  oauth2: 'OAuth 2.0',
  apiKey: 'API Key',
  personalToken: '个人令牌',
  customFields: '自定义字段',
  imapAppPassword: 'IMAP 应用密码',
}

/** 连接状态中文名（卡片状态胶囊 / 抽屉文案用） */
export const STATUS_LABEL: Record<ConnectorState, string> = {
  unconnected: '未连接',
  pending: '授权中',
  connected: '已连接',
  error: '异常',
  reauth_required: '需重新授权',
}

/** 卡片的 CSS 状态类（`reauth_required` 复用 warning 语义的 `reauth` 类） */
export function statusClass(status?: ConnectorState | null): string {
  if (!status) return 'unconnected'
  return status === 'reauth_required' ? 'reauth' : status
}

/** 连接器分类中文名（后端 category 为英文枚举；顺序/文案对齐 Octop zh.json） */
export const CONNECTOR_CATEGORY_LABEL: Record<string, string> = {
  office: '办公协作',
  knowledge: '笔记与知识',
  travel: '出行与地图',
  productivity: '通讯与效率',
  media: '资讯与娱乐',
  professional: '专业工具',
  self_hosted: '自托管',
  data: '数据',
}

/** 连接器分类显示名 */
export function categoryLabel(category?: string | null): string | null {
  if (!category) return null
  return CONNECTOR_CATEGORY_LABEL[category] ?? category
}

/** 后端能手动落凭证、但**本轮没有运行时通道**的授权方式 */
export const CREDENTIAL_AUTH_TYPES = ['apiKey', 'personalToken', 'customFields', 'imapAppPassword'] as const

/**
 * 能力分档：
 * <ol>
 *   <li><b>优先读后端字段 `supportTier`</b>（P1 目录里 17 条 gateway 大多是 api_key，
 *       但本轮没有运行时通道 —— 只看 authType 会把它们误判成「能存凭据」进而误点保存）；</li>
 *   <li>字段缺失（老后端）才按 authType 推导：oauth2→supported、四种凭证→credentialOnly、其余→unavailable。</li>
 * </ol>
 */
export type ConnectorTier = 'supported' | 'credentialOnly' | 'unavailable'

export function connectorTier(c: { authType?: string | null; supportTier?: string | null }): ConnectorTier {
  const t = c.supportTier
  if (t === 'supported' || t === 'credentialOnly' || t === 'unavailable') return t
  if (c.authType === 'oauth2') return 'supported'
  if (c.authType && (CREDENTIAL_AUTH_TYPES as readonly string[]).includes(c.authType)) return 'credentialOnly'
  return 'unavailable'
}

/**
 * 对「用户能否操作」的最终分档：后端 `hidden=true` 的条目（qcc / tencent-ardot）虽是 supported，
 * 但本机不开放 ⇒ 页面上按 unavailable 展示（置灰、不可点），并标「内部/企业」。
 */
export function effectiveTier(c: { authType?: string | null; supportTier?: string | null; hidden?: boolean | null }): ConnectorTier {
  if (c.hidden) return 'unavailable'
  return connectorTier(c)
}

/** hidden 条目的展示徽标与原因（后端 unavailableReason 为空时用这句） */
export const HIDDEN_BADGE = '内部/企业'
export const HIDDEN_REASON = '内部/企业连接器，本机不开放授权'

/** 卡片/抽屉上的分档提示（supported 不显示） */
export const TIER_NOTE: Record<ConnectorTier, string | null> = {
  supported: null,
  credentialOnly: '运行需外部适配',
  unavailable: '暂不支持该授权方式',
}

/** 卡片主按钮文案：按授权方式分化（⛔ 不能四种情况都写成「授权」） */
export function primaryActionLabel(authType: ConnectorAuthType): string {
  switch (authType) {
    case 'oauth2': return '一键授权'
    case 'apiKey': return '填 API Key'
    case 'personalToken': return '粘贴 Token'
    case 'customFields': return '配置字段'
    case 'imapAppPassword': return '填应用密码'
  }
}

/** 手动凭证表单字段（字段名原样提交给 POST /{id}/credentials 的 fields） */
export interface CredentialField {
  k: string
  label: string
  ph: string
  secret?: boolean
  /** 是否必填（后端 credentialFields.required；缺省 true） */
  required?: boolean
}

/** 非 oauth 类型（以及 oauth2 自备 client 的场景）的凭证字段规格 */
export const CREDENTIAL_FIELDS: Record<Exclude<ConnectorAuthType, 'oauth2'>, CredentialField[]> = {
  apiKey: [{ k: 'apiKey', label: 'API Key', ph: '粘贴平台生成的 API Key', secret: true }],
  personalToken: [{ k: 'token', label: '访问令牌', ph: '粘贴个人访问令牌', secret: true }],
  customFields: [
    { k: 'fieldName', label: '字段名', ph: '如 region' },
    { k: 'fieldValue', label: '字段值', ph: '对应的值', secret: true },
  ],
  imapAppPassword: [
    { k: 'username', label: 'IMAP 用户名', ph: 'you@example.com' },
    { k: 'appPassword', label: '应用密码', ph: '邮箱后台生成的应用专用密码', secret: true },
  ],
}

/** 头像兜底色板（与技能市场其它卡片的观感一致） */
const AVATAR_COLORS = ['#0f5fd4', '#5B8DC9', '#5DB872', '#7B61FF', '#C99417', '#4A6CF7', '#0a49a8', '#D9534F']

export function connectorColor(name: string): string {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return AVATAR_COLORS[h % AVATAR_COLORS.length]
}
