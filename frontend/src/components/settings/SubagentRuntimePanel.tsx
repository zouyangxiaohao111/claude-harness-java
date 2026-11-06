import { useCallback, useEffect, useState } from 'react'
import {
  subagentRuntimeApi,
  cliRuntimeConfigApi,
  type CliRuntimeConfig,
  type CliRuntimeModels,
  type SubagentExternalAgent,
} from '@/api/subagentRuntimes'
import type { AppSettings, UpdateSettingsRequest } from '@/api/types'

interface Props {
  settings: AppSettings | null
  onSaveSettings: (req: UpdateSettingsRequest) => Promise<void>
  showToast: (msg: string, kind?: 'success' | 'info') => void
  sessionId?: string | null
}

/** 下拉里「自定义模型」的哨兵值（不会与真实模型 id 冲突）。 */
const CUSTOM_MODEL = '__custom_model__'

/**
 * CLI 设置 · 外部编码 CLI 的运行配置。
 *
 * <p><b>布局参照 OpenDesign 的「你的 CLI」</b>：两列卡片网格 + 单张聚焦配置面板。
 * 「聚焦」是这里的第一原则 —— 同一时刻**只有一个** CLI 展开配置，
 * 而不是每张卡片都铺开一堆输入框互相抢注意力。
 *
 * <p><b>三条语义</b>（与后端一致，改动前先读）：
 *  1. 默认全部走仓内子代理；配了默认运行时才按默认派发；
 *  2. 用户显式指定 `subagent_type` 时永远以点名为准；
 *  3. 每项配置独立：不填 = 不注入环境变量，CLI 用自己的默认。
 */
export function SubagentRuntimePanel({ settings, onSaveSettings, showToast, sessionId }: Props) {
  const [agents, setAgents] = useState<SubagentExternalAgent[]>([])
  const [configs, setConfigs] = useState<CliRuntimeConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [rescanning, setRescanning] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)

  const load = useCallback(async () => {
    setError('')
    try {
      const [res, cfg] = await Promise.all([
        subagentRuntimeApi.list(sessionId),
        // 配置表读取失败不能把探测清单也弄没：它只是附加信息
        cliRuntimeConfigApi.list().catch(() => [] as CliRuntimeConfig[]),
      ])
      setAgents(res.agents)
      setConfigs(cfg)
      // 打开页面**不自动展开任何外部 CLI**：默认运行时是仓内内置子代理，
      // 自动展开第一个 CLI 会让人以为「默认就是它」。（用户 2026-11-04 指出）
      setSelected((prev) => (prev && cfg.some((c) => c.runtime === prev && c.available) ? prev : null))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [sessionId])

  useEffect(() => {
    void load()
  }, [load])

  const enabled = settings?.subagentExternalEnabled ?? null
  const defaultRuntime = settings?.subagentDefaultRuntime ?? ''
  const installed = configs.filter((c) => c.available)
  const uninstalled = configs.filter((c) => !c.available)
  /** 未配置默认运行时 = 全部走仓内子代理（这是默认状态，不是「没选」）。 */
  const usingBuiltin = defaultRuntime === ''

  /**
   * 该 runtime 对应的 subagent_type（设置里存的是 agentType，不是 runtime）。
   *
   * <p>没建 md 时后端也能按 runtime 名占位，但那样会走「回落内置 + WARN」；
   * 所以优先用 registry 里真实存在的 agentType。
   */
  const agentTypeOf = (runtime: string): string | null => {
    const hit = agents.find((a) => a.runtime === runtime)
    return hit ? hit.agentType : runtime
  }

  const saveSettings = async (req: UpdateSettingsRequest, okMsg: string) => {
    try {
      await onSaveSettings(req)
      showToast(okMsg, 'info')
    } catch (e) {
      showToast(e instanceof Error ? e.message : String(e), 'info')
    }
  }

  const rescan = async () => {
    setRescanning(true)
    try {
      await load()
      showToast(`扫描完成，${installed.length} 个可用。`, 'success')
    } finally {
      setRescanning(false)
    }
  }

  return (
    <div className="cli-panel">
      {/* ── 派发开关（NexusAI 自有语义，OpenDesign 没有） ── */}
      <section className="cli-section">
        <div className="cli-section-head">
          <h3>子代理外部运行时</h3>
        </div>
        <p className="cli-hint">
          把任务委派给本机安装的编码 CLI。默认全部走仓内子代理；只有选了默认运行时，
          且本次调用没有显式指定 <code>subagent_type</code> 时才会派给它。
        </p>

        <div className="cli-field-row">
          <label className="cli-field-inline">
            <span className="cli-field-label">启用外部运行时</span>
            <select
              className="cli-select"
              value={enabled === null ? '' : enabled ? 'on' : 'off'}
              onChange={(e) => {
                const v = e.target.value
                void saveSettings(
                  { subagentExternalEnabled: v === '' ? null : v === 'on' },
                  v === '' ? '已恢复为「未配置」' : v === 'on' ? '已启用外部运行时' : '已关闭外部运行时',
                )
              }}
            >
              <option value="">未配置（跟随服务端默认）</option>
              <option value="on">启用</option>
              <option value="off">关闭</option>
            </select>
          </label>

          <div className="cli-field-inline">
            <span className="cli-field-label">当前默认</span>
            {/* 默认运行时就是下面列表里带「默认」徽章的那一行，点它即可切换，
                所以这里只显示结果、不再给第二个下拉（两处都能改会互相打架）。 */}
            <div className="cli-current-default">
              <span className={usingBuiltin ? 'cli-ok' : 'cli-ink'}>
                {usingBuiltin ? '内置子代理（仓内运行）' : defaultRuntime}
              </span>
              {!usingBuiltin && (
                <button
                  className="cli-link-btn"
                  onClick={() => void saveSettings({ subagentDefaultRuntime: '' }, '已恢复为「全部使用内置子代理」')}
                >
                  改回内置
                </button>
              )}
            </div>
          </div>
        </div>
        {enabled === false && (
          <p className="cli-hint cli-hint-strong">
            已关闭外部运行时：即使把某个 CLI 设为默认也不会派发。
          </p>
        )}
      </section>

      {/* ── 你的 CLI ── */}
      <section className="cli-section">
        <div className="cli-group-head">
          <h4>你的 CLI（{installed.length}）</h4>
          <button className="cli-ghost-btn" onClick={() => void rescan()} disabled={rescanning}>
            <svg
              className={rescanning ? 'cli-spin' : undefined}
              viewBox="0 0 14 14"
              width="12"
              height="12"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
              aria-hidden="true"
            >
              <path d="M12 7a5 5 0 1 1-1.5-3.5" />
              <path d="M12 1.5V4.5H9" />
            </svg>
            {rescanning ? '扫描中' : '重新扫描'}
          </button>
        </div>

        {error !== '' && <div className="cli-error">{error}</div>}

        {loading && installed.length === 0 && (
          <div className="cli-skeleton-grid" aria-hidden="true">
            <div className="cli-skeleton" />
            <div className="cli-skeleton" />
          </div>
        )}

        {!loading && installed.length === 0 && (
          <div className="cli-empty">
            尚未检测到任何 CLI。安装 Claude Code、Codex、opencode 或 pi 后点「重新扫描」。
          </div>
        )}

        {!loading && (
          // 单列列表（对齐 OpenDesign）：默认只显示一行摘要，点哪行才展开哪行。
          // ⛔ 不要用两列网格 —— 名称+描述+版本+模型 在窄格里会挤成两行，很难扫读。
          <div className="cli-list">
            {/* 置顶：仓内内置子代理 = 默认运行时。放在外部 CLI 上面，
                让「默认是什么」第一眼就能看到（用户 2026-11-04 指出）。 */}
            <div className={`cli-card cli-card-builtin${usingBuiltin ? ' is-default' : ''}`}>
              <div
                role="button"
                tabIndex={0}
                className="cli-card-hit"
                aria-pressed={usingBuiltin}
                aria-expanded={false}
                onClick={() => {
                  // 点它 = 把默认改回内置（等价于清空 defaultRuntime）
                  if (usingBuiltin) return
                  void saveSettings({ subagentDefaultRuntime: '' }, '已恢复为「全部使用内置子代理」')
                }}
                onKeyDown={(e) => {
                  if ((e.key === 'Enter' || e.key === ' ') && !usingBuiltin) {
                    e.preventDefault()
                    void saveSettings({ subagentDefaultRuntime: '' }, '已恢复为「全部使用内置子代理」')
                  }
                }}
              >
                <span className="cli-icon cli-icon-builtin" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
                       strokeLinecap="round" strokeLinejoin="round">
                    <rect x="3.5" y="7" width="17" height="12" rx="3" />
                    <path d="M12 3.2v3.8M8.5 12.5v2M15.5 12.5v2" />
                  </svg>
                </span>
                <span className="cli-card-body">
                  <span className="cli-card-name">
                    <span className="cli-card-title">内置子代理</span>
                    <span className="cli-card-divider" aria-hidden="true">·</span>
                    <span className="cli-card-tagline">NexusAI 仓内运行</span>
                    {usingBuiltin && <span className="cli-dot" aria-hidden="true" />}
                  </span>
                  <span className="cli-card-meta">
                    {usingBuiltin ? '当前默认 · 无需安装' : '点击设为默认'}
                  </span>
                </span>
                {usingBuiltin ? (
                  <span className="cli-builtin-badge">默认</span>
                ) : (
                  <span className="cli-card-action">设为默认</span>
                )}
              </div>
            </div>

            {installed.map((c) => {
              const isOpen = selected === c.runtime
              return (
                <div
                  key={c.runtime}
                  className={`cli-card${isOpen ? ' is-open' : ''}${
                    defaultRuntime === agentTypeOf(c.runtime) ? ' is-default' : ''
                  }`}
                >
                  {/* ⛔ 这里必须是 div 而不是 <button>：行尾还有「设为默认」按钮，
                      button 套 button 是非法 HTML，浏览器会把它拆到外面，点击区全乱。 */}
                  <div
                    role="button"
                    tabIndex={0}
                    className="cli-card-hit"
                    aria-pressed={isOpen}
                    aria-expanded={isOpen}
                    onClick={() => setSelected(isOpen ? null : c.runtime)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        setSelected(isOpen ? null : c.runtime)
                      }
                    }}
                  >
                    <CliIcon runtime={c.runtime} />
                    <span className="cli-card-body">
                      <span className="cli-card-name">
                        <span className="cli-card-title">{c.displayName}</span>
                        {c.tagline && (
                          <>
                            <span className="cli-card-divider" aria-hidden="true">·</span>
                            <span className="cli-card-tagline">{c.tagline}</span>
                          </>
                        )}
                        {c.configured && <span className="cli-dot" aria-hidden="true" />}
                      </span>
                      <span className="cli-card-meta">
                        模型 {c.model ? shortModel(c.model) : 'CLI 默认设置'}
                        {c.reasoning && ` · 推理 ${capitalize(c.reasoning)}`}
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

                  {/* 「设为默认」放在点击区**外面**：它是个独立动作，
                      嵌在整行点击区里既容易误触，也需要 stopPropagation 打补丁。 */}
                  <div className="cli-card-tail">
                    {defaultRuntime === agentTypeOf(c.runtime) ? (
                      <span className="cli-builtin-badge">默认</span>
                    ) : (
                      <button
                        type="button"
                        className="cli-card-action"
                        disabled={enabled === false || !agentTypeOf(c.runtime)}
                        title={
                          enabled === false
                            ? '外部运行时已关闭'
                            : agentTypeOf(c.runtime)
                              ? '设为默认子代理'
                              : '该 CLI 尚未注册为可派发 agent（需要在 agents/ 里建 md）'
                        }
                        onClick={() => {
                          const t = agentTypeOf(c.runtime)
                          if (t) void saveSettings({ subagentDefaultRuntime: t }, `默认子代理已设为 ${t}`)
                        }}
                      >
                        设为默认
                      </button>
                    )}
                  </div>

                  {isOpen && (
                    <RuntimeConfigPanel
                      config={c}
                      onSaved={(next) => {
                        setConfigs((prev) =>
                          prev.map((x) => (x.runtime === next.runtime ? { ...x, ...next } : x)),
                        )
                        showToast(`${c.displayName} 的配置已保存`, 'success')
                      }}
                      onError={(msg) => showToast(msg, 'info')}
                    />
                  )}
                </div>
              )
            })}
          </div>
        )}

        {uninstalled.length > 0 && (
          <details className="cli-uninstalled">
            <summary>可安装（{uninstalled.length}）</summary>
            <ul>
              {uninstalled.map((c) => (
                <li key={c.runtime}>
                  <CliIcon runtime={c.runtime} small />
                  <span>{c.displayName}</span>
                  <span className="cli-dim">未在 PATH 中找到</span>
                </li>
              ))}
            </ul>
          </details>
        )}

        <p className="cli-hint">
          「已安装」只表示可执行文件存在。能否真正跑通取决于该 CLI 自己的登录态与配额，点「测试」才知道。
        </p>
      </section>
    </div>
  )
}

/**
 * CLI 品牌图标；未知运行时退化成首字母方块。
 *
 * ⚠️ codex 的官方图标是**白色标记**（专为深色背景设计），直接放浅底上会看不见 ——
 * 必须给它一个深色承托。其余三家的图标自带配色，浅底即可。
 */
function CliIcon({ runtime, small }: { runtime: string; small?: boolean }) {
  const known = ['claude', 'codex', 'opencode', 'pi'].includes(runtime)
  const size = small ? 18 : 32
  if (known) {
    return (
      <img
        className={`cli-icon cli-icon-${runtime}${small ? ' cli-icon-sm' : ''}`}
        src={`/cli-icons/${runtime}.svg`}
        alt=""
        width={size}
        height={size}
        draggable={false}
      />
    )
  }
  return (
    <span className={`cli-icon cli-icon-fallback${small ? ' cli-icon-sm' : ''}`} aria-hidden="true">
      {(runtime[0] ?? '?').toUpperCase()}
    </span>
  )
}

/**
 * 单个 CLI 的配置面板（聚焦态）。
 *
 * 三态编辑（改动前先读）：
 *  - 字段为 `undefined`（没进请求体）= **不改**；
 *  - `''` = **清空**，后端写成 NULL，回到「用 CLI 默认」；
 *  - 非空 = 覆盖。
 * ⚠️ 只在用户**真的编辑过**某个输入框时才把它放进请求 —— 否则「打开面板点保存」
 *    会把已经存着的 base URL / key 一起清掉。
 */
function RuntimeConfigPanel({
  config,
  onSaved,
  onError,
}: {
  config: CliRuntimeConfig
  onSaved: (next: Partial<CliRuntimeConfig> & { runtime: string }) => void
  onError: (msg: string) => void
}) {
  const [models, setModels] = useState<CliRuntimeModels | null>(null)
  const [loadingModels, setLoadingModels] = useState(true)
  const [version, setVersion] = useState<string | null>(null)
  const [testing, setTesting] = useState(false)
  const [testMsg, setTestMsg] = useState<{ ok: boolean; text: string } | null>(null)

  const [model, setModel] = useState(config.model ?? '')
  const [reasoning, setReasoning] = useState(config.reasoning ?? '')
  const [configDir, setConfigDir] = useState(config.configDir ?? '')
  const [baseUrl, setBaseUrl] = useState(config.baseUrl ?? '')
  const [bin, setBin] = useState(config.bin ?? '')
  const [secretDraft, setSecretDraft] = useState<Record<string, string>>({})
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const [busy, setBusy] = useState(false)

  // 版本与模型列表都是「点开才跑」的显式探测，并行取，不阻塞卡片渲染
  useEffect(() => {
    let alive = true
    void cliRuntimeConfigApi
      .version(config.runtime)
      .then((v) => { if (alive) setVersion(v.version) })
      .catch(() => { if (alive) setVersion(null) })
    setLoadingModels(true)
    void cliRuntimeConfigApi
      .models(config.runtime)
      .then((m) => { if (alive) setModels(m) })
      .catch(() => { if (alive) setModels(null) })
      .finally(() => { if (alive) setLoadingModels(false) })
    return () => { alive = false }
  }, [config.runtime])

  const modelList = models?.models ?? config.fallbackModels
  const modelSource = models?.live ? 'live' : 'fallback'

  // 「自定义模型 ID」：CLI 接受的模型不一定在列表里（例如 codex 换 provider 后
  // 预置的是内置目录里的 gpt-6.x，而 DeepSeek 只认 deepseek-flash）。
  // 判定 = 当前值非空且不在列表里 ⇒ 自动落到自定义态（已保存的自定义值重新打开时也能回显）。
  const [customMode, setCustomMode] = useState(false)
  const modelIsListed = model === '' || modelList.includes(model)
  const useCustomModel = customMode || !modelIsListed
  const customPlaceholder =
    config.runtime === 'codex'
      ? 'deepseek-flash'
      : config.runtime === 'claude'
        ? 'claude-sonnet-4-5'
        : 'deepseek/deepseek-v4-flash'

  // 推理档位**按当前选中的模型**取：codex 各模型支持的档位不同
  // （gpt-6-luna 到 max、gpt-6.1-sol 到 ultra）。拿不到就用预设的全局档位。
  const selectedModelInfo = models?.modelInfos?.find((m) => m.id === model)
  const reasoningChoices =
    selectedModelInfo && selectedModelInfo.reasoningOptions.length > 0
      ? selectedModelInfo.reasoningOptions
      : config.reasoningOptions

  // 换模型后，若原档位不被新模型支持 ⇒ 清空（否则会带着一个 CLI 不认的值）
  useEffect(() => {
    if (reasoning === '') return
    if (reasoningChoices.length > 0 && !reasoningChoices.includes(reasoning)) {
      setReasoning('')
      setTouched((t) => ({ ...t, reasoning: true }))
    }
  }, [reasoning, reasoningChoices])

  const dirty = Object.keys(touched).length > 0

  const save = async () => {
    setBusy(true)
    try {
      const req: {
        configDir?: string
        baseUrl?: string
        bin?: string
        model?: string
        reasoning?: string
        secrets?: Record<string, string>
      } = {}
      if (touched.configDir) req.configDir = configDir
      if (touched.baseUrl) req.baseUrl = baseUrl
      if (touched.bin) req.bin = bin
      if (touched.model) req.model = model
      if (touched.reasoning) req.reasoning = reasoning

      // secrets 是**整体替换**语义 ⇒ 把「已保存的 + 新填的」一起提交；
      // 已保存的用掩码占位，后端识别到掩码即保留原明文。
      const filled = Object.fromEntries(
        Object.entries(secretDraft).filter(([, v]) => v.trim() !== ''),
      )
      if (Object.keys(filled).length > 0) {
        const preserved: Record<string, string> = {}
        for (const [name, masked] of Object.entries(config.secrets)) {
          if (!(name in filled)) preserved[name] = masked
        }
        req.secrets = { ...preserved, ...filled }
      }

      const res = await cliRuntimeConfigApi.save(config.runtime, req)
      setSecretDraft({})
      setTouched({})
      onSaved({
        runtime: config.runtime,
        configDir: touched.configDir ? configDir.trim() || null : config.configDir,
        baseUrl: touched.baseUrl ? baseUrl.trim() || null : config.baseUrl,
        bin: touched.bin ? bin.trim() || null : config.bin,
        model: touched.model ? model.trim() || null : config.model,
        reasoning: touched.reasoning ? reasoning.trim() || null : config.reasoning,
        secrets: res.secrets,
        apiKeySet: res.apiKeySet,
        configured: true,
      })
      // 配置改了 ⇒ 档位集合可能变，重新探测
      void cliRuntimeConfigApi.models(config.runtime).then(setModels).catch(() => {})
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const clearSecrets = async () => {
    setBusy(true)
    try {
      const res = await cliRuntimeConfigApi.save(config.runtime, { secrets: {} })
      setSecretDraft({})
      onSaved({ runtime: config.runtime, secrets: res.secrets, apiKeySet: res.apiKeySet })
    } catch (e) {
      onError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const runTest = async () => {
    setTesting(true)
    setTestMsg(null)
    try {
      const r = await cliRuntimeConfigApi.test(config.runtime)
      setTestMsg({
        ok: r.ok,
        text: r.ok ? `${r.elapsedMs} 毫秒响应 ·「${r.sample ?? ''}」` : (r.error ?? '测试失败'),
      })
    } catch (e) {
      setTestMsg({ ok: false, text: e instanceof Error ? e.message : String(e) })
    } finally {
      setTesting(false)
    }
  }

  return (
    <div className="cli-config" role="region" aria-label={`${config.displayName} 配置`}>
      {/* 展开区头部：只补「版本 + 可执行路径」与测试按钮。
          ⛔ 不要再写一遍名称/tagline —— 它们就在正上方的列表行里，重复会让同一句话出现两次。 */}
      <div className="cli-config-head">
        <span className="cli-config-sub">
          {version ? `v${version}` : '版本未知'}
          {config.resolvedPath && (
            <span className="cli-dim" title={config.resolvedPath}>
              {' · '}
              {shortPath(config.resolvedPath)}
            </span>
          )}
        </span>
        <button
          className="cli-test-btn"
          onClick={() => void runTest()}
          disabled={testing}
          title="发送极小的测试提示词以验证连接（会消耗一次模型调用）"
        >
          {testing ? '测试中' : '测试'}
        </button>
      </div>

      {testMsg && (
        <div className={`cli-test-result ${testMsg.ok ? 'is-ok' : 'is-bad'}`} role="status">
          {testMsg.text}
        </div>
      )}

      <div className="cli-config-body">
        <label className="cli-field">
          <span className="cli-field-label">
            模型
            <span className={`cli-badge ${modelSource === 'live' && !useCustomModel ? 'is-live' : ''}`}>
              {loadingModels
                ? '探测中'
                : useCustomModel
                  ? '自定义'
                  : modelSource === 'live'
                    ? '来自 CLI 的实时列表'
                    : '内置列表'}
            </span>
          </span>
          <select
            className="cli-select"
            value={useCustomModel ? CUSTOM_MODEL : model}
            disabled={busy || loadingModels}
            onChange={(e) => {
              const v = e.target.value
              if (v === CUSTOM_MODEL) {
                // 切到自定义：保留当前值让用户接着改，没值时留空等输入
                setCustomMode(true)
                if (modelIsListed) setModel('')
                setTouched((t) => ({ ...t, model: true }))
                return
              }
              setCustomMode(false)
              setModel(v)
              setTouched((t) => ({ ...t, model: true }))
            }}
          >
            <option value="">CLI 默认设置</option>
            {modelList.map((m) => (
              <option key={m} value={m}>{m}</option>
            ))}
            <option value={CUSTOM_MODEL}>自定义（在下方输入）…</option>
          </select>
          {useCustomModel && (
            <input
              className="cli-input cli-model-custom"
              value={model}
              placeholder={`例如 ${customPlaceholder}`}
              disabled={busy}
              spellCheck={false}
              onChange={(e) => {
                setCustomMode(true)
                setModel(e.target.value)
                setTouched((t) => ({ ...t, model: true }))
              }}
            />
          )}
        </label>

        {/* 推理强度：只在 CLI 支持时渲染（claude 没有这个开关） */}
        {config.supportsReasoning && reasoningChoices.length > 0 && (
          <label className="cli-field">
            <span className="cli-field-label">
              推理强度
              <span className="cli-dim">{reasoningHint(config.reasoningStyle)}</span>
            </span>
            <select
              className="cli-select"
              value={reasoning}
              disabled={busy}
              onChange={(e) => {
                setReasoning(e.target.value)
                setTouched((t) => ({ ...t, reasoning: true }))
              }}
            >
              <option value="">Default</option>
              {reasoningChoices.map((r) => (
                <option key={r} value={r}>{capitalize(r)}</option>
              ))}
            </select>
          </label>
        )}

        <div className="cli-config-actions">
          <button className="cli-primary-btn" onClick={() => void save()} disabled={busy || !dirty}>
            {busy ? '保存中' : '保存'}
          </button>
          <span className={`cli-save-state${dirty ? ' is-dirty' : ''}`}>
            {dirty ? '有未保存的修改' : config.configured ? '已保存' : '使用 CLI 默认'}
          </span>
        </div>
      </div>

      {/* 高级：代理与自定义路径 */}
      <details className="cli-advanced">
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
          高级：代理与自定义路径
        </summary>
        <p className="cli-advanced-hint">
          覆盖该 CLI 的底层环境变量：API key、代理 base URL、自定义 home 或非标准可执行文件路径。
          不填时 CLI 用自己的默认配置。密钥只保存在本地配置中，并且只传给所选 CLI。
        </p>

        <div className="cli-advanced-grid">
          {config.configDirEnv && (
            <label className="cli-field">
              <span className="cli-field-label">
                {config.displayName} 配置目录
                <code className="cli-env-name">{config.configDirEnv}</code>
              </span>
              <input
                className="cli-input"
                placeholder="~/.codex-alt"
                disabled={busy}
                value={configDir}
                onChange={(e) => {
                  setConfigDir(e.target.value)
                  setTouched((t) => ({ ...t, configDir: true }))
                }}
              />
            </label>
          )}

          {config.binEnv && (
            <label className="cli-field">
              <span className="cli-field-label">
                可执行文件路径
                <code className="cli-env-name">{config.binEnv}</code>
              </span>
              <input
                className="cli-input"
                placeholder="/absolute/path/to/cli"
                disabled={busy}
                value={bin}
                onChange={(e) => {
                  setBin(e.target.value)
                  setTouched((t) => ({ ...t, bin: true }))
                }}
              />
            </label>
          )}

          {config.baseUrlEnv && (
            <label className="cli-field">
              <span className="cli-field-label">
                代理 base URL
                <code className="cli-env-name">{config.baseUrlEnv}</code>
              </span>
              <input
                className="cli-input"
                placeholder="https://your-proxy.example.com/v1"
                disabled={busy}
                value={baseUrl}
                onChange={(e) => {
                  setBaseUrl(e.target.value)
                  setTouched((t) => ({ ...t, baseUrl: true }))
                }}
              />
            </label>
          )}

          {config.apiKeyEnvs.map((envName) => (
            <label className="cli-field" key={envName}>
              <span className="cli-field-label">
                API key
                <code className="cli-env-name">{envName}</code>
              </span>
              <input
                className="cli-input"
                type="password"
                placeholder={
                  config.secrets[envName]
                    ? `已保存 ${config.secrets[envName]}（留空不修改）`
                    : `粘贴 ${envName}`
                }
                disabled={busy}
                value={secretDraft[envName] ?? ''}
                onChange={(e) => setSecretDraft((s) => ({ ...s, [envName]: e.target.value }))}
              />
            </label>
          ))}
        </div>

        {config.apiKeyEnvs.length === 0 && (
          <p className="cli-hint">
            该 CLI 的凭据跟 provider 走，请用它自己的登录流程（如 <code>/login</code>）配置。
          </p>
        )}

        {config.apiKeySet && (
          <div className="cli-advanced-actions">
            <button className="cli-link-btn" onClick={() => void clearSecrets()} disabled={busy}>
              清除已保存的 Key
            </button>
          </div>
        )}
      </details>
    </div>
  )
}

function reasoningHint(style: string): string {
  switch (style) {
    case 'CODEX_CONFIG':
      return 'model_reasoning_effort'
    case 'PI_THINKING':
      return '--thinking'
    case 'OPENCODE_VARIANT':
      return '--variant'
    default:
      return ''
  }
}

function capitalize(s: string): string {
  return s.length === 0 ? s : s[0].toUpperCase() + s.slice(1)
}

/** 卡片上的模型名只留后半段，避免长 provider 前缀把名字挤掉 */
function shortModel(m: string): string {
  const parts = m.split('/')
  return parts.length > 1 ? parts[parts.length - 1] : m
}

/** 路径过长时只显示尾部两段（完整值在 title 里） */
function shortPath(p: string): string {
  const parts = p.split(/[\\/]/)
  if (parts.length <= 2) return p
  return '…/' + parts.slice(-2).join('/')
}
