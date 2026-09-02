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
  onSaveSettings: (req: UpdateSettingsRequest) => Promise<void>
  /** 打开记忆编辑器（独立弹窗 MemoryEditorModal · 设置弹窗保持打开，编辑器 z-index 更高） */
  onOpenMemoryEditor: () => void
}

/**
 * 环境配置面板（设置页「环境配置」tab）· 复用模型选择器环境配置 UI：
 * 自动压缩窗口 / 记忆模块（编辑器入口 + auto-memory/auto-dream 开关走 /memory/config + dream 状态 + 目录联动）/ away-summary 门控。
 */
export function EnvConfigPanel({ settings, onSaveSettings, onOpenMemoryEditor }: EnvConfigPanelProps) {
  // 默认权限模式抽屉（对齐 Composer 胶囊+抽屉 · 点击外部收起）
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
  // /memory/config 记忆开关（D2/D5：auto-memory/auto-dream 统一走此端点，替代原 settings API「开启自动记忆」）
  const [memCfg, setMemCfg] = useState<MemoryConfig | null>(null)
  const [memCfgLoading, setMemCfgLoading] = useState(true)
  const [memCfgError, setMemCfgError] = useState('')
  // WebSearch 工具配置草稿（v0.4.4 契约 · 6 项经 GET/PUT /api/v1/settings 读写）
  const [wsEngine, setWsEngine] = useState<string>(settings?.websearchEngine ?? 'anysearch')
  const [wsUseSmallModel, setWsUseSmallModel] = useState<boolean>(settings?.websearchUseSmallModel ?? false)
  const [wsProxy, setWsProxy] = useState<string>(settings?.proxy ?? '')
  const [wsApiKey, setWsApiKey] = useState<string>(settings?.apiKey ?? '')
  const [wsBaseUrl, setWsBaseUrl] = useState<string>(settings?.websearchBaseUrl ?? '')
  const [wsDomainCheckUrl, setWsDomainCheckUrl] = useState<string>(settings?.websearchDomainCheckUrl ?? '')
  // away-summary 门控（localStorage · 两开关都开才触发 blur 摘要；后端 features API 补后接入）
  const [gates, setGates] = useState<{ AWAY_SUMMARY?: boolean; tengu_sedge_lantern?: boolean }>(() => {
    try { return JSON.parse(localStorage.getItem(AWAY_GATES_KEY) ?? '{}') } catch { return {} }
  })

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
  }, [settings?.websearchEngine, settings?.websearchUseSmallModel, settings?.proxy, settings?.apiKey, settings?.websearchBaseUrl, settings?.websearchDomainCheckUrl])

  // 挂载时读 /memory/config 开关；失败 fail loud（内联错误文案，不阻塞其他 envc 区块）
  useEffect(() => {
    let cancelled = false
    getMemoryConfig()
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
  }, [])

  const saveCompactWindow = () => {
    const raw = compactDraft.trim()
    void onSaveSettings({ autoCompactWindow: raw === '' || Number.isNaN(Number(raw)) ? null : Number(raw) })
  }

  // ---- F2 · 压缩开关组（对齐后端 SettingsDto 12 字段 · settingsApi.update 整 DTO · null 不覆盖）----
  // time-based-MC gap/keep 数字输入草稿
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

  /** 组装 12 + 11 项压缩设置整 DTO：patch 覆盖 + 现有 settings 兜底（未配置字段置 null，后端 merge 忽略 null 不覆盖） */
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
      // V54 新增 11 项压缩数值列（SM / cached-MC / 熔断重试 · 空=null 回落后端默认）
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
  const saveMcGap = () => {
    const raw = mcGapDraft.trim()
    void onSaveSettings(buildCompressionDto({ timeBasedMcGapMinutes: raw === '' || Number.isNaN(Number(raw)) ? null : Number(raw) })).catch(() => {})
  }
  const saveMcKeep = () => {
    const raw = mcKeepDraft.trim()
    void onSaveSettings(buildCompressionDto({ timeBasedMcKeepRecent: raw === '' || Number.isNaN(Number(raw)) ? null : Number(raw) })).catch(() => {})
  }

  // ---- 压缩机制域（6 个域卡片 · 每个域标题 + 白话说明 + 归属开关/数值）----
  type CompactDomainKey = 'autoCompact' | 'sm' | 'microMc' | 'snip' | 'reactive' | 'contextCollapse'
  interface CompactDomain {
    key: CompactDomainKey
    title: string
    desc: string
    /** 主动压缩：上下文窗口阈值特殊输入（保留「只缩不扩」说明） */
    window?: boolean
    /** 微压缩：time-based-MC gap/keep 特殊输入（联动 timeBasedMcEnabled 禁用） */
    gapKeep?: boolean
  }
  const COMPACT_DOMAINS: CompactDomain[] = [
    {
      key: 'autoCompact',
      title: '主动压缩（AutoCompact）',
      desc: '上下文快满时，自动用一个小模型把老对话总结成摘要替换原文，腾出空间；达到阈值才触发。',
      window: true,
    },
    {
      key: 'sm',
      title: '会话记忆压缩（SM）',
      desc: '先自动把每轮对话提炼成「分节会话笔记」，压缩时用笔记 + 保留最近真实消息代替一次全量总结，更细粒度、不调大模型摘要。',
    },
    {
      key: 'microMc',
      title: '微压缩（Micro-MC）',
      desc: '每次发请求前，把超时没用到的旧工具输出清成占位文本，或让服务端缓存删掉旧工具结果，省 token。',
      gapKeep: true,
    },
    {
      key: 'snip',
      title: '历史裁剪（Snip）',
      desc: '模型可以用 Snip 工具主动把某段历史标记剪掉，压缩器按标记剔除旧消息。',
    },
    {
      key: 'reactive',
      title: '应急压缩（Reactive）',
      desc: '请求被 API 拒绝（prompt 太长/图片超尺寸）时，当场压缩后重发，避免直接报错。',
    },
    {
      key: 'contextCollapse',
      title: '上下文折叠（ContextCollapse）',
      desc: '实验性的细粒度上下文折叠，与主动压缩互斥（开启后主动压缩被抑制）。',
    },
  ]

  // ---- V54 · 压缩数值配置组（11 项 · 空 = null 回落后端默认 · 走 buildCompressionDto 写整 DTO null 不覆盖）----
  type CompactNumberKey = 'cachedMicrocompactTriggerThreshold' | 'cachedMicrocompactKeepRecent' | 'smMinTokens' | 'smMinTextBlockMessages' | 'smMaxTokens' | 'smMinimumMessageTokensToInit' | 'smMinimumTokensBetweenUpdate' | 'smToolCallsBetweenUpdates' | 'maxConsecutiveAutocompactFailures' | 'maxPtlRetries' | 'maxCompactStreamingRetries' | 'snipNudgeThreshold'
  const COMPACT_NUMBERS: { key: CompactNumberKey; name: string; desc: string; defaultValue: number | null; domain: CompactDomainKey }[] = [
    { key: 'snipNudgeThreshold', name: 'Snip 提示消息数阈值', desc: '消息数达到该值提示模型考虑 Snip 压缩；留空按上下文窗口自适应（1M→150 / 512k→100 / 400k→60 / 200k→30）', defaultValue: null, domain: 'snip' },
    { key: 'cachedMicrocompactTriggerThreshold', name: '缓存微压缩触发阈值', desc: '活跃工具结果超过该阈值触发缓存微压缩', defaultValue: 10, domain: 'microMc' },
    { key: 'cachedMicrocompactKeepRecent', name: '缓存微压缩保留数', desc: '触发时保留最近 N 个工具结果', defaultValue: 5, domain: 'microMc' },
    { key: 'smMinTokens', name: 'SM 保留尾段最小 token 数', desc: '会话记忆压缩保留尾段的最小 token 数', defaultValue: 10000, domain: 'sm' },
    { key: 'smMinTextBlockMessages', name: 'SM 最小正文消息条数', desc: '会话记忆压缩所需的最小正文消息条数', defaultValue: 5, domain: 'sm' },
    { key: 'smMaxTokens', name: 'SM 保留尾段最大 token 数', desc: '会话记忆压缩保留尾段的最大 token 数', defaultValue: 40000, domain: 'sm' },
    { key: 'smMinimumMessageTokensToInit', name: '会话笔记初始化阈值（token）', desc: '会话笔记提取：累计 token 达到该值才初始化会话笔记', defaultValue: 10000, domain: 'sm' },
    { key: 'smMinimumTokensBetweenUpdate', name: '会话笔记更新间隔（新增 token）', desc: '会话笔记提取：两次更新之间至少新增的 token 数', defaultValue: 5000, domain: 'sm' },
    { key: 'smToolCallsBetweenUpdates', name: '会话笔记更新间隔（工具调用次数）', desc: '会话笔记提取：至少隔多少次工具调用更新一次', defaultValue: 3, domain: 'sm' },
    { key: 'maxConsecutiveAutocompactFailures', name: '自动压缩熔断阈值', desc: '自动压缩连续失败 N 次后停止尝试', defaultValue: 3, domain: 'autoCompact' },
    { key: 'maxPtlRetries', name: 'prompt 过长重试上限', desc: 'prompt 过长（PTL）时的重试上限', defaultValue: 3, domain: 'autoCompact' },
    { key: 'maxCompactStreamingRetries', name: '压缩流式重试上限', desc: '压缩流式重试上限', defaultValue: 2, domain: 'autoCompact' },
  ]
  const [compactNumDrafts, setCompactNumDrafts] = useState<Record<string, string>>(() => {
    const init: Record<string, string> = {}
    for (const n of COMPACT_NUMBERS) init[n.key] = settings?.[n.key] != null ? String(settings[n.key]) : ''
    return init
  })
  // 后端异步加载 settings 后回填草稿（V54 11 项）
  useEffect(() => {
    setCompactNumDrafts((prev) => {
      const next = { ...prev }
      for (const n of COMPACT_NUMBERS) next[n.key] = settings?.[n.key] != null ? String(settings[n.key]) : ''
      return next
    })
  }, [settings?.cachedMicrocompactTriggerThreshold, settings?.cachedMicrocompactKeepRecent, settings?.smMinTokens, settings?.smMinTextBlockMessages, settings?.smMaxTokens, settings?.smMinimumMessageTokensToInit, settings?.smMinimumTokensBetweenUpdate, settings?.smToolCallsBetweenUpdates, settings?.maxConsecutiveAutocompactFailures, settings?.maxPtlRetries, settings?.maxCompactStreamingRetries, settings?.snipNudgeThreshold])

  const saveCompactNumber = (key: CompactNumberKey) => {
    const raw = (compactNumDrafts[key] ?? '').trim()
    void onSaveSettings(buildCompressionDto({ [key]: raw === '' || Number.isNaN(Number(raw)) ? null : Number(raw) })).catch(() => {})
  }

  /** 10 项布尔压缩开关（对齐后端 SettingsDto 0.5.x 契约字段） */
  type CompactSwitchKey = 'autoCompactEnabled' | 'reactiveCompactEnabled' | 'contextCollapseEnabled' | 'historySnipEnabled' | 'smSessionMemoryEnabled' | 'smCompactEnabled' | 'cachedMicrocompactEnabled' | 'timeBasedMcEnabled' | 'disableCompact' | 'disableAutoCompact'
  const COMPACT_SWITCHES: { key: CompactSwitchKey; name: string; desc: string; danger?: boolean; domain: CompactDomainKey }[] = [
    { key: 'autoCompactEnabled', name: '主动压缩（Auto）', desc: '达到阈值时自动压缩上下文', domain: 'autoCompact' },
    { key: 'reactiveCompactEnabled', name: '反应式压缩（Reactive）', desc: '异步或受抑制的请求触发即时压缩，避免直接报错', domain: 'reactive' },
    { key: 'contextCollapseEnabled', name: '上下文折叠（ContextCollapse）', desc: '把管理性提示折叠进上下文，减少 token 占用', domain: 'contextCollapse' },
    { key: 'historySnipEnabled', name: '历史裁剪（Snip）', desc: '长对话时用 Snip 工具裁剪历史窗口', domain: 'snip' },
    { key: 'smSessionMemoryEnabled', name: '会话记忆（SM session-memory）', desc: '开启会话记忆服务（每轮提炼会话笔记）', domain: 'sm' },
    { key: 'smCompactEnabled', name: '会话记忆压缩（SM compact）', desc: '用会话记忆笔记辅助压缩，更细粒度、不调大模型摘要', domain: 'sm' },
    { key: 'cachedMicrocompactEnabled', name: '缓存微压缩（cached-MC）', desc: '对工具结果缓存做微压缩，释放上下文', domain: 'microMc' },
    { key: 'timeBasedMcEnabled', name: '时间基准微压缩（time-based-MC）', desc: '按时间间隔微压缩历史工具结果', domain: 'microMc' },
    { key: 'disableCompact', name: '禁用压缩（DISABLE_COMPACT）', desc: '总闸：禁用一切压缩', danger: true, domain: 'autoCompact' },
    { key: 'disableAutoCompact', name: '禁用主动压缩（DISABLE_AUTO_COMPACT）', desc: '禁用主动压缩（达到阈值不自动压缩）', danger: true, domain: 'autoCompact' },
  ]

  // auto-memory / auto-dream 开关切换 → PUT /memory/config 部分更新；成功回写本地，失败内联提示（fail loud）
  const toggleMemFlag = (on: boolean, key: 'autoMemoryEnabled' | 'autoDreamEnabled') => {
    void updateMemoryConfig(key === 'autoMemoryEnabled' ? { autoMemoryEnabled: on } : { autoDreamEnabled: on })
      .then((cfg) => {
        setMemCfg(cfg)
        setMemCfgError('')
      })
      .catch((e) => {
        setMemCfgError(e instanceof ApiError ? e.userMessage() : String(e))
      })
  }

  // dream 状态只读文案（未整合 / 最近已整合 + 本地时间）
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

  return (
    <div className="envc-panel">
      {/* 模块：压缩（大模块卡片包裹 · 6 机制域子卡片各自阴影 · 每域标题 + 白话说明 + 归属开关/数值） */}
      <div className="envc-card envc-group">
        <div className="envc-card-title">压缩</div>
        <div className="envc-desc" style={{ marginBottom: 14 }}>上下文自动管理：6 个机制域（主动 / 应急 / 微压缩 / 会话记忆 / 裁剪 / 折叠），每域独立配置、各自区隔</div>
      {COMPACT_DOMAINS.map((domain) => {
        const switches = COMPACT_SWITCHES.filter((sw) => sw.domain === domain.key)
        const numbers = COMPACT_NUMBERS.filter((n) => n.domain === domain.key)
        const hasWindow = !!domain.window
        const hasGapKeep = !!domain.gapKeep
        return (
          <div className="envc-card envc-domain" key={domain.key}>
            <div className="envc-card-title">{domain.title}</div>
            <div className="envc-desc" style={{ marginBottom: 12 }}>{domain.desc}</div>

            {/* 主动压缩：上下文窗口阈值（保留「只缩不扩」说明） */}
            {hasWindow && (
              <div className="envc-row">
                <div className="envc-label-group">
                  <span className="envc-name">自动压缩上下文阈值</span>
                  <span className="envc-desc">留空不限制；设置后按 min(模型窗口, 此值) 计算，只缩不扩</span>
                </div>
                <div className="envc-control">
                  <input
                    className="settings-input"
                    type="number"
                    placeholder="留空不限制"
                    value={compactDraft}
                    onChange={(e) => setCompactDraft(e.target.value)}
                  />
                  <button className="envc-save" onClick={saveCompactWindow}>保存</button>
                </div>
              </div>
            )}
            {hasWindow && switches.length > 0 && <div className="envc-divider" />}

            {/* 开关行（对齐后端 SettingsDto 契约字段 · 写整 DTO null 不覆盖） */}
            {switches.map((sw) => (
              <div className="envc-row" key={sw.key}>
                <div className="envc-label-group">
                  <span className="envc-name">{sw.name}</span>
                  <span className="envc-desc" style={sw.danger ? { color: 'var(--error)' } : undefined}>{sw.desc}</span>
                </div>
                <div className="envc-control">
                  <label className="settings-switch">
                    <input
                      type="checkbox"
                      checked={!!settings?.[sw.key]}
                      onChange={(e) => saveCompactSwitch(sw.key, e.target.checked)}
                    />
                    <span></span>
                  </label>
                </div>
              </div>
            ))}

            {/* 开关组 → 数值组分隔线 */}
            {(switches.length > 0 || hasWindow) && (hasGapKeep || numbers.length > 0) && <div className="envc-divider" />}

            {/* 微压缩：time-based-MC gap/keep 数字输入（联动开关：仅 timeBasedMcEnabled 开启时可配置） */}
            {hasGapKeep && (
              <div className={`envc-row ${settings?.timeBasedMcEnabled ? '' : 'disabled'}`}>
                <div className="envc-label-group">
                  <span className="envc-name">MC 间隔阈值（分钟 gap）</span>
                  <span className="envc-desc">时间基准微压缩的间隔阈值（分钟）· 默认 60</span>
                </div>
                <div className="envc-control">
                  <input
                    className="settings-input"
                    type="number"
                    value={mcGapDraft}
                    disabled={!settings?.timeBasedMcEnabled}
                    placeholder="默认 60 · 留空即用"
                    onChange={(e) => setMcGapDraft(e.target.value)}
                  />
                  <button className="envc-save" onClick={saveMcGap} disabled={!settings?.timeBasedMcEnabled}>保存</button>
                </div>
              </div>
            )}
            {hasGapKeep && (
              <div className={`envc-row ${settings?.timeBasedMcEnabled ? '' : 'disabled'}`}>
                <div className="envc-label-group">
                  <span className="envc-name">保留最近条数（keep）</span>
                  <span className="envc-desc">时间基准微压缩保留的最近条数 · 默认 5</span>
                </div>
                <div className="envc-control">
                  <input
                    className="settings-input"
                    type="number"
                    value={mcKeepDraft}
                    disabled={!settings?.timeBasedMcEnabled}
                    placeholder="默认 5 · 留空即用"
                    onChange={(e) => setMcKeepDraft(e.target.value)}
                  />
                  <button className="envc-save" onClick={saveMcKeep} disabled={!settings?.timeBasedMcEnabled}>保存</button>
                </div>
              </div>
            )}
            {hasGapKeep && numbers.length > 0 && <div className="envc-divider" />}

            {/* 数值行（空 = null 回落后端默认） */}
            {numbers.map((n) => (
              <div className="envc-row" key={n.key}>
                <div className="envc-label-group">
                  <span className="envc-name">{n.name}</span>
                  <span className="envc-desc">{n.desc}</span>
                </div>
                <div className="envc-control">
                  <input
                    className="settings-input"
                    type="number"
                    value={compactNumDrafts[n.key] ?? ''}
                    placeholder={n.defaultValue != null ? `默认 ${n.defaultValue} · 留空即用` : '留空自适应'}
                    onChange={(e) => setCompactNumDrafts((prev) => ({ ...prev, [n.key]: e.target.value }))}
                  />
                  <button className="envc-save" onClick={() => saveCompactNumber(n.key)}>保存</button>
                </div>
              </div>
            ))}
          </div>
        )
      })}
      </div>

      {/* 模块：记忆（D2/D5 · 编辑器入口 + auto-memory/auto-dream 开关走 /memory/config + 目录联动 + dream 状态只读） */}
      <div className="envc-card">
        <div className="envc-card-title">记忆</div>
        <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">记忆编辑</span>
          <span className="envc-desc">管理全局记忆文件，用于指导 AI 的长期行为与偏好</span>
        </div>
        <div className="envc-control">
          <button className="fm-btn primary" onClick={onOpenMemoryEditor}>打开记忆编辑器</button>
        </div>
      </div>

      <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">自动记忆</span>
          <span className="envc-desc">控制记忆文件自动注入上下文（默认关闭）</span>
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

      {/* 自动记忆目录（联动禁用 · 开关走 /memory/config memCfg.autoMemoryEnabled，D5 统一） */}
      <div className={`envc-row ${memCfg?.autoMemoryEnabled ? '' : 'disabled'}`}>
        <div className="envc-label-group">
          <span className="envc-name">自动记忆目录</span>
          <span className="envc-desc">仅开关打开时可配置，留空用默认 ~/.claude/projects</span>
        </div>
        <div className="envc-control">
          <input
            className="settings-input"
            type="text"
            value={memoryDir}
            disabled={!memCfg?.autoMemoryEnabled}
            placeholder="留空用默认 ~/.claude/projects"
            onChange={(e) => setMemoryDir(e.target.value)}
          />
          <button className="envc-save" onClick={() => void onSaveSettings({ autoMemoryDirectory: memoryDir })} disabled={!memCfg?.autoMemoryEnabled}>保存</button>
        </div>
      </div>

      <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">自动精简记忆</span>
          <span className="envc-desc">自动整合/精简记忆（默认关闭）</span>
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

      <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">记忆整合状态</span>
          <span className="envc-desc">{dreamStatusText}</span>
        </div>
      </div>

      {/* /memory/config 读写失败内联提示（fail loud） */}
      {memCfgError && (
        <div className="envc-row">
          <span className="envc-desc" style={{ color: 'var(--error)' }}>记忆配置读取/保存失败：{memCfgError}</span>
        </div>
      )}
      </div>

      {/* 模块：WebSearch 工具配置（v0.4.4 契约 · 6 项经 GET/PUT /api/v1/settings 读写） */}
      <div className="envc-card">
        <div className="envc-card-title">WebSearch</div>
        <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">搜索引擎</span>
          <span className="envc-desc">默认 anysearch</span>
        </div>
        <div className="envc-control">
          <select
            className="settings-input"
            value={wsEngine}
            onChange={(e) => setWsEngine(e.target.value)}
          >
            <option value="anysearch">anysearch</option>
            <option value="duckduckgo">duckduckgo</option>
          </select>
          <button className="envc-save" onClick={() => void onSaveSettings({ websearchEngine: wsEngine })}>保存</button>
        </div>
      </div>

      <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">小模型总结</span>
          <span className="envc-desc">开启后用弱模型对搜索结果做总结（默认关闭）</span>
        </div>
        <div className="envc-control">
          <label className="settings-switch">
            <input
              type="checkbox"
              checked={wsUseSmallModel}
              onChange={(e) => {
                setWsUseSmallModel(e.target.checked)
                void onSaveSettings({ websearchUseSmallModel: e.target.checked })
              }}
            />
            <span></span>
          </label>
        </div>
      </div>

      <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">代理</span>
          <span className="envc-desc">HTTP 代理，留空直连</span>
        </div>
        <div className="envc-control">
          <input
            className="settings-input"
            type="text"
            value={wsProxy}
            placeholder="留空直连"
            onChange={(e) => setWsProxy(e.target.value)}
          />
          <button className="envc-save" onClick={() => void onSaveSettings({ proxy: wsProxy })}>保存</button>
        </div>
      </div>

      <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">API key</span>
          <span className="envc-desc">留空使用内置默认兜底</span>
        </div>
        <div className="envc-control">
          <input
            className="settings-input"
            type="text"
            value={wsApiKey}
            placeholder="留空内置默认"
            onChange={(e) => setWsApiKey(e.target.value)}
          />
          <button className="envc-save" onClick={() => void onSaveSettings({ apiKey: wsApiKey })}>保存</button>
        </div>
      </div>

      <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">引擎 Base URL</span>
          <span className="envc-desc">搜索 API 地址，留空默认 https://api.anysearch.com</span>
        </div>
        <div className="envc-control">
          <input
            className="settings-input"
            type="text"
            value={wsBaseUrl}
            placeholder="https://api.anysearch.com"
            onChange={(e) => setWsBaseUrl(e.target.value)}
          />
          <button className="envc-save" onClick={() => void onSaveSettings({ websearchBaseUrl: wsBaseUrl })}>保存</button>
        </div>
      </div>

      <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">域预检端点</span>
          <span className="envc-desc">搜索前预检域名可达性，留空跳过</span>
        </div>
        <div className="envc-control">
          <input
            className="settings-input"
            type="text"
            value={wsDomainCheckUrl}
            placeholder="留空跳过预检"
            onChange={(e) => setWsDomainCheckUrl(e.target.value)}
          />
          <button className="envc-save" onClick={() => void onSaveSettings({ websearchDomainCheckUrl: wsDomainCheckUrl })}>保存</button>
        </div>
      </div>
      </div>

      {/* 模块：离开摘要门控（两开关都开 → blur 5min 触发离开摘要） */}
      <div className="envc-card">
        <div className="envc-card-title">离开摘要</div>
        <div className="envc-row">
        <div className="envc-label-group">
          <span className="envc-name">离开摘要（away-summary）门控</span>
          <span className="envc-desc">两个开关都开启后，窗口失焦 5 分钟自动生成离开摘要（默认关）</span>
        </div>
        <div className="envc-control envc-gates">
          <span className="envc-gate">
            <span className="envc-gate-name">AWAY_SUMMARY</span>
            <label className="settings-switch">
              <input type="checkbox" checked={!!gates.AWAY_SUMMARY} onChange={() => toggleGate('AWAY_SUMMARY')} />
              <span></span>
            </label>
          </span>
          <span className="envc-gate">
            <span className="envc-gate-name">tengu_sedge_lantern</span>
            <label className="settings-switch">
              <input type="checkbox" checked={!!gates.tengu_sedge_lantern} onChange={() => toggleGate('tengu_sedge_lantern')} />
              <span></span>
            </label>
          </span>
        </div>
      </div>
      </div>

      {/* 模块：权限模式（全局默认 · 会话可覆盖，Composer 模型名旁切换） */}
      <div className="envc-card">
        <div className="envc-card-title">权限模式</div>
        <div className="envc-row">
          <div className="envc-label-group">
            <span className="envc-name">默认权限模式</span>
            <span className="envc-desc">全局默认；会话未单独设置时生效</span>
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
      </div>

      {/* 模块：Agent Swarms（Team 协作总开关 · 写 settings.agentSwarmsEnabled → 后端 isAgentSwarmsEnabled 读） */}
      <div className="envc-card">
        <div className="envc-card-title">Agent Swarms</div>
        <div className="envc-row">
          <div className="envc-label-group">
            <span className="envc-name">Team 协作（swarm）</span>
            <span className="envc-desc">开启后右侧任务 tab 显示 Team 协作面板（创建团队/成员/消息流）；默认关闭</span>
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

    </div>
  )
}
