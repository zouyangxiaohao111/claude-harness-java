import { describe, expect, it } from 'vitest'
import { TOKEN_WARNING_TEXT, resolveCtxInfo, tokenWarningBannerText } from '../contextUsage'
import type { TokenWarningEvent } from '../../api/types'

/**
 * 上下文统计**口径统一**的纯函数回归（[P3-d] 后端「快照口径唯一权威」的前端配合项）。
 *
 * <p>WHY（为什么非测不可）：后端有两套百分比 —— 快照口径（服务端真实 usage + 模型原始窗口 + 窗口
 * 相对百分比，唯一权威）与阈值相对口径（`token_warning.percentLeft` 分母是 autoCompactThreshold，
 * `token_warning.tokenUsage` 还是本地估算）。前端原实现把后者当「剩余 %」写在 Composer 上下文条 /
 * 用量弹窗 / 告警横幅三处，且与快照口径**回落到同一个位置** —— 两套异源数值交替显示（同一位置忽大
 * 忽小），/compact 后更会拿压缩前的阈值估算顶上来（数字不降）。本组用例把「只认快照口径、无快照即
 * 不显示」这条不变式钉住。
 *
 * <p>RED 条件（改回旧口径时哪条断言会红）：
 * <ul>
 *   <li>把 {@link resolveCtxInfo} 的扫描方向改成正序（取最早一条）→ 「取最新一条快照」红（会拿到
 *       压缩前那条旧 assistant 的 used）；</li>
 *   <li>去掉 `contextTokensUsed != null && contextWindow != null` 齐备检查 → 「跳过无快照的消息 / 空
 *       列表 → null」红（半截快照被当成有效值）；</li>
 *   <li>在无快照时回落 `token_warning`（used=tokenUsage/window=contextWindow）→ 「无快照 → null，
 *       不回落阈值口径」红；</li>
 *   <li>把 {@link tokenWarningBannerText} 改回按 `percentLeft` 拼「剩余 N%」→ 「文案不随 token_warning
 *       的阈值相对百分比 / 本地估算变化」红。</li>
 * </ul>
 */

describe('resolveCtxInfo · 快照口径（唯一权威）', () => {
  it('从尾向前取最新一条完整快照（WHY：扫描源 [...msgs, ...liveBlocks] 的 live 块在尾部，压缩前那条旧 assistant 必须被后面这条盖掉）', () => {
    const info = resolveCtxInfo([
      { contextTokensUsed: 180000, contextWindow: 200000, percentLeft: 10 },  // 压缩前旧 assistant
      null,                                                                   // 非 assistant / 无快照消息
      { contextTokensUsed: 1200, contextWindow: 200000, percentLeft: 99 },    // 最新（本轮）
    ])
    expect(info).toEqual({ used: 1200, window: 200000, pct: 99 })
  })

  it('跳过缺字段的快照（WHY：只有 used+window 齐备才是可展示的一条，半截数据不得当成有效值）', () => {
    const info = resolveCtxInfo([
      { contextTokensUsed: 500, contextWindow: 200000, percentLeft: 99 },
      { contextTokensUsed: 900, contextWindow: null },   // 窗口缺失 → 跳过
      { contextTokensUsed: null, contextWindow: 200000 }, // used 缺失 → 跳过
      null,
      undefined,
    ])
    expect(info).toEqual({ used: 500, window: 200000, pct: 99 })
  })

  it('percentLeft 缺省时返回 null（WHY：百分比可选，缺省不得臆造 0 —— 展示侧按「无百分比」渲染）', () => {
    expect(resolveCtxInfo([{ contextTokensUsed: 1000, contextWindow: 200000 }]))
      .toEqual({ used: 1000, window: 200000, pct: null })
  })

  it('无任何快照 → null（RED：/compact 后 boundary 之后无带 usage 的 assistant，回落 token_warning 阈值口径即红）', () => {
    expect(resolveCtxInfo([])).toBeNull()
    expect(resolveCtxInfo([null, undefined])).toBeNull()
    expect(resolveCtxInfo([
      { contextTokensUsed: null, contextWindow: null, percentLeft: null },
      { contextTokensUsed: undefined, contextWindow: undefined },
    ])).toBeNull()
  })
})

describe('tokenWarningBannerText · 阈值告警只给文字不给数字', () => {
  const base: TokenWarningEvent = { type: 'token_warning', sessionId: 'sess-1', suppressed: false }

  it('文案不随 token_warning 的阈值相对百分比 / 本地估算 tokenUsage 变化（WHY：那是阈值口径+本地估算，当「剩余 %」渲染与快照口径数值对不上）', () => {
    const a = tokenWarningBannerText({ ...base, tokenUsage: 150000, contextWindow: 200000, percentLeft: 25 })
    const b = tokenWarningBannerText({ ...base, tokenUsage: 190000, contextWindow: 200000, percentLeft: 5 })
    expect(a).toBe(TOKEN_WARNING_TEXT)
    expect(b).toBe(TOKEN_WARNING_TEXT)
    // 文案里不得出现任何数字百分比（旧实现 `上下文剩余 ${pct}%`）
    expect(a).not.toMatch(/\d+\s*%/)
  })

  it('无告警 / 压缩成功（suppressed）→ 不渲染（WHY：suppressed=true 是后端压缩成功的抑制态，横幅必须消失）', () => {
    expect(tokenWarningBannerText(null)).toBeNull()
    expect(tokenWarningBannerText(undefined)).toBeNull()
    expect(tokenWarningBannerText({ ...base, suppressed: true })).toBeNull()
  })
})
