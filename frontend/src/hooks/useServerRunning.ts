import { useEffect, useRef } from 'react'
import { chatApi } from '@/api/chat'
import { useChatStore } from '@/stores/chatStore'

/**
 * [C6] 服务端权威运行态的**重建**通道 —— 停止键可见性的第三路信号（见 utils/turnRunning.ts）。
 *
 * <b>WHY 独立成模块</b>：本通道原先内联在 App.tsx 的两个 effect / 回调里（载入·切会话一次、重连一次），
 * 而「内联在 App 里」= 任何单测都碰不到它 —— 把 GET 整条改死，既有用例照样全绿（本仓前科：
 * e2e 不覆盖的层 = 没验）。抽成 hook + 一次性函数后，「GET 回填被改死」这件事可以被真的渲染钉红
 * （见 components/center/__tests__/Composer.stopVisibleWhenServerRunning.test.tsx 的 F5 重建用例）。
 *
 * <b>⛔ 非轮询</b>：只在【载入 / 切会话 / 重连】各查一次。运行中的实时翻转走 {@code session.status}
 * 事件（useChatSocket），不要在这里加定时器。
 *
 * <b>失败静默（不猜）</b>：查不到（后端未就绪 / 会话不存在 / 网络错）⇒ **不改** store ——
 * 既不得误标「运行中」（会显示一个点下去取消空气的停止键），也不得误标「空闲」。
 */

/** [C6] 查一次该会话的服务端运行态；返回 null = 查不到（调用方**不要**写 store）。 */
async function fetchServerRunning(sessionId: string): Promise<boolean | null> {
  try {
    const r = await chatApi.sessionRunning(sessionId)
    return !!r.running
  } catch {
    return null
  }
}

/** [C6] 重连（或任何需要按 sid 立刻对齐一次）时按会话刷新服务端运行态（非轮询 · 失败静默）。 */
export async function refreshServerRunning(sessionId: string): Promise<void> {
  const running = await fetchServerRunning(sessionId)
  if (running != null) useChatStore.getState().setServerRunning(sessionId, running)
}

/**
 * [C6] 载入 / 切会话 → 用服务端权威运行态重建「要不要显示停止键」。
 *
 * WHY 不能只靠事件：本页发送的 turn 由 activeStreams 登记、事件由 STOMP 实时到达 —— 但
 * ①后台 drain（排队命令 / cron / 任务通知）起的 run 事件在「本页发送」簿记之外，
 * ②F5 会清空本地簿记且错过 run 起轮事件。本处一次 GET 补齐（这是 F5 后仍能看见停止键的唯一途径）。
 *
 * @param sessionId 目标会话（null = 无会话，不查）
 * @param enabled   会话是否已就绪（App 侧 = isRealActive：哨兵/未载入会话不查）
 */
export function useServerRunningRebuild(sessionId: string | null, enabled: boolean): void {
  useEffect(() => {
    if (!sessionId || !enabled) return
    let alive = true
    // 过期响应不得回写（会话已切走/组件已卸载；写的是旧 sid 的键，跳过即无害）
    void fetchServerRunning(sessionId).then((running) => {
      if (alive && running != null) useChatStore.getState().setServerRunning(sessionId, running)
    })
    return () => { alive = false }
  }, [sessionId, enabled])
}

/**
 * [C6 · 遗留2] 从「上一次服务端运行态快照」到「当前快照」的**收口边沿** → 返回需回收本地登记的会话 id。
 *
 * <p><b>只认 true → false</b>。键缺省 = 从未查到过（{@code chatStore.serverRunning} 注释：键缺失一律 false），
 * 把它一并当「收口」会在**每次发送登记后立刻**把登记抹掉 —— 发送那一刻 store 里往往还是上一轮遗留的
 * 缺省/false（后端 thinking 事件尚未到达）⇒ 正常流式被本回收打断（比原缺陷更坏）。故缺省/首查为 false
 * **都不是**收口，只作「无信息」看待。
 *
 * <p>键被整个删除（{@code clearSession} 删会话）不算收口：会话已不存在，登记随之无意义。
 */
export function reapSessionsOnNotRunning(
  prev: Record<string, boolean | undefined>,
  next: Record<string, boolean | undefined>,
): string[] {
  const reaped: string[] = []
  for (const sid of Object.keys(prev)) {
    if (prev[sid] === true && next[sid] === false) reaped.push(sid)
  }
  return reaped
}

/**
 * [C6 · 遗留2] 服务端权威「不在跑」→ **对账本地 turn 簿记**（单点 · 两条服务端通道共用）。
 *
 * <p>收口边沿（true→false）一到，对被收口的会话做两件事，缺一不可：
 * <ol>
 *   <li><b>残留流式块定稿</b>（{@code chatStore.finalizeBlocks}）—— 块转正式消息。这是**唯一**能
 *       让残留块不再「永久直出原文」的动作：把块转成消息后该行退出流式臂，且
 *       {@code hasStream}（App 读 streamOrder 长度）归假。</li>
 *   <li><b>回收 turn 登记</b>（回调宿主 → App 的 activeStreams）—— 让 {@code computeTurnRunning} 回假。</li>
 * </ol>
 *
 * <p><b>缺陷背景（①②两路都是本地簿记，且清除点都只挂在「本页订阅那一条通道」的事件上）</b>：
 * {@code activeStreams[sid]} 只在 complete / cancel / error **事件**到达时清（App.handleSessionDone）；
 * {@code streams[sid]} / {@code streamOrder[sid]} 只在 complete（finalizeBlocks）与 error / cancelled
 * （clearStream）时清 —— **没有任何一路挂在「服务端权威说不在跑」上**。这些帧一旦在无断连的情况下
 * 静默丢失（帧未送达 / complete 处理中途抛错 → 其后的行不再执行），两路本地簿记就**永不回收**：
 * <ul>
 *   <li>登记残留 ⇒ {@code computeTurnRunning} 是**或**语义，服务端随后说 idle（或 GET /running 返回
 *       false）也压不住恒真的这一路 ⇒ <b>停止键永久卡在「停止」</b>；</li>
 *   <li>流式块残留 ⇒ {@code hasStream} 恒真（同上第一条）<b>且</b>那一行永远走流式臂直出原文
 *       （「流式块永久残留」那条已登记缺陷，与本回收同一根因：渲染出口只有 complete 一个）。</li>
 * </ul>
 *
 * <p><b>为什么不用 handleSessionDone 兜</b>：它带「完成未读绿点」等副作用，且其 topic 校验依赖
 * 「事件来自哪条 topic」——正是本例要绕开的那条通道。故本回收**只**做上面两件事，其余一概不碰。
 *
 * <p><b>触发源 = store 的 true→false 边沿，因此两条服务端通道天然都覆盖</b>：
 * ① session.status=idle 实时事件（useChatSocket 写 store）② GET /sessions/{id}/running 返回 false
 * （{@link refreshServerRunning} / {@link useServerRunningRebuild} 写 store）。无需在任一处各记一次
 * 调用 —— 两处写的是同一个键，边沿只有一处（防两处判据漂移）。
 *
 * <p><b>与 complete 的幂等</b>：complete 先到（主路径的顺序）时块已被 finalizeBlocks 清空，本处的
 * finalizeBlocks 见「无块」直接 no-op ⇒ 不会覆盖 complete 带来的 usage / 上下文快照。
 * ⚠️ 该幂等以「complete 先于 idle」为前提 —— 反序的服务端路径（cron drain）必须与主路径同序，
 * 否则收口边沿会先把块定稿，随后到达的 complete 变成 no-op 而丢掉 usage 元数据。
 *
 * <p><b>不进 React 渲染路径</b>：用 store 订阅（非 selector）做差分 —— 否则每次状态事件都会让整个
 * App 重渲染（{@code serverRunning} 每次写都是新对象）。
 *
 * @param onNotRunning 收口（true→false）时对**被收口的那个 sid**回调（宿主据此回收本地登记）
 * @param onRunning    可选 · **起轮**（非 true → true）时对那个 sid 回调 —— 宿主用它把「服务端确认过
 *                     在跑」的轮次钉在本轮登记上（见 App.reclaimStreamRegistration 的轮次判据：
 *                     登记晚于最近一次 true = 本轮尚未被服务端确认 ⇒ 本边沿不属于它，不得回收）。
 */
export function useServerRunningReconcile(
  onNotRunning: (sessionId: string) => void,
  onRunning?: (sessionId: string) => void,
): void {
  const cbRef = useRef(onNotRunning)
  cbRef.current = onNotRunning
  const riseRef = useRef(onRunning)
  riseRef.current = onRunning
  useEffect(() => {
    let last = useChatStore.getState().serverRunning
    return useChatStore.subscribe((state) => {
      // 高频非运行态写入（chunk / 消息 / 流式块）→ 引用相等短路（serverRunning 只在写入时换引用）
      if (state.serverRunning === last) return
      const next = state.serverRunning
      const reaped = reapSessionsOnNotRunning(last, next)
      const prev = last
      // 先推进快照：下面的 finalizeBlocks 是 store 写 → 会再触发本订阅，靠引用相等短路（不重入回收）
      last = next
      // 起轮边沿（本仓已登记「缺省 = 未查过 ≠ 收口」的镜像：缺省也不是「起轮」—— 只有非 true → true 才算）
      if (riseRef.current) {
        for (const sid of Object.keys(next)) {
          if (prev[sid] !== true && next[sid] === true) riseRef.current(sid)
        }
      }
      for (const sid of reaped) {
        // ① store 侧：残留流式块定稿成正式消息（无 complete 帧时唯一的渲染出口）
        useChatStore.getState().finalizeBlocks(sid)
        // ② 宿主侧：回收 turn 登记
        cbRef.current(sid)
      }
    })
  }, [])
}
