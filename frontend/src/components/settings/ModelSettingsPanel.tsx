import { useMemo, useState } from 'react'
import type { AppSettings, ModelTierRole, Provider } from '@/api/types'
import { tagToClass } from '@/data'
import { Icon } from '@/icons'

/**
 * 模型档位角色定义。
 *
 * <p><b>`field` = settings 列</b>：档位绑定值存 settings 单例行（`main_model_name` 等）。
 * <p><b>`active` = 后端当前版本是否真的消费这个档位</b>：
 * 只有 {@code tts} / {@code asr} 两个档位后端有 resolver 方法但**没有任何调用方**
 * （只写不读 · 配了当前版本不上发 LLM）⇒ 界面上如实标注，不假装它们生效。
 * {@code multimodal} 是<b>生效的</b>：{@code LlmAgentLoop} / {@code VisionAnalyzeTool} /
 * {@code ReadFileTool}（>10 页 PDF）都调 {@code ModelConfigResolver.resolveMultimodalModelName()}。
 */
interface TierDef {
  role: ModelTierRole
  /** 界面名 */
  label: string
  /** 档位图标 */
  glyph: string
  /** 一句话说明这个档位是干嘛的 */
  desc: string
  /** AppSettings 上的字段名 */
  field: keyof AppSettings
  /** 后端当前版本是否真的消费这个档位（false = 只写不读） */
  active: boolean
}

const TIERS: TierDef[] = [
  { role: 'main', label: '主模型', glyph: '✦', desc: '对话主力 · 新建会话默认（对话框可临时覆盖）', field: 'mainModelName', active: true },
  { role: 'strong', label: '最强模型', glyph: '◆', desc: '最强复杂任务 · 计划', field: 'strongModelName', active: true },
  { role: 'fast', label: '快速模型', glyph: '⚡', desc: '轻量任务 · 命名/摘要/away/hook', field: 'fastModelName', active: true },
  { role: 'subagent', label: '子代理', glyph: '◎', desc: '子代理运行默认模型', field: 'subagentModelName', active: true },
  { role: 'weak', label: '弱模型', glyph: '◇', desc: '轻量查询 · 快速回答', field: 'weakModelName', active: true },
  { role: 'medium', label: '中等模型', glyph: '◈', desc: '中等任务 · 记忆筛选', field: 'mediumModelName', active: true },
  { role: 'multimodal', label: '多模态模型', glyph: '◍', desc: '文本+图像理解（含视觉）', field: 'multimodalModelName', active: true },
  { role: 'tts', label: 'TTS', glyph: '◧', desc: '语音合成', field: 'ttsModelName', active: false },
  { role: 'asr', label: 'ASR', glyph: '◔', desc: '语音转文字', field: 'asrModelName', active: false },
  { role: 'fallback', label: '降级模型', glyph: '⇄', desc: '主模型失败时自动降级', field: 'fallbackModelName', active: true },
  { role: 'classifier', label: '权限分类器', glyph: '◬', desc: 'auto 权限模式分类器用模型；留空用主循环模型', field: 'classifierModel', active: true },
]

/** L4b 首启引导：仅给向导讲解/绑定涉及的 5 张档位卡挂 data-tour 标记（主/最强/快速/弱/子代理） */
const ROLE_TOUR: Partial<Record<ModelTierRole, string>> = {
  main: 'role-main',
  strong: 'role-strong',
  fast: 'role-fast',
  weak: 'role-weak',
  subagent: 'role-sub',
}

/**
 * 「一键配置到所有档位」覆盖的档位：(全部 − TTS/ASR)。
 *
 * <p><b>为什么排除 TTS / ASR</b>：这两个档位是**专用语音模型**（语音合成 / 语音转文字），
 * 把通用对话模型一键绑过去只会得到一个配错的档位 —— 用户明确要求不参与。
 * 与其让用户事后逐个清掉，不如一开始就不写它们。
 */
const APPLY_ALL_ROLES: ModelTierRole[] = [
  'main', 'strong', 'fast', 'subagent', 'weak', 'medium', 'multimodal', 'fallback', 'classifier',
]

interface Props {
  providers: Provider[]
  settings: AppSettings | null
  /** 绑定档位（写 settings 列 · PUT 传非 null 值 ⇒ 后端 merge 覆盖） */
  onBindTier: (role: ModelTierRole, fullName: string) => void
  /** 一键把某个模型写进 APPLY_ALL_ROLES 的所有档位（一次 PUT，不是 N 次） */
  onBindAllTiers: (fullName: string) => Promise<void>
  /** 解除单个档位的绑定（走 DELETE 端点 · PUT 的 merge 表达不了「清空」） */
  onClearTier: (role: ModelTierRole) => Promise<void>
  /** 解除全部档位的绑定（「一键清除所有已配置的模型」） */
  onClearAllTiers: () => Promise<void>
  /** 跳转设置「提供商」tab（footer 管理 Provider） */
  onOpenProviders?: () => void
}

/**
 * 设置页「模型」tab · 全局档位角色配置（主模型在对话框会话级选择，不在此）。
 *
 * <p><b>布局 = 主从双栏</b>：左列 11 个档位（当前绑定 + 清除×），右列模型列表。
 * 选档位 → 点模型即绑定 ⇒ 档位与模型<b>同屏可见</b>，不必来回滚动对照。
 *
 * <p><b>清空必须走独立端点</b>：后端 {@code SettingsService.update} 的 merge 契约是
 * 「{@code null} = 不覆盖」⇒ 用 PUT <b>无法</b>表达「把档位清空」。清空由调用方
 * （App）经 {@code DELETE /settings/model-tiers/{role}} 完成。
 */
export function ModelSettingsPanel({ providers, settings, onBindTier, onBindAllTiers, onClearTier, onClearAllTiers, onOpenProviders }: Props) {
  const [role, setRole] = useState<ModelTierRole>('main')
  const [search, setSearch] = useState('')
  const [confirmClearAll, setConfirmClearAll] = useState(false)
  const [applyAllFor, setApplyAllFor] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  const currentTier = TIERS.find((t) => t.role === role)!

  /** 档位当前绑定的模型全名（'' = 未配置）。 */
  const tierValue = (t: TierDef): string => {
    const v = settings?.[t.field]
    return typeof v === 'string' ? v : ''
  }

  /** 已配置的档位列表（用于「一键清除」的确认清单 + 顶部统计）。 */
  const configuredTiers = useMemo(
    () => TIERS.filter((t) => !!tierValue(t)),
    // settings 变化时重算（档位绑定值全在 settings 里）
    [settings],
  )

  /** 模型全名 → 占用它的档位名（模型行右侧的「已被哪些档位用」徽标）。 */
  const rolesByModel = useMemo(() => {
    const map = new Map<string, string[]>()
    for (const t of TIERS) {
      const v = tierValue(t)
      if (!v) continue
      const list = map.get(v) ?? []
      list.push(t.label)
      map.set(v, list)
    }
    return map
  }, [settings])

  /** 可选模型（enabled provider + enabled 对话类模型；禁用模型不列 —— 后端只认 enabled 模型）。 */
  const modelGroups = useMemo(() => {
    const q = search.trim().toLowerCase()
    const out: { provider: Provider; models: Provider['models'] }[] = []
    for (const p of providers) {
      if (!p.enabled) continue
      const models = p.models.filter((m) => {
        if (!m.enabled) return false
        if (!q) return true
        return m.name.toLowerCase().includes(q) || (m.desc ?? '').toLowerCase().includes(q)
      })
      if (models.length > 0) out.push({ provider: p, models })
    }
    return out
  }, [providers, search])

  const bind = (providerName: string, modelName: string) => {
    const fullName = `${providerName}/${modelName}`
    if (tierValue(currentTier) === fullName) return
    onBindTier(currentTier.role, fullName)
  }

  const clearTier = async (t: TierDef) => {
    setPending(true)
    try {
      await onClearTier(t.role)
    } finally {
      setPending(false)
    }
  }

  const clearAll = async () => {
    setPending(true)
    try {
      await onClearAllTiers()
      setConfirmClearAll(false)
    } finally {
      setPending(false)
    }
  }

  const applyAll = async () => {
    if (!applyAllFor) return
    setPending(true)
    try {
      await onBindAllTiers(applyAllFor)
      setApplyAllFor(null)
    } finally {
      setPending(false)
    }
  }

  /** 一键配置会覆盖的档位（含当前值，供确认框展示「哪些会被改」）。 */
  const applyAllTargets = APPLY_ALL_ROLES.map((r) => TIERS.find((t) => t.role === r)!)

  return (
    <div className="ms-panel">
      {/* 顶部：标题 + 一键清除全部 */}
      <div className="ms-head">
        <div>
          <div className="ms-head-title">模型档位</div>
          <div className="ms-head-desc">
            点左边档位 → 右边点模型即绑定
            {configuredTiers.length > 0 && ` · 已配置 ${configuredTiers.length}/${TIERS.length}`}
          </div>
          <div className="ms-head-tip">悬停模型行可「一键配置」到全部档位（不含 TTS / ASR）</div>
        </div>
        {configuredTiers.length > 0 && (
          <button
            className="ms-clear-all"
            onClick={() => setConfirmClearAll(true)}
            disabled={pending}
            title="清除所有档位与模型的绑定"
          >
            <Icon.Trash />
            一键清除全部
          </button>
        )}
      </div>

      <div className="ms-split">
        {/* 左列：档位清单 */}
        <div className="ms-tier-list">
          {TIERS.map((t) => {
            const v = tierValue(t)
            const on = role === t.role
            return (
              <div
                key={t.role}
                className={`ms-tier ${on ? 'on' : ''}`}
                data-tour={ROLE_TOUR[t.role] ?? undefined}
                onClick={() => setRole(t.role)}
                title={`${t.label} · ${t.desc}`}
              >
                <span className="ms-tier-glyph">{t.glyph}</span>
                <div className="ms-tier-body">
                  <span className="ms-tier-name">
                    {t.label}
                    {!t.active && <span className="ms-tier-flag">未生效</span>}
                  </span>
                  <span className={`ms-tier-val ${v ? 'set' : ''}`} title={v || undefined}>
                    {v || '未配置'}
                  </span>
                </div>
                {v && (
                  <button
                    className="ms-tier-clear"
                    title={`清除「${t.label}」的绑定`}
                    disabled={pending}
                    onClick={(e) => {
                      e.stopPropagation()
                      void clearTier(t)
                    }}
                  >
                    <Icon.CloseLg />
                  </button>
                )}
              </div>
            )
          })}
        </div>

        {/* 右列：模型列表 */}
        <div className="ms-pick">
          <div className="ms-pick-head">
            <div className="ms-pick-for">
              正在为 <b>{currentTier.label}</b> 选择模型
              <span className="ms-pick-desc">{currentTier.desc}</span>
            </div>
            <div className="ms-pick-search">
              <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="5.5" cy="5.5" r="3.5" /><path d="M8.5 8.5L11 11" /></svg>
              <input placeholder="搜索模型…" value={search} onChange={(e) => setSearch(e.target.value)} />
            </div>
          </div>

          <div className="ms-pick-body">
            {modelGroups.length === 0 && (
              <div className="right-empty">
                {search.trim() ? '没有匹配的模型' : '暂无可用模型 · 去 提供商 添加'}
              </div>
            )}
            {modelGroups.map(({ provider: p, models }) => (
              <div key={p.id} className="ms-pick-group">
                <div className="ms-pick-group-head">
                  {p.name.toUpperCase()}
                  <span className="ms-pick-count">{models.length} 个模型</span>
                </div>
                {models.map((m) => {
                  const fullName = `${p.name}/${m.name}`
                  const bound = tierValue(currentTier) === fullName
                  const usedBy = rolesByModel.get(fullName) ?? []
                  return (
                    <div
                      key={m.id}
                      className={`ms-pick-item ${bound ? 'bound' : ''}`}
                      onClick={() => bind(p.name, m.name)}
                    >
                      <span className={`model-tag ${tagToClass(m.tag)}`}>{m.tag}</span>
                      <div className="ms-pick-info">
                        <div className="ms-pick-name" title={fullName}>
                          {fullName}
                          <span className="ms-pick-type">{m.type}</span>
                        </div>
                        {m.desc && <div className="ms-pick-desc-line">{m.desc}</div>}
                      </div>
                      <div className="ms-pick-badges">
                        {/* 徽标最多显示 3 个 + 「+N」—— 模型可能被很多档位共用（实测有绑 9 档的），
                            全列出来会把模型名挤成几十 px。模型名比徽标重要。 */}
                        {usedBy.slice(0, 3).map((label) => (
                          <span key={label} className={`ms-role-chip ${label === currentTier.label ? 'on' : ''}`}>
                            {label}
                          </span>
                        ))}
                        {usedBy.length > 3 && (
                          <span className="ms-role-chip" title={usedBy.join('、')}>
                            +{usedBy.length - 3}
                          </span>
                        )}
                        {bound && (
                          <span className="ms-pick-check">
                            <Icon.Check />
                          </span>
                        )}
                      </div>
                      {/* 「一键配置到所有档位」—— 单个模型逐个绑 9 次很烦（主模型/最强/快速/子代理/
                          弱/中等/多模态/降级/权限分类器），一个模型就够用时常整片是一样的。
                          默认藏起来避免每行都挂个按钮；hover / 键盘聚焦才浮现（触屏常显）。 */}
                      <button
                        className="ms-apply-all"
                        title={`把 ${fullName} 配置到全部档位（不含 TTS / ASR）`}
                        disabled={pending}
                        onClick={(e) => {
                          e.stopPropagation()
                          setApplyAllFor(fullName)
                        }}
                      >
                        一键配置
                      </button>
                    </div>
                  )
                })}
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* 底部：管理模型 / Provider（同步复刻 HTML footer） */}
      <div className="ms-footer">
        <span className="manage" onClick={() => onOpenProviders?.()}>管理模型 / Provider →</span>
      </div>

      {/* 「一键清除全部」确认框 —— 逐条列出将解绑的档位 + 模型全名 */}
      {confirmClearAll && (
        <div className="ms-confirm-backdrop" onClick={() => !pending && setConfirmClearAll(false)}>
          <div className="ms-confirm" onClick={(e) => e.stopPropagation()}>
            <div className="ms-confirm-head">
              <span className="ms-confirm-ico"><Icon.Trash /></span>
              <div>
                <div className="ms-confirm-title">清除全部模型绑定？</div>
                <div className="ms-confirm-msg">
                  将解除以下 <b>{configuredTiers.length}</b> 个档位与模型的绑定。
                  清除后这些档位会回落到系统默认行为（主模型清空后，新建会话会使用第一个启用的对话类模型）。
                </div>
              </div>
            </div>
            <div className="ms-confirm-list">
              {configuredTiers.map((t) => (
                <div key={t.role} className="ms-confirm-row">
                  <span className="ms-confirm-role">{t.label}</span>
                  <span className="ms-confirm-model">{tierValue(t)}</span>
                </div>
              ))}
            </div>
            <div className="ms-confirm-foot">
              <button className="fm-btn" onClick={() => setConfirmClearAll(false)} disabled={pending}>取消</button>
              <button className="fm-btn ms-confirm-ok" onClick={() => void clearAll()} disabled={pending}>
                {pending ? '清除中…' : `确认清除 ${configuredTiers.length} 个`}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 「一键配置到全部档位」确认框 —— 会覆盖哪些档位、每个档位原本配的是什么，都列出来 */}
      {applyAllFor && (
        <div className="ms-confirm-backdrop" onClick={() => !pending && setApplyAllFor(null)}>
          <div className="ms-confirm" onClick={(e) => e.stopPropagation()}>
            <div className="ms-confirm-head">
              <span className="ms-confirm-ico ms-confirm-ico-accent"><Icon.Check /></span>
              <div>
                <div className="ms-confirm-title">一键配置到全部档位？</div>
                <div className="ms-confirm-msg">
                  将把 <b className="ms-confirm-em">{applyAllFor}</b> 写入以下{' '}
                  <b>{applyAllTargets.length}</b> 个档位。
                  TTS 与 ASR 是专用语音模型，不在本次范围内。
                </div>
              </div>
            </div>
            <div className="ms-confirm-list">
              {applyAllTargets.map((t) => {
                const old = tierValue(t)
                return (
                  <div key={t.role} className="ms-confirm-row">
                    <span className="ms-confirm-role">{t.label}</span>
                    <span className={`ms-confirm-model ${old && old !== applyAllFor ? 'overwrite' : ''}`}>
                      {old && old !== applyAllFor ? `${old} → ` : ''}
                      {applyAllFor}
                    </span>
                  </div>
                )
              })}
            </div>
            <div className="ms-confirm-foot">
              <button className="fm-btn" onClick={() => setApplyAllFor(null)} disabled={pending}>取消</button>
              <button className="fm-btn primary" onClick={() => void applyAll()} disabled={pending}>
                {pending ? '配置中…' : `确认配置 ${applyAllTargets.length} 个档位`}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
