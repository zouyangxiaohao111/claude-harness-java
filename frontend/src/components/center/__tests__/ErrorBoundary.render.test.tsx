// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ErrorBoundary } from '@/components/common/ErrorBoundary'
import { MessageList } from '../MessageList'
import { useChatStore } from '@/stores/chatStore'
import type { ChatMessageDto } from '@/api/types'

/**
 * OBS2 · 渲染错误边界的**真实渲染**守护（顶层兜底 + 逐行隔离）。
 *
 * <p><b>WHY（这是「接线拆掉测试全绿」的第 4 次预防）</b>：`ErrorBoundary` 是「整屏静止」这类事故的
 * 防线 —— 用户刚刚经历过一次「前端整屏停止更新」。而边界的失效方式**全部是静默的**：
 * `componentDidCatch` 写错（不上报）→ 照样渲染兜底 UI，看不出问题；逐行包裹被删/漏包一行 →
 * 照样编译通过、照样显示消息，只有**真有一条消息毒**时才会连带炸掉整屏。
 * 本仓前三次同类缺口（F1 窗口钩子 / U1 spawn_blocking / OBS1 main.tsx 安装）都是「拆掉接线无人知晓」，
 * 故这里必须**真的渲染一次**，而不是断言源码里有没有那个字符串。
 *
 * <p><b>两条断言各自的意图</b>：
 * <ol>
 *   <li><b>顶层兜底</b>：子组件抛错 → 渲染兜底 UI 且**异常不外泄**（`onUncaughtError` 不触发
 *       = 根容器仍有内容，而不是整棵树被卸载成空白）。同时断言 `componentDidCatch` 真的上报了 ——
 *       「渲染了兜底 UI」与「上报了错误」是边界的**两件**职责，只做前者等于日志里仍然零留痕。</li>
 *   <li><b>逐行隔离</b>：3 条消息、中间一条的正文渲染器抛错 → **另外 2 条必须照常渲染**。
 *       这是「毒一条只挂一条」这个设计意图的唯一验证；把逐行包裹删掉后，本用例必红
 *       （错误会冒到上一层，整块消息区被替换成兜底 UI，幸存者消失）。</li>
 * </ol>
 *
 * <p><b>毒的注入方式</b>：mock `@/markdown/MarkdownText`，让正文含标记的那一条在**渲染期**抛错。
 * 挑正文渲染器这一层是因为它正是真实世界里「某一条消息毒」的入口（markdown 增量解析 / 高亮 /
 * 引用解析是最复杂、最易炸的渲染路径）。⛔ 不引入任何新依赖（只用 `react-dom/client` + `act`）。
 */

/** 注入毒的正文标记。 */
const POISON = 'OBS2-POISON-MARKER'

vi.mock('@/markdown/MarkdownText', () => ({
  MarkdownText: ({ text }: { text: string }) => {
    if (text.includes(POISON)) throw new Error(`注入的渲染毒：正文含 ${POISON}`)
    return <div className="md-mock">{text}</div>
  },
}))

// ⛔ 上报出口 mock：ErrorBoundary.componentDidCatch → reportFrontendError → invoke。
//   断言「边界不只是渲染了兜底 UI，还真的把错误送进了日志通道」。
const invokeMock = vi.hoisted(() =>
  vi.fn((_cmd: string, _payload?: { tag?: string; msg?: string }): Promise<void> => Promise.resolve()),
)
vi.mock('@tauri-apps/api/core', () => ({ invoke: invokeMock }))

/** invoke 是 fire-and-forget，等它落到 mock 上。 */
async function flush(): Promise<void> {
  await new Promise((r) => setTimeout(r, 0))
}

/** 渲染期必抛的子组件（不带 state，每次渲染都抛）。 */
function Boom(): never {
  throw new Error('OBS2 根级兜底注入毒')
}

/**
 * 持续抛错，直到测试显式把 {@link retryPoison} 置 false —— 用于验证「重试」确实恢复了子树。
 *
 * <p>⚠️ 不能写成「只抛一次」：React 在并发渲染里遇到错误会**同步重渲整个根**再试一次，
 * 那种写法第二次就成功了 ⇒ 兜底 UI 从没出现过（实测踩到），测出来的不是「重试」而是 React 的自动恢复。
 */
let retryPoison = true
function PoisonUntilCleared() {
  if (retryPoison) throw new Error('OBS2 持续注入毒')
  return <div className="recovered-ok">已恢复 OBS2-RECOVERED</div>
}

/** 最小 ChatMessageDto（与 MessageList.scroll.test.tsx 同款，避免重复造全字段）。 */
function baseMsg(id: string, role: 'user' | 'assistant', sessionId: string, content: string): ChatMessageDto {
  return {
    id, sessionId, role, author: role === 'user' ? '你' : 'nexus', content,
    reasoning: null, toolCalls: null, finishReason: null, inputTokens: null, outputTokens: null,
    reasoningDurationMs: null, time: null, toolCallId: null, assistantMessageId: null,
    userMessageId: null, subtype: null, isMeta: false, isApiErrorMessage: false, apiError: null,
    error: null, errorDetails: null, matchedRule: null,
  }
}

describe('OBS2 · ErrorBoundary 真实渲染（顶层兜底 + 逐行隔离）', () => {
  let container: HTMLDivElement
  let root: Root
  let uncaught: unknown[]
  let caught: unknown[]
  let recoverable: unknown[]

  beforeEach(() => {
    ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
    retryPoison = true
    invokeMock.mockClear()
    uncaught = []
    caught = []
    recoverable = []
    container = document.createElement('div')
    document.body.appendChild(container)
    // 记录根级错误去向：onUncaughtError 触发 = 异常冒到了根（边界没接住）；
    // onCaughtError 触发 = 被边界接住（React 19 语义）。
    // onRecoverableError 必须接管：React 遇到并发渲染期错误会「同步重渲整个根」重试一次，
    //   该通知默认会被 vitest 当成 unhandled error 报出来（实测踩到）—— 在应用里它由
    //   main.tsx 的 createRoot 回调送进 frontLog，这里同样显式接收，不靠「测试环境恰好不报」。
    root = createRoot(container, {
      onUncaughtError: (e) => { uncaught.push(e) },
      onCaughtError: (e) => { caught.push(e) },
      onRecoverableError: (e) => { recoverable.push(e) },
    })
  })

  afterEach(() => {
    act(() => { root.unmount() })
    container.remove()
  })

  it('子组件抛错 → 渲染兜底 UI 且异常不外泄；且 componentDidCatch 真的上报了', async () => {
    await act(async () => {
      root.render(
        <ErrorBoundary tag="obs2.test.root" title="界面显示出错了" hint="点「重试」重新渲染。">
          <Boom />
        </ErrorBoundary>,
      )
    })

    // 兜底 UI 出现（根容器**仍有内容**，而不是被卸载成空白）
    expect(container.textContent).toContain('界面显示出错了')
    expect(container.textContent).toContain('点「重试」重新渲染。')
    expect(container.querySelector('.err-boundary-retry')).not.toBeNull()
    // 异常没冒到根 —— 这正是「边界接住了」的判据；若边界没生效，React 会卸载整棵树并走 uncaught
    expect(uncaught, '异常冒到了根 = 边界形同虚设（整棵树会被卸载）').toHaveLength(0)
    expect(caught, 'React 19 语义：被边界捕获应走 onCaughtError').toHaveLength(1)

    // 边界的第二件职责：上报。只渲染兜底 UI 不上报 = 日志里依然零留痕，等于没这层防线
    await flush()
    expect(
      invokeMock.mock.calls.some((c) => c[0] === 'frontend_log' && c[1]?.tag === 'obs2.test.root'),
      'componentDidCatch 必须把错误送进 frontLog（tag 用边界自己的 tag）—— 否则「渲染炸了」在日志里查不到',
    ).toBe(true)
  })

  it('点「重试」→ 清错误态重新渲染子树（兜底 UI 不是死路）', async () => {
    retryPoison = true
    await act(async () => {
      root.render(
        <ErrorBoundary tag="obs2.test.retry" title="这段内容显示失败了" hint="其余内容不受影响。">
          <PoisonUntilCleared />
        </ErrorBoundary>,
      )
    })
    expect(container.textContent).toContain('这段内容显示失败了')

    const btn = container.querySelector('.err-boundary-retry') as HTMLButtonElement | null
    expect(btn).not.toBeNull()
    // 毒源解除 → 点「重试」应清错误态并让子树重新渲染（而不是永远停在兜底 UI）
    retryPoison = false
    await act(async () => { btn!.click() })

    expect(container.textContent).toContain('OBS2-RECOVERED')
    expect(container.textContent).not.toContain('这段内容显示失败了')
  })

  it('逐行边界：3 条消息中间一条毒 → 只挂那一条，另外两条照常渲染（毒一条只挂一条）', async () => {
    const SID = 'sess-obs2-row'
    const msgs = [
      baseMsg('m1', 'assistant', SID, '第一条正文 OBS2-ALIVE-1'),
      baseMsg('m2', 'assistant', SID, `中间这条是毒 ${POISON}`),
      baseMsg('m3', 'assistant', SID, '第三条正文 OBS2-ALIVE-3'),
    ]
    // 每条 userMessageId 均为 null → 分组键回落 m.id → 3 条各自成组 → 恰好 3 行
    useChatStore.setState({
      messages: { [SID]: msgs }, hasMore: {},
      streams: {}, streamOrder: {}, streamTicks: {}, extendedWindow: {},
    })

    await act(async () => {
      root.render(
        <MessageList sessionId={SID} messages={msgs} onDelete={() => {}} conversationId={null}
          onLoadOlder={() => {}} onNearBottomChange={() => {}} />,
      )
    })

    const text = container.textContent ?? ''
    // ⭐ 设计意图：坏的那条只挂自己那一行。
    //   ⚠️ 文案必须对上行类型：assistant 消息走 `MemoMessage` 行（tag=MessageList.row.msg）→
    //   兜底标题是「这条消息显示失败」；「这段回复显示失败」是**流式块行**的标题，两者不同。
    expect(text, '毒行必须显示自己的兜底 UI，而不是静默消失').toContain('这条消息显示失败')
    // ⭐ 关键：幸存者必须还在 —— 这是「逐行包裹」与「整块包裹」的唯一区别
    expect(text, '逐行边界失效：中间一条毒把第一条也带走了（退化成整块兜底）').toContain('OBS2-ALIVE-1')
    expect(text, '逐行边界失效：中间一条毒把第三条也带走了（退化成整块兜底）').toContain('OBS2-ALIVE-3')
    // 异常没冒到根：整块消息区没有被替换掉
    expect(uncaught, '异常冒到了根 = 消息区整块被卸载').toHaveLength(0)
    expect(text, '消息区整层兜底不该触发（行级边界应先接住）').not.toContain('消息区显示出错了')
  })
})

// ⚠️ main.tsx 的**顶层接线**守护不在这里 —— 那是源码级断言，需要 `file:` 形式的 import.meta.url，
//   而本文件跑在 jsdom 下（import.meta.url 是 http 形式，fileURLToPath 会抛）。
//   见 `components/common/__tests__/ErrorBoundaryWiring.test.ts`（node 环境）。
