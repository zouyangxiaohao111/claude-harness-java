import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * OBS2 · 入站帧计数（诊断件）单测。
 *
 * <p><b>WHY（规则九：验证意图）—— 诊断件算错会把下次诊断引向错误结论</b>：这个模块唯一的用途是让
 * 「心跳在跳但 framesIn 不涨」这个判据成立。判据的成立依赖三件事，任何一件错了都会**误判**（而不是报错）：
 * <ol>
 *   <li><b>三类字段必须同步推进</b>：只加 `framesIn` 不更新 `lastFrameAt` ⇒ 看不出「最后一次收帧是多久前」，
 *       分不出「一直没帧」与「刚断」；不更新 `lastEventType` ⇒ 只剩一个数字，无法判断断在哪一类事件上。</li>
 *   <li><b>必须接受任意 type（含 `unknown`）</b>：前端解析失败时 socket 层会把帧落成
 *       `{ type: 'unknown' }`（`api/socket.ts` 的 `parseStreamEvent` catch 分支）。若本模块「只计认识的类型」，
 *       「帧到了但解析失败」会被读成「帧根本没到」—— 正好把诊断引向相反方向。</li>
 *   <li><b>计数必须单调</b>：它是进程级累计量，与心跳 seq 对照用；任何重置/回退都会让判据时断时续。</li>
 * </ol>
 *
 * <p>⚠️ 模块内有**模块级可变状态**（`inboundFrameStats` 单例）⇒ 每个用例前 `vi.resetModules()` +
 * 动态 import 取全新模块，杜绝用例间互相污染（否则「初始为 0」这类断言只在第一个用例成立）。
 */
type StatsModule = typeof import('../inboundFrameStats')

const MODULE_PATH = fileURLToPath(new URL('../inboundFrameStats.ts', import.meta.url))

describe('OBS2 · 入站帧计数 inboundFrameStats', () => {
  let mod: StatsModule

  beforeEach(async () => {
    vi.resetModules()
    mod = await import('../inboundFrameStats')
  })

  it('初始态：framesIn=0 / lastFrameAt=0 / lastEventType=空 —— 0 专用于表达「从未」', async () => {
    expect(mod.inboundFrameStats).toEqual({ framesIn: 0, lastFrameAt: 0, lastEventType: '' })
    // lastFrameAt=0 是「从未收帧」的哨兵：frontLog 心跳据此打「从未收帧」而不是一个天文数字的差值
    expect(mod.inboundFrameStats.lastFrameAt).toBe(0)
  })

  it('一帧：三类字段同步推进（计数 +1 / 时刻更新 / 类型为本次）', async () => {
    const before = Date.now()
    mod.noteInboundFrame('message.chunk')

    expect(mod.inboundFrameStats.framesIn).toBe(1)
    expect(mod.inboundFrameStats.lastEventType).toBe('message.chunk')
    expect(
      mod.inboundFrameStats.lastFrameAt,
      'lastFrameAt 必须落在调用时刻之后（0 会让心跳把「刚收到帧」写成「从未收帧」）',
    ).toBeGreaterThanOrEqual(before)
  })

  it('多帧：framesIn 单调累加，lastEventType 记的是最后一次（不是第一次）', async () => {
    mod.noteInboundFrame('message.chunk')
    mod.noteInboundFrame('message.usage')
    mod.noteInboundFrame('message.complete')

    expect(mod.inboundFrameStats.framesIn).toBe(3)
    expect(
      mod.inboundFrameStats.lastEventType,
      '必须是最后一帧的类型 —— 记成第一帧会让「断在哪类事件」判错',
    ).toBe('message.complete')
    expect(mod.inboundFrameStats.lastFrameAt).toBeGreaterThan(0)
  })

  it('⭐ 必须接受 unknown 类型：解析失败的帧若被过滤，「帧到了但解析失败」会被误读成「帧没到」', async () => {
    // 与 api/socket.ts 的 subscribeStream catch 分支同源：JSON.parse 失败 → { type: 'unknown' }
    mod.noteInboundFrame('unknown')

    expect(
      mod.inboundFrameStats.framesIn,
      'unknown 帧也要计数：它是「后端推了、前端解析炸了」这一类故障的唯一信号，'
        + '过滤掉它等于把判据引向相反结论（以为通道没推）',
    ).toBe(1)
    expect(mod.inboundFrameStats.lastEventType).toBe('unknown')
  })

  it('单调不减：连续调用不会出现 framesIn 回退或 lastFrameAt 归零', async () => {
    let prevFrames = 0
    let prevAt = 0
    for (const type of ['message.chunk', 'session.status', 'message.usage', 'unknown']) {
      mod.noteInboundFrame(type)
      const s = mod.inboundFrameStats
      expect(s.framesIn).toBe(prevFrames + 1)
      expect(s.lastFrameAt).toBeGreaterThanOrEqual(prevAt)
      prevFrames = s.framesIn
      prevAt = s.lastFrameAt
    }
    expect(prevFrames).toBe(4)
  })
})

/**
 * 设计前提守护（源码级）：本模块**必须保持零 import**。
 *
 * <p>WHY：`frontLog` 是 `main.tsx` 的**第一个 import**（顶层副作用即安装，为的是抓住启动期异常），
 * 它反向 import 本模块取计数。本模块一旦引入任何 import（哪怕只是 `import type`），就会把依赖图
 * 拉进启动最前 —— 而 OBS1 特意把安装提到最前正是为了「越早装越好」。这条前提**被破坏时不会有任何
 * 运行时报错**，只会在某些模块初始化顺序下悄悄改变行为，故用源码断言钉住。
 *
 * <p>⚠️ 局限（如实登记）：行级源码断言只拦「新增了 import 语句」，拦不住等价改写（动态 import()）。
 */
describe('OBS2 · inboundFrameStats 必须保持零依赖叶子模块', () => {
  it('源码里不得出现任何 import 语句', () => {
    const src = readFileSync(MODULE_PATH, 'utf8').replace(/\r\n?/g, '\n')
    const importLines = src.split('\n').filter((l) => /^\s*import\b/.test(l))

    expect(
      importLines,
      'frontLog 会在 main.tsx 的最前面 import 本模块；这里一旦有依赖，就会把那条依赖图'
        + '（乃至聊天 hook / store）拉到启动最前，改变模块初始化顺序',
    ).toEqual([])
  })
})
