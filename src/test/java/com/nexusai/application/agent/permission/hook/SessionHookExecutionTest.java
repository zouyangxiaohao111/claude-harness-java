package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nexusai.application.agent.tool.AbortController;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H5 v2 对抗核验 H5-GAP-1] executeEvent 消费 SessionHookStore — session hooks 闭环测试.
 *
 * <p>WHY (规则九 · 测试验证意图): 已登记缺口 J.md §J.2.1 H5-GAP-1 — SessionHookStore 是惰性
 * 存储, {@code addSessionHook/addFunctionHook} 注册的临时 hook 存入后从不被检索/执行
 * (executeEvent 只遍历 settings 持久化 genericHooks). CC getAllHooks (hooksSettings.ts:146-158)
 * 会把 session hooks 并入匹配集再执行. 本测试验证<b>三级存储 → 执行</b>闭环:
 * <ul>
 *   <li>command session hook 注册后, executeEvent 按 event+matcher 命中并执行
 *       (对齐 CC getHooksConfig hooks.ts:1500-1560 session hooks 合并)</li>
 *   <li>function hook 回调按 CC executeFunctionHook (hooks.ts:4740-4830) 语义执行:
 *       false → blocking (blockingError.command='function'), true → success, 超时 → cancelled</li>
 *   <li>onHookSuccess 仅 outcome=success 触发 (对齐 CC hooks.ts:2906-2925)</li>
 * </ul>
 * 若闭合失败: session hook 注册了永不执行 = 配置失效静默吞掉 (sub-agent 校验/强制逻辑失效).
 *
 * @since H5 v2 对抗核验修复
 */
@DisplayName("[H5-GAP-1] executeEvent 消费 SessionHookStore")
class SessionHookExecutionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 固定主会话 UUID · WHY（IMP-HR-07 测试调和）: isSessionHookEligible 要求事件 ∈ CC appState
     *  发射点集合 且 会话活跃（LlmAgentLoop.isSessionRunning），旧非 UUID 标识 SESSION_UUID.toString() parse 失败 →
     *  门控 false → session hook 永不执行。本类用例均为 PRE_TOOL_USE（CC appState 发射点，
     *  hooks.ts:2001 传 toolUseContext），故 @BeforeEach markRunning 建立活跃会话（对齐
     *  HookRegistryTest 6a/6b/6c 同款 seam）。 */
    private static final String SESSION_UUID = "00000000-0000-0000-0000-0000000000b1";

    /** 其他会话 UUID（session 隔离用例注册方）。 */
    private static final UUID OTHER_SESSION_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @BeforeEach
    void markMainSessionRunning() {
        // [IMP-HR-07 · OPD-WF6-01-05] 会话活跃才执行 session hooks（CC getHooksConfig
        // hooks.ts:1541 appState !== undefined 的 Java 等价，事件维度由事件类型承载）
        LlmAgentLoop.markRunning(SESSION_UUID);
    }

    @AfterEach
    void markMainSessionIdle() {
        // RUNNING_SESSIONS 全局注册表清理，避免跨测试泄漏（LlmAgentLoop.markIdle 计数归零移除）
        LlmAgentLoop.markIdle(SESSION_UUID);
    }

    /** 记录被执行的 command hook · WHY: 证明 executeEvent 真正把 session hook 派发到 CommandHookExecutor. */
    static class RecordingCommandHookExecutor extends CommandHookExecutor {
        volatile CommandHook lastCommand;
        volatile int callCount;

        RecordingCommandHookExecutor() {
            super();
        }

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                         String pluginRoot, String pluginId, String skillRoot,
                                         Integer hookIndex, boolean forceSyncExecution,
                                         com.nexusai.application.agent.tool.AbortController parentAbort) {
            this.lastCommand = hook;
            this.callCount++;
            return new CommandHookResult("", "", "", 0, false, false);
        }

        @Override
        public CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName, String jsonInput,
                                         String pluginRoot, String pluginId, String skillRoot,
                                         Integer hookIndex, boolean forceSyncExecution,
                                         com.nexusai.application.agent.tool.AbortController parentAbort,
                                         long defaultTimeoutMs, String hookCwd) {
            // [IMP-HOOKS-S9 DEL-01d] 12 参重载委托 10 参 — prompt 回调 参数已删除
            return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                hookIndex, forceSyncExecution, parentAbort);
        }
    }

    private static CommandHook commandHook(String command) {
        return new CommandHook(command, null, null, null, null, null, null, null);
    }

    private static HookEvent preTool(String toolName, String sessionId) {
        // [EX-HOOK R7 修正] 主线程形状（agentId=null）→ 匹配 key 回落 sessionId（CC
        // hooks.ts:2003 toolUseContext?.agentId ?? getSessionId()）；子代理形状
        // （agentId 非 null）匹配 key=agentId，见 WorktreeHooksTest R7 组。
        return HookEvent.toolPre(toolName, null, sessionId, null);
    }

    /**
     * WHY (H5-GAP-1): 注册 command session hook 后 executeEvent 必须命中并执行 — 若 matcher
     * 匹配但未执行, session hook 配置了等于没配 (CC getAllHooks 并入匹配集的等价闭环断裂).
     */
    @Test
    @DisplayName("command session hook 按 event+matcher 命中并执行")
    void commandSessionHook_executesViaExecuteEvent() {
        HookRegistry registry = new HookRegistry();
        RecordingCommandHookExecutor executor = new RecordingCommandHookExecutor();
        registry.setCommandHookExecutor(executor);
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));

        CommandHook hook = commandHook("echo session-hook");
        registry.addSessionHook(SESSION_UUID.toString(), HookEventType.PRE_TOOL_USE, "Bash", hook, null, null);

        GenericHook.HookResult result = registry.executeEvent(preTool("Bash", SESSION_UUID.toString()));

        assertThat(executor.callCount).isEqualTo(1);
        assertThat(executor.lastCommand).isEqualTo(hook);
        assertThat(result.preventContinuation()).isFalse();
    }

    /**
     * WHY (H5-GAP-1): matcher 不匹配的 session hook 必须跳过 (CC getMatchingHooks :1684
     * matchesPattern 过滤). 若不过滤, 同 session 其他工具的 hook 会误触发.
     */
    @Test
    @DisplayName("matcher 不匹配 → command session hook 不执行")
    void commandSessionHook_matcherMismatch_skipped() {
        HookRegistry registry = new HookRegistry();
        RecordingCommandHookExecutor executor = new RecordingCommandHookExecutor();
        registry.setCommandHookExecutor(executor);
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));

        CommandHook hook = commandHook("echo write-only");
        registry.addSessionHook(SESSION_UUID.toString(), HookEventType.PRE_TOOL_USE, "Write", hook, null, null);

        GenericHook.HookResult result = registry.executeEvent(preTool("Bash", SESSION_UUID.toString()));

        assertThat(executor.callCount).isZero();
        assertThat(result.preventContinuation()).isFalse();
    }

    /**
     * WHY (H5-GAP-1): 不同 session 的 hook 互不串扰 — CC session hooks 按 sessionId 隔离
     * (sessionHooks.ts:62 Map<string, SessionStore>). 若串扰, 父循环 session hook 会在子
     * agent 里误触发.
     */
    @Test
    @DisplayName("其他 session 的 hook 不执行 (session 隔离)")
    void sessionHooks_areIsolatedBySessionId() {
        HookRegistry registry = new HookRegistry();
        RecordingCommandHookExecutor executor = new RecordingCommandHookExecutor();
        registry.setCommandHookExecutor(executor);
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));

        CommandHook hook = commandHook("echo other-session");
        registry.addSessionHook(OTHER_SESSION_UUID.toString(), HookEventType.PRE_TOOL_USE, "Bash", hook, null, null);

        registry.executeEvent(preTool("Bash", SESSION_UUID.toString()));

        assertThat(executor.callCount).isZero();
    }

    /**
     * WHY (H5-GAP-1): function hook callback 返回 false → blocking (对齐 CC executeFunctionHook
     * hooks.ts:4791-4797 {@code {blockingError:{blockingError, command:'function'}, outcome:'blocking'}}).
     * 若返回 false 仍放行, 结构校验/强制逻辑失效.
     */
    @Test
    @DisplayName("function hook 回调 false → blocking (blockingError.command=function)")
    void functionHook_falseBlocksExecution() {
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));

        registry.addFunctionHook(SESSION_UUID.toString(), HookEventType.PRE_TOOL_USE, "Bash",
                (messages, signal) -> CompletableFuture.completedFuture(false), "must call StructuredOutput", null, null);

        GenericHook.HookResult result = registry.executeEvent(preTool("Bash", SESSION_UUID.toString()));

        assertThat(result.preventContinuation()).isTrue();
        assertThat(result.blockingError()).isNotNull();
        assertThat(result.blockingError().blockingError()).isEqualTo("must call StructuredOutput");
        assertThat(result.blockingError().command()).isEqualTo("function");
    }

    /**
     * WHY (H5-GAP-1): function hook callback 返回 true → success 放行 (hooks.ts:4784-4790).
     * 若 true 仍拦截, 已满足条件却重入 loop = 死循环风险.
     */
    @Test
    @DisplayName("function hook 回调 true → 放行")
    void functionHook_truePassesThrough() {
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));

        registry.addFunctionHook(SESSION_UUID.toString(), HookEventType.PRE_TOOL_USE, "Bash",
                (messages, signal) -> CompletableFuture.completedFuture(true), "blocked", null, null);

        GenericHook.HookResult result = registry.executeEvent(preTool("Bash", SESSION_UUID.toString()));

        assertThat(result.preventContinuation()).isFalse();
        assertThat(result.blockingError()).isNull();
    }

    /**
     * WHY (H5-GAP-1): function hook 收到 executeEvent 传入的 messages (CC executeHooks messages
     * 参数). 若 messages 不传递, 依赖消息判断的回调 (如 hasSuccessfulToolCall) 永远拿空列表误判.
     */
    @Test
    @DisplayName("function hook 回调收到 executeEvent 传入的 messages")
    void functionHook_receivesMessages() {
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));

        AtomicReference<List<ChatMessageDto>> captured = new AtomicReference<>();
        registry.addFunctionHook(SESSION_UUID.toString(), HookEventType.PRE_TOOL_USE, "Bash",
                (messages, signal) -> {
                    captured.set(messages);
                    return CompletableFuture.completedFuture(true);
                }, "blocked", null, null);

        ChatMessageDto msg = new ChatMessageDto(null, null, Role.user,
                null, "hi", null, null, null, null, null, null, null, null, null, null, null, null);
        registry.executeEvent(preTool("Bash", SESSION_UUID.toString()), List.of(msg));

        assertThat(captured.get()).containsExactly(msg);
    }

    /**
     * WHY (H5-GAP-1): command hook 执行成功后 onHookSuccess 触发 (对齐 CC hooks.ts:2906-2925
     * getSessionHookCallback + onHookSuccess, 仅 outcome=success). 若成功通知丢失, 依赖
     * onHookSuccess 的调用方 (sub-agent 校验) 收不到完成信号.
     */
    @Test
    @DisplayName("command hook 成功后 onHookSuccess 触发 (仅 success)")
    void commandSessionHook_successFiresOnHookSuccess() {
        HookRegistry registry = new HookRegistry();
        RecordingCommandHookExecutor executor = new RecordingCommandHookExecutor();
        registry.setCommandHookExecutor(executor);
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));

        AtomicReference<SessionHook> captured = new AtomicReference<>();
        CommandHook hook = commandHook("echo success");
        registry.addSessionHook(SESSION_UUID.toString(), HookEventType.PRE_TOOL_USE, "Bash", hook,
                (h, result) -> captured.set(h), null);

        registry.executeEvent(preTool("Bash", SESSION_UUID.toString()));

        assertThat(captured.get()).isSameAs(hook);
    }

    /**
     * WHY (D-06): CC FunctionHookCallback 签名 (messages, signal?) (sessionHooks.ts:15-18),
     * executeFunctionHook 用 createCombinedAbortSignal 竞速, abort → cancelled
     * (hooks.ts:4758-4788). Java 端以 {@link AbortController} 承载该信号: 超时路径先
     * {@code signal.abort} 再返回 CANCELLED — 否则回调无法感知外部取消, 长回调不会提前停止,
     * 与 CC 语义偏离.
     */
    @Test
    @DisplayName("function hook 回调收到 signal; 超时 → outcome=CANCELLED 且 signal 已取消 (D-06)")
    void functionHook_signalPassed_timeoutCancelsSignal() {
        HookRegistry registry = new HookRegistry();
        registry.setHookMatcherEngine(new HookMatcherEngine(null, null));

        AtomicReference<AbortController> capturedSignal = new AtomicReference<>();
        AtomicBoolean cancelObserved = new AtomicBoolean();
        registry.addFunctionHook(SESSION_UUID.toString(), HookEventType.PRE_TOOL_USE, "Bash",
                (messages, signal) -> {
                    capturedSignal.set(signal);
                    signal.onCancel(s -> cancelObserved.set(true));
                    return new CompletableFuture<>(); // 永不完成 → 超时
                }, "blocked", 200L, null);

        GenericHook.HookResult result = registry.executeEvent(preTool("Bash", SESSION_UUID.toString()));

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.CANCELLED);
        assertThat(capturedSignal.get()).isNotNull();
        assertThat(capturedSignal.get().isCancelled()).isTrue();
        assertThat(cancelObserved.get()).isTrue();
    }
}
