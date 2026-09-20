// @vitest-environment jsdom
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Composer } from '../Composer'
import { useChatStore } from '@/stores/chatStore'
import { computeTurnRunning } from '@/utils/turnRunning'
import { useServerRunningRebuild } from '@/hooks/useServerRunning'
import { chatApi } from '@/api/chat'

/**
 * ⭐ [C6] 「停止键可见性必须依赖服务端真实运行态」守门测试。
 *
 * <b>缺陷背景（用户原话：「能够 UI 中手动终止会话循环」）</b>：前端原先判「要不要显示停止键」
 * 只有两路<b>本地簿记</b>信号 —— ① activeStreams（只在「本页发送」时登记）② 有无流式块。
 * 后台 drain（排队命令 / cron / 任务通知）启动的 run 两路皆假：它不是本页发的；run 卡在思考
 * 或等待权限时更永远没有 chunk ⇒ UI 上<b>连停止键都不出现</b>，用户无从终止；F5 还会清空本地簿记。
 *
 * <b>服务端这一路有两条独立通道，本文件两条都要钉</b>：
 * <ol>
 *   <li><b>重建通道</b>（GET /sessions/{id}/running ⇒ store ⇒ 停止键）：F5 后仍能看见停止键的
 *       <b>唯一</b>途径。用例 = 下面 3 条「F5 / 载入 / 切会话」组。</li>
 *   <li><b>实时通道</b>（session.status 事件）：在 useChatSocket 里，由
 *       hooks/__tests__/useChatSocket.sessionStatus.test.tsx 钉方向。</li>
 * </ol>
 *
 * <b>WHY 必须真的渲染 Composer + 真的跑 App 那个 hook</b>：本批修的缺陷不在纯函数里，而在<b>接线</b>上
 * ——「服务端说在跑」到「用户看得见停止键」之间要经过 GET → store 键控 → 判定 → Composer 的
 * `streaming` 分支。只测 `computeTurnRunning` 会漏掉任何一环；把 App 里那段 GET 内联逻辑照抄进
 * Host（而非调用真实现）则更坏 —— 把 App 的 GET <b>整条改死</b>用例照样全绿（已实测），正是本仓
 * 反复踩的「假守护」。故 Host 调用的就是 App 调用的那两处真实现：
 * {@link useServerRunningRebuild}（载入/切会话）与 {@link computeTurnRunning}。
 *
 * <b>手法</b>：沿用本仓既有 jsdom 真实渲染模式（`Composer.popEditable.test.tsx`）：`createRoot` + `act`，
 * 不引入任何新依赖。streamRegistered / hasStream 恒 false —— 正是「非本页发送、且尚无流式块」的事故现场。
 */

vi.mock('@tauri-apps/api/core', () => ({ isTauri: () => false, invoke: vi.fn(async () => undefined) }))
vi.mock('@tauri-apps/api/webview', () => ({ getCurrentWebview: () => ({ onDragDropEvent: () => Promise.resolve(() => {}) }) }))
vi.mock('@tauri-apps/plugin-fs', () => ({ stat: vi.fn(), readFile: vi.fn(), writeTextFile: vi.fn() }))
vi.mock('@/api/chat', () => ({ uploadAttachment: vi.fn(), chatApi: { sessionRunning: vi.fn() } }))

const SID = 'sess-c6-stop'
const sessionRunning = vi.mocked(chatApi.sessionRunning)

interface Harness {
  container: HTMLDivElement
  root: Root
  stopVisible: () => boolean
  sendVisible: () => boolean
  setServerRunning: (sid: string, running: boolean) => void
}

const stopBtn = (c: HTMLElement) => c.querySelector('button.send-btn.danger')
const sendBtn = (c: HTMLElement) => c.querySelector('button.send-btn:not(.danger)')

/** 放行 GET 的 promise 链（含 hook effect 里的 then）——不 sleep，只让微任务队列跑完。 */
async function flushPromises() {
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
}

function mountHost(): Harness {
  ;(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)

  function Host() {
    // 与 App.tsx 同源的接线：同一 hook + 同一选择器 + 同一判定 + 同一 Composer 分支
    useServerRunningRebuild(SID, true)                                  // App: useServerRunningRebuild(activeSessionId, isRealActive)
    const serverRunning = useChatStore((s) => !!s.serverRunning[SID])   // App 同款选择器
    const turnRunning = computeTurnRunning({
      streamRegistered: false,   // 非本页发送 ⇒ activeStreams 无登记
      hasStream: false,          // 卡在思考/等待权限 ⇒ 无任何流式块
      serverRunning,
    })
    return (
      <Composer
        composerText=""
        setComposerText={() => {}}
        sendMessage={() => {}}
        showToast={() => {}}
        streaming={turnRunning}
        onStop={() => {}}
        queuedCommands={[]}
        popEditable={async () => null}
        boundProjectName={null}
        onSelectProject={() => {}}
        currentModel="ds-openai/deepseek-v4-flash"
        permissionMode="default"
        empty={false}
        sessionId={SID}
        localRead={false}
      />
    )
  }
  act(() => { root.render(<Host />) })

  return {
    container,
    root,
    stopVisible: () => !!stopBtn(container),
    sendVisible: () => !!sendBtn(container),
    setServerRunning: (sid, running) => {
      act(() => { useChatStore.getState().setServerRunning(sid, running) })
    },
  }
}

describe('[C6] 停止键可见性 = 服务端真实运行态（非本页发送 / 后台 drain 起的 run 也必须能停）', () => {
  let h: Harness | undefined

  beforeEach(() => {
    useChatStore.setState({ serverRunning: {} })   // 单例 store：每个用例显式归零，防跨用例污染
    // 默认「GET 永不返回」：不干扰直接驱动 store 的用例（那些用例不关心重建通道）
    sessionRunning.mockReset()
    sessionRunning.mockReturnValue(new Promise(() => {}))
  })

  afterEach(() => {
    if (h) {
      const cur = h
      act(() => cur.root.unmount())
      cur.container.remove()
      h = undefined
    }
    useChatStore.setState({ serverRunning: {} })
  })

  it('⭐ 服务端说「该会话在跑」⇒ 停止键出现（本页没发过、也没有任何流式块）', () => {
    h = mountHost()
    // 事故现场：非本页发送 + 无流式块 ⇒ 本地两路信号全假
    expect(h.stopVisible()).toBe(false)
    expect(h.sendVisible()).toBe(true)

    h.setServerRunning(SID, true)

    // 服务端有 run 存活 ⇒ 停止键必须出现，否则用户无从终止（本批要修的就是这个）
    expect(h.stopVisible()).toBe(true)
    expect(h.sendVisible()).toBe(false)
    // 文案只描述功能（本仓铁律 ui-no-cc-terms：不出现「会话循环 / run / CC」这类内部术语）
    expect(stopBtn(h.container)?.getAttribute('title')).toBe('停止生成')
  })

  it('run 收口（服务端说不在跑）⇒ 停止键消失、发送键回来', () => {
    h = mountHost()
    h.setServerRunning(SID, true)
    expect(h.stopVisible()).toBe(true)

    h.setServerRunning(SID, false)

    // run 已收口 ⇒ 停止键必须消失（否则 UI 永久停在「运行中」、Esc 永久走停止分支）
    expect(h.stopVisible()).toBe(false)
    expect(h.sendVisible()).toBe(true)
  })

  it('多会话隔离：别的会话在跑，本会话不得显示停止键', () => {
    h = mountHost()
    h.setServerRunning('sess-other', true)

    // 运行态必须按会话键控 —— 否则点停止取消的是别的会话（多会话并行串台）
    expect(h.stopVisible()).toBe(false)
    expect(h.sendVisible()).toBe(true)
  })

  // ── 重建通道（GET /running）：F5 / 载入 / 切会话 ────────────────────────────────

  it('⭐ [F5 重建] 载入时 GET /running 说在跑 ⇒ 停止键出现（本地簿记已被 F5 清空也不丢）', async () => {
    sessionRunning.mockResolvedValue({ running: true })
    h = mountHost()

    // 首帧还没有服务端事实（本地两路全假）—— 随后由 GET 回填
    expect(h.stopVisible()).toBe(false)

    await flushPromises()

    expect(sessionRunning).toHaveBeenCalledWith(SID)
    expect(h.stopVisible()).toBe(true)
    expect(h.sendVisible()).toBe(false)
  })

  it('[F5 重建] GET 说不在跑 ⇒ 不得显示停止键（不误标「运行中」）', async () => {
    sessionRunning.mockResolvedValue({ running: false })
    h = mountHost()

    await flushPromises()

    expect(sessionRunning).toHaveBeenCalledWith(SID)
    expect(h.stopVisible()).toBe(false)
    expect(h.sendVisible()).toBe(true)
  })

  it('[F5 重建] GET 失败（后端未就绪 / 网络错）⇒ 不猜：维持「不显示」，且不把请求失败升级成错误', async () => {
    sessionRunning.mockRejectedValue(new Error('Network Error'))
    h = mountHost()

    await flushPromises()

    // 查不到运行态 ⇒ 既不误标运行中（无效停止键），也不误标空闲（没有东西可写）
    expect(h.stopVisible()).toBe(false)
    expect(h.sendVisible()).toBe(true)
    expect(useChatStore.getState().serverRunning[SID]).toBeUndefined()
  })
})
