package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.AggregatedHookResult;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S6 E7·CCJ-T6-21] hook 执行期间 abort → 工具不执行 (CC stop case).
 *
 * <p>验证单元 (RED→GREEN):
 * <ol>
 *   <li>hook 批执行中 abortController.abort() → tool.execute 调用计数 = 0</li>
 *   <li>t.result = error (content = CANCEL_MESSAGE, errorCategory=abort)
 *       —— [IMP-C4 REQ-G3-2-3] content 从旧 `Error: ${stopReason}` 对齐 CC
 *       toolExecution.ts:848-860 createToolResultStopMessage content=CANCEL_MESSAGE
 *       （messages.ts:622-630；toolUseResult 元数据 `Error: ${stopReason}` 是独立字段，
 *       Java ToolResult 无独立通道，content 为 LLM 可见文本）。</li>
 *   <li>executePostToolUseFailure 不触发 (stoppedByHookAbort 短路, CC stop case return
 *       不触发失败链)</li>
 * </ol>
 */
@DisplayName("[IMP-HOOKS-S6 E7] hook 执行期间 abort → 工具不执行 + 无失败链")
class AbortDuringHookStopTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolRegistry registryWith(Tool... tools) {
        ToolRegistry r = new ToolRegistry();
        for (Tool t : tools) r.register(t);
        return r;
    }

    @Test
    @DisplayName("hook 中 abort → tool.execute 0 调用 + content=CANCEL_MESSAGE + failure hook 不触发")
    void abortDuringHook_toolNotExecuted_noFailureChain() throws Exception {
        AtomicInteger executeCalls = new AtomicInteger();
        AtomicInteger failureHookCalls = new AtomicInteger();
        AbortController abortController = new AbortController();
        Tool stub = new Tool() {
            @Override public String name() { return "abort_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                executeCalls.incrementAndGet();
                return ToolResult.success(call.id(), "ok");
            }
        };
        HookRegistry hooks = new HookRegistry();
        // hook 内部触发 abort (模拟用户中断) — 运行在 HOOK_EXECUTOR 线程
        hooks.registerPreToolUse("aborter", (toolName, input, ctx) -> {
            abortController.abort("interrupt");
            return AggregatedHookResult.proceed();
        });
        // PostToolUseFailure 观察者: abort-stop 结果不得触发失败链
        hooks.register("failure-observer", event -> {
            failureHookCalls.incrementAndGet();
            return GenericHook.HookResult.proceed();
        }, HookEventType.POST_TOOL_USE_FAILURE);
        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", abortController, List.of(), null, PermissionMode.DEFAULT);
        StreamingToolExecutor exec = new StreamingToolExecutor(registryWith(stub), ctx, null, null, hooks);
        Telemetry telemetry = new Telemetry();
        exec.setTelemetry(telemetry);

        ObjectNode input = JSON.createObjectNode();
        input.put("cmd", "ls");
        exec.add(new ToolUseBlock("toolu_abort_1", "abort_stub", input));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(executeCalls.get())
            .as("hook 期间 abort → tool.execute 必须 0 调用 (CC stop case 不执行工具)")
            .isZero();
        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_abort_1"))
            .as("abort-stop 结果必须标记 error（IMP-C2 后 isError 由执行器推导）")
            .isTrue();
        assertThat(String.valueOf(results.get(0).data()))
            .as("stop 分支 content = CANCEL_MESSAGE（CC toolExecution.ts:855 createToolResultStopMessage content=CANCEL_MESSAGE）")
            .isEqualTo(com.nexusai.application.agent.permission.PermissionRejectMessages.CANCEL_MESSAGE);
        assertThat(failureHookCalls.get())
            .as("abort-stop 结果不触发 PostToolUseFailure hooks (CC stop case return)")
            .isZero();
    }

    @Test
    @DisplayName("abort + hook stopReason → content 仍为 CANCEL_MESSAGE（stopReason 不注入 LLM 文本）")
    void abortWithStopReason_contentRemainsCancelMessage() {
        AtomicInteger executeCalls = new AtomicInteger();
        AbortController abortController = new AbortController();
        Tool stub = new Tool() {
            @Override public String name() { return "abort_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                executeCalls.incrementAndGet();
                return ToolResult.success(call.id(), "ok");
            }
        };
        HookRegistry hooks = new HookRegistry();
        hooks.registerPreToolUse("aborter", (toolName, input, ctx) -> {
            abortController.abort("interrupt");
            return new AggregatedHookResult(
                null, null, true, "user said stop", null, null, null,
                null, null, null, null, null, null, null, null, null);
        });

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", abortController, List.of(), null, PermissionMode.DEFAULT);
        StreamingToolExecutor exec = new StreamingToolExecutor(registryWith(stub), ctx, null, null, hooks);
        exec.setTelemetry(new Telemetry());

        exec.add(new ToolUseBlock("toolu_abort_2", "abort_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(executeCalls.get()).isZero();
        assertThat(String.valueOf(results.get(0).data()))
            .as("stop 分支 content 恒 CANCEL_MESSAGE（CC toolExecution.ts:855；toolUseResult 'Error: ${stopReason}' 是独立元数据）")
            .isEqualTo(com.nexusai.application.agent.permission.PermissionRejectMessages.CANCEL_MESSAGE);
        assertThat(exec.getResultErrorFlags().get("toolu_abort_2"))
            .as("abort-stop 结果必须标记 error（errorCategory 已改走 OTel/failure-hook 通道）")
            .isTrue();
    }
}
