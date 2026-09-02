package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H5 v2 对抗核验未登记缺口] SubagentExecutor finally 真正调用 clearSessionHooks — 集成兜底测试.
 *
 * <p>WHY (规则九 · 测试验证意图): H5.md 步骤 2 必含测试项「集成: SubagentExecutor finally 真正调用
 * clearSessionHooks」, 但 SessionHookStoreTest 仅覆盖 SessionHookStore 自身, 无任何测试覆盖
 * SubagentExecutor 的 finally 接线 (对抗核验报告登记为未登记缺口). 实现代码在
 * {@code SubagentExecutor.execute} finally 块 Step 21.2b 调 {@link SubagentExecutor#cleanupSessionHooks(UUID)}
 * (对齐 CC runAgent.ts:822 clearSessionHooks). 若接线断裂: sub-agent 会话结束的运行时临时 hook
 * 不清理, 泄漏到后续会话复用 (注册了永不清理).
 *
 * <p><b>测试方式说明</b>: {@code execute} 22 步主流程依赖 LLM 循环等重依赖, 无法在单测中跑全流程.
 * 故把 finally 的 clearSessionHooks 抽成 package-private {@link SubagentExecutor#cleanupSessionHooks(UUID)}
 * seam, 本测试:
 * <ul>
 *   <li>用真实 {@link HookRegistry} + 真实 {@link CommandHook} 注册 session hook (模拟 sub-agent 注册)</li>
 *   <li>调用 {@code cleanupSessionHooks(sessionId)} (finally 块的等价执行路径)</li>
 *   <li>断言 {@link HookRegistry#getSessionHooks} 已清空 (SessionHookStore 三级存储 delete 语义)</li>
 * </ul>
 * finally 接线本身由 grep 硬指标兜底 ({@code grep -n "cleanupSessionHooks" SubagentExecutor.java} 命中
 * finally 调用点), 本测试验证清理行为真实生效.
 *
 * @since H5 v2 对抗核验修复
 */
@DisplayName("[H5 v2] SubagentExecutor cleanupSessionHooks finally 接线清理生效")
class SubagentExecutorSessionHookCleanupTest {

    private static CommandHook commandHook(String command) {
        return new CommandHook(command, null, null, null, null, null, null, null);
    }

    /**
     * WHY: sub-agent 会话结束必须清理注册过的运行时临时 hook — 若 cleanupSessionHooks 只写
     * 注释不真正删 store key, 注册了永不清理, 跨 turn 复用泄漏 (CC runAgent.ts:822 clearSessionHooks
     * sessionHooks.ts:442 {@code prev.sessionHooks.delete(sessionId)}).
     */
    @Test
    @DisplayName("cleanupSessionHooks 清空 HookRegistry 中该 session 的全部临时 hook")
    void cleanupSessionHooks_clearsRegisteredSessionHooks() {
        HookRegistry hookRegistry = new HookRegistry();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String sessionIdStr = sessionId.toString();

        // 模拟 sub-agent 注册的 session command hook (addSessionHook) + function hook
        hookRegistry.addSessionHook(sessionIdStr, HookEventType.PRE_TOOL_USE, "Bash",
                commandHook("echo subagent"), null, null);
        hookRegistry.addFunctionHook(sessionIdStr, HookEventType.PRE_TOOL_USE, "Bash",
                (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(true),
                "blocked", null, null);
        // 另一 session 的 hook 用于证明只清本 session (隔离性)
        String otherSession = UUID.randomUUID().toString();
        hookRegistry.addSessionHook(otherSession, HookEventType.PRE_TOOL_USE, "Bash",
                commandHook("echo other"), null, null);

        // 其余依赖传 null: execute() 主流程不会跑, 仅 finally 清理 seam 被验证
        SubagentExecutor executor = new SubagentExecutor(
                null, hookRegistry, null, null, null, "model", "system-prompt");

        executor.cleanupSessionHooks(sessionId, UUID.randomUUID());

        // 本 session 的 command + function hook 全部清空
        assertThat(hookRegistry.getSessionHooks(sessionIdStr, HookEventType.PRE_TOOL_USE)).isEmpty();
        assertThat(hookRegistry.getSessionFunctionHooks(sessionIdStr, HookEventType.PRE_TOOL_USE)).isEmpty();
        // 其他 session 不受影响 (隔离)
        assertThat(hookRegistry.getSessionHooks(otherSession, HookEventType.PRE_TOOL_USE))
                .containsKey(HookEventType.PRE_TOOL_USE);
    }

    /**
     * WHY: sessionId 为 null 时 cleanupSessionHooks 必须安全 no-op — finally 块在 sub-agent 异常
     * 路径可能拿到 null sessionId (createSubagentContext 失败). 若抛 NPE, 会掩盖原始异常.
     */
    @Test
    @DisplayName("cleanupSessionHooks(null) 安全 no-op 不抛异常")
    void cleanupSessionHooks_nullSessionId_isNoop() {
        HookRegistry hookRegistry = new HookRegistry();
        SubagentExecutor executor = new SubagentExecutor(
                null, hookRegistry, null, null, null, "model", "system-prompt");

        // 不抛异常即为通过
        executor.cleanupSessionHooks(null, null);
    }
}
