// @vitest-environment jsdom
import { beforeAll, describe, expect, it, vi } from 'vitest'

/**
 * `console.log` 劫持的关键字闸 + 廉价预筛（OBS2）。
 *
 * <p><b>WHY（规则九：验证意图）</b>：OBS1 只劫持了 `console.error/warn`，于是**第三方库用 console.log
 * 吞掉的异常零留痕** —— 实测（本机 `node_modules/@stomp/stompjs/esm6/parser.js:197`）：
 * ```js
 * catch (e) { console.log(`Ignoring an exception thrown by a frame handler. Original exception: `, e) }
 * ```
 * 这正是「帧收到了却没渲染」那类故障的**唯一痕迹**，而 2026-09-17「前端整屏停止更新」事故复盘时
 * 前端零日志、无从分辨。所以本测试钉住两条相反的意图：
 * <ol>
 *   <li><b>不许漏</b>：stompjs 这条吞异常文案必须被上报（关键字闸若只列 `error|fail` 就会把它漏掉 ——
 *       原文里只有 `exception` / `Ignoring`，故白名单必须覆盖它们）；</li>
 *   <li><b>不许淹</b>：`console.log` 是最高频通道，普通文本与「首参非字符串/非 Error 的对象日志」
 *       必须放过（后者连序列化都不做）—— 否则日志通道自己成了性能与噪声问题，比没上报更糟。</li>
 * </ol>
 *
 * <p>做法：设 `__TAURI_INTERNALS__` 触发 frontLog 的顶层安装（其守卫条件是运行时判定，故必须先于 import
 * 设置），mock `@tauri-apps/api/core` 的 invoke 捕获上报载荷。断言按「msg 内容」过滤而非断言调用总数 ——
 * 避免被其它用例/心跳的调用噪声干扰。
 */
/** 上报载荷形状（`frontend_log` 命令的两个入参）。 */
interface LogPayload {
  tag?: string
  msg?: string
}

const invokeMock = vi.hoisted(() =>
  vi.fn((_cmd: string, _payload?: LogPayload): Promise<void> => Promise.resolve()),
)

vi.mock('@tauri-apps/api/core', () => ({ invoke: invokeMock }))

/** 等一拍：`report()` 里 invoke 是 fire-and-forget，等它落到 mock 上。 */
async function flush(): Promise<void> {
  await new Promise((r) => setTimeout(r, 0))
}

/** 取「console.log 上报里 msg 含 needle」的载荷。 */
function logReportsContaining(needle: string): LogPayload[] {
  return invokeMock.mock.calls
    .filter((c) => c[0] === 'frontend_log')
    .map((c) => c[1] ?? {})
    .filter((p) => p.tag === 'console.log' && String(p.msg ?? '').includes(needle))
}

describe('frontLog · console.log 劫持（OBS2）', () => {
  beforeAll(async () => {
    // ⛔ 必须在 import 前设置：frontLog 的 install() 是顶层副作用，守卫条件在模块求值时就判过了
    ;(window as unknown as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__ = {}
    await import('../frontLog')
  })

  it('stompjs 用 console.log 吞掉的帧处理异常必须被上报（否则该故障路径永远无痕）', async () => {
    // 文案逐字取自 @stomp/stompjs/esm6/parser.js:197 的 catch 分支
    console.log('Ignoring an exception thrown by a frame handler. Original exception: ', new Error('帧处理炸了'))
    await flush()

    const hits = logReportsContaining('Ignoring an exception thrown by a frame handler')
    expect(
      hits.length,
      'stompjs 的 parser.js 用 console.log 吞掉帧处理异常 —— 这条通道一度零留痕；'
        + '若关键字闸漏掉 exception/Ignoring，本用例变红',
    ).toBeGreaterThan(0)
    // 同时带上异常本体（否则只知道「有个异常被忽略」，不知道是什么）
    expect(String(hits[0].msg)).toContain('帧处理炸了')
  })

  it('普通文本 console.log 不上报（高频通道必须静默）', async () => {
    console.log('这是普通调试文本 OBS2-NOISE-MARKER')
    await flush()

    expect(
      logReportsContaining('OBS2-NOISE-MARKER'),
      '无关键字的普通文本若也上报，console.log 会把日志刷爆 —— 通道变成噪声源',
    ).toHaveLength(0)
  })

  it('首参非字符串且非 Error 的对象日志不做序列化、不上报（廉价预筛 · 保性能）', async () => {
    console.log({ error: '这个对象里带关键字也不该被序列化上报', deep: { nested: 1 } })
    await flush()

    expect(
      logReportsContaining('这个对象里带关键字也不该被序列化上报'),
      '对象首参若也走 JSON.stringify，高频路径上序列化开销本身就是问题；本通道绝不能自己变成性能问题',
    ).toHaveLength(0)
  })

  it('Error 首参必上报（不看关键字）', async () => {
    console.log(new Error('OBS2-ERROR-MARKER'))
    await flush()

    expect(
      logReportsContaining('OBS2-ERROR-MARKER').length,
      'Error 实例永远值得上报：它不命中关键字文案时若被放过，等于把最该记的东西丢了',
    ).toBeGreaterThan(0)
  })
})
