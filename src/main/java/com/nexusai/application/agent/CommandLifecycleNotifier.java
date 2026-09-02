package com.nexusai.application.agent;

/**
 * 命令生命周期通知器 · 对齐 CC utils/commandLifecycle.ts notifyCommandLifecycle.
 *
 * <p>关键路径对齐 query.ts:230-238 + 1632-1643：
 * <ul>
 *   <li><b>started</b>：loop() 内部 drain commandQueue 时，每条 item 触发。</li>
 *   <li><b>completed</b>：loop() 退出前，对所有已 started 的 UUID 统一触发。</li>
 * </ul>
 *
 * <p>Java 端注入策略：{@code @Autowired(required=false)} 缺省走 {@link NoOp} 兜底，
 * 不破坏现有 caller（tests + 老 caller 都不感知）。
 *
 * <h2>CC 对齐证据</h2>
 * <pre>{@code
 * // query.ts:229-230
 * const consumedCommandUuids: string[] = []
 * const terminal = yield* queryLoop(params, consumedCommandUuids)
 *
 * // query.ts:235-238
 * for (const uuid of consumedCommandUuids) {
 *   notifyCommandLifecycle(uuid, 'completed')
 * }
 *
 * // query.ts:1632-1643
 * if (consumedCommands.length > 0) {
 *   for (const cmd of consumedCommands) {
 *     if (cmd.uuid) {
 *       consumedCommandUuids.push(cmd.uuid)
 *       notifyCommandLifecycle(cmd.uuid, 'started')
 *     }
 *   }
 *   removeFromQueue(consumedCommands)
 * }
 * }</pre>
 */
public interface CommandLifecycleNotifier {

    /**
     * 命令开始消费（drain 后立即调用）。
     *
     * @param uuid 命令幂等 ID（cc: cmd.uuid），null 时静默跳过
     */
    void notifyStarted(String uuid);

    /**
     * 命令消费完成（loop 退出前统一调用）。
     *
     * @param uuid 命令幂等 ID（cc: cmd.uuid），null 时静默跳过
     */
    void notifyCompleted(String uuid);

    /**
     * 默认空实现 · 注入时 @Autowired(required=false) 缺省走这个。
     *
     * <p>行为：所有方法 no-op，确保无人监听时不抛异常。
     */
    final class NoOp implements CommandLifecycleNotifier {
        @Override
        public void notifyStarted(String uuid) {
            // no-op
        }

        @Override
        public void notifyCompleted(String uuid) {
            // no-op
        }
    }
}
