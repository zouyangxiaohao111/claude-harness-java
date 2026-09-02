package com.nexusai.application.agent;

import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.hook.CollapseHookSummaries;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Notification;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * [R6-IMP] DEL-TH-06 恢复聚焦测试：stop_hook_summary 逐 hook 累计。
 *
 * <p>WHY 这组测试重要（09 §7.5 DEL-TH-06 登记）：CC stopHooks.ts:175-333 逐 result 累计
 * hookCount / hookInfos / hookErrors / hasOutput / preventedContinuation + stopReason →
 * createStopHookSummaryMessage（stopHooks.ts:298-308）+ hookErrors 通知（stopHooks.ts:310-317）。
 * Java 此前 executeEvent 折叠单条 + DEL-TH-06 删除近似追踪 → 摘要链路 0 生产调用点。
 * R6 恢复为 {@link HookRegistry#executeStopHooksCollecting}（executeEventAll 逐 hook）：
 * <ul>
 *   <li>blockingError（exit=2）→ hookErrors.push + hasOutput（CC stopHooks.ts:240-247）</li>
 *   <li>hook_non_blocking_error → stderr || 'Exit code N' + hasOutput（CC :207-212）</li>
 *   <li>hook_error_during_execution → content + hasOutput（CC :213-216）</li>
 *   <li>hook_success stdout/stderr trim 非空 → hasOutput（CC :217-228）</li>
 *   <li>preventContinuation → preventedContinuation=true + stopReason（CC :252-256）</li>
 *   <li>无 hook → hookCount=0 零副作用（CC executeStopHooks hasHookForEvent 早返）</li>
 * </ul>
 * 测试验证<b>意图</b>：摘要内容逐条忠实于 CC 累计规则（非仅"方法被调用"）。
 */
@DisplayName("[R6-IMP] Stop hook summary 逐条累计（DEL-TH-06 恢复 · CC stopHooks.ts:175-333）")
class StopHookSummaryCollectTest {

    // ═══════════════════════════════════════════════════════════════════════
    // 1. blockingError（exit=2 语义）→ hookErrors + hasOutput
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("blockingError → hookErrors 含文本 + hasOutput=true；Java exit=2 双置不污染 preventedContinuation")
    void blockingError_addsToHookErrorsAndHasOutput() {
        // WHY: CC stopHooks.ts:240-247 blockingError → hookErrors.push + hasOutput=true。
        //   Java CommandHookExecutor exit=2 映射同时置 preventContinuation=true（既有契约），
        //   CC 真源 exit=2 仅 yield blockingError —— 累计必须排除 blockingError 结果，
        //   否则 exit=2 会错误显示为"阻止继续"而非"阻塞重入"。
        HookRegistry registry = registryWithStop(event ->
            GenericHook.HookResult.stop("blocked", "stop hook blocked: disk full"));

        HookRegistry.StopHookCollectResult collect =
            registry.executeStopHooksCollecting(HookEvent.stop("s-1", null, false, null), null);

        assertThat(collect.hookCount()).isEqualTo(1);
        assertThat(collect.hookErrors()).containsExactly("stop hook blocked: disk full");
        assertThat(collect.hasOutput()).isTrue();
        assertThat(collect.preventedContinuation())
            .as("exit=2 blocking 不得污染 preventedContinuation（CC 真源 exit=2 无 preventContinuation）")
            .isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. hook_non_blocking_error attachment → stderr || 'Exit code N'
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("hook_non_blocking_error → hookErrors 取 stderr + hasOutput=true（CC stopHooks.ts:207-212）")
    void nonBlockingErrorAttachment_usesStderr() {
        HookRegistry registry = registryWithStop(event -> new GenericHook.HookResult(false, null, null, null,
            AttachmentMessageDto.hookNonBlockingError("hook-1", null, "Stop", "boom: timeout", "out", 1, "cmd-1", 10L),
            null, null, null, null, null, null, null, null, null, null, null, null, null));

        HookRegistry.StopHookCollectResult collect =
            registry.executeStopHooksCollecting(HookEvent.stop("s-1", null, false, null), null);

        assertThat(collect.hookErrors()).containsExactly("boom: timeout");
        assertThat(collect.hasOutput()).as("non_blocking_error 恒有输出（CC :208 hasOutput=true）").isTrue();
    }

    @Test
    @DisplayName("hook_non_blocking_error 空 stderr → 'Exit code N' 兜底（CC stderr || `Exit code ${exitCode}`）")
    void nonBlockingErrorAttachment_emptyStderr_fallsBackToExitCode() {
        HookRegistry registry = registryWithStop(event -> new GenericHook.HookResult(false, null, null, null,
            AttachmentMessageDto.hookNonBlockingError("hook-1", null, "Stop", null, "out", 1, "cmd-1", 10L),
            null, null, null, null, null, null, null, null, null, null, null, null, null));

        HookRegistry.StopHookCollectResult collect =
            registry.executeStopHooksCollecting(HookEvent.stop("s-1", null, false, null), null);

        assertThat(collect.hookErrors()).containsExactly("Exit code 1");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. hook_error_during_execution → content
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("hook_error_during_execution → hookErrors 取 content + hasOutput=true（CC stopHooks.ts:213-216）")
    void errorDuringExecution_addsContentToHookErrors() {
        HookRegistry registry = registryWithStop(event -> new GenericHook.HookResult(false, null, null, null,
            AttachmentMessageDto.hookErrorDuringExecution("hook-1", null, "Stop", "java boom: NPE"),
            null, null, null, null, null, null, null, null, null, null, null, null, null));

        HookRegistry.StopHookCollectResult collect =
            registry.executeStopHooksCollecting(HookEvent.stop("s-1", null, false, null), null);

        assertThat(collect.hookErrors()).containsExactly("java boom: NPE");
        assertThat(collect.hasOutput()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. hook_success → stdout/stderr trim 非空才有 hasOutput
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("hook_success 带 stdout → hasOutput=true（CC stopHooks.ts:217-228）")
    void hookSuccess_withStdout_setsHasOutput() {
        HookRegistry registry = registryWithStop(event -> new GenericHook.HookResult(false, null, null, null,
            AttachmentMessageDto.hookSuccess("hook-1", null, "Stop", "", "hello world", null, 0, "cmd-1", 5L),
            null, null, null, null, null, null, null, null, null, null, null, null, null));

        HookRegistry.StopHookCollectResult collect =
            registry.executeStopHooksCollecting(HookEvent.stop("s-1", null, false, null), null);

        assertThat(collect.hasOutput()).isTrue();
        assertThat(collect.hookErrors()).as("hook_success 不进 hookErrors").isEmpty();
    }

    @Test
    @DisplayName("hook_success 无输出 → hasOutput=false（CC trim 后空 → 不置位）")
    void hookSuccess_noOutput_keepsHasOutputFalse() {
        HookRegistry registry = registryWithStop(event -> new GenericHook.HookResult(false, null, null, null,
            AttachmentMessageDto.hookSuccess("hook-1", null, "Stop"),
            null, null, null, null, null, null, null, null, null, null, null, null, null));

        HookRegistry.StopHookCollectResult collect =
            registry.executeStopHooksCollecting(HookEvent.stop("s-1", null, false, null), null);

        assertThat(collect.hasOutput()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. preventContinuation + stopReason
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("preventContinuation → preventedContinuation=true + stopReason 透传（CC stopHooks.ts:252-256）")
    void preventContinuation_setsStopReason() {
        HookRegistry registry = registryWithStop(event ->
            GenericHook.HookResult.stop("Hook 'guard' 禁止继续执行"));

        HookRegistry.StopHookCollectResult collect =
            registry.executeStopHooksCollecting(HookEvent.stop("s-1", null, false, null), null);

        assertThat(collect.preventedContinuation()).isTrue();
        assertThat(collect.stopReason()).isEqualTo("Hook 'guard' 禁止继续执行");
    }

    @Test
    @DisplayName("preventContinuation 无 stopReason → 默认 'Stop hook prevented continuation'（CC || 兜底）")
    void preventContinuation_withoutStopReason_usesDefault() {
        // CC stopHooks.ts:255-256: stopReason = result.stopReason || 'Stop hook prevented continuation'
        HookRegistry registry = registryWithStop(event -> GenericHook.HookResult.stop((String) null));

        HookRegistry.StopHookCollectResult collect =
            registry.executeStopHooksCollecting(HookEvent.stop("s-1", null, false, null), null);

        assertThat(collect.preventedContinuation()).isTrue();
        assertThat(collect.stopReason()).isEqualTo("Stop hook prevented continuation");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. hookInfos 命令清单（getHookDisplayText 等价：statusMessage 优先）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("hookInfos 收集命令清单 · statusMessage 优先（CC getHookDisplayText hooksSettings.ts:68-90）")
    void hookInfos_collectsCommandsWithStatusMessagePrecedence() {
        // WHY: CC progress 消息 command = getHookDisplayText(hook)（hooks.ts:2103），statusMessage
        // 非空时优先（hooksSettings.ts:71-75）。Java stopHookCommandOf 必须同序，否则摘要命令清单
        // 显示原始命令而非 hook 自定义展示文案。
        HookRegistry registry = new HookRegistry();
        registry.register("plain", event -> GenericHook.HookResult.proceed()
            .withHook(new CommandHook("echo hi", null, null, null, null, null, null, null)),
            HookEventType.STOP);
        registry.register("styled", event -> GenericHook.HookResult.proceed()
            .withHook(new CommandHook("echo hi", null, null, null, "检查磁盘空间…", null, null, null)),
            HookEventType.STOP);

        HookRegistry.StopHookCollectResult collect =
            registry.executeStopHooksCollecting(HookEvent.stop("s-1", null, false, null), null);

        assertThat(collect.hookCount()).isEqualTo(2);
        assertThat(collect.hookInfos()).containsExactly("echo hi", "检查磁盘空间…");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. 无 hook 零副作用
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无 Stop hook → hookCount=0 + results 空（CC executeStopHooks hasHookForEvent 早返等价）")
    void noStopHooks_zeroSideEffect() {
        // WHY: CC executeStopHooks（hooks.ts:3646-3650）hasHookForEvent 不命中直接 return，
        //   generator 无任何 yield → hookCount 保持 0 → 不生成 summary（stopHooks.ts:298）。
        //   Java 端 results 空 + hookCount=0 即"零副作用"契约。
        HookRegistry registry = new HookRegistry();
        registry.register("other-event", event -> GenericHook.HookResult.proceed(),
            HookEventType.PRE_TOOL_USE);

        HookRegistry.StopHookCollectResult collect =
            registry.executeStopHooksCollecting(HookEvent.stop("s-1", null, false, null), null);

        assertThat(collect).isNotNull();
        assertThat(collect.results()).isEmpty();
        assertThat(collect.hookCount()).isZero();
        assertThat(collect.hookInfos()).isEmpty();
        assertThat(collect.hookErrors()).isEmpty();
        assertThat(collect.preventedContinuation()).isFalse();
        assertThat(collect.hasOutput()).isFalse();
        assertThat(collect.stopReason()).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. SubagentStop 路径同样生效
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SubagentStop 事件 → attachment hookEvent=SubagentStop 同样累计（CC attachment 双事件过滤）")
    void subagentStop_event_accumulates() {
        // WHY: CC stopHooks.ts:204-205 attachment 过滤 hookEvent ∈ {Stop, SubagentStop}；
        //   子代理 turn 结束（executeStopHooks subagentId → 'SubagentStop'）必须走同一累计。
        HookRegistry registry = registryWithStop(event -> new GenericHook.HookResult(false, null, null, null,
            AttachmentMessageDto.hookNonBlockingError("hook-1", null, "SubagentStop", "sub boom", null, 1, null, null),
            null, null, null, null, null, null, null, null, null, null, null, null, null), HookEventType.SUBAGENT_STOP);

        HookRegistry.StopHookCollectResult collect = registry.executeStopHooksCollecting(
            HookEvent.subagentStop("agent-1", "explore", "agent-1", false, null, "done"), null);

        assertThat(collect.hookCount()).isEqualTo(1);
        assertThat(collect.hookErrors()).containsExactly("sub boom");
        assertThat(collect.hasOutput()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. LlmAgentLoop Stop 段摘要通道端到端：recordStopHookSummary 保留 stopReason/hookErrors
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("recordStopHookSummary 通道保留 stopReason + hookErrors（LlmAgentLoop Stop 段记录契约）")
    void recordStopHookSummary_roundTripsStopReasonAndErrors() {
        // WHY: LlmAgentLoop Stop 段把 StopHookCollectResult 组装为 SimpleHookMsg 后
        //   recordStopHookSummary —— 该通道（AgentState 本地暂存，@JsonIgnore）必须原样承载
        //   stopReason 与 hookErrors，否则摘要丢失 preventContinuation 原因（验收契约）。
        AgentState state = new AgentState("s-1");
        // [IMP-HOOKS-S7 H6] 生产形态 hookLabel=null（CC stopHooks.ts:297-308 8 参无 hookLabel）——
        //   守卫不过不折叠，单条摘要原样保留
        state.recordStopHookSummary(new CollapseHookSummaries.SimpleHookMsg(
            null, 2, List.of("echo a", "echo b"), List.of("boom"), true, true, 12L, "guard 阻止"));

        List<CollapseHookSummaries.HookMessage> summaries = state.stopHookSummaries();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).hookCount()).isEqualTo(2);
        assertThat(summaries.get(0).hookErrors()).containsExactly("boom");
        assertThat(summaries.get(0).preventedContinuation()).isTrue();
        assertThat(summaries.get(0).stopReason()).isEqualTo("guard 阻止");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 10. hookErrors>0 → 通知（CC stopHooks.ts:310-317 addNotification 等价通道）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("notifyStopHookError 触发 addNotification（key=stop-hook-error, level=ERROR）")
    void notifyStopHookError_firesAddNotification() {
        // WHY: CC stopHooks.ts:310-317 hookErrors>0 → addNotification({key:'stop-hook-error',...})
        //   priority immediate。Java 等价通道 ToolUseContext.addNotification()（Stage 3.3 UI
        //   回调），通知契约必须可被前端消费（id=CC key, level=ERROR）。
        AtomicReference<Notification> captured = new AtomicReference<>();
        ToolUseContext tuc = tucWithNotification(captured::set);

        LlmAgentLoop.notifyStopHookError(tuc, List.of("boom"));

        Notification n = captured.get();
        assertThat(n).isNotNull();
        assertThat(n.id()).isEqualTo("stop-hook-error");
        assertThat(n.level()).isEqualTo(Notification.Level.ERROR);
        assertThat(n.title()).contains("Stop hook error");
    }

    @Test
    @DisplayName("notifyStopHookError 无 addNotification 回调 → 静默（仅日志，不抛异常）")
    void notifyStopHookError_noopConsumer_doesNotThrow() {
        // WHY: CC addNotification?.() 可选链 —— TUC 未接线 UI 回调（子代理 compact ctor 置 null）
        //   时必须 no-op 不抛（CC "subagents can't control parent UI"）。
        ToolUseContext bareTuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, List.of(), null, AbortController.NOOP);
        assertThatCode(() -> LlmAgentLoop.notifyStopHookError(bareTuc, List.of("boom")))
            .doesNotThrowAnyException();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // [IMP-HOOKS-S5 D-11 ②] session function hooks 参与 Stop 收集
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * WHY (D-11 ②): CC executeStopHooks（hooks.ts:3688-3696）走含 appState 的 executeHooks →
     * session function hooks 与 session command hooks 都参与 Stop/SubagentStop 收集
     * （getHooksConfig hooks.ts:1552-1562 并入 sessionFunctionHooks）。Java 旧 executeEventAll
     * 只执行配置驱动 + programmatic，session function hooks 丢失 → Stop 收集缺该通道。
     * 白名单（SESSION_HOOK_EVENTS）内事件在 executeEventAll 补执行 session function hooks。
     */
    @Test
    @DisplayName("D-11 ②: session function hook（Stop）→ executeStopHooksCollecting 收集（blocking → hookErrors）")
    void sessionFunctionHook_participatesInStopCollecting() {
        HookRegistry registry = new HookRegistry();
        registry.addFunctionHook("sess-1", HookEventType.STOP, "*",
            (messages, signal) -> java.util.concurrent.CompletableFuture.completedFuture(false),
            "session fn guard", null, null);

        HookRegistry.StopHookCollectResult collect =
            registry.executeStopHooksCollecting(HookEvent.stop("sess-1", null, false, null), null);

        assertThat(collect.hookCount())
            .as("session function hook 必须参与 Stop 收集（CC getHooksConfig 并入 sessionFunctionHooks）")
            .isEqualTo(1);
        assertThat(collect.hookErrors())
            .as("function hook false → blocking errorMessage（CC executeFunctionHook hooks.ts:4792-4797）")
            .contains("session fn guard");
        assertThat(collect.hasOutput()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static HookRegistry registryWithStop(GenericHook hook) {
        return registryWithStop(hook, HookEventType.STOP);
    }

    private static HookRegistry registryWithStop(GenericHook hook, HookEventType type) {
        HookRegistry registry = new HookRegistry();
        registry.register("stop-hook", hook, type);
        return registry;
    }

    /** 32 参构造器构建携带真实 addNotification 回调的 TUC（of() 工厂不含 UI 回调）。 */
    private static ToolUseContext tucWithNotification(Consumer<Notification> addNotification) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, null, null, null, Map.of(), p -> {},
            null, null, null, null,
            addNotification, null, null, null, null, null, null, null, null, null);
    }
}
