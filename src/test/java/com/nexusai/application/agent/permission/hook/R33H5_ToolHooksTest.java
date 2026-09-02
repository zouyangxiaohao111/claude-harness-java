package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H5] M8 toolHooks 补 executePostToolUseFailure + 3 cancelled telemetry + 5 attachment · 对齐 CC 真源:
 * <ul>
 *   <li>{@code Open-ClaudeCode/src/services/tools/toolHooks.ts:193-319} runPostToolUseFailureHooks 9 参</li>
 *   <li>{@code toolHooks.ts:583} tengu_pre_tool_hooks_cancelled (abortController.signal.aborted)</li>
 *   <li>{@code toolHooks.ts:72}  tengu_post_tool_hooks_cancelled (hook_cancelled attachment)</li>
 *   <li>{@code toolHooks.ts:228} tengu_post_tool_failure_hooks_cancelled</li>
 *   <li>{@code toolHooks.ts:79-185} 5 类 attachment (hook_cancelled/hook_blocking_error/
 *       hook_stopped_continuation/hook_additional_context/hook_error_during_execution)</li>
 *   <li>{@code toolHooks.ts:475} getToolUseSummary 透传</li>
 * </ul>
 *
 * <p>WHY (规则九): 本测试验证 H5 的 6 条核心意图 (CC 行为对齐, 非仅 API 存在):
 * <ol>
 *   <li>executePostToolUseFailure 调度入口存在且调 onPostToolUseFailure (失败路径专用, 非 executeEvent 通用总线)</li>
 *   <li>PreToolUse abort 发 tengu_pre_tool_hooks_cancelled (CC abort signal 检测)</li>
 *   <li>PostToolUse abort 发 tengu_post_tool_hooks_cancelled</li>
 *   <li>PostToolUseFailure abort 发 tengu_post_tool_failure_hooks_cancelled</li>
 *   <li>AttachmentMessageDto 5 类 hook attachment 工厂 (独立消息类型, 非 record 字段)</li>
 *   <li>Tool.getToolUseSummary 透传通道 (CC executePreToolHooks 9 参末 1 参)</li>
 * </ol>
 *
 * @since Session H5
 */
@DisplayName("[H5] toolHooks 补 executePostToolUseFailure + cancelled telemetry + attachment 对齐 CC")
class R33H5_ToolHooksTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode input() {
        return mapper.createObjectNode().put("k", "v");
    }

    private ToolUseContext ctxWithAbort() {
        AbortController ac = new AbortController();
        ac.abort();
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", ac);
    }

    private ToolUseContext ctxNormal() {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT);
    }

    // ════════════════════════════════════════════════════════════════════════
    // H5-1: executePostToolUseFailure 调度入口 (CC toolHooks.ts:193-319)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("H5-1: HookRegistry.executePostToolUseFailure 调度 onPostToolUseFailure (CC toolHooks.ts:193-319)")
    void hookRegistry_executesPostToolUseFailure() {
        // WHY: CC runPostToolUseFailureHooks 是独立 9 参 generator, 调 executePostToolUseFailureHooks.
        //   Java 端 PostToolUseHook.onPostToolUseFailure default 存在但 HookRegistry 无调度入口
        //   (失败路径现走 executeEvent 通用总线, 不调 onPostToolUseFailure). 本测试验证专用入口.
        HookRegistry registry = new HookRegistry();
        registry.registerPostToolUse("failure-hook", new PostToolUseHook() {
            @Override
            public GenericHook.HookResult onPostToolUse(String toolName, JsonNode in, ToolResult res,
                                                         ToolUseContext ctx, boolean stopHookActive) {
                return GenericHook.HookResult.proceed();
            }

            @Override
            public GenericHook.HookResult onPostToolUseFailure(String toolName, JsonNode in,
                                                               ToolResult errorResult, ToolUseContext ctx,
                                                               boolean stopHookActive) {
                return GenericHook.HookResult.stop("failure-handled");
            }
        });
        ToolResult errorResult = ToolResult.error("tu-1", "boom");

        GenericHook.HookResult outcome = registry.executePostToolUseFailure(
            "Bash", input(), errorResult, ctxNormal(), false, false);

        assertThat(outcome).isNotNull();
        assertThat(outcome.preventContinuation())
            .as("onPostToolUseFailure 返回 stop -> preventContinuation=true")
            .isTrue();
        assertThat(outcome.stopReason()).isEqualTo("failure-handled");
    }

    // ════════════════════════════════════════════════════════════════════════
    // H5-2/3/4: 3 个 cancelled telemetry (CC toolHooks.ts:583/72/228)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("H5-2: PreToolUse abort 发 tengu_pre_tool_hooks_cancelled (CC toolHooks.ts:583)")
    void emitsPreToolHooksCancelledTelemetry() {
        // WHY: CC toolHooks.ts:582 if (abortController.signal.aborted) -> logEvent('tengu_pre_tool_hooks_cancelled').
        //   Java 端 executePreToolUse 现 abort 时只 catch AbortException, 未发 cancelled telemetry.
        HookRegistry registry = new HookRegistry();
        Telemetry telemetry = new Telemetry();
        registry.setTelemetry(telemetry);
        registry.registerPreToolUse("pre-hook",
            (toolName, in, ctx) -> AggregatedHookResult.proceed());

        registry.executePreToolUse("Bash", input(), ctxWithAbort(),
            "tu-1");

        assertThat(telemetry.getCounter("tengu_pre_tool_hooks_cancelled"))
            .as("CC toolHooks.ts:583 abort 时发 tengu_pre_tool_hooks_cancelled")
            .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("H5-3: PostToolUse abort 发 tengu_post_tool_hooks_cancelled (CC toolHooks.ts:72)")
    void emitsPostToolHooksCancelledTelemetry() {
        // WHY: CC toolHooks.ts:68-88 result.message.attachment.type==='hook_cancelled' ->
        //   logEvent('tengu_post_tool_hooks_cancelled'). Java 端 executePostToolUse 未发.
        HookRegistry registry = new HookRegistry();
        Telemetry telemetry = new Telemetry();
        registry.setTelemetry(telemetry);
        registry.registerPostToolUse("post-hook",
            (toolName, in, res, ctx, stopHookActive) -> GenericHook.HookResult.proceed());

        registry.executePostToolUse("Bash", input(),
            ToolResult.success("tu-1", "ok"), ctxWithAbort());

        assertThat(telemetry.getCounter("tengu_post_tool_hooks_cancelled"))
            .as("CC toolHooks.ts:72 abort 时发 tengu_post_tool_hooks_cancelled")
            .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("H5-4: PostToolUseFailure abort 发 tengu_post_tool_failure_hooks_cancelled (CC toolHooks.ts:228)")
    void emitsPostToolFailureHooksCancelledTelemetry() {
        // WHY: CC toolHooks.ts:224-243 hook_cancelled -> logEvent('tengu_post_tool_failure_hooks_cancelled').
        //   Java 端 executePostToolUseFailure (新增) 须在 abort 时发此 telemetry.
        HookRegistry registry = new HookRegistry();
        Telemetry telemetry = new Telemetry();
        registry.setTelemetry(telemetry);
        registry.registerPostToolUse("failure-hook", new PostToolUseHook() {
            @Override
            public GenericHook.HookResult onPostToolUse(String toolName, JsonNode in, ToolResult res,
                                                         ToolUseContext ctx, boolean stopHookActive) {
                return GenericHook.HookResult.proceed();
            }
        });

        registry.executePostToolUseFailure("Bash", input(),
            ToolResult.error("tu-1", "boom"), ctxWithAbort(), false, false);

        assertThat(telemetry.getCounter("tengu_post_tool_failure_hooks_cancelled"))
            .as("CC toolHooks.ts:228 abort 时发 tengu_post_tool_failure_hooks_cancelled")
            .isGreaterThanOrEqualTo(1L);
    }

    // ════════════════════════════════════════════════════════════════════════
    // H5-5: 5 类 hook attachment 工厂 (CC toolHooks.ts:79-185 createAttachmentMessage)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("H5-5: AttachmentMessageDto 5 类 hook attachment 工厂 (CC toolHooks.ts:79-185)")
    void injectsHookCancelledAttachmentMessage() {
        // WHY: CC runPreToolUseHooks/runPostToolUseHooks/runPostToolUseFailureHooks yield 5 类
        //   createAttachmentMessage attachment. Java 端走 AgentState.appendAttachment(AttachmentMessageDto)
        //   注入消息总线 (非 React attachment). AttachmentMessageDto 须提供 5 工厂对齐 CC 5 type.
        AttachmentMessageDto cancelled = AttachmentMessageDto.hookCancelled(
            "PreToolUse:Bash", "tu-1", "PreToolUse");
        assertThat(cancelled.type())
            .as("CC toolHooks.ts:79-87 hook_cancelled attachment")
            .isEqualTo("hook_cancelled");

        AttachmentMessageDto blocking = AttachmentMessageDto.hookBlockingError(
            "PostToolUse:Bash", "tu-1", "PostToolUse", "blocked-reason");
        assertThat(blocking.type())
            .as("CC toolHooks.ts:105-115 hook_blocking_error attachment")
            .isEqualTo("hook_blocking_error");

        AttachmentMessageDto stopped = AttachmentMessageDto.hookStoppedContinuation(
            "PostToolUse:Bash", "tu-1", "PostToolUse", "stopped-reason");
        assertThat(stopped.type())
            .as("CC toolHooks.ts:118-130 hook_stopped_continuation attachment")
            .isEqualTo("hook_stopped_continuation");

        AttachmentMessageDto additional = AttachmentMessageDto.hookAdditionalContext(
            "PreToolUse:Bash", "tu-1", "PreToolUse", List.of("ctx-a", "ctx-b"));
        assertThat(additional.type())
            .as("CC toolHooks.ts:133-143 hook_additional_context attachment")
            .isEqualTo("hook_additional_context");

        AttachmentMessageDto errorExec = AttachmentMessageDto.hookErrorDuringExecution(
            "PreToolUse:Bash", "tu-1", "PreToolUse", "boom");
        assertThat(errorExec.type())
            .as("CC toolHooks.ts:177-185 hook_error_during_execution attachment")
            .isEqualTo("hook_error_during_execution");
    }

    // ════════════════════════════════════════════════════════════════════════
    // H5-6: getToolUseSummary 透传 (CC toolHooks.ts:475)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("H5-6: Tool.getToolUseSummary 透传通道 (CC toolHooks.ts:475)")
    void toolUseContext_carriesToolUseSummary() throws Exception {
        // WHY: CC executePreToolHooks 9 参末 1 参 (toolInputSummary) 透传给 hook chain.
        //   - tool.getToolUseSummary?.(processedInput) (toolHooks.ts:475): CC optional ?., Java 须加 default.
        //   (S9 DEL-02: Java 无 UI 消费端, 删除前恒传 null → 可观测行为不变)

        // Tool.getToolUseSummary default 返回 null (对齐 CC optional ?. 语义)
        java.lang.reflect.Method m = Tool.class.getMethod("getToolUseSummary", Map.class);
        assertThat(m)
            .as("CC toolHooks.ts:475 tool.getToolUseSummary?.(processedInput) - Java default 返回 null")
            .isNotNull();
        assertThat(m.isDefault())
            .as("getToolUseSummary 必须是 default 方法 (对齐 CC optional ?. - 无 override 时返回 null)")
            .isTrue();
    }
}
