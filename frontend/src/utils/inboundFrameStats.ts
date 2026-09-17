/**
 * 入站帧计数（OBS2）· 模块级单例，只有两处读写：
 * <ul>
 *   <li>写 —— {@code useChatSocket.dispatchEvent}（每收到一帧 STOMP 事件记一次）；</li>
 *   <li>读 —— {@code frontLog} 的 10s 心跳（把计数带进 note）。</li>
 * </ul>
 *
 * <p><b>WHY（2026-09-17 事故）</b>：OBS1 的心跳只证明「JS 还活着」（定时器在跑、IPC 通），
 * <b>不证明还在收帧</b>。于是「前端整屏停止更新」时日志上分不出两种完全不同的病因：
 * <ul>
 *   <li>心跳在跳 + {@code framesIn} 不涨 → <b>通道死了</b>（半开连接 / 后端没推 / 推给零订阅者）；</li>
 *   <li>心跳在跳 + {@code framesIn} 在涨 → 帧收到了，<b>问题在渲染链</b>。</li>
 * </ul>
 * 这是把「没推」与「没渲染」分开的唯一手段。
 *
 * <p>⛔ <b>独立成零依赖模块</b>，而不是把计数放 {@code useChatSocket} 里让 {@code frontLog} 去 import：
 * {@code frontLog} 是 {@code main.tsx} 的<b>第一个 import</b>（顶层副作用即安装），若它反向 import
 * {@code useChatSocket}，会把整条聊天 hook + store 依赖图拉到启动最前，改变模块初始化顺序（OBS1
 * 特意把安装提到最前，正是为了抓住启动期异常）。本文件无任何 import，不存在这个风险。
 */

/** 入站帧统计（进程内累计，不随会话切换重置 —— 与心跳 seq 同为「进程还活着」的对照量）。 */
export const inboundFrameStats = {
  /** 累计收到的 STOMP 事件帧数（单调递增）。 */
  framesIn: 0,
  /** 最后一次收到帧的时刻（Date.now()；0 = 从未收到）。 */
  lastFrameAt: 0,
  /** 最后一帧的事件类型（后端 StreamEvent.type；'' = 从未收到）。 */
  lastEventType: '',
}

/** 记一帧入站事件（由 dispatchEvent 在分派入口调用，覆盖所有 type）。 */
export function noteInboundFrame(eventType: string): void {
  inboundFrameStats.framesIn += 1
  inboundFrameStats.lastFrameAt = Date.now()
  inboundFrameStats.lastEventType = eventType
}
