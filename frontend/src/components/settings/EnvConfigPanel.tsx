import { useEffect, useRef, useState } from 'react'
import { PERMISSION_MODE_LABELS, PERMISSION_MODE_DESCRIPTIONS, type PermissionMode } from '@/api/types'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'
import { getMemoryConfig, updateMemoryConfig } from '@/api/memory'
import type { MemoryConfig } from '@/api/memory'
import { ApiError } from '@/api/rest'
import { useTeamStore } from '@/stores/teamStore'

/** away-summary 门控 localStorage key（后端 GET /api/v1/features 实现前前端承载） */
export const AWAY_GATES_KEY = 'nexusai-away-gates'

interface EnvConfigPanelProps {
  settings: AppSettings | null
  onSaveSettings: (req: UpdateSettingsRequest) => Promise<boolean>
  /** 打开记忆编辑器（独立弹窗 MemoryEditorModal · 设置弹窗保持打开，编辑器 z-index 更高） */
  onOpenMemoryEditor: () => void
  /** 当前活动会话 id：/memory/config 的 dreamStatus 是 per-project 数据，后端 sessionId 必填
   *  （缺值 400）。null = 无活动会话 → 记忆区块呈现显式错误态。 */
  sessionId?: string | null
}

/** WebSearch 引擎 id → 展示名（后端 websearchEngine 的取值：anysearch / duckduckgo / deepseek）。 */
const ENGINE_LABEL: Record<string, string> = {
  anysearch: 'AnySearch',
  deepseek: 'DeepSeek',
  duckduckgo: 'DuckDuckGo',
  builtin: '内置（本机）',
}

/** 压缩数值项定义（key 与后端 SettingsDto 列一一对应）。 */
interface CompactNumDef {
  key: CompactNumKey
  name: string
  /** 口径说明（默认值 / 单位 / 有效域）——全部来自后端默认，不臆造 */
  note: string
}

type CompactNumKey =
  | 'cachedMicrocompactTriggerThreshold'
  | 'cachedMicrocompactKeepRecent'
  | 'smMinTokens'
  | 'smMinTextBlockMessages'
  | 'smMaxTokens'
  | 'smMinimumMessageTokensToInit'
  | 'smMinimumTokensBetweenUpdate'
  | 'smToolCallsBetweenUpdates'
  | 'maxConsecutiveAutocompactFailures'
  | 'maxPtlRetries'
  | 'maxCompactStreamingRetries'
  | 'snipNudgeThreshold'

/** 按机制域分组的数值参数：齿轮点开时只显示本域的，避免一屏十几个输入框 */
const SM_NUMS: CompactNumDef[] = [
  { key: 'smMinTokens', name: '尾段最小 token', note: '默认 10000' },
  { key: 'smMaxTokens', name: '尾段最大 token', note: '默认 40000' },
  { key: 'smMinTextBlockMessages', name: '最小正文消息数', note: '条 · 默认 5' },
  { key: 'smMinimumMessageTokensToInit', name: '笔记初始化阈值', note: '累计 token · 默认 10000' },
  { key: 'smMinimumTokensBetweenUpdate', name: '笔记更新间隔', note: '新增 token · 默认 5000' },
  { key: 'smToolCallsBetweenUpdates', name: '笔记更新间隔', note: '工具调用次数 · 默认 3' },
]
const CMC_NUMS: CompactNumDef[] = [
  { key: 'cachedMicrocompactTriggerThreshold', name: '触发阈值', note: '活跃工具结果数 · 默认 10' },
  { key: 'cachedMicrocompactKeepRecent', name: '保留最近', note: '个工具结果 · 默认 5' },
]
const SNIP_NUMS: CompactNumDef[] = [
  { key: 'snipNudgeThreshold', name: '提示剩余上下文阈值', note: '1–100% · 默认 30' },
]
const RETRY_NUMS: CompactNumDef[] = [
  { key: 'maxConsecutiveAutocompactFailures', name: '熔断阈值', note: '连续失败几次后停手 · 默认 3' },
  { key: 'maxPtlRetries', name: 'PTL 重试上限', note: 'prompt 过长时 · 默认 3' },
  { key: 'maxCompactStreamingRetries', name: '流式重试上限', note: '默认 2' },
]

/**
 * 环境配置面板（设置页「环境配置」tab）。
 *
 * <p><b>2026-11-12 改版要点</b>（改前先读）：
 *  1. 版式对齐 CLI 页：4 个 section 卡片，行与行用 hairline 分隔，不再每行一张卡；
 *  2. 压缩参数从「页面底部 15 项大网格」改为「行尾齿轮 → 该行下方就地展开」，
 *     解决「看不出这个参数属于哪个机制」；
 *  3. 取消每行一个「保存」按钮：改动即时保存（文本/数值在失焦时提交）。
 *     ⚠️ 保存语义没变 —— 仍走 buildCompressionDto 的「整 DTO + null 不覆盖」。
 */
export function EnvConfigPanel({ settings, onSaveSettings, onOpenMemoryEditor, sessionId }: EnvConfigPanelProps) {
  // 默认权限模式抽屉（点击外部收起）
  const [permOpen, setPermOpen] = useState(false)
  const permRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!permOpen) return
    const onDoc = (e: MouseEvent) => {
      if (permRef.current && !permRef.current.contains(e.target as Node)) setPermOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [permOpen])

  const [compactDraft, setCompactDraft] = useState<string>(
    settings?.autoCompactWindow != null ? String(settings.autoCompactWindow) : '',
  )
  const [memoryDir, setMemoryDir] = useState<string>(settings?.autoMemoryDirectory ?? '')

  // /memory/config 记忆开关（auto-memory/auto-dream 统一走此端点）
  const [memCfg, setMemCfg] = useState<MemoryConfig | null>(null)
  const [memCfgLoading, setMemCfgLoading] = useState(true)
  const [memCfgError, setMemCfgError] = useState('')

  // WebSearch / WebFetch 配置草稿（经 GET/PUT /api/v1/settings 读写）
  const [wsEngine, setWsEngine] = useState<string>(settings?.websearchEngine ?? 'anysearch')
  const [wsUseSmallModel, setWsUseSmallModel] = useState<boolean>(settings?.websearchUseSmallModel ?? false)
  const [wsProxy, setWsProxy] = useState<string>(settings?.proxy ?? '')
  const [wsApiKey, setWsApiKey] = useState<string>(settings?.apiKey ?? '')
  const [wsBaseUrl, setWsBaseUrl] = useState<string>(settings?.websearchBaseUrl ?? '')
  const [wsDomainCheckUrl, setWsDomainCheckUrl] = useState<string>(settings?.websearchDomainCheckUrl ?? '')
  const [wsFetchEngine, setWsFetchEngine] = useState<string>(settings?.webfetchEngine ?? 'builtin')

  // 协调者模式
  const [coordinatorModeOn, setCoordinatorModeOn] = useState<boolean>(settings?.coordinatorModeEnabled ?? false)

  // away-summary 门控（localStorage · 两开关都开才触发 blur 摘要）
  const [gates, setGates] = useState<{ AWAY_SUMMARY?: boolean; tengu_sedge_lantern?: boolean }>(() => {
    try { return JSON.parse(localStorage.getItem(AWAY_GATES_KEY) ?? '{}') } catch { return {} }
  })

  // 齿轮展开态（按参数区 id）与联网卡展开态（同一时刻只展开一张）
  const [openParams, setOpenParams] = useState<Record<string, boolean>>({})
  const [openEngine, setOpenEngine] = useState<string | null>(null)
  const toggleParams = (id: string) => setOpenParams((prev) => ({ ...prev, [id]: !prev[id] }))

  useEffect(() => {
    setCompactDraft(settings?.autoCompactWindow != null ? String(settings.autoCompactWindow) : '')
    setMemoryDir(settings?.autoMemoryDirectory ?? '')
  }, [settings?.autoCompactWindow, settings?.autoMemoryDirectory])

  // WebSearch 草稿 ← settings（后端异步加载后回填）
  useEffect(() => {
    setWsEngine(settings?.websearchEngine ?? 'anysearch')
    setWsUseSmallModel(settings?.websearchUseSmallModel ?? false)
    setWsProxy(settings?.proxy ?? '')
    setWsApiKey(settings?.apiKey ?? '')
    setWsBaseUrl(settings?.websearchBaseUrl ?? '')
    setWsDomainCheckUrl(settings?.websearchDomainCheckUrl ?? '')
    setWsFetchEngine(settings?.webfetchEngine ?? 'builtin')
  }, [settings?.websearchEngine, settings?.websearchUseSmallModel, settings?.proxy, settings?.apiKey, settings?.websearchBaseUrl, settings?.websearchDomainCheckUrl, settings?.webfetchEngine])

  useEffect(() => {
    setCoordinatorModeOn(settings?.coordinatorModeEnabled ?? false)
  }, [settings?.coordinatorModeEnabled])

  // 挂载时读 /memory/config 开关；失败 fail loud（内联错误文案，不阻塞其他区块）
  // 无活动会话 → 不发请求（后端 sessionId 必填会 400），直接内联错误态。
  useEffect(() => {
    if (!sessionId) {
      setMemCfg(null)
      setMemCfgLoading(false)
      setMemCfgError('无活动会话：记忆配置需带当前会话（后端 sessionId 必填）')
      return
    }
    let cancelled = false
    getMemoryConfig(sessionId)
      .then((cfg) => {
        if (cancelled) return
        setMemCfg(cfg)
        setMemCfgError('')
      })
      .catch((e) => {
        if (cancelled) return
        setMemCfgError(e instanceof ApiError ? e.userMessage() : String(e))
      })
      .finally(() => {
        if (!cancelled) setMemCfgLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sessionId])

  // ---- 时间基准微压缩 gap/keep 草稿 ----
  const [mcGapDraft, setMcGapDraft] = useState<string>(
    settings?.timeBasedMcGapMinutes != null ? String(settings.timeBasedMcGapMinutes) : '',
  )
  const [mcKeepDraft, setMcKeepDraft] = useState<string>(
    settings?.timeBasedMcKeepRecent != null ? String(settings.timeBasedMcKeepRecent) : '',
  )
  useEffect(() => {
    setMcGapDraft(settings?.timeBasedMcGapMinutes != null ? String(settings.timeBasedMcGapMinutes) : '')
    setMcKeepDraft(settings?.timeBasedMcKeepRecent != null ? String(settings.timeBasedMcKeepRecent) : '')
  }, [settings?.timeBasedMcGapMinutes, settings?.timeBasedMcKeepRecent])

  /** 组装整 DTO：patch 覆盖 + 现有 settings 兜底（未配置字段置 null，后端 merge 忽略 null 不覆盖） */
  const buildCompressionDto = (patch: UpdateSettingsRequest): UpdateSettingsRequest => {
    const s: Partial<AppSettings> = settings ?? {}
    return {
      autoCompactEnabled: patch.autoCompactEnabled ?? s.autoCompactEnabled ?? null,
      reactiveCompactEnabled: patch.reactiveCompactEnabled ?? s.reactiveCompactEnabled ?? null,
      contextCollapseEnabled: patch.contextCollapseEnabled ?? s.contextCollapseEnabled ?? null,
      historySnipEnabled: patch.historySnipEnabled ?? s.historySnipEnabled ?? null,
      snipNudgeThreshold: patch.snipNudgeThreshold ?? s.snipNudgeThreshold ?? null,
      smSessionMemoryEnabled: patch.smSessionMemoryEnabled ?? s.smSessionMemoryEnabled ?? null,
      smCompactEnabled: patch.smCompactEnabled ?? s.smCompactEnabled ?? null,
      cachedMicrocompactEnabled: patch.cachedMicrocompactEnabled ?? s.cachedMicrocompactEnabled ?? null,
      timeBasedMcEnabled: patch.timeBasedMcEnabled ?? s.timeBasedMcEnabled ?? null,
      timeBasedMcGapMinutes: patch.timeBasedMcGapMinutes ?? s.timeBasedMcGapMinutes ?? null,
      timeBasedMcKeepRecent: patch.timeBasedMcKeepRecent ?? s.timeBasedMcKeepRecent ?? null,
      disableCompact: patch.disableCompact ?? s.disableCompact ?? null,
      disableAutoCompact: patch.disableAutoCompact ?? s.disableAutoCompact ?? null,
      cachedMicrocompactTriggerThreshold: patch.cachedMicrocompactTriggerThreshold ?? s.cachedMicrocompactTriggerThreshold ?? null,
      cachedMicrocompactKeepRecent: patch.cachedMicrocompactKeepRecent ?? s.cachedMicrocompactKeepRecent ?? null,
      smMinTokens: patch.smMinTokens ?? s.smMinTokens ?? null,
      smMinTextBlockMessages: patch.smMinTextBlockMessages ?? s.smMinTextBlockMessages ?? null,
      smMaxTokens: patch.smMaxTokens ?? s.smMaxTokens ?? null,
      smMinimumMessageTokensToInit: patch.smMinimumMessageTokensToInit ?? s.smMinimumMessageTokensToInit ?? null,
      smMinimumTokensBetweenUpdate: patch.smMinimumTokensBetweenUpdate ?? s.smMinimumTokensBetweenUpdate ?? null,
      smToolCallsBetweenUpdates: patch.smToolCallsBetweenUpdates ?? s.smToolCallsBetweenUpdates ?? null,
      maxConsecutiveAutocompactFailures: patch.maxConsecutiveAutocompactFailures ?? s.maxConsecutiveAutocompactFailures ?? null,
      maxPtlRetries: patch.maxPtlRetries ?? s.maxPtlRetries ?? null,
      maxCompactStreamingRetries: patch.maxCompactStreamingRetries ?? s.maxCompactStreamingRetries ?? null,
    }
  }

  const saveCompactSwitch = (key: CompactSwitchKey, on: boolean) => {
    void onSaveSettings(buildCompressionDto({ [key]: on })).catch(() => {})
  }

  // ---- 数值草稿（11 项 + snip 阈值）----
  const NUM_KEYS: CompactNumKey[] = [
    'cachedMicrocompactTriggerThreshold', 'cachedMicrocompactKeepRecent',
    'smMinTokens', 'smMinTextBlockMessages', 'smMaxTokens',
    'smMinimumMessageTokensToInit', 'smMinimumTokensBetweenUpdate', 'smToolCallsBetweenUpdates',
    'maxConsecutiveAutocompactFailures', 'maxPtlRetries', 'maxCompactStreamingRetries',
    'snipNudgeThreshold',
  ]
  const [compactNumDrafts, setCompactNumDrafts] = useState<Record<string, string>>(() => {
    const init: Record<string, string> = {}
    for (const k of NUM_KEYS) init[k] = settings?.[k] != null ? String(settings[k]) : ''
    return init
  })
  useEffect(() => {
    setCompactNumDrafts((prev) => {
      const next = { ...prev }
      for (const k of NUM_KEYS) next[k] = settings?.[k] != null ? String(settings[k]) : ''
      return next
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    settings?.cachedMicrocompactTriggerThreshold, settings?.cachedMicrocompactKeepRecent,
    settings?.smMinTokens, settings?.smMinTextBlockMessages, settings?.smMaxTokens,
    settings?.smMinimumMessageTokensToInit, settings?.smMinimumTokensBetweenUpdate, settings?.smToolCallsBetweenUpdates,
    settings?.maxConsecutiveAutocompactFailures, settings?.maxPtlRetries, settings?.maxCompactStreamingRetries,
    settings?.snipNudgeThreshold,
  ])

  // snip 阈值本地校验失败内联提示（fail loud · 越界不往返后端）
  const [snipThresholdError, setSnipThresholdError] = useState('')

  /**
   * 提交一个数值（失焦/回车时调用）。
   * 只在草稿真的变了时才发请求 —— 避免只点进输入框就白写一次。
   */
  const commitCompactNumber = (key: CompactNumKey, draft?: string) => {
    const raw = (draft ?? compactNumDrafts[key] ?? '').trim()
    const parsed = raw === '' || Number.isNaN(Number(raw)) ? null : Number(raw)
    if (parsed != null && key === 'snipNudgeThreshold' && (parsed < 1 || parsed > 100)) {
      setSnipThresholdError(`必须在 1–100 之间（当前 ${parsed}）`)
      return
    }
    setSnipThresholdError('')
    const current = settings?.[key] ?? null
    if (parsed === current) return
    void onSaveSettings(buildCompressionDto({ [key]: parsed })).catch(() => {})
  }

  // 上下文窗口阈值（空 = null 回落后端默认）
  const commitCompactWindow = () => {
    const raw = compactDraft.trim()
    const parsed = raw === '' || Number.isNaN(Number(raw)) ? null : Number(raw)
    if (parsed === (settings?.autoCompactWindow ?? null)) return
    void onSaveSettings(buildCompressionDto({ autoCompactWindow: parsed })).catch(() => {})
  }

  // ===== 以下为 render 依赖的派生值 =====
  type CompactSwitchKey =
    | 'autoCompactEnabled' | 'reactiveCompactEnabled' | 'contextCollapseEnabled' | 'historySnipEnabled'
    | 'smSessionMemoryEnabled' | 'smCompactEnabled' | 'cachedMicrocompactEnabled' | 'timeBasedMcEnabled'
    | 'disableCompact' | 'disableAutoCompact'

  const enabledCount = [
    settings?.autoCompactEnabled, settings?.reactiveCompactEnabled, settings?.contextCollapseEnabled,
    settings?.historySnipEnabled, settings?.smSessionMemoryEnabled, settings?.smCompactEnabled,
    settings?.cachedMicrocompactEnabled, settings?.timeBasedMcEnabled,
  ].filter(Boolean).length

  const dreamStatusText = memCfgLoading
    ? '加载中…'
    : memCfg?.dreamStatus === 'last_ran'
      ? `最近已整合${memCfg.lastConsolidatedAtMs ? ` · ${new Date(memCfg.lastConsolidatedAtMs).toLocaleString('zh-CN', { hour12: false })}` : ''}`
      : '从未整合'

  const toggleGate = (k: 'AWAY_SUMMARY' | 'tengu_sedge_lantern') => {
    const next = { ...gates, [k]: !gates[k] }
    setGates(next)
    localStorage.setItem(AWAY_GATES_KEY, JSON.stringify(next))
  }

  const toggleMemFlag = (on: boolean, key: 'autoMemoryEnabled' | 'autoDreamEnabled') => {
    if (!sessionId) {
      setMemCfgError('无活动会话：记忆配置需带当前会话（后端 sessionId 必填）')
      return
    }
    void updateMemoryConfig(sessionId, key === 'autoMemoryEnabled' ? { autoMemoryEnabled: on } : { autoDreamEnabled: on })
      .then((cfg) => {
        setMemCfg(cfg)
        setMemCfgError('')
      })
      .catch((e) => {
        setMemCfgError(e instanceof ApiError ? e.userMessage() : String(e))
      })
  }

  /** WebSearch/WebFetch 字段失焦提交：只在与已存值不同时写 */
  const commitWeb = (key: keyof UpdateSettingsRequest, value: string, stored: string | null | undefined) => {
    if (value === (stored ?? '')) return
    void onSaveSettings({ [key]: value }).catch(() => {})
  }

  /**
   * 凭据说明：后端只有**一个** apiKey 槽位（不分供应商），必须如实说清这份 key 现在会被谁用。
   *
   * ⚠️ 四种组合要分别说，不能用 `||` 一句话概括 ——
   *    搜索选 DeepSeek、抓取选内置时，说「搜索和抓取都用它」是假的（抓取根本不读这个 key）。
   */
  const dsSearch = wsEngine === 'deepseek'
  const dsFetch = wsFetchEngine === 'deepseek'
  const credentialHint = (() => {
    if (dsSearch && dsFetch) {
      return '填 DeepSeek 的 key —— 搜索和抓取都用它。留空则两个请求都返回 401，不回落内置 key。'
    }
    if (dsSearch) {
      return `填 DeepSeek 的 key —— 只用于搜索（抓取走${wsFetchEngine === 'builtin' ? '内置' : '其他通道'}，不读这个 key）。留空则搜索返回 401。`
    }
    if (dsFetch) {
      return `填 DeepSeek 的 key —— 只用于抓取（搜索走 ${ENGINE_LABEL[wsEngine] ?? wsEngine}，不读这个 key）。留空则抓取返回 401。`
    }
    return '当前搜索与抓取都不经过 DeepSeek，这一项留空即可；AnySearch 会用内置默认 key。'
  })()

  /**
   * Base URL 说明。后端同样只有**一个** websearchBaseUrl 槽位，被 AnySearch 搜索 /
   * DeepSeek 搜索 / DeepSeek 抓取三家共用（builtin 抓取不用）。所以当搜索与抓取分数不同服务时，
   * 必须提示「一个槽位无法同时满足两家不同的官方地址」—— 留空时各用各自默认，填了就同时生效。
   */
  const anySearchOn = wsEngine === 'anysearch'
  const baseHint = (() => {
    const dsUsed = dsSearch || dsFetch
    if (dsUsed && anySearchOn) {
      return '搜索走 AnySearch、抓取走 DeepSeek，两者官方地址不同，但这里只有一个地址槽位：留空时各用各自默认，填写后会同时作用于两者。'
    }
    if (dsUsed) {
      return '留空 = 使用 DeepSeek 官方地址 https://api.deepseek.com。'
    }
    return '留空 = 使用 AnySearch 官方地址 https://api.anysearch.com。'
  })()
  /** 是否出现「一个地址槽位要同时服务两家」的冲突（用于高亮提示） */
  const baseConflict = (dsSearch || dsFetch) && anySearchOn

  return (
    <div className="envc-panel">
      <p className="envc-page-hint">
        改动即时保存，不再逐项点「保存」。
        <span className="envc-autosave"><i />已启用 {enabledCount} 项压缩</span>
      </p>
      {/* ═══════════ 上下文压缩 ═══════════ */}
      <section className="envc-sec">
        <div className="envc-sec-head">
          <h3>上下文压缩</h3>
          <span className="envc-sec-cnt">6 个机制</span>
          <span className="envc-sec-right">已启用 {enabledCount} 项</span>
        </div>
        <p className="envc-sec-hint">
          长对话快占满窗口时按下面的机制腾出空间。每个机制只做一件事、可单独关闭；
          它自己的数值参数收在行尾齿轮里，点开就在原地出现。
        </p>

        <div className="envc-list">
          {/* 自动压缩（含上下文窗口阈值 + 4 个重试/熔断参数） */}
          <div className="envc-row is-wrap">
            <div className="envc-label-group">
              <span className="envc-name">自动压缩 <span className="envc-tag mono">AutoCompact</span></span>
              <span className="envc-desc">达到阈值时用小模型把老对话总结成摘要替换原文。</span>
            </div>
            <div className="envc-control">
              <input
                className="settings-input"
                type="number"
                style={{ width: 104, textAlign: 'right' }}
                value={compactDraft}
                placeholder="留空不限制"
                title="上下文阈值（留空 = 只按模型窗口，只缩不扩）"
                onChange={(e) => setCompactDraft(e.target.value)}
                onBlur={commitCompactWindow}
                onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
              />
              <GearButton id="auto" open={!!openParams.auto} onClick={toggleParams} count={4} />
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!settings?.autoCompactEnabled}
                  onChange={(e) => saveCompactSwitch('autoCompactEnabled', e.target.checked)}
                />
                <span></span>
              </label>
            </div>
            <div id="envc-params-auto" className={`envc-params ${openParams.auto ? 'is-open' : ''}`}>
              <label className="envc-field">
                <span className="envc-field-label">上下文阈值<span className="envc-field-note">留空 = 只按模型窗口</span></span>
                <input
                  className="settings-input"
                  type="number"
                  value={compactDraft}
                  placeholder="留空不限制"
                  onChange={(e) => setCompactDraft(e.target.value)}
                  onBlur={commitCompactWindow}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                />
              </label>
              {RETRY_NUMS.map((n) => (
                <CompactNumField
                  key={n.key}
                  def={n}
                  value={compactNumDrafts[n.key] ?? ''}
                  onChange={(v) => setCompactNumDrafts((prev) => ({ ...prev, [n.key]: v }))}
                  onCommit={() => commitCompactNumber(n.key)}
                  error={n.key === 'snipNudgeThreshold' ? snipThresholdError : ''}
                />
              ))}
            </div>
          </div>

          {/* 禁用主动压缩（保留：原面板有该开关，语义独立于总闸） */}
          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">禁用主动压缩 <span className="envc-tag mono">DISABLE_AUTO_COMPACT</span></span>
              <span className="envc-desc">达到阈值也不自动压缩；其余机制不受影响。</span>
            </div>
            <div className="envc-control">
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!settings?.disableAutoCompact}
                  onChange={(e) => saveCompactSwitch('disableAutoCompact', e.target.checked)}
                />
                <span></span>
              </label>
            </div>
          </div>

          <div className="envc-grp">会话记忆</div>

          <div className="envc-row is-wrap">
            <div className="envc-label-group">
              <span className="envc-name">会话记忆服务 <span className="envc-tag mono">session-memory</span></span>
              <span className="envc-desc">每轮把对话提炼成分节笔记；压缩时用笔记 + 最近真实消息，比全量总结更细。</span>
            </div>
            <div className="envc-control">
              <GearButton id="sm" open={!!openParams.sm} onClick={toggleParams} count={6} />
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!settings?.smSessionMemoryEnabled}
                  onChange={(e) => saveCompactSwitch('smSessionMemoryEnabled', e.target.checked)}
                />
                <span></span>
              </label>
            </div>
            <div id="envc-params-sm" className={`envc-params ${openParams.sm ? 'is-open' : ''}`}>
              {SM_NUMS.map((n) => (
                <CompactNumField
                  key={n.key}
                  def={n}
                  value={compactNumDrafts[n.key] ?? ''}
                  onChange={(v) => setCompactNumDrafts((prev) => ({ ...prev, [n.key]: v }))}
                  onCommit={() => commitCompactNumber(n.key)}
                />
              ))}
            </div>
          </div>

          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">会话记忆压缩 <span className="envc-tag mono">SM compact</span></span>
              <span className="envc-desc">用笔记辅助压缩，不额外调用大模型摘要。共用上面的笔记参数。</span>
            </div>
            <div className="envc-control">
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!settings?.smCompactEnabled}
                  onChange={(e) => saveCompactSwitch('smCompactEnabled', e.target.checked)}
                />
                <span></span>
              </label>
            </div>
          </div>

          <div className="envc-grp">工具结果</div>

          <div className="envc-row is-wrap">
            <div className="envc-label-group">
              <span className="envc-name">缓存微压缩 <span className="envc-tag mono">cached-MC</span></span>
              <span className="envc-desc">把旧工具输出清成占位文本，或让服务端缓存删掉旧结果。</span>
            </div>
            <div className="envc-control">
              <GearButton id="cmc" open={!!openParams.cmc} onClick={toggleParams} count={2} />
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!settings?.cachedMicrocompactEnabled}
                  onChange={(e) => saveCompactSwitch('cachedMicrocompactEnabled', e.target.checked)}
                />
                <span></span>
              </label>
            </div>
            <div id="envc-params-cmc" className={`envc-params ${openParams.cmc ? 'is-open' : ''}`}>
              {CMC_NUMS.map((n) => (
                <CompactNumField
                  key={n.key}
                  def={n}
                  value={compactNumDrafts[n.key] ?? ''}
                  onChange={(v) => setCompactNumDrafts((prev) => ({ ...prev, [n.key]: v }))}
                  onCommit={() => commitCompactNumber(n.key)}
                />
              ))}
            </div>
          </div>

          <div className={`envc-row is-wrap ${settings?.timeBasedMcEnabled ? '' : 'disabled'}`}>
            <div className="envc-label-group">
              <span className="envc-name">时间基准微压缩 <span className="envc-tag mono">time-based-MC</span></span>
              <span className="envc-desc">按时间间隔微压缩历史工具结果。开启后才可填间隔与保留条数。</span>
            </div>
            <div className="envc-control">
              <GearButton id="tmc" open={!!openParams.tmc} onClick={toggleParams} count={2} />
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!settings?.timeBasedMcEnabled}
                  onChange={(e) => saveCompactSwitch('timeBasedMcEnabled', e.target.checked)}
                />
                <span></span>
              </label>
            </div>
            <div id="envc-params-tmc" className={`envc-params ${openParams.tmc ? 'is-open' : ''}`}>
              <label className="envc-field">
                <span className="envc-field-label">间隔<span className="envc-field-note">分钟 · 默认 60</span></span>
                <input
                  className="settings-input"
                  type="number"
                  value={mcGapDraft}
                  disabled={!settings?.timeBasedMcEnabled}
                  placeholder="默认 60 · 留空即用"
                  onChange={(e) => setMcGapDraft(e.target.value)}
                  onBlur={() => {
                    const raw = mcGapDraft.trim()
                    const parsed = raw === '' || Number.isNaN(Number(raw)) ? null : Number(raw)
                    if (parsed === (settings?.timeBasedMcGapMinutes ?? null)) return
                    void onSaveSettings(buildCompressionDto({ timeBasedMcGapMinutes: parsed })).catch(() => {})
                  }}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                />
              </label>
              <label className="envc-field">
                <span className="envc-field-label">保留最近<span className="envc-field-note">条 · 默认 5</span></span>
                <input
                  className="settings-input"
                  type="number"
                  value={mcKeepDraft}
                  disabled={!settings?.timeBasedMcEnabled}
                  placeholder="默认 5 · 留空即用"
                  onChange={(e) => setMcKeepDraft(e.target.value)}
                  onBlur={() => {
                    const raw = mcKeepDraft.trim()
                    const parsed = raw === '' || Number.isNaN(Number(raw)) ? null : Number(raw)
                    if (parsed === (settings?.timeBasedMcKeepRecent ?? null)) return
                    void onSaveSettings(buildCompressionDto({ timeBasedMcKeepRecent: parsed })).catch(() => {})
                  }}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                />
              </label>
            </div>
          </div>

          <div className="envc-grp">兜底与裁剪</div>

          <div className="envc-row is-wrap">
            <div className="envc-label-group">
              <span className="envc-name">历史裁剪 <span className="envc-tag mono">Snip</span></span>
              <span className="envc-desc">模型可主动把某段历史标记剪掉，压缩器按标记剔除。</span>
            </div>
            <div className="envc-control">
              <GearButton id="snip" open={!!openParams.snip} onClick={toggleParams} count={1} />
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!settings?.historySnipEnabled}
                  onChange={(e) => saveCompactSwitch('historySnipEnabled', e.target.checked)}
                />
                <span></span>
              </label>
            </div>
            <div id="envc-params-snip" className={`envc-params ${openParams.snip ? 'is-open' : ''}`}>
              {SNIP_NUMS.map((n) => (
                <CompactNumField
                  key={n.key}
                  def={n}
                  value={compactNumDrafts[n.key] ?? ''}
                  onChange={(v) => setCompactNumDrafts((prev) => ({ ...prev, [n.key]: v }))}
                  onCommit={() => commitCompactNumber(n.key)}
                  error={snipThresholdError}
                  min={1}
                  max={100}
                />
              ))}
            </div>
          </div>

          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">应急压缩 <span className="envc-tag mono">Reactive</span></span>
              <span className="envc-desc">请求被拒（prompt 太长 / 图片超尺寸）时当场压缩后重发，不直接报错。</span>
            </div>
            <div className="envc-control">
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!settings?.reactiveCompactEnabled}
                  onChange={(e) => saveCompactSwitch('reactiveCompactEnabled', e.target.checked)}
                />
                <span></span>
              </label>
            </div>
          </div>

          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">
                上下文折叠 <span className="envc-tag mono">ContextCollapse</span> <span className="envc-tag warn">互斥</span>
              </span>
              <span className="envc-desc">细粒度折叠；开启后主动压缩会被抑制。与自动压缩互斥，别同时开。</span>
            </div>
            <div className="envc-control">
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!settings?.contextCollapseEnabled}
                  onChange={(e) => saveCompactSwitch('contextCollapseEnabled', e.target.checked)}
                />
                <span></span>
              </label>
            </div>
          </div>

          <div className="envc-grp">总闸</div>

          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">禁用一切压缩 <span className="envc-tag mono">DISABLE_COMPACT</span></span>
              <span className="envc-desc" style={{ color: 'var(--error)' }}>关闭后所有压缩机制都不执行，超长对话会直接报错。</span>
            </div>
            <div className="envc-control">
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!settings?.disableCompact}
                  onChange={(e) => saveCompactSwitch('disableCompact', e.target.checked)}
                />
                <span></span>
              </label>
            </div>
          </div>
        </div>
      </section>

      {/* ═══════════ 记忆 ═══════════ */}
      <section className="envc-sec">
        <div className="envc-sec-head">
          <h3>记忆</h3>
          <span className="envc-sec-right">{dreamStatusText}</span>
        </div>
        <p className="envc-sec-hint">跨会话保留的行为偏好与项目事实。自动记忆关闭时不会注入上下文。</p>

        <div className="envc-list">
          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">全局记忆文件</span>
              <span className="envc-desc">查看与编辑用于指导 AI 的长期行为与偏好。</span>
            </div>
            <div className="envc-control">
              <button type="button" className="fm-btn primary" onClick={onOpenMemoryEditor}>打开记忆编辑器</button>
            </div>
          </div>

          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">自动记忆</span>
              <span className="envc-desc">开启后把记忆文件自动注入上下文。默认关闭。</span>
            </div>
            <div className="envc-control">
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={memCfg?.autoMemoryEnabled ?? false}
                  onChange={(e) => toggleMemFlag(e.target.checked, 'autoMemoryEnabled')}
                />
                <span></span>
              </label>
            </div>
          </div>

          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">自动精简记忆 <span className="envc-tag mono">auto-dream</span></span>
              <span className="envc-desc">定期整合、去重、精简记忆。默认关闭。</span>
            </div>
            <div className="envc-control">
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={memCfg?.autoDreamEnabled ?? false}
                  onChange={(e) => toggleMemFlag(e.target.checked, 'autoDreamEnabled')}
                />
                <span></span>
              </label>
            </div>
          </div>

          {/* 记忆目录：开关关时禁用（联动 memCfg.autoMemoryEnabled） */}
          <div className={`envc-row ${memCfg?.autoMemoryEnabled ? '' : 'disabled'}`}>
            <div className="envc-label-group">
              <span className="envc-name">记忆目录</span>
              <span className="envc-desc">留空用默认 <code>~/.claude/projects</code>。仅自动记忆开启时可改。</span>
            </div>
            <div className="envc-control">
              <input
                className="settings-input"
                type="text"
                style={{ width: 290 }}
                value={memoryDir}
                disabled={!memCfg?.autoMemoryEnabled}
                placeholder="留空用默认"
                onChange={(e) => setMemoryDir(e.target.value)}
                onBlur={() => {
                  if (memoryDir === (settings?.autoMemoryDirectory ?? '')) return
                  void onSaveSettings({ autoMemoryDirectory: memoryDir }).catch(() => {})
                }}
                onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
              />
            </div>
          </div>

          {/* /memory/config 读写失败内联提示（fail loud） */}
          {memCfgError && (
            <div className="envc-row">
              <span className="envc-desc" style={{ color: 'var(--error)' }}>记忆配置读取/保存失败：{memCfgError}</span>
            </div>
          )}
        </div>
      </section>

      {/* ═══════════ 联网能力 ═══════════ */}
      <section className="envc-sec">
        <div className="envc-sec-head">
          <h3>联网能力</h3>
          <span className="envc-sec-cnt">WebSearch · WebFetch</span>
        </div>
        <p className="envc-sec-hint">
          搜索和抓取各选一家：点卡片尾部的「设为搜索 / 设为抓取」。
          凭据只有一份，由当前选中的供应商共用（见下方「凭据」）。
        </p>

        {/* 当前生效：不用去卡片尾部逐个找 */}
        <div className="envc-cur">
          <span className="envc-cur-item">
            <span className="envc-cur-k">
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" aria-hidden="true">
                <circle cx="7" cy="7" r="4.4" /><path d="M10.3 10.3L14 14" />
              </svg>
              当前搜索
            </span>
            <span className="envc-cur-v">{ENGINE_LABEL[wsEngine] ?? wsEngine}</span>
          </span>
          <span className="envc-cur-sep" aria-hidden="true" />
          <span className="envc-cur-item">
            <span className="envc-cur-k">
              <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M6.2 9.8l3.6-3.6M4.5 11.5l-1 1a2.1 2.1 0 0 1-3-3l2-2M11.5 4.5l1-1a2.1 2.1 0 0 1 3 3l-2 2" />
              </svg>
              当前抓取
            </span>
            <span className="envc-cur-v">{ENGINE_LABEL[wsFetchEngine] ?? wsFetchEngine}</span>
          </span>
        </div>

        <div className="envc-eng-list">
          {/* 内置抓取：不需要凭据，与 CLI 页「内置子代理」同构 */}
          <div className={`envc-eng${openEngine === 'builtin' ? ' is-open' : ''}`}>
            <div
              className="envc-eng-hit"
              role="button"
              tabIndex={0}
              aria-expanded={openEngine === 'builtin'}
              onClick={() => setOpenEngine(openEngine === 'builtin' ? null : 'builtin')}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  setOpenEngine(openEngine === 'builtin' ? null : 'builtin')
                }
              }}
            >
              <span className="envc-eng-logo bld" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round">
                  <rect x="3.5" y="7" width="17" height="12" rx="3" />
                  <path d="M12 3.2v3.8M8.5 12.5v2M15.5 12.5v2" />
                </svg>
              </span>
              <span className="envc-eng-body">
                <span className="envc-eng-name">
                  内置抓取 <span className="envc-eng-sep">·</span>
                  <span className="envc-eng-tagline">NexusAI 本机直连</span>
                </span>
                <span className="envc-eng-meta">不需要 key · 不经过第三方 · 受本机网络与代理设置影响</span>
              </span>
              <span className="envc-radio" aria-hidden="true" />
            </div>
            <div className="envc-eng-tail">
              <button
                type="button"
                className={`envc-usebtn${wsFetchEngine !== 'deepseek' ? ' is-on' : ''}`}
                onClick={() => {
                  if (wsFetchEngine === 'builtin') return
                  setWsFetchEngine('builtin')
                  void onSaveSettings({ webfetchEngine: 'builtin' }).catch(() => {})
                }}
              >
                {wsFetchEngine !== 'deepseek' ? '抓取中' : '设为抓取'}
              </button>
            </div>
            <div className="envc-eng-cfg">
              <div className="envc-note">
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" aria-hidden="true">
                  <circle cx="8" cy="8" r="6" /><path d="M8 7.2v4M8 4.9v.1" />
                </svg>
                <span>本机直接请求目标网页，不需要 API key。走下方「HTTP 代理」（若已配置）。</span>
              </div>
            </div>
          </div>

          {/* AnySearch：只提供搜索（后端 websearchEngine 才认它） */}
          <div className={`envc-eng${openEngine === 'anysearch' ? ' is-open' : ''}`}>
            <div
              className="envc-eng-hit"
              role="button"
              tabIndex={0}
              aria-expanded={openEngine === 'anysearch'}
              onClick={() => setOpenEngine(openEngine === 'anysearch' ? null : 'anysearch')}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  setOpenEngine(openEngine === 'anysearch' ? null : 'anysearch')
                }
              }}
            >
              <span className="envc-eng-logo any" aria-hidden="true">ANY</span>
              <span className="envc-eng-body">
                <span className="envc-eng-name">
                  AnySearch <span className="envc-eng-sep">·</span>
                  <span className="envc-eng-tagline">聚合搜索，内置默认 key</span>
                </span>
                <span className="envc-eng-meta">免配置可用 · 支持代理与域预检 · 只提供搜索</span>
              </span>
              <span className="envc-radio" aria-hidden="true" />
            </div>
            <div className="envc-eng-tail">
              <button
                type="button"
                className={`envc-usebtn${wsEngine === 'anysearch' ? ' is-on' : ''}`}
                onClick={() => {
                  if (wsEngine === 'anysearch') return
                  setWsEngine('anysearch')
                  void onSaveSettings({ websearchEngine: 'anysearch' }).catch(() => {})
                }}
              >
                {wsEngine === 'anysearch' ? '搜索中' : '设为搜索'}
              </button>
            </div>
            <div className="envc-eng-cfg">
              <ProviderField label="API key" note="留空用内置默认" shared={credentialHint}>
                <input
                  className="settings-input"
                  type="password"
                  value={wsApiKey}
                  placeholder="留空使用内置默认 key"
                  onChange={(e) => setWsApiKey(e.target.value)}
                  onBlur={() => commitWeb('apiKey', wsApiKey, settings?.apiKey)}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                />
              </ProviderField>
              <ProviderField label="Base URL" note="留空 = 官方地址">
                <input
                  className="settings-input"
                  type="text"
                  value={wsBaseUrl}
                  placeholder="https://api.anysearch.com"
                  onChange={(e) => setWsBaseUrl(e.target.value)}
                  onBlur={() => commitWeb('websearchBaseUrl', wsBaseUrl, settings?.websearchBaseUrl)}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                />
              </ProviderField>
              <ProviderField label="HTTP 代理" note="留空直连">
                <input
                  className="settings-input"
                  type="text"
                  value={wsProxy}
                  placeholder="http://127.0.0.1:7890"
                  onChange={(e) => setWsProxy(e.target.value)}
                  onBlur={() => commitWeb('proxy', wsProxy, settings?.proxy)}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                />
              </ProviderField>
              <ProviderField label="域预检端点" note="留空跳过预检">
                <input
                  className="settings-input"
                  type="text"
                  value={wsDomainCheckUrl}
                  placeholder="留空跳过"
                  onChange={(e) => setWsDomainCheckUrl(e.target.value)}
                  onBlur={() => commitWeb('websearchDomainCheckUrl', wsDomainCheckUrl, settings?.websearchDomainCheckUrl)}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                />
              </ProviderField>
              {baseConflict && (
                <div className="envc-note warn">
                  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" aria-hidden="true">
                    <path d="M8 2.2L14.2 13H1.8L8 2.2Z" /><path d="M8 6.4v3.2M8 11.4v.1" />
                  </svg>
                  <span>{baseHint}</span>
                </div>
              )}
            </div>
          </div>

          {/* DeepSeek：搜索与抓取都支持 */}
          <div className={`envc-eng${openEngine === 'deepseek' ? ' is-open' : ''}`}>
            <div
              className="envc-eng-hit"
              role="button"
              tabIndex={0}
              aria-expanded={openEngine === 'deepseek'}
              onClick={() => setOpenEngine(openEngine === 'deepseek' ? null : 'deepseek')}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  setOpenEngine(openEngine === 'deepseek' ? null : 'deepseek')
                }
              }}
            >
              <span className="envc-eng-logo ds" aria-hidden="true">DS</span>
              <span className="envc-eng-body">
                <span className="envc-eng-name">
                  DeepSeek <span className="envc-eng-sep">·</span>
                  <span className="envc-eng-tagline">搜索与抓取都支持</span>
                </span>
                <span className="envc-eng-meta">用你自己的额度 · 不回落内置 key · 未填 key 时请求返回 401</span>
              </span>
              <span className="envc-radio" aria-hidden="true" />
            </div>
            <div className="envc-eng-tail">
              <button
                type="button"
                className={`envc-usebtn${wsEngine === 'deepseek' ? ' is-on' : ''}`}
                onClick={() => {
                  if (wsEngine === 'deepseek') return
                  setWsEngine('deepseek')
                  void onSaveSettings({ websearchEngine: 'deepseek' }).catch(() => {})
                }}
              >
                {wsEngine === 'deepseek' ? '搜索中' : '设为搜索'}
              </button>
              <button
                type="button"
                className={`envc-usebtn${wsFetchEngine === 'deepseek' ? ' is-on' : ''}`}
                onClick={() => {
                  if (wsFetchEngine === 'deepseek') return
                  setWsFetchEngine('deepseek')
                  void onSaveSettings({ webfetchEngine: 'deepseek' }).catch(() => {})
                }}
              >
                {wsFetchEngine === 'deepseek' ? '抓取中' : '设为抓取'}
              </button>
            </div>
            <div className="envc-eng-cfg">
              <ProviderField label="API key" note="必填 · 不回落内置 key" shared={credentialHint}>
                <input
                  className="settings-input"
                  type="password"
                  value={wsApiKey}
                  placeholder="粘贴 DeepSeek API key"
                  onChange={(e) => setWsApiKey(e.target.value)}
                  onBlur={() => commitWeb('apiKey', wsApiKey, settings?.apiKey)}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                />
              </ProviderField>
              <ProviderField label="Base URL" note="留空 = 官方地址">
                <input
                  className="settings-input"
                  type="text"
                  value={wsBaseUrl}
                  placeholder="https://api.deepseek.com"
                  onChange={(e) => setWsBaseUrl(e.target.value)}
                  onBlur={() => commitWeb('websearchBaseUrl', wsBaseUrl, settings?.websearchBaseUrl)}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                />
              </ProviderField>
              <ProviderField label="HTTP 代理" note="留空直连">
                <input
                  className="settings-input"
                  type="text"
                  value={wsProxy}
                  placeholder="http://127.0.0.1:7890"
                  onChange={(e) => setWsProxy(e.target.value)}
                  onBlur={() => commitWeb('proxy', wsProxy, settings?.proxy)}
                  onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                />
              </ProviderField>
              {(dsSearch || dsFetch) && wsApiKey.trim() === '' && (
                <div className="envc-note warn">
                  <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" aria-hidden="true">
                    <path d="M8 2.2L14.2 13H1.8L8 2.2Z" /><path d="M8 6.4v3.2M8 11.4v.1" />
                  </svg>
                  <span>
                    {dsSearch && dsFetch ? '搜索和抓取都选了它，' : dsSearch ? '搜索选了它，' : '抓取选了它，'}
                    但 API key 为空 ⇒ 请求会返回 401。填上 key，或把它换掉。
                  </span>
                </div>
              )}
            </div>
          </div>

          {/* DuckDuckGo：只提供搜索，无需 key */}
          <div className={`envc-eng${openEngine === 'duckduckgo' ? ' is-open' : ''}`}>
            <div
              className="envc-eng-hit"
              role="button"
              tabIndex={0}
              aria-expanded={openEngine === 'duckduckgo'}
              onClick={() => setOpenEngine(openEngine === 'duckduckgo' ? null : 'duckduckgo')}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  setOpenEngine(openEngine === 'duckduckgo' ? null : 'duckduckgo')
                }
              }}
            >
              <span className="envc-eng-logo ddg" aria-hidden="true">DDG</span>
              <span className="envc-eng-body">
                <span className="envc-eng-name">
                  DuckDuckGo <span className="envc-eng-sep">·</span>
                  <span className="envc-eng-tagline">免 key，抓 HTML 结果页</span>
                </span>
                <span className="envc-eng-meta">不需要 key · 复用已配置的代理 · 可用性受目标站点限制 · 只提供搜索</span>
              </span>
              <span className="envc-radio" aria-hidden="true" />
            </div>
            <div className="envc-eng-tail">
              <button
                type="button"
                className={`envc-usebtn${wsEngine === 'duckduckgo' ? ' is-on' : ''}`}
                onClick={() => {
                  if (wsEngine === 'duckduckgo') return
                  setWsEngine('duckduckgo')
                  void onSaveSettings({ websearchEngine: 'duckduckgo' }).catch(() => {})
                }}
              >
                {wsEngine === 'duckduckgo' ? '搜索中' : '设为搜索'}
              </button>
            </div>
            <div className="envc-eng-cfg">
              <div className="envc-note">
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" aria-hidden="true">
                  <circle cx="8" cy="8" r="6" /><path d="M8 7.2v4M8 4.9v.1" />
                </svg>
                <span>不需要 API key，也没有额外参数。若结果为空或被限流，换 AnySearch 更稳。</span>
              </div>
            </div>
          </div>
        </div>
        <div className="envc-grp">通用选项</div>
        <div className="envc-list">
          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">小模型总结</span>
              <span className="envc-desc">开启后用弱模型对搜索结果先做一轮总结，省主模型 token。默认关闭。</span>
            </div>
            <div className="envc-control">
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={wsUseSmallModel}
                  onChange={(e) => {
                    setWsUseSmallModel(e.target.checked)
                    void onSaveSettings({ websearchUseSmallModel: e.target.checked }).catch(() => {})
                  }}
                />
                <span></span>
              </label>
            </div>
          </div>
        </div>

      </section>

      {/* ═══════════ 权限与协作 ═══════════ */}
      <section className="envc-sec">
        <div className="envc-sec-head">
          <h3>权限与协作</h3>
          <span className="envc-sec-right">会话内可单独覆盖</span>
        </div>
        <p className="envc-sec-hint">这里的取值是全局默认，会话里改了就以会话为准。</p>

        <div className="envc-list">
          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">默认权限模式</span>
              <span className="envc-desc">新建会话时套用。会话里可在输入框旁临时切换。</span>
            </div>
            <div className="envc-control">
              <div className="perm-mode-pill-wrap" ref={permRef}>
                <div className="perm-mode-pill" onClick={() => setPermOpen((v) => !v)} title="默认权限模式（全局）">
                  <svg className="pm-icon" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="1.5">
                    <path d="M7 1.5L12 3.5V6.5C12 9.5 9.8 11.8 7 12.5C4.2 11.8 2 9.5 2 6.5V3.5L7 1.5Z" />
                  </svg>
                  <span className="pm-label">权限模式:</span>
                  <span className="pm-value">{PERMISSION_MODE_LABELS[settings?.permissionMode ?? 'default']}</span>
                  <svg className={`pm-chevron ${permOpen ? 'open' : ''}`} viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="2.2" style={{ width: 12, height: 12 }}>
                    <path d="M2 4L6 8L10 4" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                </div>
                {permOpen && (
                  <div className="pill-drawer perm-mode-drawer">
                    {(Object.keys(PERMISSION_MODE_LABELS) as PermissionMode[]).map((m) => (
                      <div
                        key={m}
                        className={`pill-row pill-option ${(settings?.permissionMode ?? 'default') === m ? 'active' : ''}`}
                        title={PERMISSION_MODE_DESCRIPTIONS[m]}
                        onClick={() => { setPermOpen(false); void onSaveSettings({ permissionMode: m }).catch(() => {}) }}
                      >
                        <span className="pill-label">{PERMISSION_MODE_LABELS[m]}</span>
                        <span className="pill-desc">{PERMISSION_MODE_DESCRIPTIONS[m]}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>

          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">协调者模式 <span className="envc-tag mono">coordinator</span></span>
              <span className="envc-desc">开启后主模型只负责拆解与派发，具体执行交给子代理。默认关闭。</span>
            </div>
            <div className="envc-control">
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={coordinatorModeOn}
                  onChange={(e) => {
                    setCoordinatorModeOn(e.target.checked)
                    void onSaveSettings({ coordinatorModeEnabled: e.target.checked })
                  }}
                />
                <span></span>
              </label>
            </div>
          </div>

          <div className="envc-row">
            <div className="envc-label-group">
              <span className="envc-name">Team 协作 <span className="envc-tag mono">swarms</span></span>
              <span className="envc-desc">开启后右侧任务页出现 Team 面板（建团、成员、消息流）。默认关闭。</span>
            </div>
            <div className="envc-control">
              <label className="settings-switch">
                <input
                  type="checkbox"
                  checked={!!settings?.agentSwarmsEnabled}
                  onChange={(e) => {
                    // 乐观同步 store（TeamPanel 立即响应显隐），同时写 settings 持久化
                    useTeamStore.getState().setAgentSwarms(e.target.checked)
                    void onSaveSettings({ agentSwarmsEnabled: e.target.checked }).catch(() => {})
                  }}
                />
                <span></span>
              </label>
            </div>
          </div>
        </div>

        <details className="cli-advanced" style={{ marginTop: 12 }}>
          <summary>
            <svg
              className="cli-advanced-chevron"
              viewBox="0 0 12 12"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <path d="M4.5 3L7.5 6L4.5 9" />
            </svg>
            实验功能
          </summary>
          <div className="envc-list">
            <div className="envc-row">
              <div className="envc-label-group">
                <span className="envc-name">离开摘要 <span className="envc-tag warn">实验</span></span>
                <span className="envc-desc">两个开关都开启后，窗口失焦 5 分钟自动生成离开摘要。默认关闭。</span>
              </div>
              <div className="envc-control" style={{ flexDirection: 'column', alignItems: 'flex-end', gap: 10 }}>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>
                  <span className="envc-gate-name">AWAY_SUMMARY</span>
                  <label className="settings-switch">
                    <input type="checkbox" checked={!!gates.AWAY_SUMMARY} onChange={() => toggleGate('AWAY_SUMMARY')} />
                    <span></span>
                  </label>
                </span>
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>
                  <span className="envc-gate-name">tengu_sedge_lantern</span>
                  <label className="settings-switch">
                    <input type="checkbox" checked={!!gates.tengu_sedge_lantern} onChange={() => toggleGate('tengu_sedge_lantern')} />
                    <span></span>
                  </label>
                </span>
              </div>
            </div>
          </div>
        </details>
      </section>

    </div>
  )
}

/**
 * 行尾齿轮：点开 = 在该行下方就地展开本机制的数值参数。
 *
 * <p>为什么不把参数平铺在页面上：15 个数字堆在底部时，用户看不出「这个数属于哪个机制」。
 * 收进齿轮后，参数与它作用的开关在同一行内闭合。
 */
function GearButton({
  id,
  open,
  onClick,
  count,
}: {
  id: string
  open: boolean
  onClick: (id: string) => void
  /** 该机制有几个参数（写进 title，让用户点之前就知道有多少） */
  count: number
}) {
  return (
    <button
      type="button"
      className={`envc-gear${open ? ' is-open' : ''}`}
      aria-expanded={open}
      aria-label={`参数（${count}）`}
      title={`参数（${count}）`}
      onClick={() => onClick(id)}
    >
      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth={1.4} aria-hidden="true">
        <circle cx="8" cy="8" r="2.4" />
        <path d="M8 1.6v1.8M8 12.6v1.8M1.6 8h1.8M12.6 8h1.8M3.5 3.5l1.3 1.3M11.2 11.2l1.3 1.3M12.5 3.5l-1.3 1.3M4.8 11.2l-1.3 1.3" />
      </svg>
    </button>
  )
}

/**
 * 压缩数值输入（失焦/回车提交）。
 *
 * <p>⚠️ 提交语义：空 = null（回落后端默认），不是 0。后端 buildCompressionDto 的
 *     「null 不覆盖」只作用于未出现在 patch 里的字段，这里显式传 null 是「清空」。
 */
function CompactNumField({
  def,
  value,
  onChange,
  onCommit,
  error,
  min,
  max,
}: {
  def: CompactNumDef
  value: string
  onChange: (v: string) => void
  onCommit: () => void
  error?: string
  min?: number
  max?: number
}) {
  return (
    <label className="envc-field">
      <span className="envc-field-label">
        {def.name}
        <span className="envc-field-note">{def.note}</span>
      </span>
      <input
        className="settings-input"
        type="number"
        min={min}
        max={max}
        value={value}
        placeholder="留空用默认"
        onChange={(e) => onChange(e.target.value)}
        onBlur={onCommit}
        onKeyDown={(e) => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
      />
      {error ? <span className="envc-field-note" style={{ color: 'var(--error)' }}>{error}</span> : null}
    </label>
  )
}

/**
 * 供应商卡内的一个配置字段。
 *
 * <p>布局对齐设计稿：标签与灰字说明在**同一行**，输入框在下面（不是上下两行）。
 */
function ProviderField({
  label,
  note,
  shared,
  children,
}: {
  label: string
  note: string
  /** 该字段是全局唯一槽位、被多个用途共用时的说明（跟在 note 后） */
  shared?: string
  children: React.ReactNode
}) {
  return (
    <div className="envc-pfield">
      <span className="envc-pfield-label">
        {label}
        <span className="envc-pfield-note">{note}</span>
      </span>
      {children}
      {shared ? <span className="envc-pfield-shared">{shared}</span> : null}
    </div>
  )
}
