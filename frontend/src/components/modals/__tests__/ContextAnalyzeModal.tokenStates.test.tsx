// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ContextAnalyzeModal } from '../ContextAnalyzeModal'
import type { ContextAnalyzeResponse } from '@/api/types'

const { analyze } = vi.hoisted(() => ({ analyze: vi.fn() }))
vi.mock('@/api/context', () => ({ contextApi: { analyze } }))

/**
 * 「上下文分析」面板的四态渲染守护（(a1) 精确 / (a2)(a3) 估算 / (b) 不可用 / (c) 真 0）。
 *
 * <p><b>WHY（这是本次修复的根）</b>：后端原先把「算不出来」（`null`）抹成 `0`，于是 wire 上
 * 「端点缺失」与「真的是 0」字节相同 —— 面板显示一个**骗人的 0**，用户以为统计成功。
 * 后端修好只是第一步：**前端若仍把 null/0 渲染成一回事，用户看到的还是一样**。
 * 所以这里必须**真的渲染一次面板**，按四态逐条钉死可见输出（角标文案 / 悬停提示 / 破折号），
 * 而不是断言源码里有没有某个字符串。
 *
 * <p>用例来自用户裁定「方案 3：四态全可辨」：① 服务端真实计数不标；② 本地估算标「估算」；
 * ③ 算不出来显示「—」+「不可用」且⛔不显示 0；④ 真 0 照常显示 0。
 */

/** 基线响应 · 各用例只覆写自己关心的字段（其余保持「确切的 0」，不干扰断言）。 */
function baseResponse(over: Partial<ContextAnalyzeResponse> = {}): ContextAnalyzeResponse {
  return {
    systemPromptTokens: 0,
    systemPromptTokensSource: 'api',
    systemPromptSections: [],
    claudeMdTokens: 0,
    claudeMdTokensSource: 'api',
    memoryFiles: [],
    builtInToolTokens: 0,
    builtInToolTokensSource: 'api',
    mcpToolTokens: 0,
    mcpToolTokensSource: 'api',
    skills: null,
    categories: [],
    ...over,
  }
}

/** 顶部合计行（「系统提示词 … tokens」）—— 用例只在这一行内断言，避免跨行串味。 */
function totalRow(container: HTMLElement): HTMLElement {
  const el = container.querySelector<HTMLElement>('.ca-total')
  if (!el) throw new Error('未渲染 .ca-total（合计行）')
  return el
}

/**
 * 技能加载失败 + 分类节的 fixture（后端 `buildCategories` 的真实产物形态）。
 *
 * <p>⚠️ 这里**必须**有 `categories` 且其中**有 `tokens: null` 的行** —— 分类行不可用态只有在
 * 分类节真的渲染出行时才可达（原先前端用例固定 `categories: []`，结构上盖不到分类行，那种用例
 * 是恒绿的空壳，本轮复核专门点出过）。
 *
 * <p>成因由后端下发（`unavailableCause`）：技能清单读不到 ⇒ 技能行 = `local`、被同一个 null
 * 传染的扣减行 = `derived`；工具节的「内置工具」（全量）不受影响仍显示真数 300 ⇒ 正是用户看到
 * 「同名两行一真一假」的那一幕。
 */
function skillLoadFailureResponse(): ContextAnalyzeResponse {
  return baseResponse({
    builtInToolTokens: 300,
    builtInToolTokensSource: 'api',
    skills: { totalSkills: null, includedSkills: null, tokens: null, tokensSource: 'unavailable' },
    categories: [
      { name: '系统提示词', tokens: 1200, color: 'promptBorder', tokenSource: 'api' },
      { name: '内置工具（不含技能）', tokens: null, color: 'inactive', tokenSource: 'unavailable', unavailableCause: 'derived' },
      { name: 'MCP 工具', tokens: 40, color: 'cyan_FOR_SUBAGENTS_ONLY', tokenSource: 'api' },
      { name: '记忆文件', tokens: 9, color: 'claude', tokenSource: 'api' },
      { name: '技能', tokens: null, color: 'warning', tokenSource: 'unavailable', unavailableCause: 'local' },
    ],
  })
}

/** 取某一行（按行名定位）里「不可用」角标的悬停提示。 */
function unavailableTipOfRowNamed(label: string): string | null {
  const row = Array.from(container.querySelectorAll<HTMLElement>('.ca-row'))
    .find((r) => r.querySelector('.ca-name')?.textContent === label)
  if (!row) throw new Error(`未渲染名为「${label}」的行`)
  return row.querySelector<HTMLElement>('.ca-badge-na')?.getAttribute('title') ?? null
}

/**
 * 在**「分类」节内**按行名取「不可用」角标的悬停提示。
 *
 * <p>⚠️ 分类节里有与工具/记忆文件节**同名**的行（如「MCP 工具」）—— 按名字在全面板找会命中先渲染的
 * 工具节那一行（它显示真数、没有角标），断言会拿到 `null` 而误判。故限定在分类节内找。
 */
function categoryRowTipNamed(label: string): string | null {
  const section = Array.from(container.querySelectorAll<HTMLElement>('.ca-section'))
    .find((s) => s.querySelector('.ca-section-title')?.textContent === '分类')
  if (!section) throw new Error('未渲染「分类」节')
  const row = Array.from(section.querySelectorAll<HTMLElement>('.ca-row'))
    .find((r) => r.querySelector('.ca-name')?.textContent === label)
  if (!row) throw new Error(`分类节内未渲染名为「${label}」的行`)
  return row.querySelector<HTMLElement>('.ca-badge-na')?.getAttribute('title') ?? null
}

let container: HTMLDivElement
let root: Root

beforeEach(() => {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
})

afterEach(() => {
  act(() => { root.unmount() })
  container.remove()
})

/** 渲染面板并等 analyze() 的 promise 落地（面板从「分析中…」切到数据）。 */
async function renderPanel(data: ContextAnalyzeResponse): Promise<void> {
  analyze.mockResolvedValue(data)
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => { root.render(<ContextAnalyzeModal onClose={() => {}} />) })
  await act(async () => {}) // 让 mockResolvedValue 的 then 走完
  expect(container.textContent).not.toContain('分析中')
}

describe('ContextAnalyzeModal · 四态标注', () => {
  it('(a1) 服务端真实计数：数值原样、不标角标', async () => {
    await renderPanel(baseResponse({ systemPromptTokens: 1500, systemPromptTokensSource: 'api' }))

    const row = totalRow(container)
    expect(row.textContent).toBe('系统提示词 1.5k tokens')
    expect(row.querySelectorAll('.ca-badge').length).toBe(0)
  })

  it('(a2) 本地估算：数值旁标「估算」+ 悬停提示', async () => {
    await renderPanel(baseResponse({ systemPromptTokens: 1500, systemPromptTokensSource: 'estimate' }))

    const row = totalRow(container)
    expect(row.textContent).toBe('系统提示词 1.5k tokens估算')
    const badge = row.querySelector<HTMLElement>('.ca-badge')
    expect(badge?.textContent).toBe('估算')
    expect(badge?.getAttribute('title')).toBe('本地大致统计，和模型实际用量可能有出入')
  })

  it('(a3) 逐行生效：分节行与技能行各自带「估算」角标（不是只有合计行标）', async () => {
    await renderPanel(baseResponse({
      systemPromptSections: [{ name: 'System', tokens: 30, tokenSource: 'estimate' }],
      skills: { totalSkills: 2, includedSkills: 2, tokens: 40, tokensSource: 'estimate' },
    }))

    // 合计行是真 0（api）→ 不标；两条明细行各一个角标
    expect(totalRow(container).querySelectorAll('.ca-badge').length).toBe(0)
    expect(container.querySelectorAll('.ca-badge').length).toBe(2)
    expect(container.textContent).toContain('30估算')
    expect(container.textContent).toContain('40估算')
  })

  it('(b) 算不出来：显示「—」+「不可用」角标与提示，⛔ 不显示 0', async () => {
    await renderPanel(baseResponse({ systemPromptTokens: null, systemPromptTokensSource: 'unavailable' }))

    const row = totalRow(container)
    expect(row.querySelector('.ca-na')?.textContent).toBe('—')
    const badge = row.querySelector<HTMLElement>('.ca-badge-na')
    expect(badge?.textContent).toBe('不可用')
    expect(badge?.getAttribute('title')).toBe('当前模型提供商不提供精确计数，这段数字无法显示。')
    expect(row.textContent).not.toContain('0')
  })

  it('(c) 真 0：照常显示 0、无角标（估算来源的 0 也不加角标）', async () => {
    await renderPanel(baseResponse({ systemPromptTokens: 0, systemPromptTokensSource: 'api' }))
    expect(totalRow(container).textContent).toBe('系统提示词 0 tokens')
    expect(totalRow(container).querySelectorAll('.ca-badge').length).toBe(0)

    // 0 只可能来自「确实没有内容」的短路，是个确切值 —— 来源标成估算也不该挂角标（否则制造噪声）
    await act(() => { root.unmount() })
    container.remove()
    await renderPanel(baseResponse({ systemPromptTokens: 0, systemPromptTokensSource: 'estimate' }))
    expect(totalRow(container).textContent).toBe('系统提示词 0 tokens')
    expect(totalRow(container).querySelectorAll('.ca-badge').length).toBe(0)
  })

  it('面板文案：图例说明存在，且不出现内部术语/字段名', async () => {
    await renderPanel(baseResponse({
      builtInToolTokens: 100,
      mcpToolTokens: 200,
      categories: [{ name: '内置工具（不含技能）', tokens: 100, color: 'inactive', tokenSource: 'api' }],
    }))

    const text = container.textContent ?? ''
    expect(text).toContain('数字按段落分别统计，合计和模型实际用量可能有出入；「—」表示这项数字没能统计出来。')
    expect(text).toContain('内置工具')       // 工具节那行（全量）
    expect(text).toContain('内置工具（不含技能）') // 分类节扣减行（改名后）
    expect(text).toContain('MCP 工具')
    expect(text).toContain('分类')
    // ⛔ 内部术语/字段名/扣减实现细节不得出现在用户可见文案里
    for (const jargon of ['builtInToolTokens', 'mcpToolTokens', 'categories', '扣减值承载', '对齐 CC', 'count_tokens', 'fallback', 'provider', 'unavailableCause', 'derived']) {
      expect(text).not.toContain(jargon)
    }
  })

  it('分类行：类别名本地化后可原样渲染（后端「系统提示词/内置工具（不含技能）/MCP 工具/记忆文件/技能」，⛔ 不再中英混排）', async () => {
    await renderPanel(baseResponse({
      categories: [
        { name: '系统提示词', tokens: 100, color: 'promptBorder', tokenSource: 'api' },
        { name: 'MCP 工具', tokens: 200, color: 'cyan_FOR_SUBAGENTS_ONLY', tokenSource: 'api' },
      ],
    }))

    // 分类行按后端下发的名称渲染（前端不做映射/翻译，也不硬编码英文串）
    expect(container.textContent).toContain('系统提示词')
    expect(container.textContent).toContain('MCP 工具')
    expect(container.textContent).not.toContain('System tools')
  })

  it('(b) 技能加载失败：技能数未知 + 「— 不可用」，⛔ 不显示「技能数 0 / 0」的假 0', async () => {
    await renderPanel(baseResponse({
      // 后端：技能清单加载失败 → totalSkills/includedSkills/tokens 全为 null + tokensSource=unavailable
      skills: { totalSkills: null, includedSkills: null, tokens: null, tokensSource: 'unavailable' },
    }))

    const text = container.textContent ?? ''
    // 「连有几个技能都不知道」必须如实说，⛔ 不能拿 0 顶替（0 与「不可用」角标自相矛盾）
    expect(text).toContain('技能数未知')
    expect(text).not.toContain('技能数 0')
    expect(text).toContain('不可用')
    expect(text).toContain('—')
  })

  /**
   * 用户裁定「为技能行单独出一条文案」。
   *
   * <p><b>WHY</b>：技能行的数字**恒为本地粗估**（`round(len/4)`），**从不经过模型提供商** ——
   * 所以它显示「不可用」时，成因只可能是**本地**读不到技能清单（后端 countSkillTokens 的兜底分支
   * 是唯一致 null 的路径）。原先把通用的「当前模型提供商不提供精确计数」挂在技能行上，等于
   * **把用户指向一个结构上不可能的原因**。这条用例钉死：技能行的提示必须说「本地」，且⛔不出现那句
   * 归因于提供商的话。测试直接读 title 属性（悬停提示的真实载体），而不是只看正文文字。
   */
  it('(b) 技能行「不可用」：提示归因于本地，⛔ 不把原因推给提供商', async () => {
    await renderPanel(baseResponse({
      skills: { totalSkills: null, includedSkills: null, tokens: null, tokensSource: 'unavailable' },
    }))

    const rows = Array.from(container.querySelectorAll<HTMLElement>('.ca-row'))
    const skillRow = rows.find((r) => (r.textContent ?? '').includes('技能数未知'))
    if (!skillRow) throw new Error('未渲染技能行（应为「技能数未知」那一行）')

    const titles = Array.from(skillRow.querySelectorAll<HTMLElement>('[title]'))
      .map((el) => el.getAttribute('title') ?? '')
    expect(titles.length).toBeGreaterThan(0)
    for (const tip of titles) {
      expect(tip).not.toContain('当前模型提供商不提供精确计数')
      expect(tip).not.toContain('提供商')
      expect(tip).toContain('本地没能读到技能清单')
    }
  })

  it('技能行的专属提示不影响其它行：合计行（提供商侧成因）仍用通用「不可用」文案', async () => {
    await renderPanel(baseResponse({
      systemPromptTokens: null,
      systemPromptTokensSource: 'unavailable',
      skills: { totalSkills: null, includedSkills: null, tokens: null, tokensSource: 'unavailable' },
    }))

    // 合计行的成因在提供商侧（count_tokens 接口缺失/调用失败）⇒ 仍保留原通用文案，⛔ 不被技能行的文案顶掉
    const totalTip = totalRow(container).querySelector<HTMLElement>('.ca-badge-na')?.getAttribute('title')
    expect(totalTip).toBe('当前模型提供商不提供精确计数，这段数字无法显示。')
  })

  /**
   * 图例（LEGEND）的同类失真修正。
   *
   * <p><b>WHY</b>：同一个「—」在面板上有两类成因 —— 服务端计数接口缺失/调用失败（各段），
   * 以及**本地**读不到技能清单（技能行）。原图例把「—」一律说成「该提供商不提供精确计数」，
   * 与技能行那句是同一个失真（把用户指错方向）。现在图例只说「没能统计出来」，成因交给各行的提示。
   */
  it('图例的「—」说明不指向提供商（成因有两类，图例不替用户下结论）', async () => {
    await renderPanel(baseResponse({
      skills: { totalSkills: null, includedSkills: null, tokens: null, tokensSource: 'unavailable' },
    }))
    const legend = container.querySelector<HTMLElement>('.ca-legend')
    if (!legend) throw new Error('未渲染 .ca-legend（图例）')
    expect(legend.textContent)
      .toBe('数字按段落分别统计，合计和模型实际用量可能有出入；「—」表示这项数字没能统计出来。')
    expect(legend.textContent ?? '').not.toContain('提供商')
  })

  /**
   * 用户裁定「两行一起改」：技能加载失败时，分类节的「技能」行与「内置工具（不含技能）」行都会
   * 变成「—」，它们**不得**再弹把成因推给提供商的那句提示。
   *
   * <p><b>WHY</b>：技能 frontmatter token 恒为本地粗估（`round(len/4)`，全程无 HTTP）；
   * 「内置工具（不含技能）」行是扣减值（全量 − 技能），技能读不到时它跟着变 null —— 两行的
   * 不可用都**不只是**提供商的事。原先两行都弹通用文案「当前模型提供商不提供精确计数」，而同一屏
   * 的技能节行写着「本地没能读到技能清单」= 同一面板同一个数字两种互相矛盾的归因。
   *
   * <p>⚠️ fixture 必须能走到**分类行不可用态**（`categories: []` 那种结构上盖不到分类行，只会恒绿）。
   */
  it('分类行不可用：技能行与扣减行的提示都不指向提供商（各自按成因取文案）', async () => {
    await renderPanel(skillLoadFailureResponse())

    // 本 fixture 下只有三处「— 不可用」：技能节行 + 分类节的技能行 / 扣减行
    const badged = Array.from(container.querySelectorAll<HTMLElement>('.ca-badge-na'))
    expect(badged.length).toBe(3)
    for (const badge of badged) {
      const tip = badge.getAttribute('title') ?? ''
      expect(tip).not.toContain('当前模型提供商不提供精确计数')
      expect(tip).not.toContain('提供商')
    }
    // 两行按**成因**拿到各自的文案（local / derived），不是共用一句
    expect(unavailableTipOfRowNamed('技能')).toBe('本地没能读到技能清单，这段数字无法显示。')
    expect(unavailableTipOfRowNamed('内置工具（不含技能）')).toBe(
      '这项数字要用到技能和工具两边的统计，其中有部分没统计出来，所以无法显示。')
  })

  /**
   * 反向面：分类行里**成因确在提供商侧**的那行仍保留通用文案。
   *
   * <p><b>WHY</b>：本批次只改「技能」与「内置工具（不含技能）」两行（用户裁定「两行一起改」）——
   * 若为了「不出现提供商字样」而把通用文案也换掉，本来指向正确的那几行会一起失去信息。
   */
  it('分类行不可用：成因在提供商侧的行仍用通用「不可用」文案', async () => {
    await renderPanel(baseResponse({
      systemPromptTokens: null,
      systemPromptTokensSource: 'unavailable',
      categories: [
        { name: '系统提示词', tokens: null, color: 'promptBorder', tokenSource: 'unavailable', unavailableCause: 'provider' },
      ],
    }))

    expect(unavailableTipOfRowNamed('系统提示词')).toBe('当前模型提供商不提供精确计数，这段数字无法显示。')
  })

  /**
   * 用户裁定「一起修掉」（第 5 轮）：非 anthropic 提供商下，「系统提示词 / MCP 工具 / 记忆文件」
   * 三行**不走服务端计数**（走本地 tiktoken 估算）—— 它们显示「不可用」的成因是**本地估算出错**，
   * 面板不得再说「当前模型提供商不提供精确计数」（那是把用户指向一个结构上不可能的原因）。
   *
   * <p>⚠️ 也**不得**与技能行那句（`local`）共用：技能行说的是「读不到技能清单」，是**另一件事** ——
   * 两件事共用一句文案就重演了「同一句话指向错误的成因」这个病根本身。
   */
  it('分类行不可用：本地估算出错（localEstimate）的行不指向提供商，也不与技能行共用文案', async () => {
    await renderPanel(baseResponse({
      categories: [
        { name: '系统提示词', tokens: null, color: 'promptBorder', tokenSource: 'unavailable', unavailableCause: 'localEstimate' },
        { name: 'MCP 工具', tokens: null, color: 'cyan_FOR_SUBAGENTS_ONLY', tokenSource: 'unavailable', unavailableCause: 'localEstimate' },
        { name: '记忆文件', tokens: null, color: 'claude', tokenSource: 'unavailable', unavailableCause: 'localEstimate' },
        { name: '技能', tokens: null, color: 'warning', tokenSource: 'unavailable', unavailableCause: 'local' },
      ],
    }))

    const estimateTip = categoryRowTipNamed('记忆文件')
    expect(estimateTip).toBe('本地估算这次没算出来，这段数字无法显示。')
    // ⛔ 不把成因推给提供商
    expect(estimateTip).not.toContain('当前模型提供商不提供精确计数')
    expect(estimateTip).not.toContain('提供商')
    // ⛔ 不与技能行那句混用（技能行 = 读不到技能清单，是另一件事）
    expect(estimateTip).not.toContain('技能清单')
    expect(estimateTip).not.toBe(categoryRowTipNamed('技能'))
    // 三行拿的是同一条文案（同一件事只有一种说法）
    expect(categoryRowTipNamed('系统提示词')).toBe(estimateTip)
    expect(categoryRowTipNamed('MCP 工具')).toBe(estimateTip)
    // 技能行仍是它自己那句（本轮没有动它）
    expect(categoryRowTipNamed('技能')).toBe('本地没能读到技能清单，这段数字无法显示。')
  })

  /**
   * 用户裁定「只改分类节那行的名字」：工具节的「内置工具」（全量）与分类节的扣减行不再同名。
   *
   * <p><b>WHY</b>：技能未知时分类行显示「—」而工具节显示真数 ⇒ 同名两行一真一假，用户无法分辨
   * 哪个数才是他要的。改名只在**显示名**这一层，后端扣减语义不变（若让分类行改用全量值，
   * 技能那部分 token 会同时算进两行 = 重复计数）。
   */
  it('分类节扣减行改名：不再与工具节的「内置工具」同名（同名两行一真一假）', async () => {
    await renderPanel(skillLoadFailureResponse())

    const rowNames = Array.from(container.querySelectorAll<HTMLElement>('.ca-row .ca-name'))
      .map((el) => el.textContent ?? '')

    // 工具节那行仍是全量「内置工具」（不受技能未知影响，照常显示真数）
    expect(rowNames).toContain('内置工具')
    // 分类节那行换了名（扣减值），两行不再同名
    expect(rowNames).toContain('内置工具（不含技能）')
    expect(rowNames.filter((n) => n === '内置工具').length).toBe(1)
  })
})
