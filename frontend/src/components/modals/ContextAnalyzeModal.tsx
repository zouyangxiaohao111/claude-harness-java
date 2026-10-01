import { useEffect, useState } from 'react'
import { contextApi } from '@/api/context'
import type { ContextAnalyzeResponse, ContextCategoryUnavailableCause, ContextTokenSource } from '@/api/types'

export const fmt = (n: number) => (n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n))

/**
 * 提示文案（⛔ 禁内部术语：不出现「对齐 CC」「count_tokens」「fallback」「provider」等）。
 * ⚠️ 措辞基线：本仓一律写「**提供商**」，不写「服务商 / 供应商」。
 */
const ESTIMATE_TIP = '本地大致统计，和模型实际用量可能有出入'
const UNAVAILABLE_TIP = '当前模型提供商不提供精确计数，这段数字无法显示。'

/**
 * 技能行专用的「不可用」提示（用户裁定「为技能行单独出一条文案」）。
 *
 * <p>⛔ 不能沿用 {@link UNAVAILABLE_TIP}：那句把成因归给<b>提供商</b>，而技能行的数字
 * <b>恒为本地粗估</b>（`round(len/4)`，见后端 `SkillsLoader.estimateSkillFrontmatterTokens`），
 * <b>根本不经过提供商</b>。技能行显示「不可用」的成因只有一条：本地没能解析出技能清单
 * （后端 `ContextAnalyzeService.countSkillTokens` 的兜底分支，唯一致 null 的路径）。
 * 沿用通用文案 = 把用户指向一个不可能的原因。
 */
const SKILL_UNAVAILABLE_TIP = '本地没能读到技能清单，这段数字无法显示。'

/**
 * 「本地估算出错」行专用的「不可用」提示（系统提示词 / MCP 工具 / 记忆文件三行，非 anthropic 提供商下）。
 *
 * <p><b>WHY</b>：这三行原先一律弹 {@link UNAVAILABLE_TIP}，而那只有在**真的走服务端计数**时才成立。
 * 非 anthropic 提供商走的是**本地估算**（`OpenAICountTokensClient` 的 tiktoken，全程无 HTTP）——
 * 这时三行显示「不可用」的原因是**本地估算自己出错**，与提供商毫无关系。沿用通用文案 = 把用户指向
 * 一个结构上不可能的原因（用户裁定「一起修掉」，正是本轮修的那条病灶换到了别的行）。
 *
 * <p>⛔ 与 {@link SKILL_UNAVAILABLE_TIP} 分开：那句说的是「读不到技能清单」，是**另一件事** ——
 * 两件事共用一个成因值/一句文案，就会重演「同一句话指向错误的成因」。
 */
const LOCAL_ESTIMATE_UNAVAILABLE_TIP = '本地估算这次没算出来，这段数字无法显示。'

/**
 * 分类节**扣减/合成**行（「内置工具（不含技能）」）专用的「不可用」提示。
 *
 * <p>该行的数字 = 内置工具全量 − 技能 frontmatter（后端 `buildCategories`），技能清单读不到时它跟着
 * 变成「—」—— 和技能行是同一个**本地**成因，此时若仍弹 {@link UNAVAILABLE_TIP}（把成因推给提供商），
 * 同一面板同一个数字就会出现两种互相矛盾的归因（用户裁定「两行一起改」）。
 * 而它也可能反过来因提供商侧（工具计数失败）不可用 ⇒ 文案**不归因到任何一侧**，只说清这项数字要用到
 * 哪两边的统计、其中有部分没出来。
 */
const DERIVED_UNAVAILABLE_TIP = '这项数字要用到技能和工具两边的统计，其中有部分没统计出来，所以无法显示。'

/**
 * 分类行「不可用」提示 · 按后端下发的**成因**选文案（{@link ContextCategoryUnavailableCause}）。
 *
 * <p>⛔ 不按 `categories[].name` 匹配：名字是显示文案（后端本地化过、日后可能再改），拿它当键会让
 * 「改个译名就静默换错文案」；成因是与显示名解耦的机器值。
 */
function categoryUnavailableTip(cause?: ContextCategoryUnavailableCause | null): string {
  if (cause === 'provider') return UNAVAILABLE_TIP
  // 本地成因①：读不到技能清单 ⇒ 与技能行共用同一条文案（同一件事只有一种说法）
  if (cause === 'local') return SKILL_UNAVAILABLE_TIP
  // 本地成因②：本地估算出错（非 anthropic 提供商的系统提示词 / MCP 工具 / 记忆文件三行）
  //   ⛔ 不走上面那句：那句说的是「读不到技能清单」，是另一件事
  if (cause === 'localEstimate') return LOCAL_ESTIMATE_UNAVAILABLE_TIP
  // 'derived'（扣减/合成行），以及成因缺失/未知（旧版后端 / 后端新增了成因值）：
  // 都走不归因文案 —— ⛔ 宁可少说，不可把用户指向一个结构上不可能的原因
  return DERIVED_UNAVAILABLE_TIP
}

/**
 * 面板顶部说明（三类图例）· 说明「按段落分别统计」的合计口径 + 「—」的含义。
 *
 * <p>⚠️ 「—」这句话<b>不写成因</b>（原先写「该提供商不提供精确计数」已失真）：同一个「—」
 * 在面板上有三类成因 —— 服务端计数接口缺失/调用失败（仅实际走服务端计数的段）、
 * <b>本地</b>读不到技能清单（技能行，见 {@link SKILL_UNAVAILABLE_TIP}）、
 * 以及<b>本地</b>估算出错（非 anthropic 提供商的系统提示词/记忆文件/工具各行，见
 * {@link LOCAL_ESTIMATE_UNAVAILABLE_TIP}）。把「—」一律说成提供商的事，会让后两类成因被指错方向。
 * 具体成因由各行的悬停提示给。
 */
const LEGEND = '数字按段落分别统计，合计和模型实际用量可能有出入；「—」表示这项数字没能统计出来。'

/**
 * token 数值出口 · **四态**渲染。
 *
 * <p>四态来自后端两个维度（{@code tokenSource} × 数值是否为 null）：
 * <ol>
 *   <li>(a1) 服务端真实计数 —— 数值原样，**不标**；</li>
 *   <li>(a2)/(a3) 本地估算 —— 数值旁小角标「估算」；</li>
 *   <li>(b) 算不出来（{@code tokens === null}）—— 数值位显示「—」+ 角标「不可用」，
 *       ⛔ **不显示 0**（后端原先把 null 抹成 0，用户会以为统计成功）；</li>
 *   <li>(c) 真 0 —— 照常显示 0，无角标。</li>
 * </ol>
 *
 * <p><b>为什么 0 一律不加角标</b>：0 只可能来自「确实没有内容」的短路，是个确切值；
 * 给它挂「估算」只会制造噪声、也让 (c) 与 (a)<i> 混淆。
 *
 * <p>{@code unit}（如 " tokens"）只在有数值时追加，避免「— tokens」这种残缺读法。
 *
 * <p><b>{@code unavailableTip}（(b) 态的悬停提示）可覆写</b>：默认是通用文案
 * {@link UNAVAILABLE_TIP}（成因在提供商侧）；成因确实在<b>本地</b>的行必须传自己的文案
 * （技能行 {@link SKILL_UNAVAILABLE_TIP}、本地估算出错的三行 {@link LOCAL_ESTIMATE_UNAVAILABLE_TIP}），
 * 否则会把用户指向一个不可能的原因。
 */
export function TokenCell({ tokens, tokenSource, unit = '', unavailableTip = UNAVAILABLE_TIP }: {
  tokens: number | null | undefined
  tokenSource?: ContextTokenSource
  unit?: string
  /** (b) 算不出来时的悬停提示 · 默认 {@link UNAVAILABLE_TIP}；成因在本地时传专属文案 */
  unavailableTip?: string
}) {
  if (tokens == null) {
    return (
      <span className="ca-tokens">
        <span className="ca-na" title={unavailableTip}>—</span>
        <span className="ca-badge ca-badge-na" title={unavailableTip}>不可用</span>
      </span>
    )
  }
  const estimated = tokenSource === 'estimate' && tokens > 0
  return (
    <span className="ca-tokens">
      {fmt(tokens)}{unit}
      {estimated && <span className="ca-badge" title={ESTIMATE_TIP}>估算</span>}
    </span>
  )
}

/** /context analyze 展示 · 对齐 CC analyzeContextUsage 分类计数（system/memory/tools） */
export function ContextAnalyzeModal({ onClose }: { onClose: () => void }) {
  const [data, setData] = useState<ContextAnalyzeResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    contextApi.analyze()
      .then((d) => { if (!cancelled) setData(d) })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  return (
    <div className="ca-backdrop" onClick={onClose}>
      <div className="ca-modal" onClick={(e) => e.stopPropagation()}>
        <div className="ca-header">
          <span className="ca-title">上下文分析</span>
          <button className="ca-close" onClick={onClose} aria-label="关闭">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5" style={{ width: 12, height: 12 }}>
              <path d="M2 2L10 10M10 2L2 10" />
            </svg>
          </button>
        </div>
        <div className="ca-body">
          {loading ? (
            <div className="right-empty">分析中…</div>
          ) : error ? (
            <div className="right-empty" style={{ color: 'var(--error)' }}>分析失败：{error}</div>
          ) : data ? (
            <>
              <div className="ca-total">
                系统提示词 <b><TokenCell tokens={data.systemPromptTokens} tokenSource={data.systemPromptTokensSource} unit=" tokens" /></b>
              </div>
              <div className="ca-legend">{LEGEND}</div>
              <div className="ca-section">
                <div className="ca-section-title">系统提示词分节</div>
                {(data.systemPromptSections ?? []).map((s) => (
                  <div key={s.name} className="ca-row">
                    <span className="ca-name">{s.name}</span>
                    <TokenCell tokens={s.tokens} tokenSource={s.tokenSource} />
                  </div>
                ))}
                {(data.systemPromptSections ?? []).length === 0 && <div className="right-empty">无分节数据</div>}
              </div>
              <div className="ca-section">
                <div className="ca-section-title">CLAUDE.md</div>
                <div className="ca-row">
                  <span className="ca-name">CLAUDE.md</span>
                  <TokenCell tokens={data.claudeMdTokens} tokenSource={data.claudeMdTokensSource} />
                </div>
              </div>
              <div className="ca-section">
                <div className="ca-section-title">记忆文件</div>
                {(data.memoryFiles ?? []).map((f) => (
                  <div key={f.path} className="ca-row">
                    <span className="ca-path">{f.path} <em>{f.type}</em></span>
                    <TokenCell tokens={f.tokens} tokenSource={f.tokenSource} />
                  </div>
                ))}
                {(data.memoryFiles ?? []).length === 0 && <div className="right-empty">无记忆文件</div>}
              </div>
              <div className="ca-section">
                <div className="ca-section-title">工具</div>
                <div className="ca-row">
                  <span className="ca-name">内置工具</span>
                  <TokenCell tokens={data.builtInToolTokens} tokenSource={data.builtInToolTokensSource} />
                </div>
                <div className="ca-row">
                  <span className="ca-name">MCP 工具</span>
                  <TokenCell tokens={data.mcpToolTokens} tokenSource={data.mcpToolTokensSource} />
                </div>
              </div>
              {data.categories && data.categories.length > 0 && (
                <div className="ca-section">
                  <div className="ca-section-title">分类</div>
                  {data.categories.map((c) => (
                    <div key={c.name} className="ca-row">
                      <span className="ca-name" style={{ color: c.color ?? undefined }}>{c.name}</span>
                      {/* 不可用提示按后端下发的**成因**选（⛔ 不按类别名匹配，见 categoryUnavailableTip） */}
                      <TokenCell tokens={c.tokens} tokenSource={c.tokenSource}
                        unavailableTip={categoryUnavailableTip(c.unavailableCause)} />
                    </div>
                  ))}
                </div>
              )}
              {data.skills && (
                <div className="ca-section">
                  <div className="ca-section-title">技能</div>
                  <div className="ca-row">
                    {/* 技能数未知（清单加载失败）时⛔不渲染「技能数 0 / 0」：
                        那是与右侧「— 不可用」自相矛盾的假 0（0 说「确实没有」，角标说「不知道」）。 */}
                    <span className="ca-name">
                      {data.skills.totalSkills == null
                        ? '技能数未知'
                        : `技能数 ${data.skills.totalSkills} / ${data.skills.includedSkills}`}
                    </span>
                    {/* 技能行的「不可用」成因在**本地**（读不到技能清单），⛔ 不用默认那句归给提供商的文案 */}
                    <TokenCell tokens={data.skills.tokens} tokenSource={data.skills.tokensSource} unavailableTip={SKILL_UNAVAILABLE_TIP} />
                  </div>
                </div>
              )}
            </>
          ) : null}
        </div>
      </div>
    </div>
  )
}
