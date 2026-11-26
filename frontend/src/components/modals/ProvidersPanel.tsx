import { Fragment, useEffect, useRef, useState } from 'react'
import type { Model, Provider } from '@/api/types'
import { FormModal, type FormSection } from '@/components/ui/FormModal'
import { ModelFormModal } from '@/components/ui/ModelFormModal'
import { ImportModelsModal } from '@/components/modals/ImportModelsModal'
import { KvEditor } from '@/components/common/KvEditor'
import { brandTagFromName } from '@/data'
import { settingsApi, getHttpProxy, putHttpProxy, probeHttpProxy, testHttpProxy, type HttpProxyCandidate } from '@/api/settings'
import { APPLY_ALL_ROLES, APPLY_ALL_TIER_FIELDS } from '@/components/settings/ModelSettingsPanel'
import type { UseProviders } from '@/hooks/useProviders'
import { ApiError } from '@/api/rest'
import { providerApi, type BuiltinBrandModel, type BuiltinConnection, type BuiltinProviderStatus } from '@/api/providers'
import type { CreateModelRequest, CreateProviderRequest, GoogleOAuthStatus, ModelType, UpdateModelRequest, UpdateProviderRequest, UpdateSettingsRequest } from '@/api/types'

interface ProvidersPanelProps {
  /** 后端拉取的 providers 列表（已嵌套 models） */
  providers: Provider[]
  /** 真实后端 CRUD API */
  providersApi: UseProviders
  showToast: (msg: string, type?: 'success' | 'info') => void
  /**
   * 保存全局设置（可选）。
   *
   * <p>注入时用它 —— 它是 App 侧 settings 的**唯一写入口**（`applyAppSettings`，内部 mergeSettings
   * 保留只读字段）⇒ 写完主模型后，App 的 `appSettings.mainModelName` 立刻生效（新建会话即用新主模型）。
   * 未注入（独立挂载 / 单测）时退化为直连 `settingsApi.update`，只影响本面板的「当前」标记。
   *
   * <p>返回 false = 保存失败（调用方已 toast 过原因）。
   */
  onSaveSettings?: (req: UpdateSettingsRequest) => Promise<boolean>
}

const EMPTY_PROVIDER: Provider = {
  id: '',
  name: '',
  type: 'openai_compatible',
  baseUrl: '',
  apiKeyMasked: '',
  extraHeaders: null,
  enabled: true,
  models: [],
}

const EMPTY_MODEL: Model = {
  id: '',
  name: '',
  alias: null,
  tag: 'DS',
  desc: '',
  type: 'chat',
  maxTokens: 128000,
  temperature: 1.0,
  topP: null,
  think: '',
  /** 模型级额外请求体（JSON 对象字符串）· 空 = 无额外参数 */
  extraBody: '',
  enabled: true,
  maxContextTokens: null,
}

/** L4b-2：新建 provider 的「首个模型」name 预填提示（anthropic → claude-sonnet-4-5；其余留空，由引导文案示例引导） */
const firstModelNameHint = (p: Provider): string => {
  if (p.models.length > 0) return ''
  const isAnthropic = p.type === 'anthropic' || (p.baseUrl ?? '').toLowerCase().includes('api.anthropic.com')
  return isAnthropic ? 'claude-sonnet-4-5' : ''
}

/**
 * 新模型的 tag（提供商标识）。
 *
 * <p>[2026-11-01] 「提供商标识」只读块已从模型弹窗移除 ⇒ tag 不再由表单携带固定默认值
 * （那会给任何 provider 加模型都写成 `DS`）。改按 provider 名推断品牌，
 * 再退化到模型名，最后才用历史默认值。
 */
const tagForNewModel = (providerName: string, modelName: string, fallback: string): string =>
  brandTagFromName(providerName) ?? brandTagFromName(modelName) ?? fallback

/** ChatGPT 订阅是否已登录（后端两个字段同时成立才算 · 只看 authMode 会把「登过又失效」误判为已登录）。 */
const isOAuthLoggedIn = (p: Provider): boolean => p.authMode === 'codex_oauth' && !!p.oauthAccountId

/**
 * Claude 订阅是否已登录。
 *
 * <p>⚠️ 与 ChatGPT 的判据不同：Claude 的 token 响应**不带账号标识**（无
 * {@code oauth_account_id}），只有 {@code auth_mode} 这一个可靠信号 —— 后端只在成功换到
 * token 后才把它写成 `claude_oauth`（退出登录会立刻回落 `api_key`）。
 */
const isClaudeOAuthLoggedIn = (p: Provider): boolean => p.authMode === 'claude_oauth'

/** 订阅账号只露后 6 位 —— 整串不展示，避免被顺手抄走。 */
const maskAccountId = (accountId: string): string =>
  accountId.length > 6 ? `…${accountId.slice(-6)}` : accountId

/** 左栏副标题：地址可解析时取 host（比整条 URL 短），否则退回协议类型。 */
const railSubtitle = (p: Provider): string => {
  const raw = (p.baseUrl ?? '').trim()
  if (raw) {
    try {
      const host = new URL(raw.includes('://') ? raw : `https://${raw}`).host
      if (host) return host
    } catch {
      /* 非法地址 → 退回协议类型 */
    }
  }
  return p.type
}

/**
 * 有官方图形的内置品牌（与 `public/provider-icons/*.svg` 一一对应）。
 *
 * <p>没列到的品牌（腾讯混元 / 智谱 / xAI / Groq / 阶跃 / 硅基流动）→ 内置子代理的
 * 机器人图标。用户 2026-11-16 明确：找不到品牌图就用它，⛔ 不要退化成字母。
 */
const BRAND_ICON_KEYS = new Set([
  'openai', 'claude', 'deepseek', 'minimax', 'opencode', 'qwen', 'kimi', 'wenxin',
  'doubao', 'gemini', 'mistral', 'ollama', 'openrouter', 'vllm', 'lmstudio', 'mimo',
  'hunyuan', 'zhipu', 'xai', 'groq', 'stepfun', 'siliconflow',
  // [2026-11-23 Octop provider 导入] 新品牌（图标见 public/provider-icons/）
  'modelscope', 'iflytek', 'tencent',
])

/** 品牌图标路径；未知 key → 兜底机器人图标。 */
const brandIconSrc = (key: string | null | undefined): string =>
  `/provider-icons/${key && BRAND_ICON_KEYS.has(key) ? key : '_fallback'}.svg`

/**
 * 打开系统浏览器（与 `utils/artifactOpen.ts` 同一判据）。
 *
 * <p>Tauri WebView 里 `window.open` 不会唤起系统浏览器（会被拦截或在壳内开空白页），
 * 必须走 `plugin-opener` 的 `openUrl`；浏览器 / dev 环境才用 `window.open`。
 */
const openExternal = async (url: string): Promise<void> => {
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    if (isTauri()) {
      const { openUrl } = await import('@tauri-apps/plugin-opener')
      await openUrl(url)
      return
    }
  } catch {
    /* 桌面壳不可用 → 回落浏览器 */
  }
  window.open(url, '_blank', 'noopener')
}

/**
 * Google 专用授权打开路径（⛔ 不改上方共享 openExternal，GPT/Claude 行为不变）。
 *
 * <p>Tauri openUrl 明确异常 → 返回警告文案（调用方展示 + 保持 pending + URL 兜底），
 * ⛔ 不静默回落 window.open（壳内会开 WebView / 被拦）。
 * Web window.open 带 noopener/noreferrer 成功也返回 null ⇒ null 不作为失败证据。
 */
const openGoogleAuthUrl = async (url: string): Promise<string | null> => {
  let tauri = false
  try {
    const { isTauri } = await import('@tauri-apps/api/core')
    tauri = isTauri()
  } catch {
    tauri = false
  }
  if (tauri) {
    try {
      const { openUrl } = await import('@tauri-apps/plugin-opener')
      await openUrl(url)
      return null
    } catch (e) {
      return `无法打开系统浏览器：${e instanceof Error ? e.message : String(e)}。请复制下方授权链接手动打开。`
    }
  }
  window.open(url, '_blank', 'noopener,noreferrer')
  return null
}

/**
 * 官方内置条目（方案 A）· 与后端识别约定一致。
 *
 * <p>`type=openai_response` 且（name ∈ {OpenAI, OpenAI-订阅} 或已登录 auth_mode=codex_oauth）。
 * ⇒ 普通列表必须滤掉它，否则与顶部固定卡片重复显示。
 */
const isOfficialProvider = (p: Provider): boolean =>
  p.type === 'openai_response' && (p.name === 'OpenAI' || p.name === 'OpenAI-订阅' || p.authMode === 'codex_oauth')

/** 自动回调链路：浏览器登录完成后后端已直接落库 ⇒ 前端按此节奏轮询登录结果。 */
const OAUTH_POLL_INTERVAL_MS = 2000
/** 轮询上限（2 分钟 / 2 秒 = 60 次）；超时只停轮询，粘贴区保留作兜底。 */
const OAUTH_POLL_MAX_TICKS = 60


/**
 * 协议类型选项（分段控件 · 三种模式）。
 *
 * <p><b>为什么不用原生下拉 + 原始枚举值</b>：此前下拉里直接写着 `openai_compatible` ——
 * 那是给程序看的 wire 值，用户根本不知道它是什么。分段控件给出中文名 + 端点路径，
 * wire 值仍是枚举本身（后端契约不变）。
 *
 * <p><b>三种模式</b>：
 * <ul>
 *   <li><b>OpenAI 兼容</b> —— /v1/chat/completions（绝大多数第三方网关）</li>
 *   <li><b>Anthropic 原生</b> —— /v1/messages（Claude 官方或原生兼容端点）</li>
 *   <li><b>OpenAI Responses</b> —— /v1/responses。
 *       ⚠️ <b>当前后端未实现</b>（后端 {@code ProviderType} 枚举无此值，
 *       {@code ProviderService.toProviderDto} 走 {@code ProviderType.valueOf(...)}
 *       ⇒ 真提交会 500）⇒ 界面上<b>显示但置灰不可选</b>，让用户知道「有这一种、只是还没支持」，
 *       而不是把整条能力从界面上抹掉。</li>
 * </ul>
 */
const PROTOCOL_OPTIONS = [
  { value: 'openai_compatible', label: 'OpenAI 兼容', desc: '/v1/chat/completions' },
  { value: 'anthropic', label: 'Anthropic 原生', desc: '/v1/messages' },
  { value: 'openai_response', label: 'OpenAI Responses', desc: '/v1/responses' },
]

/**
 * Base URL 提示的文案表 · 按协议类型取值（新建 / 编辑共用）。
 *
 * <p><b>为什么必须写在界面上</b>：后端<b>从不</b>替用户补或删 `/v1` —— 只 trim 去掉尾部斜杠
 * （`OpenAiSdkProvider.normalizeBaseUrl` / `AnthropicSdkProvider.normalizeBaseUrl`），
 * 路径由各协议<b>固定拼接</b>：OpenAI 兼容 = 地址 + `/chat/completions`，
 * Anthropic = 地址 + `/v1/messages`。⇒ 两种协议要求的结尾格式<b>正好相反</b>，
 * 填错只会拿到 404，而后端文案是「baseUrl 不正确（404）」，看不出该带还是不该带。
 *
 * <p><b>为什么每条都写「请求 {base}/…」</b>：只写「填到 /v1 为止」用户仍要自己拼一次才能确认；
 * 把最终路径摊开，用户拿自己的地址一对照就知道对不对。
 *
 * <p>导出仅为单测能直接对真值表断言 —— 走 UI 需要构造一个 `type: 'openai_response'` 的
 * Provider，而 `Provider.type` 是后端枚举子集（无此值），只能 cast 硬造，那是在测不存在的世界。
 */
export const BASE_URL_HINT: Record<string, string> = {
  openai_compatible: '填到 /v1 为止 · 请求 {base}/chat/completions',
  anthropic: '只填域名、不带 /v1 · 请求 {base}/v1/messages',
  openai_response: '填到 /v1 为止 · 请求 {base}/responses',
}

/** 未知 / 空类型（新建表单初值、历史数据）按 OpenAI 兼容处理 —— 那也正是后端保存时的推断结果。 */
export const baseUrlHint = (type: unknown): string => {
  const t = typeof type === 'string' ? type : ''
  return BASE_URL_HINT[t] ?? BASE_URL_HINT.openai_compatible
}

/** 模型类型 → 界面上的一行文字（与 ModelFormModal 的 TYPE_OPTIONS 标签一致，只用于只读展示）。 */
const MODEL_TYPE_LABEL: Record<string, string> = {
  chat: 'Chat',
  text: 'Text',
  vision: 'Vision',
  multimodal: 'Multimodal',
  image_generation: 'Image Generation',
  embedding: 'Embedding',
  audio: 'Audio',
  rerank: 'Rerank',
  moderation: 'Moderation',
  tts: 'TTS',
  asr: 'ASR',
}

/**
 * 模型分组（只渲染有模型的分组）。顺序按用途：对话 → 向量 → 重排序 → 多模态 → 语音 → 审核。
 * 未命中任何分组的类型进「其他」，避免模型被漏渲染。
 */
const MODEL_GROUPS: { label: string; types: ModelType[] }[] = [
  { label: '对话', types: ['chat', 'text'] },
  { label: '向量', types: ['embedding'] },
  { label: '重排序', types: ['rerank'] },
  { label: '多模态', types: ['multimodal', 'vision', 'image_generation'] },
  { label: '语音', types: ['audio', 'tts', 'asr'] },
  { label: '审核', types: ['moderation'] },
]

/** token 数 → 等宽短串（272K / 1M）。null 显示调用方给的默认值文案。 */
const fmtTokens = (n: number | null | undefined, fallback: string): string => {
  if (n == null) return fallback
  if (n >= 1_000_000) {
    const v = n / 1_000_000
    return `${Number.isInteger(v) ? v : v.toFixed(1)}M`
  }
  if (n >= 1_000) return `${Math.round(n / 1_000)}K`
  return String(n)
}

/** 模型能力标签（最多两枚 · 纯展示，不参与请求）。 */
const modelCaps = (m: Model): string[] => {
  const caps: string[] = []
  if (m.type === 'vision' || m.type === 'multimodal' || m.type === 'image_generation') caps.push('vision')
  if (m.type === 'embedding') caps.push('embedding')
  if (m.type === 'rerank') caps.push('rerank')
  if (m.type === 'audio' || m.type === 'tts' || m.type === 'asr') caps.push('audio')
  if ((m.think ?? '').trim()) caps.push('reasoning')
  return caps.slice(0, 2)
}

/**
 * 「自定义请求头」section（新建 / 编辑共用）。
 *
 * <p>值挂在 FormModal 的 `custom` 字段上（键名对齐 `Provider.extraHeaders`），由 KvEditor 维护；
 * 两个请求构造器再把 `p.extraHeaders` 发出去 —— 少任何一环，用户就改不动这个字段。
 */
const headersSection = (): FormSection => ({
  title: '自定义请求头',
  fields: [
    {
      type: 'custom',
      name: 'extraHeaders',
      label: '请求头',
      hint: '随请求一并发送的额外 HTTP 请求头；值里可写 ${session_id} 表示当前会话 ID',
      // value 是 unknown（FormModal 刻意不用 any 逼出显式收窄）；KvEditor 全空产出 {}
      render: (v, set) => <KvEditor value={v as Record<string, string> | null} onChange={set} />,
    },
  ],
})

/**
 * ProvidersPanel · Phase 7 联调版
 *
 * <p>所有 CRUD 走真实后端 API（useProviders hook）。组件本身不持有 providers 列表副本，
 * 完全从 `providers` prop 渲染（hook 自己管理 state）。
 *
 * <p>Form 数据约定：表单中名为 `apiKeyMasked` 的字段实际承载**原始 key**（用户输入的明文）。
 * Save 时把它当作 `apiKey` 发给后端，由后端脱敏后存库。
 */
export function ProvidersPanel({ providersApi, showToast, onSaveSettings }: ProvidersPanelProps) {
  const { list, loading, error, refresh, createProvider, updateProvider, deleteProvider, toggleProvider, createModel, updateModel, deleteModel } = providersApi

  const [editingProvider, setEditingProvider] = useState<Provider | null>(null)
  const [addingProvider, setAddingProvider] = useState(false)
  /** [V299] 品牌选择弹窗：先选品牌再填连接（一个品牌可建多条） */
  const [brandPickerOpen, setBrandPickerOpen] = useState(false)
  /** [V299] 非空 = 弹窗处于「归入品牌」模式（把这条自定义连接挂到某个内置品牌下） */
  const [adoptProviderId, setAdoptProviderId] = useState<string | null>(null)
  /** 内置品牌状态（后端只读快照；拉取失败静默，面板不空） */
  const [builtinStatuses, setBuiltinStatuses] = useState<BuiltinProviderStatus[]>([])
  /** 从内置卡进入新建表单时预填的品牌（null = 手动「+ 添加提供商」） */
  const [builtinPrefill, setBuiltinPrefill] = useState<BuiltinProviderStatus | null>(null)
  const [editingModel, setEditingModel] = useState<{ providerId: string; model: Model } | null>(null)
  const [addingModelFor, setAddingModelFor] = useState<{ providerId: string; prefillName: string } | null>(null)
  /** 拉取远端模型并导入（保存提供商后自动打开 · 见 onAddProvider） */
  const [importingModelsFor, setImportingModelsFor] = useState<Provider | null>(null)
  /** 内置卡新建时带过来的模板模型建议（[2026-11-23] 只在「导入模型」里作建议，用户可改） */
  const [importSuggestions, setImportSuggestions] = useState<BuiltinBrandModel[]>([])
  const [pending, setPending] = useState(false)
  /** ChatGPT 订阅：已展开粘贴区的 providerId → 粘贴内容（键存在 = 展开，空串也算已展开） */
  const [oauthPaste, setOauthPaste] = useState<Record<string, string>>({})
  /** 订阅登录动作（登录 / 完成 / 退出）进行中的 providerId ⇒ 该卡按钮置灰，防重复提交 */
  const [oauthPendingId, setOauthPendingId] = useState<string | null>(null)
  /** 自动回调登录的轮询定时器（同一时刻只保留一个；卸载时必须清掉） */
  const oauthPollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  /** 官方内置条目（方案 A）：挂载时拉一次；列表里若已有则以列表为准 */
  const [officialProvider, setOfficialProvider] = useState<Provider | null>(null)
  /** 官方卡片登录动作进行中（首次点击时条目还不存在 ⇒ 不能只靠 oauthPendingId 判） */
  const [officialPending, setOfficialPending] = useState(false)
  /** 内置 Claude 卡片登录动作进行中（首次点击时条目还不存在，同 officialPending 的理由） */
  const [claudePending, setClaudePending] = useState(false)
  /** 等待浏览器回调的 providerId（状态灯「等待」态；oauthPendingId 只覆盖发起请求那一瞬） */
  const [oauthWaitingId, setOauthWaitingId] = useState<string | null>(null)
  /** Google 订阅条目（挂载时 GET /official/google；204 → null）。 */
  const [googleProvider, setGoogleProvider] = useState<Provider | null>(null)
  /** Google 登录状态（GET .../oauth/google/status 是唯一权威；null = 还没读到）。 */
  const [googleStatus, setGoogleStatus] = useState<GoogleOAuthStatus | null>(null)
  /** Google 登录动作（ensure+authorize）进行中 —— 首次点击时条目可能还不存在。 */
  const [googlePending, setGooglePending] = useState(false)
  /** Google 手动完成 / 退出进行中（Google 专用，⛔ 不与 ChatGPT/Claude 共享 oauthPendingId 防串票）。 */
  const [googleBusy, setGoogleBusy] = useState(false)
  /** Google 手动粘贴区（null = 收起；id 绑定展开时的 Google 条目，⛔ 不复用无类型的 oauthPaste）。 */
  const [googlePaste, setGooglePaste] = useState<{ id: string; value: string } | null>(null)
  /** Google 状态轮询定时器（独立于 ChatGPT/Claude 的 oauthPollRef）。 */
  const googlePollRef = useRef<ReturnType<typeof setInterval> | null>(null)
  /** Google 轮询截止时刻（epoch ms · 以服务端 authorize/status 的 expiresAt 为准，前端不自行缩短）。 */
  const googleDeadlineRef = useRef<number>(0)
  /** Google 异步世代：退出 / 切走 / 卸载 / 新登录时 +1；晚到回包世代不符直接丢弃（不 setState、不起 timer）。 */
  const googleGenRef = useRef(0)
  /** 本次 authorize 的授权 URL（绑定 provider id；logout/ready/超时/重新 authorize/切卡清，⛔ 不跨 attempt 串用）。 */
  const [googleAuthUrl, setGoogleAuthUrl] = useState<{ id: string; url: string } | null>(null)
  /** Google 打开浏览器失败警告（Tauri openUrl 明确异常；与 OAuth 状态分离，不强行置 error）。 */
  const [googleOpenWarn, setGoogleOpenWarn] = useState<string | null>(null)
  /** Google 独立到期 timer（单次 status 请求悬挂也能按绝对截止收口）。 */
  const googleExpiryRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  /** 左栏当前选中的条目：'official' 或 provider.id */
  const [selectedKey, setSelectedKey] = useState<string>('official')
  /** 展开中的模型溢出菜单（model.id） */
  const [openModelMenu, setOpenModelMenu] = useState<string | null>(null)
  /** 全局主模型（`settings.mainModelName`，格式 `provider/model`）· 模型行 ⋯ 菜单可写 */
  const [mainModelName, setMainModelName] = useState<string | null>(null)
  /** [V298] 官方卡片的出站线路（HTTP 代理）· LLM 专用，与 WebSearch 的代理互不影响 */
  const [httpProxy, setHttpProxy] = useState('')
  /** 已落库的代理值（用于区分「输入框里改了但没点记住」） */
  const [proxySaved, setProxySaved] = useState('')
  /** [V298] 代理格式校验的就近红字（null = 无错误） */
  const [proxyError, setProxyError] = useState<string | null>(null)
  /** [V298] 「记住」保存中（防连点） */
  const [proxySaving, setProxySaving] = useState(false)
  /** [代理自动检测] 命中的本机代理候选（null = 还没探测过 / 探测失败） */
  const [proxyCandidates, setProxyCandidates] = useState<HttpProxyCandidate[] | null>(null)
  /** [代理自动检测] 提示条是否已被「填入 / 忽略」处理（true = 本次不再显示） */
  const [proxyDetectHandled, setProxyDetectHandled] = useState(false)
  /** [代理自动检测] 探测中（按钮转圈，防连点） */
  const [proxyProbing, setProxyProbing] = useState(false)
  /** [代理自动检测] 记住时的实测结果：null=没测过；ok=false 时 text 是可读原因（已保存） */
  const [proxyTestNote, setProxyTestNote] = useState<{ ok: boolean; text: string } | null>(null)

  /** 重新拉官方条目（登录成功后列表可能还没刷新，卡片要能立刻显示「已登录」）。 */
  const refreshOfficial = async () => {
    try {
      setOfficialProvider(await providerApi.officialCodex())
    } catch {
      // 查询失败不阻断面板：列表里若已有官方条目仍会显示
    }
  }

  /** 重新拉内置品牌状态（Claude 登录会新建/改动 provider ⇒ 左栏卡片要更新「已配置」与状态）。 */
  const refreshBuiltin = async () => {
    try {
      setBuiltinStatuses((await providerApi.listBuiltin()) ?? [])
    } catch (e) {
      // 同挂载时的口径：左栏增强信息，失败静默，不影响已渲染内容
      console.warn('[ProvidersPanel] 刷新内置品牌状态失败', e)
    }
  }

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const p = await providerApi.officialCodex()
        if (!cancelled) setOfficialProvider(p)
      } catch {
        // 同上：失败不回滚已有 state
      }
    })()
    return () => { cancelled = true }
  }, [])

  /**
   * 内置品牌状态：与官方条目同批在挂载时拉一次。
   *
   * <p><b>失败静默</b>（只 console.warn）：这是左栏「内置」组的增强信息，
   * 拉不到时面板仍按 providers 列表正常工作，⛔ 不能让整块空白。
   */
  useEffect(() => {
    let cancelled = false
    void providerApi.listBuiltin()
      .then((s) => { if (!cancelled) setBuiltinStatuses(s ?? []) })
      .catch((e) => { console.warn('[ProvidersPanel] 读取内置品牌状态失败，左栏仅显示已配置项', e) })
    return () => { cancelled = true }
  }, [])

  /**
   * Google 订阅：挂载只读已有条目 + 状态（⛔ 不 ensure、不 authorize、不取票）。
   * 状态读取失败落 error 终态，不留「读取中」悬挂；条目读取失败保持「未登录」（登录按钮会 ensure）。
   */
  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const p = await providerApi.officialGoogle()
        if (cancelled) return
        setGoogleProvider(p)
        if (!p) return
        try {
          const st = await providerApi.oauthGoogleStatus(p.id)
          if (!cancelled) setGoogleStatus(st)
        } catch {
          if (!cancelled) setGoogleStatus({ status: 'error', oauthProject: null, expiresAt: null, message: '读取登录状态失败，请重新登录或稍后重试' })
        }
      } catch {
        // 条目读取失败不阻断面板：卡片显示「未登录」，用户点登录会重试
      }
    })()
    return () => { cancelled = true }
  }, [])

  /**
   * 只读读取全局主模型（`settings.mainModelName`，格式 `provider/model`）：
   * 模型行上的「当前」标记与左侧强调线需要它。写入口 = 模型行 ⋯ 菜单的「设为当前」/「配置到所有档位」。
   */
  useEffect(() => {
    let cancelled = false
    void settingsApi.get()
      .then((s) => { if (!cancelled) setMainModelName(s.mainModelName ?? null) })
      .catch(() => { /* 读不到就不标「当前」，不影响面板其余功能 */ })
    return () => { cancelled = true }
  }, [])

  /** [V298] 挂载时拉当前出站代理回填（读不到就留空 = 直连，不阻断面板） */
  useEffect(() => {
    let cancelled = false
    void getHttpProxy()
      .then((r) => {
        if (cancelled) return
        setHttpProxy(r.proxy ?? '')
        setProxySaved(r.proxy ?? '')
        // [代理自动检测] 从未配置过才静默探一次；已有配置不动（避免每次打开面板都扫端口）。
        //   探测结果只进提示条，不写输入框 —— 是否填入由用户决定。
        if (!r.proxy) {
          setProxyProbing(true)
          void probeHttpProxy()
            .then((res) => {
              if (cancelled) return
              if (res.candidates.length > 0) setProxyCandidates(res.candidates)
            })
            .catch(() => { /* 探测失败静默：不打扰，用户可点「自动检测」重试 */ })
            .finally(() => { if (!cancelled) setProxyProbing(false) })
        }
      })
      .catch(() => { /* 读失败不改 state：留空即直连 */ })
    return () => { cancelled = true }
  }, [])

  /**
   * [V298] 代理格式：`http(s)://host:port`（⛔ SOCKS 端口不行）。
   * 只做「能不能用」的粗校验，真正的解析仍在后端 OutboundProxy.parse。
   */
  const PROXY_PATTERN = /^https?:\/\/[^\s/:]+:\d{1,5}$/
  const PROXY_FORMAT_HINT = '端口写在冒号后面，比如 http://127.0.0.1:7897'

  /**
   * 「自动检测」：后端并发试本机常见端口，**只认真的能转发 HTTPS 的 HTTP 代理**。
   *
   * <p>结果不自动填入 ⇒ 输入框上方出现提示条，由用户点「填入」才写进输入框；点「忽略」收起。
   */
  const onProbeProxy = async () => {
    if (proxyProbing) return
    setProxyProbing(true)
    setProxyError(null)
    setProxyTestNote(null)
    setProxyDetectHandled(false)
    try {
      const r = await probeHttpProxy()
      if (r.candidates.length === 0) {
        setProxyCandidates(null)
        setProxyError('没找到本机代理。可以手动填，比如 http://127.0.0.1:7897')
      } else {
        setProxyCandidates(r.candidates)
      }
    } catch {
      setProxyCandidates(null)
      setProxyError('探测没跑成，稍后再试一次（也可以直接手填）')
    } finally {
      setProxyProbing(false)
    }
  }

  /** 提示条「填入」：把该候选写进输入框，其余候选留作 chip 备用。 */
  const onFillDetectedProxy = (c: HttpProxyCandidate) => {
    setHttpProxy(`http://127.0.0.1:${c.port}`)
    setProxyError(null)
    setProxyTestNote(null)
    setProxyDetectHandled(true)
  }

  /** 提示条「忽略」：本次不再提示（不静默消失，状态行会说明）。 */
  const onDismissDetectedProxy = () => {
    setProxyDetectHandled(true)
  }

  /**
   * [保密 2026-11-22 B6] 「清空」：显式清除代理配置（不是靠留空）。
   * 三态里 null = 未配置（回落环境变量/直连）；与「留空 + 记住」等价，但有明确按钮与提示。
   */
  const onClearProxy = async () => {
    if (!proxySaved) return
    setProxyError(null)
    setProxySaving(true)
    setProxyTestNote(null)
    try {
      const r = await putHttpProxy(null)
      setHttpProxy(r.proxy ?? '')
      setProxySaved(r.proxy ?? '')
      showToast('已清除代理配置（回落环境变量/直连）', 'info')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setProxySaving(false)
    }
  }

  /** 「记住」：先实测再落库；**实测失败也照存**（用户明确要求），只把原因提示出来 */
  const onSaveProxy = async () => {
    const v = httpProxy.trim()
    // [保密 2026-11-22 B6] 后端 GET 只回 `user:****@host`（口令段掩码），该形态过不了
    // PROXY_PATTERN（本来也不允许 userinfo）。未改动（=== 已保存值）时跳过格式校验，
    // 原样回传 → 后端用哨兵换回真口令。只有用户真改了才校验。
    if (v !== '' && v !== proxySaved.trim() && !PROXY_PATTERN.test(v)) {
      setProxyError(PROXY_FORMAT_HINT)
      return
    }
    setProxyError(null)
    setProxySaving(true)
    setProxyTestNote(null)
    let note: { ok: boolean; text: string } | null = null
    if (v !== '') {
      try {
        const t = await testHttpProxy(v)
        note = t.ok
          ? { ok: true, text: `通了，延迟 ${t.latencyMs}ms。下一次请求从这条线路出去。` }
          : { ok: false, text: `连不上（${t.error ?? '未知原因'}）。已按你的选择保存，请求可能失败。` }
      } catch {
        note = { ok: false, text: '没能验证这条线路。已保存，可以稍后回来重试。' }
      }
    }
    try {
      const r = await putHttpProxy(v === '' ? null : v)
      setHttpProxy(r.proxy ?? '')
      setProxySaved(r.proxy ?? '')
      setProxyTestNote(note)
      showToast(
        note === null ? '已改为直连' : note.ok ? '通了，已记住' : '已保存，但没连通',
        note === null ? 'info' : note.ok ? 'success' : 'info',
      )
    } catch (e) {
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      showToast(msg, 'info')
    } finally {
      setProxySaving(false)
    }
  }

  const officialFromList = list.find((p) => isOfficialProvider(p)) ?? null
  /** 官方条目：优先取列表里那条（最新），其次 state（ensure 后 / 挂载时拉到的） */
  const officialEntry = officialFromList ?? officialProvider
  /** Google 订阅条目：优先列表里最新那条（已知 id 或 google_oauth 兜底），其次挂载 / ensure 后拉到的 state。 */
  const googleFromList = list.find((p) => p.id === googleProvider?.id || p.authMode === 'google_oauth') ?? null
  const googleEntry = googleFromList ?? googleProvider
  /** 普通列表：滤掉官方条目与 Google 订阅条目，避免与左栏固定卡片重复显示 */
  const regularProviders = list.filter((p) => !isOfficialProvider(p) && p.id !== googleEntry?.id)

  /** 内置组里除 openai 外的 4 张常驻卡（openai 仍走既有官方条目行为）。 */
  const builtinCards = builtinStatuses.filter((b) => b.key !== 'openai')

  /**
   * Claude 订阅条目：优先取 `authMode=claude_oauth` 那条（登录后唯一可靠判据），
   * 其次按内置品牌的 host/name 命中（「已配置但还没登录」的同一条记录）。
   */
  const claudeBrand = builtinStatuses.find((b) => b.key === 'claude') ?? null
  const claudeEntry = list.find((p) => isClaudeOAuthLoggedIn(p))
    ?? (claudeBrand?.providerId ? list.find((p) => p.id === claudeBrand.providerId) ?? null : null)
  const claudeLoggedIn = !!claudeEntry && isClaudeOAuthLoggedIn(claudeEntry)
  const claudeWaiting = !!claudeEntry && oauthWaitingId === claudeEntry.id
  /** 左栏 Claude 卡的三态文案（未登录 / 等待授权 / 已登录） */
  const claudeStateText = claudeLoggedIn ? '已登录' : claudeWaiting ? '等待授权' : '未登录'
  /** Google 卡五态（status 接口是唯一权威；有条目但状态未读 → loading 中间态，⛔ 不假就绪）。 */
  const googleState = googleStatus?.status ?? (googleEntry ? 'loading' : 'logged_out')
  const googleWaiting = googleState === 'pending' || googleState === 'onboarding'
  const googleReady = googleState === 'ready'
  const googleStateText = googleReady ? '已就绪'
    : googleState === 'pending' ? '等待授权'
      : googleState === 'onboarding' ? '账号初始化'
        : googleState === 'error' ? '失败'
          : googleState === 'loading' ? '读取中'
            : '未登录'
  /** Google 详情视图激活：虚拟键 'google'（条目未创建）或真实条目 id。 */
  const showGoogle = selectedKey === 'google' || (!!googleEntry && selectedKey === googleEntry.id)
  const googleStat = googleReady
    ? { cls: 'pv-stat is-ok', dot: 'pv-dot is-ok', text: `已就绪${googleStatus?.oauthProject ? ` · ${googleStatus.oauthProject}` : ''}` }
    : googleWaiting
      ? { cls: 'pv-stat is-wait', dot: 'pv-dot is-wait', text: googleStateText }
      : { cls: 'pv-stat', dot: 'pv-dot', text: googleStateText }
  /** 已被某个内置品牌命中的 provider.id（自定义组据此排除；一个品牌可命中多条）。 */
  const builtinMatchedIds = new Set<string>()
  builtinStatuses.forEach((b) => {
    if (b.providerId) builtinMatchedIds.add(b.providerId)
    ;(b.connections ?? []).forEach((c) => builtinMatchedIds.add(c.id))
  })
  /** 自定义组：普通列表里未被任何内置品牌命中的那些。 */
  const customProviders = regularProviders.filter((p) => !builtinMatchedIds.has(p.id))

  /**
   * 左栏分节：后端 group → 国际 / 国内 / 本地。
   *
   * <p>⚠️ 兜底：旧后端（未带 group）返回 undefined ⇒ 不分组会让所有品牌都不渲染。
   * 此时退回单一「内置」组，绝不静默丢卡片。
   */
  /** 这条 provider 被哪个内置品牌识别（brand_key 或 host/name 兜底命中）；未命中 → null。 */
  const brandKeyForProvider = (pid: string): string | null => {
    for (const b of builtinStatuses) {
      if (b.providerId === pid) return b.key
      if ((b.connections ?? []).some((c) => c.id === pid)) return b.key
    }
    return null
  }

  /** 品牌选择弹窗的分组（旧后端无 group 时退回单组「内置」，不丢牌子）。 */
  const pickerGroups = (() => {
    const gs = ['国际', '国内', '本地']
      .map((label) => ({ label, items: builtinStatuses.filter((b) => b.group === label) }))
      .filter((g) => g.items.length > 0)
    return gs.length > 0 ? gs : [{ label: '内置', items: builtinStatuses }]
  })()

  /** 左栏只列「有连接」的品牌（Claude 订阅常驻，另算）；未配置的品牌只在添加弹窗里出现。 */
  const hasVisibleRows = (b: BuiltinProviderStatus): boolean =>
    b.key === 'claude' || (b.connections ?? []).length > 0 || !!b.providerId

  const BUILTIN_GROUP_ORDER = ['国际', '国内', '本地']
  const hasBuiltinGroup = builtinCards.some((b) => BUILTIN_GROUP_ORDER.includes(b.group))
  const builtinGroups = hasBuiltinGroup
    ? BUILTIN_GROUP_ORDER
        .map((label) => ({ label, items: builtinCards.filter((b) => b.group === label && hasVisibleRows(b)) }))
        // 「国际」组即使没有别的连接也要留：OpenAI 订阅卡固定挂在这一组
        .filter((g) => g.items.length > 0 || g.label === '国际')
    : (builtinCards.length > 0 ? [{ label: '内置', items: builtinCards.filter(hasVisibleRows) }] : [])

  /** 一行连接（品牌图标 + 连接名 + 品牌徽章 + 模型数）。 */
  const renderConnRow = (b: BuiltinProviderStatus, c: BuiltinConnection) => (
    <button
      key={c.id}
      type="button"
      className={`pv-rail-card${c.enabled ? '' : ' is-off'}`}
      aria-selected={selectedKey === c.id}
      title={`${b.name} · ${c.name}`}
      onClick={() => setSelectedKey(c.id)}
    >
      <span className="pv-rail-card-icon" aria-hidden="true">
        <img src={brandIconSrc(b.key)} alt="" />
      </span>
      <span className="pv-rail-card-body">
        <span className="pv-rail-card-name">
          {c.name}
          <span className="pv-rail-card-badge">{b.name}</span>
        </span>
        <span className="pv-rail-card-sub">{b.name} · {c.modelCount} 个模型</span>
      </span>
      <span className="pv-rail-card-side">
        {c.authMode && c.authMode !== 'api_key' ? '订阅' : 'Key'}
      </span>
    </button>
  )

  /**
   * 一个品牌在左栏占几行：有几条连接就几行；没有连接 → 一行「未配置」（点击去新建）。
   *
   * <p>[V299] 一个品牌可以有多条连接（不同 Key / 不同账号）⇒ 左栏是「品牌分组 > 连接」，
   * 不再是「一个品牌一张卡」。OpenAI 官方卡仍在上方（订阅登录入口在它上面），
   * 但 openai 名下**额外**的 Key 连接也在这里展开。
   */
  const renderBuiltinRows = (b: BuiltinProviderStatus) => {
    // 兼容旧后端（无 connections 字段）：已配置时按单条连接渲染
    const all: BuiltinConnection[] = (b.connections
      ?? (b.providerId
        ? [{ id: b.providerId, name: b.providerName ?? b.name, modelCount: b.modelCount, enabled: true }]
        : [])).filter((c) => c.id !== googleEntry?.id)

    // Claude 订阅 = 内置常驻行（未登录也显示；点击直接走登录）
    if (b.key === 'claude') {
      const others = all.filter((c) => c.id !== claudeEntry?.id)
      return (
        <Fragment key={b.key}>
          <button
            type="button"
            className="pv-rail-card"
            aria-selected={!!claudeEntry && selectedKey === claudeEntry.id}
            onClick={() => { void onClaudeOpenSubscription() }}
          >
            <span className="pv-rail-card-icon" aria-hidden="true">
              <img src={brandIconSrc('claude')} alt="" />
            </span>
            <span className="pv-rail-card-body">
              <span className="pv-rail-card-name">
                Claude 订阅
                <span className="pv-rail-card-badge">内置</span>
                <span className="pv-rail-card-badge is-sub">订阅</span>
              </span>
              <span className="pv-rail-card-sub">Claude 订阅 · {claudeStateText}</span>
            </span>
            <span className="pv-rail-card-side">
              {claudeEntry ? `${claudeEntry.models.length} 个模型` : '未配置'}
            </span>
          </button>
          {others.map((c) => renderConnRow(b, c))}
        </Fragment>
      )
    }

    // OpenAI：订阅条目由上方专属卡承担，这里只展开额外连接
    if (b.key === 'openai') {
      const extra = all.filter((c) => c.id !== officialEntry?.id)
      return <Fragment key={b.key}>{extra.map((c) => renderConnRow(b, c))}</Fragment>
    }

    // 其它品牌：只列已配置的连接；没配置就不出现在列表里（到「+ 添加提供商」的品牌库里选）
    if (all.length === 0) return null
    return (
      <Fragment key={b.key}>
        {all.map((c) => renderConnRow(b, c))}
      </Fragment>
    )
  }

  /**
   * 「归入内置品牌」：把自定义连接挂到某个品牌下（写 `brand_key`）。
   *
   * <p>写完后两件事都要做：provider 列表刷新 + builtin 状态刷新。
   * 少一个就会出现「详情已有品牌、左栏还在自定义组」的半截状态。
   */
  const adoptBrand = (brandKey: string) => {
    const id = adoptProviderId
    if (!id) return
    setAdoptProviderId(null)
    setBrandPickerOpen(false)
    void wrap(async () => {
      await updateProvider(id, { brandKey })
      await refreshBuiltin()
      setSelectedKey(id)
    }, '已归入内置品牌')
  }

  /** 清轮询定时器（幂等）。 */
  const stopOAuthPoll = () => {
    if (oauthPollRef.current !== null) {
      clearInterval(oauthPollRef.current)
      oauthPollRef.current = null
    }
  }

  // 组件卸载：不停掉定时器会在卸载后继续 setState
  useEffect(() => () => {
    if (oauthPollRef.current !== null) {
      clearInterval(oauthPollRef.current)
      oauthPollRef.current = null
    }
  }, [])

  // ---- Google 订阅轮询（独立 timer + 世代保护，⛔ 不与 ChatGPT/Claude 共享） ----
  /** 清 Google 轮询 + 到期定时器（幂等）。 */
  const stopGooglePoll = () => {
    if (googlePollRef.current !== null) {
      clearInterval(googlePollRef.current)
      googlePollRef.current = null
    }
    if (googleExpiryRef.current !== null) {
      clearTimeout(googleExpiryRef.current)
      googleExpiryRef.current = null
    }
  }

  /** 作废全部在途 Google 异步回包（退出 / 切走 / 卸载 / 新登录）并停轮询。 */
  const invalidateGoogle = () => {
    googleGenRef.current += 1
    stopGooglePoll()
  }

  /** 本次 attempt 绝对截止收口：停 timer/poll、作废世代、清链接、落 error 终态（迟到 ready 不能复活）。 */
  const expireGoogleAttempt = (gen: number) => {
    if (gen !== googleGenRef.current) return
    invalidateGoogle()
    setGoogleAuthUrl(null)
    setGoogleOpenWarn(null)
    setGoogleStatus({ status: 'error', oauthProject: null, expiresAt: null, message: '授权等待超时，请重新点击登录' })
  }

  /**
   * 有界 status 轮询：绝对硬截止 = 初始 authorize.expiresAt（覆盖整个流程）。
   * ⛔ 不跟随 status 更晚的 expiresAt 后滑（只取 min 收紧更早过期）；
   * 独立到期 timer 保证单次 status 请求悬挂也能收口。ready/error/logged_out 收口；
   * 世代不符的晚到回包直接丢弃（切走 / 卸载后不 setState、不起 timer）。
   */
  const startGooglePoll = (providerId: string, expiresAt: number) => {
    stopGooglePoll()
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
      setGoogleStatus({ status: 'error', oauthProject: null, expiresAt: null, message: '授权链接有效期异常，请重新点击登录' })
      return
    }
    googleDeadlineRef.current = expiresAt
    const gen = googleGenRef.current
    googleExpiryRef.current = setTimeout(() => { expireGoogleAttempt(gen) }, Math.max(0, expiresAt - Date.now()))
    googlePollRef.current = setInterval(() => {
      void (async () => {
        if (gen !== googleGenRef.current) { stopGooglePoll(); return }
        if (Date.now() >= googleDeadlineRef.current) { expireGoogleAttempt(gen); return }
        try {
          const st = await providerApi.oauthGoogleStatus(providerId)
          if (gen !== googleGenRef.current) return
          if ((st.status === 'pending' || st.status === 'onboarding')
            && Number.isFinite(st.expiresAt) && (st.expiresAt as number) < googleDeadlineRef.current) {
            googleDeadlineRef.current = st.expiresAt as number
          }
          setGoogleStatus(st)
          if (st.status === 'ready') {
            invalidateGoogle()
            setGoogleAuthUrl(null)
            setGoogleOpenWarn(null)
            setGooglePaste(null)
            showToast('Google 订阅已就绪', 'success')
            await refresh()
            await refreshBuiltin()
          } else if (st.status === 'error' || st.status === 'logged_out') {
            invalidateGoogle()
            setGoogleAuthUrl(null)
            setGoogleOpenWarn(null)
          }
        } catch {
          // 后端可能正忙：本轮忽略，绝对截止兜底总时限
        }
      })()
    }, OAUTH_POLL_INTERVAL_MS)
  }

  // 切走 Google 卡：停轮询 + 作废在途回包 + 清授权链接（晚到 ready/pending 不得污染新卡或复活轮询）
  useEffect(() => {
    if (!showGoogle) {
      invalidateGoogle()
      setGooglePending(false)
      setGoogleBusy(false)
      setGoogleAuthUrl(null)
      setGoogleOpenWarn(null)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [showGoogle])

  // 卸载：停 Google 轮询并作废在途回包
  useEffect(() => () => { invalidateGoogle() }, [])

  // ---- Provider CRUD 包装（带错误处理 + toast） ----
  const wrap = async (fn: () => Promise<unknown>, successMsg: string) => {
    setPending(true)
    try {
      await fn()
      showToast(successMsg, 'success')
    } catch (e) {
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      showToast(`${msg}`, 'info')
    } finally {
      setPending(false)
    }
  }

  const onToggle = (id: string, currentlyEnabled: boolean) => {
    const p = list.find((x) => x.id === id)
    void wrap(
      () => toggleProvider(id, !currentlyEnabled),
      `${p?.name} ${!currentlyEnabled ? '已启用' : '已停用'}`
    )
  }

  const onRemoveProvider = (id: string) => {
    const p = list.find((x) => x.id === id)
    if (!confirm(`删除提供商 "${p?.name}"？所有关联模型也会被删除。`)) return
    void wrap(() => deleteProvider(id), '已删除提供商')
  }

  // ---- ChatGPT 订阅登录（自动回调为主 · 手动粘贴兜底 · 仅 openai_response） ----
  /**
   * 「登录 ChatGPT 订阅」：后端生成授权 URL → 浏览器打开 → 同时展开粘贴区 + 开始轮询登录结果。
   *
   * <p>后端在 1455 上监听回调，浏览器登录完成后<b>直接落库</b>；前端每 2 秒查一次该 provider，
   * 看到 `authMode=codex_oauth && oauthAccountId` 就收起粘贴区。若 1455 被占用（后端降级），
   * 轮询会一直查不到 ⇒ 2 分钟后停轮询，粘贴区仍在，用户照旧可粘 URL 点「完成登录」。
   *
   * <p>Claude 订阅复用同一套轮询，只是「已登录」判据不同（见 {@link isClaudeOAuthLoggedIn}）。
   */
  const startOAuthPoll = (providerId: string, isLoggedIn: (p: Provider) => boolean = isOAuthLoggedIn) => {
    stopOAuthPoll()
    setOauthWaitingId(providerId)
    let ticks = 0
    oauthPollRef.current = setInterval(() => {
      ticks += 1
      void (async () => {
        try {
          const fresh = await providerApi.get(providerId)
          if (isLoggedIn(fresh)) {
            stopOAuthPoll()
            setOauthWaitingId(null)
            setOauthPaste((prev) => {
              const next = { ...prev }
              delete next[providerId]
              return next
            })
            showToast('登录成功', 'success')
            await refresh()
            await refreshOfficial()
            await refreshBuiltin()
            return
          }
        } catch {
          // 后端可能正忙/重启：本轮忽略，下一轮再试；超时由下面的 ticks 上限兜底
        }
        if (ticks >= OAUTH_POLL_MAX_TICKS) {
          stopOAuthPoll()
          setOauthWaitingId(null)
        }
      })()
    }, OAUTH_POLL_INTERVAL_MS)
  }

  const onOAuthStart = async (p: Provider) => {
    setOauthPendingId(p.id)
    try {
      const { url } = await providerApi.oauthAuthorize(p.id)
      await openExternal(url)
      startOAuthPoll(p.id)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setOauthPendingId(null)
    }
  }

  const onOAuthComplete = async (p: Provider) => {
    const input = (oauthPaste[p.id] ?? '').trim()
    if (!input) {
      showToast('请先粘贴登录后浏览器地址栏里的整条 URL', 'info')
      return
    }
    setOauthPendingId(p.id)
    try {
      const res = await providerApi.oauthComplete(p.id, input)
      if (!res.ok) {
        showToast(res.message ?? '登录失败', 'info')
        return
      }
      showToast('ChatGPT 订阅已登录', 'success')
      stopOAuthPoll()
      setOauthWaitingId(null)
      setOauthPaste((prev) => {
        const next = { ...prev }
        delete next[p.id]
        return next
      })
      await refresh()
      await refreshOfficial()
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setOauthPendingId(null)
    }
  }

  const onOAuthLogout = async (p: Provider) => {
    if (!confirm(`退出 "${p.name}" 的 ChatGPT 订阅登录？`)) return
    setOauthPendingId(p.id)
    try {
      await providerApi.oauthLogout(p.id)
      stopOAuthPoll()
      setOauthWaitingId(null)
      showToast('已退出 ChatGPT 订阅', 'success')
      await refresh()
      await refreshOfficial()
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setOauthPendingId(null)
    }
  }

  /**
   * 官方卡片「登录 ChatGPT 订阅」：先幂等 ensure 拿到 id（首次点击才创建），
   * 再复用 {@link onOAuthStart}（授权 URL + 打开浏览器 + 粘贴区 + 轮询）。
   */
  const onOfficialOAuthStart = async () => {
    setOfficialPending(true)
    try {
      const target = officialEntry ?? (await providerApi.ensureOfficialCodex())
      setOfficialProvider(target)
      await onOAuthStart(target)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setOfficialPending(false)
    }
  }

  // ---- Claude 订阅登录（127.0.0.1:53692 自动回调 · 端口占用时手动粘贴兜底） ----
  /**
   * 「登录 Claude 订阅」：后端生成授权 URL（state = PKCE verifier）→ 打开浏览器 → 轮询登录结果。
   *
   * <p>Claude 后端监听 127.0.0.1:53692 自动接住回调；53692 被占用时后端降级，轮询查不到，
   * 用户仍可用下方粘贴区手动完成。
   */
  const onClaudeOAuthStart = async (p: Provider) => {
    setOauthPendingId(p.id)
    try {
      const { url } = await providerApi.oauthClaudeAuthorize(p.id)
      await openExternal(url)
      startOAuthPoll(p.id, isClaudeOAuthLoggedIn)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setOauthPendingId(null)
    }
  }

  /**
   * 点「Claude 订阅」行：只切到这条连接，**不发起登录**。
   *
   * <p>⛔ 用户 2026-11-16 明确：订阅不能「点开就默认打开授权页」，只有用户点详情里的
   * 「登录 Claude 订阅」按钮时才弹。没有条目时先幂等 ensure 出条目不登录。
   */
  const onClaudeOpenSubscription = async () => {
    if (claudeEntry) {
      setSelectedKey(claudeEntry.id)
      return
    }
    if (claudePending) return
    setClaudePending(true)
    try {
      const target = await providerApi.ensureOfficialClaude()
      await refresh()
      await refreshBuiltin()
      setSelectedKey(target.id)
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setClaudePending(false)
    }
  }

  const onClaudeOAuthComplete = async (p: Provider) => {
    const input = (oauthPaste[p.id] ?? '').trim()
    if (!input) {
      showToast('请先粘贴登录后浏览器地址栏里的整条 URL', 'info')
      return
    }
    setOauthPendingId(p.id)
    try {
      const res = await providerApi.oauthClaudeComplete(p.id, input)
      if (!res.ok) {
        showToast(res.message ?? '登录失败', 'info')
        return
      }
      showToast('Claude 订阅已登录', 'success')
      stopOAuthPoll()
      setOauthWaitingId(null)
      setOauthPaste((prev) => {
        const next = { ...prev }
        delete next[p.id]
        return next
      })
      await refresh()
      await refreshBuiltin()
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setOauthPendingId(null)
    }
  }

  const onClaudeOAuthLogout = async (p: Provider) => {
    if (!confirm(`退出 "${p.name}" 的 Claude 订阅登录？`)) return
    setOauthPendingId(p.id)
    try {
      await providerApi.oauthClaudeLogout(p.id)
      stopOAuthPoll()
      setOauthWaitingId(null)
      showToast('已退出 Claude 订阅', 'success')
      await refresh()
      await refreshBuiltin()
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setOauthPendingId(null)
    }
  }

  // ---- Google 订阅登录（Gemini Code Assist · 动态端口回调 · 手动粘贴兜底） ----
  /** 读一次 Google 状态（纯只读）。失败落 error 终态，⛔ 不留「读取中」悬挂；世代不符不回写。 */
  const refreshGoogleStatus = async (id: string, gen: number): Promise<GoogleOAuthStatus | null> => {
    try {
      const st = await providerApi.oauthGoogleStatus(id)
      if (gen === googleGenRef.current) setGoogleStatus(st)
      return st
    } catch {
      if (gen === googleGenRef.current) {
        setGoogleStatus({ status: 'error', oauthProject: null, expiresAt: null, message: '读取登录状态失败，请重试' })
      }
      return null
    }
  }

  /** 点「Google 订阅」卡：只选中 + 读已有状态，⛔ 不创建条目、不发起登录。 */
  const onGoogleOpenSubscription = () => {
    if (googleEntry) {
      setSelectedKey(googleEntry.id)
      void refreshGoogleStatus(googleEntry.id, googleGenRef.current)
    } else {
      setSelectedKey('google')
    }
  }

  /** 「登录 Google 订阅」：只有显式点击才 ensure 条目 → authorize → 打开外部浏览器 → 有界轮询。 */
  const onGoogleOAuthStart = async () => {
    if (googlePending || googleBusy) return
    setGooglePending(true)
    const gen = ++googleGenRef.current
    stopGooglePoll()
    setGoogleAuthUrl(null)
    setGoogleOpenWarn(null)
    try {
      const target = googleEntry ?? (await providerApi.ensureOfficialGoogle())
      if (gen !== googleGenRef.current) return
      setGoogleProvider(target)
      setSelectedKey(target.id)
      const { url, expiresAt } = await providerApi.oauthGoogleAuthorize(target.id)
      if (gen !== googleGenRef.current) return
      // 绝对截止前置校验：无效 / Infinity / NaN / 已过 → 拒绝，不开浏览器、不起轮询
      if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
        setGoogleStatus({ status: 'error', oauthProject: null, expiresAt: null, message: '授权链接有效期异常，请重新点击登录' })
        return
      }
      setGoogleAuthUrl({ id: target.id, url })
      const warn = await openGoogleAuthUrl(url)
      if (gen !== googleGenRef.current) return
      setGoogleOpenWarn(warn)
      if (warn) showToast(warn, 'info')
      setGoogleStatus({ status: 'pending', oauthProject: null, expiresAt, message: null })
      startGooglePoll(target.id, expiresAt)
      await refresh()
      await refreshBuiltin()
    } catch (e) {
      if (gen === googleGenRef.current) {
        const msg = e instanceof ApiError ? e.userMessage() : String(e)
        showToast(msg, 'info')
        setGoogleStatus({ status: 'error', oauthProject: null, expiresAt: null, message: msg })
      }
    } finally {
      if (gen === googleGenRef.current) setGooglePending(false)
    }
  }

  /** 手动完成：整条回调 URL 或裸 code。complete ok ≠ ready —— 必须再读 status 确认，⛔ 不假就绪。 */
  const onGoogleOAuthComplete = async (p: Provider) => {
    // 只接受 Google 条目自己的粘贴（id + 协议双校验，⛔ 不完成到 ChatGPT/Claude 端点）
    if (p.type !== 'gemini_code_assist' && p.authMode !== 'google_oauth') return
    const input = (googlePaste?.id === p.id ? googlePaste.value : '').trim()
    if (!input) {
      showToast('请先粘贴登录后浏览器地址栏里的整条 URL（或裸授权码）', 'info')
      return
    }
    if (googleBusy || googlePending) return
    setGoogleBusy(true)
    const gen = googleGenRef.current
    try {
      const res = await providerApi.oauthGoogleComplete(p.id, input)
      if (gen !== googleGenRef.current) return
      if (!res.ok) {
        setGoogleStatus({ status: 'error', oauthProject: null, expiresAt: null, message: res.message ?? '登录失败' })
        return
      }
      stopGooglePoll()
      setGooglePaste(null)
      setGoogleAuthUrl(null)
      setGoogleOpenWarn(null)
      const st = await refreshGoogleStatus(p.id, gen)
      if (gen !== googleGenRef.current) return
      if (st?.status === 'ready') {
        showToast('Google 订阅已就绪', 'success')
        await refresh()
        await refreshBuiltin()
      } else if (st && (st.status === 'pending' || st.status === 'onboarding') && Number.isFinite(st.expiresAt)) {
        startGooglePoll(p.id, st.expiresAt as number)
      }
    } catch (e) {
      if (gen === googleGenRef.current) {
        const msg = e instanceof ApiError ? e.userMessage() : String(e)
        showToast(msg, 'info')
        setGoogleStatus({ status: 'error', oauthProject: null, expiresAt: null, message: msg })
      }
    } finally {
      if (gen === googleGenRef.current) setGoogleBusy(false)
    }
  }

  /** 退出 Google 订阅：保留确认；作废在途回包 + 清状态 / 粘贴 / 轮询。 */
  const onGoogleOAuthLogout = async (p: Provider) => {
    if (googleBusy || googlePending) return
    if (!confirm(`退出 "${p.name}" 的 Google 订阅登录？`)) return
    setGoogleBusy(true)
    const gen = ++googleGenRef.current
    stopGooglePoll()
    try {
      await providerApi.oauthGoogleLogout(p.id)
      if (gen !== googleGenRef.current) return
      setGoogleStatus({ status: 'logged_out', oauthProject: null, expiresAt: null, message: null })
      setGooglePaste(null)
      setGoogleAuthUrl(null)
      setGoogleOpenWarn(null)
      showToast('已退出 Google 订阅', 'success')
      await refresh()
      await refreshBuiltin()
    } catch (e) {
      if (gen === googleGenRef.current) showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      if (gen === googleGenRef.current) setGoogleBusy(false)
    }
  }

  /** 复制授权链接：剪贴板失败必须提示，⛔ 不假成功；URL 不落日志。 */
  const copyGoogleAuthUrl = async () => {
    const url = googleAuthUrl?.url
    if (!url) return
    try {
      await navigator.clipboard.writeText(url)
      showToast('授权链接已复制', 'success')
    } catch {
      showToast('复制失败，请手动选择链接后复制', 'info')
    }
  }

  // 表单提交：apiKeyMasked 字段是 raw key
  const onSaveProvider = (p: Provider) => {
    const inferred = (p.baseUrl ?? '').toLowerCase().includes('api.anthropic.com') ? 'anthropic' : 'openai_compatible'
    const req: UpdateProviderRequest = {
      name: p.name,
      type: p.type === 'anthropic' || p.type === 'openai_response' ? p.type : inferred,
      baseUrl: p.baseUrl,
      // 编辑时若 key 字段未被修改（仍为后端掩码 sk-****）则不发送 apiKey，
      // 避免把掩码字符串当明文存库导致 API 401（后端 req.apiKey null 时不更新）
      apiKey: p.apiKeyMasked === editingProvider?.apiKeyMasked ? undefined : p.apiKeyMasked,
      enabled: p.enabled,
      // 自定义请求头：与上面的 apiKey 取舍**无关**（掩码特例只针对 key，不能波及 header）。
      // 清空契约：{} = 显式清空（后端写 NULL）、null/缺字段 = 不触碰。KvEditor 全空产出 {}，
      // 故「删光所有 header」可表达；unset（表单未初始化出该键）仍传 undefined → 不触碰。
      extraHeaders: p.extraHeaders,
    }
    void wrap(async () => {
      await updateProvider(p.id, req)
      // 改名 / 改地址都可能改变品牌归属 ⇒ 同步刷新左栏分组
      await refreshBuiltin()
      setEditingProvider(null)
    }, `已更新: ${p.name}`)
  }

  const onAddProvider = (p: Provider) => {
    // 模板模型建议只在本次从内置卡进来时有效；手动「+ 添加提供商」为空
    const suggestions = builtinPrefill?.models ?? []
    // 协议类型：显式 anthropic 用 anthropic；否则按 baseUrl 智能推断（含 api.anthropic.com → anthropic）
    const inferred = (p.baseUrl ?? '').toLowerCase().includes('api.anthropic.com') ? 'anthropic' : 'openai_compatible'
    const req: CreateProviderRequest = {
      name: p.name,
      type: p.type === 'anthropic' || p.type === 'openai_response' ? p.type : inferred,
      baseUrl: p.baseUrl,
      apiKey: p.apiKeyMasked,    // raw key
      enabled: p.enabled ?? true,
      // 自定义请求头（CreateProviderRequest.extraHeaders 不收 null，全空 → 不发该字段）
      extraHeaders: p.extraHeaders ?? undefined,
      // [V299] 归属品牌：从品牌选择器进来时已确定；手填「+ 添加提供商」为 undefined（后端按地址猜）
      brandKey: builtinPrefill?.key,
    }
    void wrap(async () => {
      const created = await createProvider(req)
      // 乐观归组：先把新连接挂到所选品牌下 —— 图标 / 分组立刻正确，不等后端返回。
      if (builtinPrefill) {
        const bk = builtinPrefill.key
        setBuiltinStatuses((prev) => prev.map((b) => (b.key === bk
          ? {
              ...b,
              configured: true,
              providerId: b.providerId ?? created.id,
              connections: [
                ...(b.connections ?? []),
                { id: created.id, name: created.name, modelCount: 0, enabled: true },
              ],
            }
          : b)))
      }
      // 再拉一次校准（后端按 brand_key / host 的最终归属）
      await refreshBuiltin()
      setAddingProvider(false)
      setBuiltinPrefill(null)
      // 建议清单交给「导入模型」弹窗（仅本次；关弹窗即清空）
      setImportSuggestions(suggestions)
      // L4b-2 首启引导信号：带 created 详情（内含 models），引导据此判断是否需补插「添加模型」步
      window.dispatchEvent(new CustomEvent('nexusai:provider-saved', { detail: created }))
      // [2026-11-03 用户裁定] **保存后不自动拉模型** —— 保存就保存，导入是另一个明确动作
      //   （用户原话：「点击导入模型 自动导入 保存就跟之前一样 不要自动触发吧」）。
      //   用户要的是「表单里有个『导入模型』按钮，想用就点」⇒ 按钮在表单的 footer 上，
      //   见 addingProvider 的 footerActions。
      //
      //   ⚠️ 但**点了「导入模型」**时是要保存 + 拉取的（拉取端点按 provider id ⇒ 得先有 id）。
      //   那条路径在 onAddProviderAndImport 里。
      if (importAfterSaveRef.current) {
        importAfterSaveRef.current = false
        setImportingModelsFor(created)
      }
    }, `已添加: ${p.name}`)
  }

  /**
   * 「导入模型」按钮：**先保存提供商 → 立刻打开导入弹窗**（拉取端点按 provider id ⇒ 必须先有 id）。
   *
   * <p>⚠️ 与「保存」按钮的行为差异：保存 = 只存；导入模型 = 存完接着拉清单。
   * 两者都走同一个 `onAddProvider`（避免两套建提供商的逻辑漂移），靠
   * {@link importAfterSaveRef} 这个「一次性意图」标记区分。
   */
  const importAfterSaveRef = useRef(false)
  const onAddProviderAndImport = (p: Provider) => {
    importAfterSaveRef.current = true
    onAddProvider(p)
  }

  // Model CRUD
  const onSaveModel = (_providerId: string, m: Model) => {
    const req: UpdateModelRequest = {
      name: m.name,
      alias: m.alias || null,
      desc: m.desc,
      type: m.type,
      maxTokens: m.maxTokens,
      maxContextTokens: m.maxContextTokens ?? null,
      temperature: m.temperature,
      topP: m.topP,
      think: m.think || null,
      // [2026-10-22] 模型级额外请求体（逐键合并进请求体；空 → null 不写）
      extraBody: m.extraBody || null,
      // ⚠️ 2026-11-14 修复断链：这几项在表单里能填，但此前请求体没带 ⇒ 永远落不了库。
      //    清空（undefined/null）按 0 提交 = 回到默认值（PATCH 语义里 null 是「不改」，
      //    不传 0 就无法把已配的值改回默认）。
      embeddingDimensions: m.embeddingDimensions ?? 0,
      embeddingBatchSize: m.embeddingBatchSize ?? 0,
      maxConcurrency: m.maxConcurrency ?? 0,
      // ⚠️ rerank 两项保持 null（PATCH null = 不改）：0 / 空串在 rerank 里不是「默认值」，
      //    写 0 会被当成真的 topN=0。填了值才落库。
      rerankTopN: m.rerankTopN ?? null,
      rerankInstruct: m.rerankInstruct ?? null,
      enabled: m.enabled,
    }
    void wrap(async () => {
      await updateModel(m.id, req)
      setEditingModel(null)
    }, `已更新模型: ${m.name}`)
  }

  const onAddModel = (providerId: string, m: Model) => {
    const providerName = list.find((x) => x.id === providerId)?.name ?? ''
    const req: CreateModelRequest = {
      name: m.name,
      alias: m.alias || null,
      // [2026-11-01] 提供商标识不再由表单提供 ⇒ 按 provider / 模型名推断品牌
      tag: tagForNewModel(providerName, m.name, m.tag),
      desc: m.desc,
      type: m.type,
      maxTokens: m.maxTokens,
      maxContextTokens: m.maxContextTokens ?? null,
      temperature: m.temperature,
      topP: m.topP,
      think: m.think || null,
      // [2026-10-22] 模型级额外请求体（逐键合并进请求体；空 → null 不写）
      extraBody: m.extraBody || null,
      // ⚠️ 2026-11-14 修复断链：表单可填但此前请求体没带（同更新路径）。
      embeddingDimensions: m.embeddingDimensions ?? null,
      embeddingBatchSize: m.embeddingBatchSize ?? null,
      maxConcurrency: m.maxConcurrency ?? null,
      rerankTopN: m.rerankTopN ?? null,
      rerankInstruct: m.rerankInstruct ?? null,
      enabled: m.enabled,
    }
    void wrap(async () => {
      await createModel(providerId, req)
      setAddingModelFor(null)
      // L4b-2 首启引导信号：模型真实创建成功后派发，引导据此从「添加模型」步进入主模型绑定
      window.dispatchEvent(new CustomEvent('nexusai:model-added'))
    }, `已添加模型: ${m.name}`)
  }

  const onRemoveModel = (_providerId: string, modelId: string) => {
    const m = list.flatMap((p) => p.models).find((x) => x.id === modelId)
    if (!confirm(`删除模型 "${m?.name}"？`)) return
    void wrap(() => deleteModel(modelId), '已删除模型')
  }

  /* ---- 全局模型档位的写入口（模型行 ⋯ 菜单） ---- */

  /**
   * 保存全局设置。
   *
   * <p>优先走注入的 {@link ProvidersPanelProps.onSaveSettings}（App 的唯一写入口，写完 appSettings 就更新）；
   * 未注入时直连 `settingsApi.update`（后端是 merge 语义：`null` = 不覆盖 ⇒ 只传目标字段不会清空别的列）。
   *
   * @returns 是否成功（失败时已 toast 原因）
   */
  const saveSettings = async (req: UpdateSettingsRequest): Promise<boolean> => {
    if (onSaveSettings) return onSaveSettings(req)
    try {
      await settingsApi.update(req)
      return true
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      return false
    }
  }

  /** 重新拉取全局主模型回填「当前」标记（写库成功后调，别拿请求体当结果）。 */
  const reloadMainModel = async () => {
    try {
      const s = await settingsApi.get()
      setMainModelName(s.mainModelName ?? null)
    } catch {
      // 读不到就保留旧标记：写库已经成功，不能因此报错
    }
  }

  /** 「设为当前」：把 `provider/model` 写成全局主模型（`settings.mainModelName`）。 */
  const onSetCurrentModel = async (p: Provider, m: Model) => {
    setOpenModelMenu(null)
    const fullName = `${p.name}/${m.name}`
    const ok = await saveSettings({ mainModelName: fullName })
    if (!ok) return
    await reloadMainModel()
    showToast(`已设为当前模型：${fullName}`, 'success')
  }

  /**
   * 「配置到所有档位」：一次 PUT 把该模型写进 {@link APPLY_ALL_ROLES} 的全部档位。
   *
   * <p><b>一次 PUT 而不是 N 次</b>：settings 的 PUT 天然支持多字段合并 ⇒ 9 档一次写完；
   * 分 9 次发请求既慢又可能半成功。字段名来自 {@link APPLY_ALL_TIER_FIELDS}（由面板 TIERS 派生），
   * ⛔ 不按角色名拼字符串（`classifier` 的列名是 `classifierModel`）。
   */
  const onApplyAllTiers = async (p: Provider, m: Model) => {
    setOpenModelMenu(null)
    const fullName = `${p.name}/${m.name}`
    const ok = confirm(
      `把 ${fullName} 写入全部 ${APPLY_ALL_ROLES.length} 个档位？\n\n` +
      '将覆盖：主模型、最强模型、快速模型、子代理、弱模型、中等模型、多模态模型、降级模型、权限分类器。\n\n' +
      'TTS 与 ASR 是专用语音模型，不在本次范围内（不会被改动）。',
    )
    if (!ok) return
    const req = Object.fromEntries(
      APPLY_ALL_TIER_FIELDS.map((field) => [field, fullName]),
    ) as UpdateSettingsRequest
    if (!(await saveSettings(req))) return
    await reloadMainModel()
    showToast(`已把 ${m.name} 配置到全部档位`, 'success')
  }

  /* ---- 视觉辅助（不改 CRUD / 登录逻辑） ---- */

  /** 模型行是否「当前」：全局主模型 = `provider/model`（⋯ 菜单「设为当前」写的就是这个值）。 */
  const isCurrentModel = (p: Provider, m: Model): boolean =>
    mainModelName != null && mainModelName === `${p.name}/${m.name}`

  /** 状态灯：官方未登录灰 / 等待黄 / 已登录绿；普通提供商按启用状态。 */
  const providerStatus = (p: Provider): { cls: string; dot: string; text: string } => {
    if (p.type === 'openai_response') {
      if (isOAuthLoggedIn(p)) {
        return { cls: 'pv-stat is-ok', dot: 'pv-dot is-ok', text: `已登录 · key ${p.apiKeyMasked} · ${p.models.length} 个模型` }
      }
      if (oauthWaitingId === p.id) return { cls: 'pv-stat is-wait', dot: 'pv-dot is-wait', text: '等待授权' }
    }
    return {
      cls: 'pv-stat',
      dot: 'pv-dot',
      text: `${p.enabled ? '已启用' : '已停用'} · key ${p.apiKeyMasked} · ${p.models.length} 个模型`,
    }
  }

  /**
   * 模型行：四列对齐（模型 / 上下文 / 输出 / 能力）+ 行尾 ⋯。
   *
   * <p>从「自由排布的两行」改成表格列：多个模型时能竖着扫上下文与输出，
   * 能力标签也不再随名称长短左右漂移。
   */
  const renderModelRow = (p: Provider, m: Model) => {
    const current = isCurrentModel(p, m)
    const caps = modelCaps(m)
    const menuOpen = openModelMenu === m.id
    return (
      <div key={m.id} className={`pv-mrow${current ? ' is-current' : ''}`}>
        <div className="pv-mid">
          <span className="pv-mtag">{(m.tag || 'AI').slice(0, 3).toUpperCase()}</span>
          <div className="pv-mid-body">
            <div className="pv-mline1">
              <span className="pv-mname">{m.name}</span>
              {current && <span className="pv-mcur">当前</span>}
            </div>
            <span className="pv-malias">
              {m.alias ? `${m.alias} · ${MODEL_TYPE_LABEL[m.type] ?? m.type}` : (MODEL_TYPE_LABEL[m.type] ?? m.type)}
            </span>
          </div>
        </div>
        <span className="pv-mnum" title="上下文窗口">{fmtTokens(m.maxContextTokens, '—')}</span>
        <span className="pv-mnum" title="最大输出">{fmtTokens(m.maxTokens, '—')}</span>
        <div className="pv-mcaps">
          {caps.map((c) => <span key={c} className="pv-cap">{c}</span>)}
        </div>
        <button
          type="button"
          className="pv-mmore"
          aria-label="更多操作"
          onClick={(e) => {
            e.stopPropagation()
            setOpenModelMenu(menuOpen ? null : m.id)
          }}
        >
          <svg viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.5" /><circle cx="12" cy="12" r="1.5" /><circle cx="19" cy="12" r="1.5" /></svg>
        </button>
        {menuOpen && (
          <div className="pv-menu" onClick={(e) => e.stopPropagation()}>
            {current ? (
              <button type="button" disabled title="该模型已是当前全局主模型">当前模型</button>
            ) : (
              <button type="button" onClick={() => void onSetCurrentModel(p, m)}>设为当前</button>
            )}
            <button type="button" onClick={() => void onApplyAllTiers(p, m)}>配置到所有档位</button>
            <button type="button" onClick={() => { setOpenModelMenu(null); setEditingModel({ providerId: p.id, model: m }) }}>编辑</button>
            <button type="button" className="is-danger" onClick={() => { setOpenModelMenu(null); onRemoveModel(p.id, m.id) }}>删除</button>
          </div>
        )}
      </div>
    )
  }

  /**
   * 模型列表：按 {@link MODEL_GROUPS} 分组渲染（组内仍是原来的 pv-mrow）。
   * 未命中分组的类型收进「其他」——宁可多一组，也不静默漏掉模型。
   */
  const renderModelGroups = (p: Provider) => {
    const known = new Set<string>(MODEL_GROUPS.flatMap((g) => g.types))
    const groups = MODEL_GROUPS
      .map((g) => ({ label: g.label, models: p.models.filter((m) => g.types.includes(m.type)) }))
      .filter((g) => g.models.length > 0)
    const others = p.models.filter((m) => !known.has(m.type))
    if (others.length > 0) groups.push({ label: '其他', models: others })
    return (
      <>
        <div className="pv-mthead" aria-hidden="true">
          <span>模型</span>
          <span>上下文</span>
          <span>输出</span>
          <span>能力</span>
          <span />
        </div>
        {groups.map((g) => (
          <div key={g.label} className="pv-mgroup">
            <div className="pv-mgroup-title">
              <span className="pv-mgroup-chip">{g.label}</span>
              <span className="pv-count">{g.models.length}</span>
            </div>
            {g.models.map((m) => renderModelRow(p, m))}
          </div>
        ))}
      </>
    )
  }

  /** 该 provider 是否走 Anthropic 协议（含 Claude 订阅 OAuth）—— 出站线路要对它开放。 */
  const isAnthropicProvider = (p: Provider): boolean =>
    p.type === 'anthropic'
    || isClaudeOAuthLoggedIn(p)
    || (p.baseUrl ?? '').toLowerCase().includes('api.anthropic.com')

  /**
   * 出站线路（HTTP 代理）。
   *
   * <p>[V298] 原先只给 ChatGPT 官方一块；2026-11-16 用户要求 Claude 也能配 ⇒ 抽成可复用块，
   * ChatGPT 与 Claude 各一份，互不覆盖。
   *
   * <p>⚠️ `idSuffix` 不能省：两个 view 同时挂在 DOM（用 data-active 切换 display），
   * 重复 id 会让 label 指到另一块的输入框。
   */
  const renderProxyBlock = (idSuffix: string, note: string) => {
    const inputId = `pv-http-proxy-${idSuffix}`
    /** 首选候选（延迟最低的那个）；探测没结果时为 null。 */
    const detectCandidate = proxyCandidates?.[0] ?? null
    return (
      <div className="pv-block">
        <div className="pv-block-head">
          <div className="pv-block-title">
            <h3>出站线路</h3>
          </div>
          <div className="pv-block-actions">
            <span className="pv-block-hint">仅境外官方</span>
          </div>
        </div>
        <div className="pv-field">
          <div className="pv-field-head">
            <label className="pv-field-label" htmlFor={inputId}>HTTP 代理</label>
            {httpProxy.trim() === proxySaved ? (
              proxySaved ? (
                <span className="pv-field-state"><span className="pv-dot is-ok" />已记住 · 下一次请求生效</span>
              ) : null
            ) : (
              <span className="pv-field-state is-dirty">改了还没记住</span>
            )}
          </div>
          {/* [代理自动检测] 提示条在输入框**上方**：只告知 + 给「填入 / 忽略」，不替用户决定 */}
          {detectCandidate && !proxyDetectHandled && (
            <div className="pv-detect">
              <svg className="pv-detect-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                   strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M20 6L9 17l-5-5" />
              </svg>
              <span className="pv-detect-body">
                检测到本机 HTTP 代理 <b>127.0.0.1:{detectCandidate.port}</b>
                <span className="pv-detect-ms">{detectCandidate.latencyMs}ms</span>，要填入吗？
              </span>
              <span className="pv-detect-acts">
                <button type="button" className="pv-detect-btn" onClick={() => onFillDetectedProxy(detectCandidate)}>填入</button>
                <button type="button" className="pv-detect-btn is-ghost" onClick={onDismissDetectedProxy}>忽略</button>
              </span>
            </div>
          )}
          <div className="pv-input-row">
            <input
              id={inputId}
              className={`pv-input${proxyError ? ' is-error' : ''}`}
              type="text"
              inputMode="url"
              spellCheck={false}
              placeholder="http://127.0.0.1:7897"
              value={httpProxy}
              onChange={(e) => {
                setHttpProxy(e.target.value)
                if (proxyError !== null) setProxyError(null)
              }}
            />
            <button
              type="button"
              className="pv-btn pv-btn-tint pv-btn-sm"
              disabled={proxyProbing}
              onClick={() => { void onProbeProxy() }}
            >{proxyProbing ? '检测中…' : '自动检测'}</button>
            {proxySaved ? (
              <button
                type="button"
                className="pv-btn pv-btn-tint pv-btn-sm"
                disabled={proxySaving}
                onClick={() => { void onClearProxy() }}
              >清空</button>
            ) : null}
            <button
              type="button"
              className="pv-btn pv-btn-primary pv-btn-sm"
              disabled={proxySaving}
              onClick={() => { void onSaveProxy() }}
            >{proxySaving ? '保存中…' : '记住'}</button>
          </div>
          {proxyError && <div className="pv-field-err">{proxyError}</div>}
          {proxyTestNote && (
            <div className={`pv-field-note${proxyTestNote.ok ? ' is-ok' : ' is-warn'}`}>{proxyTestNote.text}</div>
          )}
          <p className="pv-field-hint">{note}</p>
        </div>
      </div>
    )
  }

  /**
   * Claude 订阅块（仅当该 provider 就是「Claude 订阅」条目 / 已登录 claude_oauth 时渲染）。
   *
   * <p>三态（未登录 / 等待授权 / 已登录）+ 手动粘贴兜底：53692 被占用时后端降级，
   * 用户把浏览器地址栏整条 URL 粘回这里完成登录。
   */
  const renderClaudeOAuthBlock = (p: Provider) => {
    const isClaudeProvider = isClaudeOAuthLoggedIn(p) || (!!claudeEntry && p.id === claudeEntry.id)
    if (!isClaudeProvider) return null
    const loggedIn = isClaudeOAuthLoggedIn(p)
    const waiting = oauthWaitingId === p.id
    const busy = oauthPendingId === p.id
    return (
      <div className="pv-block">
        <div className="pv-block-head">
          <div className="pv-block-title">
            <h3>Claude 订阅</h3>
          </div>
          <div className="pv-block-actions">
            <span className="pv-block-hint">
              {loggedIn ? '已登录' : waiting ? '等待授权' : '未登录'}
            </span>
          </div>
        </div>
        <div className={`pv-connect-box${waiting ? ' is-wait' : ''}`}>
          <div className="pv-connect-main">
            <span className="pv-connect-icon">
              {loggedIn ? (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M4.5 12.5l5 5 10-10" /></svg>
              ) : waiting ? (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 1.8" /></svg>
              ) : (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="12" r="8.5" /><path d="M12 15v-4M12 8h.01" /></svg>
              )}
            </span>
            <div className="pv-connect-text">
              <div className="pv-connect-title">
                {loggedIn ? '订阅连接正常' : waiting ? '等待浏览器授权' : '还没有登录 Claude 订阅'}
              </div>
              <div className="pv-connect-desc">
                {loggedIn
                  ? '用你已有的 Claude 订阅额度调用 Anthropic 官方模型'
                  : waiting
                    ? '点完授权会自动接住，这一页不用操作'
                    : '浏览器里点完授权，这一页会自动接住'}
              </div>
            </div>
          </div>
          <div className="pv-connect-actions">
            {loggedIn ? (
              <button
                type="button"
                className="pv-btn-text"
                disabled={busy}
                onClick={() => { void onClaudeOAuthLogout(p) }}
              >退出登录</button>
            ) : (
              <>
                {oauthPaste[p.id] === undefined && (
                  <button
                    type="button"
                    className="pv-btn-text"
                    title="浏览器回调失败时，手动粘贴登录后的地址"
                    onClick={() => setOauthPaste((prev) => ({ ...prev, [p.id]: prev[p.id] ?? '' }))}
                  >登录遇到问题？</button>
                )}
                {waiting ? (
                  <button
                    type="button"
                    className="pv-btn pv-btn-tint pv-btn-sm"
                    disabled={busy}
                    onClick={() => { void onClaudeOAuthStart(p) }}
                  >重开一次</button>
                ) : (
                  <button
                    type="button"
                    className="pv-btn pv-btn-primary pv-btn-sm"
                    disabled={busy}
                    onClick={() => { void onClaudeOAuthStart(p) }}
                  >登录 Claude 订阅</button>
                )}
              </>
            )}
          </div>
        </div>

        {!loggedIn && oauthPaste[p.id] !== undefined && (
          <div className="pv-paste is-open">
            <label>登录后浏览器会跳到 localhost:53692/callback 的地址，把整条 URL 粘到这里</label>
            <textarea
              className="pv-textarea"
              rows={2}
              placeholder="http://localhost:53692/callback?code=…&state=…"
              value={oauthPaste[p.id]}
              onChange={(e) => {
                const value = e.target.value
                setOauthPaste((prev) => ({ ...prev, [p.id]: value }))
              }}
            />
            <div className="pv-actions" style={{ marginTop: 0 }}>
              <button
                type="button"
                className="pv-btn pv-btn-primary pv-btn-sm"
                disabled={busy}
                onClick={() => { void onClaudeOAuthComplete(p) }}
              >完成登录</button>
              <button
                type="button"
                className="pv-btn-text"
                onClick={() => {
                  setOauthPaste((prev) => {
                    const next = { ...prev }
                    delete next[p.id]
                    return next
                  })
                }}
              >算了</button>
            </div>
          </div>
        )}
      </div>
    )
  }

  /** 官方条目的登录态 / 等待态（与普通 provider 共用同一套 state / handler）。 */
  const officialLoggedIn = !!officialEntry && isOAuthLoggedIn(officialEntry)
  const officialWaiting = !!officialEntry && oauthWaitingId === officialEntry.id
  const officialStatus = officialLoggedIn
    ? { cls: 'pv-stat is-ok', dot: 'pv-dot is-ok', text: `已登录${officialEntry?.oauthAccountId ? ` · ${maskAccountId(officialEntry.oauthAccountId)}` : ''}` }
    : officialWaiting
      ? { cls: 'pv-stat is-wait', dot: 'pv-dot is-wait', text: '等待授权' }
      : { cls: 'pv-stat', dot: 'pv-dot', text: '未登录' }

  /** 左栏选中项兜底：选中的 provider 被删掉时回到官方条目。 */
  /**
   * 只有显式选中 'official' 才显示官方视图。
   *
   * <p>⛔ 不再回落：旧判据 `|| !regularProviders.some(id)` 会在「点未配置的内置卡」时
   * 让背后的详情落回 OpenAI 官方视图 —— 用户看到的是 OpenAI 的「出站线路」，
   * 会误以为刚点的品牌（如 DeepSeek）有 HTTP 代理（2026-11-16 用户报障）。
   */
  const showOfficial = selectedKey === 'official'

  /** 官方登录「重开一次」：已 ensure 过就直接复用授权；否则先 ensure。 */
  const reopenOfficialLogin = () => {
    if (officialEntry) void onOAuthStart(officialEntry)
    else void onOfficialOAuthStart()
  }

  return (
    <div className="pv-page">
      <header className="pv-masthead">
        <div>
          <h1>模型提供商</h1>
          <p>
            接哪家模型、用什么凭证、请求从哪条线路出去。
            {loading && ' · 加载中…'}
            {error && <span className="pv-error"> · {error}</span>}
          </p>
        </div>
        <button
          className="pv-btn pv-btn-primary"
          data-tour="add-provider"
          onClick={() => setBrandPickerOpen(true)}
          disabled={pending}
        >
          + 添加提供商
        </button>
      </header>

      <div className="pv-layout">
        <div className="pv-shell">
          <nav className="pv-rail" aria-label="提供商">
            {builtinGroups.map((g) => (
              <Fragment key={g.label}>
                <div className="pv-rail-label">{g.label}</div>
                {/* OpenAI 官方固定排在国际组首位（它走 OAuth 专属分支，不在 builtinCards 里） */}
                {(g.label === '国际' || g.label === '内置') && (
                  <button
                    type="button"
                    className="pv-rail-card"
                    aria-selected={showOfficial}
                    onClick={() => setSelectedKey('official')}
                  >
                    <span className="pv-rail-card-icon" aria-hidden="true">
                      <img src={brandIconSrc('openai')} alt="" />
                    </span>
                    <span className="pv-rail-card-body">
                      <span className="pv-rail-card-name">
                        OpenAI 订阅
                        <span className="pv-rail-card-badge">内置</span>
                        <span className="pv-rail-card-badge is-sub">订阅</span>
                      </span>
                      <span className="pv-rail-card-sub">
                        ChatGPT 订阅 · {officialLoggedIn ? '已登录' : officialWaiting ? '等待授权' : '未登录'}
                      </span>
                    </span>
                    <span className="pv-rail-card-side">
                      {officialEntry ? `${officialEntry.models.length} 个模型` : '未配置'}
                    </span>
                  </button>
                )}
                {/* Google 订阅常驻卡（独立于 Gemini API Key 品牌；点击只选中 + 读状态，不创建 / 不登录） */}
                {(g.label === '国际' || g.label === '内置') && (
                  <button
                    type="button"
                    className="pv-rail-card"
                    aria-selected={showGoogle}
                    onClick={onGoogleOpenSubscription}
                  >
                    <span className="pv-rail-card-icon" aria-hidden="true">
                      <img src={brandIconSrc('gemini')} alt="" />
                    </span>
                    <span className="pv-rail-card-body">
                      <span className="pv-rail-card-name">
                        Google 订阅
                        <span className="pv-rail-card-badge">内置</span>
                        <span className="pv-rail-card-badge is-sub">订阅</span>
                      </span>
                      <span className="pv-rail-card-sub">Google 订阅 · {googleStateText}</span>
                    </span>
                    <span className="pv-rail-card-side">
                      {googleEntry ? `${googleEntry.models.length} 个模型` : '未配置'}
                    </span>
                  </button>
                )}
                {g.items.map(renderBuiltinRows)}
              </Fragment>
            ))}
            <div className="pv-rail-label" style={{ paddingTop: 12 }}>自定义</div>
            {customProviders.map((p) => (
              <button
                key={p.id}
                type="button"
                className={`pv-rail-card${p.enabled ? '' : ' is-off'}`}
                aria-selected={selectedKey === p.id}
                onClick={() => setSelectedKey(p.id)}
              >
                <span className="pv-rail-card-icon" aria-hidden="true">
                  <img src={brandIconSrc(null)} alt="" />
                </span>
                <span className="pv-rail-card-body">
                  <span className="pv-rail-card-name">{p.name}</span>
                  <span className="pv-rail-card-sub">{railSubtitle(p)}</span>
                </span>
                <span className="pv-rail-card-side">
                  <span className={`pv-dot${p.enabled ? ' is-ok' : ''}`} />
                  {p.models.length} 个模型
                </span>
              </button>
            ))}
          </nav>

          <section className="pv-sheet" onClick={() => setOpenModelMenu(null)}>
          {/* 官方内置条目（方案 A）· ⛔ 不参与添加/编辑/删除流程 */}
          <div className="pv-view" data-active={showOfficial}>
            <div className="pv-sheet-head">
              <div className="pv-head-left">
                <div className="pv-title-row">
                  <h2 className="pv-sheet-title">OpenAI 官方</h2>
                </div>
                <p className="pv-sheet-sub">ChatGPT 订阅 · 用你已有的 Plus / Pro 额度，不再单独付 API 费用</p>
                <div className={officialStatus.cls}>
                  <span className={officialStatus.dot} />
                  <span>{officialStatus.text}</span>
                </div>
              </div>
            </div>

            <div className="pv-connect">
              <div className={`pv-connect-box${officialWaiting ? ' is-wait' : ''}`}>
                <div className="pv-connect-main">
                  <span className="pv-connect-icon">
                    {officialLoggedIn ? (
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M4.5 12.5l5 5 10-10" /></svg>
                    ) : officialWaiting ? (
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 1.8" /></svg>
                    ) : (
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="12" r="8.5" /><path d="M12 15v-4M12 8h.01" /></svg>
                    )}
                  </span>
                  <div className="pv-connect-text">
                    <div className="pv-connect-title">
                      {officialLoggedIn ? '订阅连接正常' : officialWaiting ? '等待浏览器授权' : '还没有登录 ChatGPT 订阅'}
                    </div>
                    <div className="pv-connect-desc">
                      {officialLoggedIn
                        ? '额度跟随账号迁移，换机器重新登录即可'
                        : officialWaiting
                          ? '点完授权会自动接住，这一页不用操作'
                          : '浏览器里点完授权，这一页会自动接住'}
                    </div>
                  </div>
                </div>
                <div className="pv-connect-actions">
                  {officialLoggedIn ? (
                    <button
                      type="button"
                      className="pv-btn-text"
                      disabled={officialPending || oauthPendingId === officialEntry?.id}
                      onClick={() => { if (officialEntry) void onOAuthLogout(officialEntry) }}
                    >退出登录</button>
                  ) : (
                    <>
                      {officialEntry && oauthPaste[officialEntry.id] === undefined && (
                        <button
                          type="button"
                          className="pv-btn-text"
                          title="浏览器回调失败时，手动粘贴登录后的地址"
                          onClick={() => {
                            const id = officialEntry.id
                            setOauthPaste((prev) => ({ ...prev, [id]: prev[id] ?? '' }))
                          }}
                        >登录遇到问题？</button>
                      )}
                      {officialWaiting ? (
                        <button type="button" className="pv-btn pv-btn-tint pv-btn-sm" onClick={reopenOfficialLogin}>重开一次</button>
                      ) : (
                        <button
                          type="button"
                          className="pv-btn pv-btn-primary pv-btn-sm"
                          disabled={officialPending || oauthPendingId === officialEntry?.id}
                          onClick={() => { void onOfficialOAuthStart() }}
                        >登录 ChatGPT 订阅</button>
                      )}
                    </>
                  )}
                </div>
              </div>
              {officialWaiting && <div className="pv-waitbar"><i /></div>}
            </div>

            {/* 手动粘贴兜底（自动回调失败时用）· 与普通卡片的粘贴区同一套 state/handler */}
            {officialEntry && !officialLoggedIn && oauthPaste[officialEntry.id] !== undefined && (
              <div className="pv-paste is-open">
                <label>登录后浏览器会跳到 localhost:1455/... 的地址，把整条 URL 粘到这里</label>
                <textarea
                  className="pv-textarea"
                  rows={2}
                  placeholder="http://localhost:1455/auth/callback?code=…&state=…"
                  value={oauthPaste[officialEntry.id]}
                  onChange={(e) => {
                    const value = e.target.value
                    const id = officialEntry.id
                    setOauthPaste((prev) => ({ ...prev, [id]: value }))
                  }}
                />
                <div className="pv-actions" style={{ marginTop: 0 }}>
                  <button
                    type="button"
                    className="pv-btn pv-btn-primary"
                    disabled={oauthPendingId === officialEntry.id}
                    onClick={() => { void onOAuthComplete(officialEntry) }}
                  >完成登录</button>
                  <button
                    type="button"
                    className="pv-btn-text"
                    onClick={() => {
                      setOauthPaste((prev) => {
                        const next = { ...prev }
                        delete next[officialEntry.id]
                        return next
                      })
                    }}
                  >算了</button>
                </div>
              </div>
            )}

            {/* 已登录后可直接管理模型（官方条目已从普通列表滤除，不在此处给入口就没法加模型） */}
            {officialLoggedIn && officialEntry && (
              <div className="pv-block">
                <div className="pv-block-head">
                  <div className="pv-block-title">
                    <h3>模型</h3>
                    <span className="pv-count">{officialEntry.models.length}</span>
                    <span className="pv-block-hint">跟随账号</span>
                  </div>
                  <div className="pv-block-actions">
                    <button
                      type="button"
                      className="pv-btn pv-btn-tint pv-btn-sm"
                      data-tour="add-model"
                      onClick={() => setAddingModelFor({ providerId: officialEntry.id, prefillName: 'gpt-5.1' })}
                    >
                      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>
                      添加模型
                    </button>
                  </div>
                </div>
                {officialEntry.models.length === 0 ? (
                  <div className="pv-empty">内置模型正在准备</div>
                ) : (
                  <div className="pv-list">
                    {renderModelGroups(officialEntry)}
                  </div>
                )}
              </div>
            )}

            {/* 出站线路：ChatGPT 官方与 Claude 各有一个入口，但读写的是同一份全局代理值 */}
            {renderProxyBlock('official', '给境外官方（ChatGPT / Claude）的登录和对话用；国内模型不受影响，始终直连。留空 = 直连，SOCKS 端口不行。')}

            {regularProviders.length === 0 && !loading && !error && (
              <div className="pv-empty" style={{ marginTop: 32 }}>
                暂无提供商 · 点击「+ 添加提供商」开始
              </div>
            )}
          </div>

          {/* Google 订阅（Gemini Code Assist）· 独立入口，⛔ 不冒充 Gemini API Key；不参与添加/编辑/删除 */}
          <div className="pv-view" data-active={showGoogle}>
            <div className="pv-sheet-head">
              <div className="pv-head-left">
                <div className="pv-title-row">
                  <h2 className="pv-sheet-title">Google 订阅</h2>
                </div>
                <p className="pv-sheet-sub">Gemini Code Assist · 用你已有的 Google 账号订阅额度（与 Gemini API Key 是两条独立连接）</p>
                <div className={googleStat.cls}>
                  <span className={googleStat.dot} />
                  <span>{googleStat.text}</span>
                </div>
              </div>
            </div>

            <div className="pv-connect">
              <div className={`pv-connect-box${googleWaiting ? ' is-wait' : ''}`}>
                <div className="pv-connect-main">
                  <span className="pv-connect-icon">
                    {googleReady ? (
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M4.5 12.5l5 5 10-10" /></svg>
                    ) : googleWaiting ? (
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 1.8" /></svg>
                    ) : (
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="12" r="8.5" /><path d="M12 15v-4M12 8h.01" /></svg>
                    )}
                  </span>
                  <div className="pv-connect-text">
                    <div className="pv-connect-title">
                      {googleReady ? '订阅连接正常'
                        : googleState === 'pending' ? '等待浏览器授权'
                          : googleState === 'onboarding' ? '账号初始化中'
                            : googleState === 'error' ? '登录未完成'
                              : googleState === 'loading' ? '读取登录状态…'
                                : '还没有登录 Google 订阅'}
                    </div>
                    <div className="pv-connect-desc">
                      {googleReady ? '用你已有的 Google 订阅额度调用 Gemini 模型（可用资格以账号为准）'
                        : googleState === 'pending' ? '等待浏览器完成授权，本页会自动更新；没打开可复制下方链接手动完成'
                          : googleState === 'onboarding' ? 'Google 正在为该账号开通订阅项目，等待更新；可点左栏卡片刷新状态'
                            : googleState === 'error' ? (googleStatus?.message ?? '请重新点击登录')
                              : googleState === 'loading' ? '正在向后端确认登录状态'
                                : '浏览器里点完授权，这一页会自动接住'}
                    </div>
                  </div>
                </div>
                <div className="pv-connect-actions">
                  {googleState === 'loading' ? null : googleReady ? (
                    <button
                      type="button"
                      className="pv-btn-text"
                      disabled={googleBusy || googlePending}
                      onClick={() => { if (googleEntry) void onGoogleOAuthLogout(googleEntry) }}
                    >退出登录</button>
                  ) : (
                    <>
                      {googleEntry && googlePaste === null && (
                        <button
                          type="button"
                          className="pv-btn-text"
                          title="浏览器回调失败时，手动粘贴登录后的地址"
                          onClick={() => setGooglePaste({ id: googleEntry.id, value: '' })}
                        >登录遇到问题？</button>
                      )}
                      <button
                        type="button"
                        className={`pv-btn pv-btn-sm ${googleWaiting || googleState === 'error' ? 'pv-btn-tint' : 'pv-btn-primary'}`}
                        disabled={googlePending || googleBusy}
                        onClick={() => { void onGoogleOAuthStart() }}
                      >{googleWaiting ? '重开一次' : googleState === 'error' ? '重新登录' : '登录 Google 订阅'}</button>
                    </>
                  )}
                </div>
              </div>
              {googleWaiting && <div className="pv-waitbar"><i /></div>}
            </div>

            {/* 打开浏览器失败警告（Tauri openUrl 明确异常）：与 OAuth 状态分离，保持 pending 供手动完成 */}
            {googleOpenWarn && <p className="pv-error" style={{ marginTop: 8 }}>{googleOpenWarn}</p>}

            {/* 常驻风险提示（非模态、不阻塞）：官方条款不允许第三方 CLI OAuth 直连，知情后再登录 */}
            <p className="pv-error" style={{ marginTop: 8 }}>
              风险提示：Google 官方条款不允许第三方软件通过 Gemini CLI OAuth 直连 Code Assist，账号可能被暂停或终止。请确认已了解风险后再登录。
            </p>

            {/* pending 期间恒有授权 URL 兜底：可点击 / 复制 / 手动选择（⛔ 不写「已打开浏览器」假成功） */}
            {googleEntry && googleState === 'pending' && googleAuthUrl?.id === googleEntry.id && (
              <div className="pv-paste is-open">
                <label>浏览器没有自动打开？复制这条授权链接，在浏览器里手动打开完成登录</label>
                <div className="pv-actions">
                  <a
                    className="pv-btn pv-btn-tint pv-btn-sm"
                    href={googleAuthUrl.url}
                    target="_blank"
                    rel="noopener noreferrer"
                  >打开授权页</a>
                  <button
                    type="button"
                    className="pv-btn pv-btn-tint pv-btn-sm"
                    onClick={() => { void copyGoogleAuthUrl() }}
                  >复制授权链接</button>
                </div>
                <input
                  className="pv-textarea"
                  readOnly
                  aria-label="Google 授权链接"
                  value={googleAuthUrl.url}
                  onFocus={(e) => e.currentTarget.select()}
                />
              </div>
            )}

            {googleEntry && !googleReady && googlePaste?.id === googleEntry.id && (
              <div className="pv-paste is-open">
                <label htmlFor="google-oauth-paste">登录后浏览器会跳到 127.0.0.1 的回调地址，把整条 URL 粘到这里（裸授权码也可以）</label>
                <textarea
                  id="google-oauth-paste"
                  className="pv-textarea"
                  rows={2}
                  placeholder="http://127.0.0.1:<端口>/oauth2callback?code=…&state=…"
                  autoComplete="off"
                  autoCorrect="off"
                  spellCheck={false}
                  value={googlePaste.value}
                  onChange={(e) => {
                    const value = e.target.value
                    setGooglePaste((prev) => (prev ? { ...prev, value } : prev))
                  }}
                />
                <div className="pv-actions" style={{ marginTop: 0 }}>
                  <button
                    type="button"
                    className="pv-btn pv-btn-primary pv-btn-sm"
                    disabled={googleBusy || googlePending}
                    onClick={() => { if (googleEntry) void onGoogleOAuthComplete(googleEntry) }}
                  >完成登录</button>
                  <button
                    type="button"
                    className="pv-btn-text"
                    onClick={() => setGooglePaste(null)}
                  >算了</button>
                </div>
              </div>
            )}

            {/* 就绪后管理模型：清单由后端下发（ensure 种子模板），⛔ 前端不写死模型清单 */}
            {googleReady && googleEntry && (
              <div className="pv-block">
                <div className="pv-block-head">
                  <div className="pv-block-title">
                    <h3>模型</h3>
                    <span className="pv-count">{googleEntry.models.length}</span>
                    <span className="pv-block-hint">跟随账号 · 以后端下发为准</span>
                  </div>
                  <div className="pv-block-actions">
                    <button
                      type="button"
                      className="pv-btn pv-btn-tint pv-btn-sm"
                      onClick={() => setAddingModelFor({ providerId: googleEntry.id, prefillName: '' })}
                    >
                      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>
                      添加模型
                    </button>
                  </div>
                </div>
                {googleEntry.models.length === 0 ? (
                  <div className="pv-empty">内置模型正在准备</div>
                ) : (
                  <div className="pv-list">
                    {renderModelGroups(googleEntry)}
                  </div>
                )}
              </div>
            )}
          </div>

          {regularProviders.map((p) => {
            const st = providerStatus(p)
            return (
              <div key={p.id} className="pv-view" data-active={selectedKey === p.id}>
                <div className="pv-sheet-head">
                  <div className="pv-head-left">
                    <div className="pv-title-row">
                      <h2 className="pv-sheet-title">{p.name}</h2>
                    </div>
                    <p className="pv-sheet-sub is-mono">{p.baseUrl}</p>
                    <div className={st.cls}>
                      <span className={st.dot} />
                      <span>{st.text}</span>
                    </div>
                  </div>
                  <div className="pv-head-right">
                    <button
                      type="button"
                      className={`pv-switch${p.enabled ? ' is-on' : ''}`}
                      role="switch"
                      aria-checked={p.enabled}
                      title={p.enabled ? '停用' : '启用'}
                      onClick={() => onToggle(p.id, p.enabled)}
                    />
                    <button
                      type="button"
                      className="pv-iconbtn"
                      title="编辑提供商"
                      aria-label="编辑提供商"
                      onClick={() => setEditingProvider(p)}
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M4 20h4l10.5-10.5a2.1 2.1 0 0 0-3-3L5 17v3z" /><path d="M13.5 6.5l3 3" /></svg>
                    </button>
                  </div>
                </div>

                <div className="pv-block">
                  <div className="pv-block-head">
                    <div className="pv-block-title">
                      <h3>模型</h3>
                      <span className="pv-count">{p.models.length}</span>
                      <span className="pv-block-hint">手动添加</span>
                    </div>
                    <div className="pv-block-actions">
                      {/* [2026-11-03] 「从提供商读取」：拉远端清单批量导入（比逐个手填快得多） */}
                      <button
                        type="button"
                        className="pv-btn-text"
                        title="从提供商读取可用模型并批量导入"
                        onClick={() => {
                          const target = list.find((x) => x.id === p.id)
                          if (target) setImportingModelsFor(target)
                        }}
                      >从提供商读取</button>
                      <button
                        type="button"
                        className="pv-btn pv-btn-tint pv-btn-sm"
                        data-tour="add-model"
                        onClick={() => setAddingModelFor({ providerId: p.id, prefillName: firstModelNameHint(p) })}
                      >
                        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>
                        添加模型
                      </button>
                    </div>
                  </div>
                  {p.models.length === 0 ? (
                    <div className="pv-empty">还没有模型，先从提供商读取一份清单</div>
                  ) : (
                    <div className="pv-list">
                      {renderModelGroups(p)}
                    </div>
                  )}
                </div>

                {/* [2026-11-15] ChatGPT 订阅登录（仅 OpenAI Responses · 列表卡片内，⛔ 不进添加/编辑表单） */}
                {p.type === 'openai_response' && (
                  <div className="pv-block">
                    <div className="pv-block-head">
                      <div className="pv-block-title">
                        <h3>ChatGPT 订阅</h3>
                      </div>
                      <div className="pv-block-actions">
                        <span className="pv-block-hint">
                          {isOAuthLoggedIn(p)
                            ? `已登录${p.oauthAccountId ? ` · ${maskAccountId(p.oauthAccountId)}` : ''}`
                            : oauthWaitingId === p.id ? '等待授权' : '未登录'}
                        </span>
                      </div>
                    </div>
                    <div className={`pv-connect-box${oauthWaitingId === p.id ? ' is-wait' : ''}`}>
                      <div className="pv-connect-main">
                        <span className="pv-connect-icon">
                          {isOAuthLoggedIn(p) ? (
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d="M4.5 12.5l5 5 10-10" /></svg>
                          ) : oauthWaitingId === p.id ? (
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 1.8" /></svg>
                          ) : (
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="12" r="8.5" /><path d="M12 15v-4M12 8h.01" /></svg>
                          )}
                        </span>
                        <div className="pv-connect-text">
                          <div className="pv-connect-title">
                            {isOAuthLoggedIn(p) ? '订阅连接正常' : oauthWaitingId === p.id ? '等待浏览器授权' : '还没有登录 ChatGPT 订阅'}
                          </div>
                          <div className="pv-connect-desc">
                            {isOAuthLoggedIn(p)
                              ? '额度跟随账号迁移，换机器重新登录即可'
                              : oauthWaitingId === p.id
                                ? '点完授权会自动接住，这一页不用操作'
                                : '浏览器里点完授权，这一页会自动接住'}
                          </div>
                        </div>
                      </div>
                      <div className="pv-connect-actions">
                        {isOAuthLoggedIn(p) ? (
                          <button
                            type="button"
                            className="pv-btn-text"
                            disabled={oauthPendingId === p.id}
                            onClick={() => { void onOAuthLogout(p) }}
                          >退出登录</button>
                        ) : (
                          <>
                            {oauthPaste[p.id] === undefined && (
                              <button
                                type="button"
                                className="pv-btn-text"
                                title="浏览器回调失败时，手动粘贴登录后的地址"
                                onClick={() => setOauthPaste((prev) => ({ ...prev, [p.id]: prev[p.id] ?? '' }))}
                              >登录遇到问题？</button>
                            )}
                            <button
                              type="button"
                              className="pv-btn pv-btn-primary pv-btn-sm"
                              disabled={oauthPendingId === p.id}
                              onClick={() => { void onOAuthStart(p) }}
                            >登录 ChatGPT 订阅</button>
                          </>
                        )}
                      </div>
                    </div>

                    {!isOAuthLoggedIn(p) && oauthPaste[p.id] !== undefined && (
                      <div className="pv-paste is-open">
                        <textarea
                          className="pv-textarea"
                          rows={2}
                          placeholder="登录后浏览器会跳到 localhost:1455/... 的地址，把整条 URL 粘到这里"
                          value={oauthPaste[p.id]}
                          onChange={(e) => {
                            const value = e.target.value
                            setOauthPaste((prev) => ({ ...prev, [p.id]: value }))
                          }}
                        />
                        <div className="pv-actions" style={{ marginTop: 0 }}>
                          <button
                            type="button"
                            className="pv-btn pv-btn-primary pv-btn-sm"
                            disabled={oauthPendingId === p.id}
                            onClick={() => { void onOAuthComplete(p) }}
                          >完成登录</button>
                          <button
                            type="button"
                            className="pv-btn-text"
                            onClick={() => {
                              setOauthPaste((prev) => {
                                const next = { ...prev }
                                delete next[p.id]
                                return next
                              })
                            }}
                          >算了</button>
                        </div>
                      </div>
                    )}
                  </div>
                )}

                {/* Claude 订阅登录（仅内置 Claude 条目 / 已登录 claude_oauth 的 provider） */}
                {renderClaudeOAuthBlock(p)}

                {/* 出站线路：Anthropic 协议（含 Claude 订阅）也给入口 · 与 ChatGPT 共用同一份全局代理 */}
                {isAnthropicProvider(p) && renderProxyBlock(
                  `claude-${p.id}`,
                  '与 ChatGPT 官方共用这一份出站代理（改任一处都生效）；国内模型不受影响，始终直连。留空 = 直连，SOCKS 端口不行。',
                )}

                <div className="pv-danger">
                  {/* [V299] 只有「内置品牌都认不出」的才需要手动归入（能认出的已在品牌组下） */}
                  {!p.brandKey && !brandKeyForProvider(p.id) && (
                    <button
                      type="button"
                      className="pv-btn-text"
                      onClick={() => {
                        setAdoptProviderId(p.id)
                        setBrandPickerOpen(true)
                      }}
                    >归入内置品牌…</button>
                  )}
                  <button
                    type="button"
                    className="pv-btn-text is-danger"
                    onClick={() => onRemoveProvider(p.id)}
                  >删除这个提供商</button>
                </div>
              </div>
            )
          })}
          </section>
        </div>
      </div>


      {editingProvider && (
        <FormModal<Provider>
          title="编辑提供商"
          subtitle={editingProvider.name}
          initial={editingProvider}
          sections={[
            {
              title: '基础信息',
              fields: [
                { type: 'text', name: 'name', label: '名称', placeholder: 'e.g. DeepSeek' },
                { type: 'radio-segments', name: 'type', label: '协议类型', options: PROTOCOL_OPTIONS, hint: '决定请求走哪个 API 端点 · baseUrl 含 api.anthropic.com 会自动推断为 Anthropic' },
                { type: 'text', name: 'baseUrl', label: 'Base URL', placeholder: 'https://api.example.com/v1', hint: (v) => baseUrlHint(v.type) },
                { type: 'password', name: 'apiKeyMasked', label: 'API Key', placeholder: 'sk-****abcd', hint: '完整 key，后端脱敏后存储' },
              ],
            },
            {
              title: '行为',
              fields: [
                { type: 'toggle', name: 'enabled', label: '启用', hint: '关闭后该提供商不出现在选择器中' },
              ],
            },
            headersSection(),
          ]}
          onSave={onSaveProvider}
          onCancel={() => setEditingProvider(null)}
          destructiveLabel="移除此提供商 · 不可恢复"
          onDestructive={() => { onRemoveProvider(editingProvider.id); setEditingProvider(null) }}
        />
      )}

      {/* [V299] 品牌选择：先选品牌，再填连接（同一个品牌可建多条） */}
      {brandPickerOpen && (
        <div
          className="brand-picker-backdrop"
          onClick={() => { setBrandPickerOpen(false); setAdoptProviderId(null) }}
        >
          <div className="brand-picker" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true">
            <div className="brand-picker-head">
              <div>
                <h3>{adoptProviderId ? '归入内置品牌' : '选择提供商'}</h3>
                <p>
                  {adoptProviderId
                    ? '选一个品牌，这条连接会移动到该品牌分组下，并使用它的图标。'
                    : '先选品牌；同一个品牌可以建多条连接（不同 Key / 不同账号）。'}
                </p>
              </div>
              <button
                className="pv-iconbtn"
                aria-label="关闭"
                onClick={() => { setBrandPickerOpen(false); setAdoptProviderId(null) }}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"><path d="M6 6l12 12M18 6L6 18" /></svg>
              </button>
            </div>
            <div className="brand-picker-body">
              {pickerGroups.map((g) => (
                <Fragment key={g.label}>
                  <div className="brand-picker-label">{g.label}</div>
                  <div className="brand-picker-grid">
                    {g.items.map((b) => (
                      <button
                        key={b.key}
                        type="button"
                        className="brand-picker-tile"
                        title={b.subtitle}
                        onClick={() => {
                          if (adoptProviderId) { adoptBrand(b.key); return }
                          setBrandPickerOpen(false)
                          setBuiltinPrefill(b)
                          setAddingProvider(true)
                        }}
                      >
                        <img src={brandIconSrc(b.key)} alt="" />
                        <span>{b.name}</span>
                      </button>
                    ))}
                  </div>
                </Fragment>
              ))}
              {/* 归入品牌时不提供「自定义」——那正是它要离开的分组 */}
              {!adoptProviderId && (
                <>
                  <div className="brand-picker-label">其他</div>
                  <button
                    type="button"
                    className="brand-picker-custom"
                    onClick={() => {
                      setBrandPickerOpen(false)
                      setBuiltinPrefill(null)
                      setAddingProvider(true)
                    }}
                  >
                    <img src={brandIconSrc(null)} alt="" />
                    <span className="brand-picker-custom-body">
                      <b>自定义连接</b>
                      <small>任意 OpenAI 兼容服务 · 自己填 Base URL 与 API Key</small>
                    </span>
                    <span className="brand-picker-arrow">›</span>
                  </button>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {addingProvider && (
        <FormModal<Provider>
          title="添加提供商"
          subtitle={builtinPrefill ? '内置预置了地址和协议，你只需要填自己的 Key。' : '填写后保存，立即可用'}
          initial={builtinPrefill
            ? {
                ...EMPTY_PROVIDER,
                name: builtinPrefill.name,
                type: builtinPrefill.type,
                baseUrl: builtinPrefill.baseUrl,
                // ⛔ 内置卡绝不预置任何 Key：留空由用户自己填
                apiKeyMasked: '',
              }
            : EMPTY_PROVIDER}
          sections={[
            {
              title: '基础信息',
              fields: [
                { type: 'text', name: 'name', label: '名称', placeholder: 'e.g. OpenAI' },
                { type: 'radio-segments', name: 'type', label: '协议类型', options: PROTOCOL_OPTIONS, hint: '决定请求走哪个 API 端点 · baseUrl 含 api.anthropic.com 会自动推断为 Anthropic' },
                { type: 'text', name: 'baseUrl', label: 'Base URL', placeholder: 'https://api.openai.com/v1', hint: (v) => baseUrlHint(v.type) },
                { type: 'password', name: 'apiKeyMasked', label: 'API Key', placeholder: builtinPrefill ? '粘贴你自己的 Key' : 'sk-****abcd' },
              ],
            },
            {
              title: '行为',
              fields: [
                { type: 'toggle', name: 'enabled', label: '立即启用' },
              ],
            },
            headersSection(),
          ]}
          onSave={onAddProvider}
          onCancel={() => { setAddingProvider(false); setBuiltinPrefill(null) }}
          // [2026-11-03] 「导入模型」= 保存后接着拉远端清单并批量导入。
          //   与「保存」并列放在 footer（不是 body 末尾）—— 用户要能一眼看见并点到它。
          footerActions={(values) => (
            <button
              type="button"
              className="fm-btn"
              disabled={pending}
              title="保存这个提供商，并读取它的可用模型清单供你勾选导入"
              onClick={() => onAddProviderAndImport(values)}
            >导入模型</button>
          )}
        />
      )}

      {editingModel && (
        <ModelFormModal
          initial={editingModel.model}
          providerTag={editingModel.model.tag
            || brandTagFromName(list.find((x) => x.id === editingModel.providerId)?.name ?? '')
            || undefined}
          mode="edit"
          onSave={(m) => onSaveModel(editingModel.providerId, m)}
          onCancel={() => setEditingModel(null)}
          onDelete={() => {
            onRemoveModel(editingModel.providerId, editingModel.model.id)
            setEditingModel(null)
          }}
        />
      )}

      {addingModelFor && (
        <ModelFormModal
          initial={{ ...EMPTY_MODEL, name: addingModelFor.prefillName }}
          providerTag={tagForNewModel(
            list.find((x) => x.id === addingModelFor.providerId)?.name ?? '',
            addingModelFor.prefillName,
            EMPTY_MODEL.tag,
          )}
          mode="create"
          onSave={(m) => onAddModel(addingModelFor.providerId, m)}
          onCancel={() => setAddingModelFor(null)}
        />
      )}

      {/* [2026-11-03] 拉取远端模型清单并导入（保存提供商后自动打开；失败仍可退回手动添加） */}
      {importingModelsFor && (
        <ImportModelsModal
          provider={importingModelsFor}
          suggestedModels={importSuggestions}
          createModel={createModel}
          onClose={() => { setImportingModelsFor(null); setImportSuggestions([]) }}
          showToast={showToast}
        />
      )}
    </div>
  )
}