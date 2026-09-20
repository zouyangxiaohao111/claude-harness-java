/**
 * [C6] 「turn 运行中」判定 —— 发送键 ⇄ 停止键、Esc 分支、思考指示、对话操作弹窗门控的唯一判据入口。
 *
 * <p>三个信号取【或】，缺一不可：
 * <ol>
 *   <li><b>streamRegistered</b> = 本页发送已登记 activeStreams（含 thinking 阶段，此时还没有流式块）；</li>
 *   <li><b>hasStream</b> = 本会话有流式块（打字机在推）—— 防「打字机在动但发送键已出」脱节；</li>
 *   <li><b>serverRunning</b> = <b>服务端权威运行态</b>（GET /sessions/{id}/running 重建 · session.status 事件翻转）。</li>
 * </ol>
 *
 * <p><b>WHY 必须有第三路</b>：①② 都是<b>前端本地簿记</b>。后台 drain（排队命令 / cron / 任务通知）
 * 启动的 run 不经「本页发送」⇒ activeStreams 里没有它；run 卡在思考或等待权限时也永远没有 chunk
 * ⇒ hasStream 假。两个信号全假 ⇒ UI 上连停止键都不出现，用户无从终止；F5 还会清空本地簿记，
 * 连「后来补回」都做不到。第三路读的是服务端真实的 run 存活态（后端 LlmAgentLoop.isSessionActive），
 * 载入 / 切会话 / 重连各查一次即可重建 —— 这也是 F5 后仍能看见停止键的唯一途径。
 *
 * <p>提成纯函数是为了让「服务端这一路被拿掉就会红」这件事可被测试钉住
 * （见 components/center/__tests__/Composer.stopVisibleWhenServerRunning.test.tsx）。
 */
export function computeTurnRunning(signals: {
  /** 本页发送已登记 activeStreams */
  streamRegistered: boolean
  /** 本会话有流式块（打字机在推） */
  hasStream: boolean
  /** 服务端权威运行态（GET /running 或 session.status 事件） */
  serverRunning: boolean
}): boolean {
  return signals.streamRegistered || signals.hasStream || signals.serverRunning
}
