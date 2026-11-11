import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * 文档助手面板：从「一问一答单气泡」改为「多轮对话」。
 *
 * <h2>用户要求</h2>
 * 「知识库 助手那块我认为需要采用我们现有的主会话的对话形式 复刻过去即可」
 *
 * <h2>原实现的缺陷（这次要锁死的）</h2>
 * 状态是扁平的 `question / answer / phase`，于是：
 * <ol>
 *   <li>`ask()` 开头 `setAnswer('')` ⇒ **上一轮的答案被清空**，用户看不到历史</li>
 *   <li>只有一个气泡 ⇒ 多轮里分不清哪句话是哪一轮的回答</li>
 * </ol>
 *
 * <h2>为什么用源码级断言而不是渲染测试</h2>
 * 这个组件的多轮正确性取决于两件**结构事实**，都在源码里能精确判定：
 * <ul>
 *   <li>状态是数组（`Turn[]`）而不是单个字符串 ⇒ 历史天然不被覆盖</li>
 *   <li>流式增量按 `activeTurnRef` **定位到具体轮**，而不是「最后一轮」
 *       ⇒ 用户在两轮之间切换时不会写错气泡</li>
 * </ul>
 * 前者用 `setAnswer('')` 这种字面写法就能反向证伪；后者靠「有没有把 turn id 传进
 * setTurns 的 map 判据」判定。渲染层测试对这两点判别力反而更差（要 mock STOMP）。
 */
const PANEL = fileURLToPath(new URL('../KbAssistantPanel.tsx', import.meta.url))

function src(): string {
  return readFileSync(PANEL, 'utf8')
}

describe('KbAssistantPanel · 多轮对话', () => {
  it('状态是轮次数组，不是单个 answer 字符串', () => {
    const s = src()

    expect(s).toContain('interface Turn')
    expect(s).toContain('const [turns, setTurns] = useState<Turn[]>')
    // ⛔ 扁平单答案状态必须消失：它就是「上一轮被清空」的根因
    expect(s).not.toMatch(/const \[answer, setAnswer\]/)
  })

  it('问一轮时把新轮 push 进数组（历史保留），而不是覆盖', () => {
    const s = src()

    // 新增一轮：append 到数组尾部
    expect(s).toMatch(/setTurns\(\(prev\) => \[\.\.\.prev,/)
    // 清空只允许发生在「新会话」，⛔ 不能在每次提问时清空
    expect(s).not.toMatch(/setTurns\(\[\]\)\s*[\s\S]{0,200}void ask/)
  })

  it('⭐ 流式增量按 turn id 定位，⛔ 不写进「最后一轮」', () => {
    const s = src()

    // 必须有 activeTurnRef 记录进行中的那一轮
    expect(s).toContain('activeTurnRef')
    // 累加判据：map 里比较 id，而不是取最后一个元素
    expect(s).toMatch(/x\.id === turnId/)
    // 反向：不能出现「改最后一个元素」这种写法
    expect(s).not.toMatch(/prev\[prev\.length - 1\]/)
  })

  it('迟到的流式事件不会写进别的轮（无进行中轮时直接丢弃）', () => {
    const s = src()

    expect(s).toMatch(/if \(turnId == null\)/)
    // 丢弃时要有注释说明原因（可维护性：后人别误以为漏处理）
    expect(s).toContain('迟到事件')
  })

  it('渲染复用主会话同款 qa-thread / qa-turn / qa-q / qa-a 类（不新造类名）', () => {
    const s = src()

    expect(s).toContain('className="qa-thread"')
    expect(s).toContain('className="qa-turn"')
    expect(s).toContain('className="qa-q"')
    expect(s).toContain('className="qa-a"')
    // ⛔ 自造类名没有样式，会渲染成裸 div。
    // ⚠️ 只查 className= 的实际用法，不查全文 —— 注释里会写「⛔ 不自造 assistant-thread」，
    //    全文匹配会把这条说明也当成违规（本仓踩过同类假红）。
    expect(s).not.toContain('className="assistant-thread')
    expect(s).not.toContain('className="assistant-bubble')
  })

  it('答案走 MarkdownTextV2（源站流水线 + 流式守卫），不是纯文本直出', () => {
    const s = src()

    // 2026-11-11：从 V1（本仓自研）切到 V2（源站流水线移植版）。
    // V2 的关键差异是**流式期也渲染 markdown**，靠 streamingGuards 兜未闭合语法。
    expect(s).toContain("from '@/markdown/vendor/renderer'")
    expect(s).toContain('<MarkdownTextV2')
    // 流式标记必须传给渲染器，否则守卫不生效
    expect(s).toContain("streaming={t.phase === 'generating'}")
  })

  it('每轮独立渲染：question 与 answer 都在同一 turn 里成对出现', () => {
    const s = src()

    expect(s).toContain('{t.question}')
    expect(s).toContain('t.answer')
  })

  it('有进行中的轮时拒绝新提问（避免两条流交错写进同一个 turn）', () => {
    const s = src()

    expect(s).toMatch(/if \(activeTurnRef\.current != null\) return/)
  })

  it('「新会话」按钮清空历史并重置 WS（不是只清输入框）', () => {
    const s = src()

    expect(s).toContain('resetThread')
    // 重置要包含：断开 WS、清 session、清轮次
    const resetBlock = s.slice(s.indexOf('const resetThread'))
    expect(resetBlock).toContain('deactivate()')
    expect(resetBlock).toContain('sessionRef.current = null')
    expect(resetBlock).toContain('setTurns([])')
  })

  it('空态仍显示三条引导语（首屏不被多轮改造破坏）', () => {
    const s = src()

    expect(s).toContain('const empty = turns.length === 0')
    expect(s).toContain('assistant-starter')
    expect(s).toContain('STARTERS.map')
  })
})
