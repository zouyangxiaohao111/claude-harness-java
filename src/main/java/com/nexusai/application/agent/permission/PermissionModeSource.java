package com.nexusai.application.agent.permission;

/**
 * [批 A4e] 初始权限模式输入的<b>显式来源标注</b> —— 把 {@code RunRequest.permissionModeCli}
 * 的 {@code null} 两个身份（「没有覆盖」/「确实没给」）与第三态「<b>调用方忘了传</b>」区分开。
 *
 * <h2>WHY：{@code null} 的第三态曾静默三个批次</h2>
 * <p>{@code permissionModeCli == null} 在本仓有两个<b>合法</b>身份：
 * <ol>
 *   <li><b>「没有覆盖」</b> ⇒ {@link InitialPermissionModeResolver} 回落 settings 槽
 *       （{@code settings.permissions.defaultMode}）；</li>
 *   <li><b>「CLI 确实没给」</b> ⇒ 是 {@code auto} opt-in 意图的判断依据
 *       （CC {@code main.tsx:1409} {@code !permissionModeCli && isDefaultPermissionModeAuto()}，
 *       Java 侧见 {@code LlmAgentLoop} 的 {@code autoModeIntent}）。</li>
 * </ol>
 * 但批 A4b 修的那个 bug（{@code CronIdleExecutor} 队列 drain 起轮走「硬编码 null 的便捷重载」）
 * 说明存在<b>第三个身份</b>：调用方<b>本该解析会话选定权限模式却没解析</b>。三者在日志与数据结构上
 * <b>一模一样</b> ⇒ 该缺陷静默穿越了三个批次才被发现。
 *
 * <p>⇒ 本枚举把「<b>哪个槽该承载这个值</b>」显式写在 {@link com.nexusai.application.agent.RunRequest}
 * 上，由消费点（{@code LlmAgentLoop.doRun}）做一致性守护：
 * <b>{@link #NOT_APPLICABLE} 却携带会话 / 携带值 ⇒ ≥WARN</b>（「忘了传」的可检形态）。
 *
 * <h2>取值语义（= 值「属于哪个槽」，不是「有没有取到」）</h2>
 * <ul>
 *   <li>{@link #CLI_ARGUMENT}：值属于 <b>CLI/请求体槽</b>（CC {@code --permission-mode} 等价；
 *       web = per-call HTTP 请求体 ?? 会话 override 的解析结果）。<b>可能为 {@code null}</b>
 *       （该槽确实为空 ⇒ 合法回落，正是 CC {@code permissionModeCli === undefined} 语义）。</li>
 *   <li>{@link #SESSION_OVERRIDE}：值属于 <b>会话 override 列</b>（{@code sessions.permission_mode}，
 *       无 per-call 来源的路径：cron 队列 drain / 主会话后台化）。<b>可能为 {@code null}</b>
 *       （会话未设 override ⇒ 合法回落；取不到会话已由调用方自己 ≥WARN 留痕）。</li>
 *   <li>{@link #NOT_APPLICABLE}：本 run <b>不承载会话权限语义</b>（无会话的主线程 / verify /
 *       测试夹具 / 硬编码 {@code null} 的便捷重载）。值恒 {@code null}。</li>
 * </ul>
 *
 * <h2>⛔ 硬约束（不可违反）</h2>
 * <p>本标注<b>只描述来源，不参与取值</b> —— 消费点守护<b>只打日志，不改值</b>。
 * 「把 {@code null} 替换成解析后的全局值」会破坏 {@code auto} opt-in 语义
 * （{@code autoModeIntent} 依赖 {@code permissionModeCli == null}），本批<b>明令禁止</b>。
 *
 * <p>另：{@link #NOT_APPLICABLE} 的判定<b>不看 {@code SessionKeys.NO_SESSION} 哨兵</b> ——
 * 该哨兵语义是「确无会话」（全局 cron / 普通 prompt），本就不承载会话权限语义，属合法。
 *
 * @see InitialPermissionModeResolver
 * @see com.nexusai.application.agent.RunRequest#permissionModeSource()
 */
public enum PermissionModeSource {

    /** 值属于 CLI/请求体槽（{@code --permission-mode} 等价；web = per-call ?? 会话 override）。可为 null。 */
    CLI_ARGUMENT,

    /** 值属于会话 override 列（{@code sessions.permission_mode}；cron drain / 主会话后台化）。可为 null。 */
    SESSION_OVERRIDE,

    /** 本 run 不承载会话权限语义（无会话 / 测试 / 硬编码 null 的便捷重载）。值恒 null。 */
    NOT_APPLICABLE
}
