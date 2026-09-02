package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookBlockingError;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S6 CCJ-T6-02] PostToolUse 多 blocking → 逐 hook 产 N 个
 * hook_blocking_error 附件 (CC toolHooks.ts:105-115).
 */
@DisplayName("[IMP-HOOKS-S6 CCJ-T6-02] 多 blocking 逐 hook 附件")
class MultiBlockingAttachmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolRegistry registryWith(Tool... tools) {
        ToolRegistry r = new ToolRegistry();
        for (Tool t : tools) r.register(t);
        return r;
    }

    @Test
    @DisplayName("2 个 blocking PostToolUse hook → 恰 2 个 hook_blocking_error 附件")
    void twoBlockingPostHooks_twoBlockingErrorAttachments() {
        Tool stub = new Tool() {
            @Override public String name() { return "multi_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
        };
        HookRegistry hooks = new HookRegistry();
        // 2 个 hook 各自返回 blockingError (逐 hook 完成序收集)
        hooks.registerPostToolUse("blocker-a", (toolName, input, result, ctx, stopHookActive) ->
            new GenericHook.HookResult(false,
                new HookBlockingError("blocked by A", "hook-a"),
                null, null, null, null, null, null, null,
                GenericHook.HookOutcome.BLOCKING, null, null, null, null, null, null, null, null));
        hooks.registerPostToolUse("blocker-b", (toolName, input, result, ctx, stopHookActive) ->
            new GenericHook.HookResult(false,
                new HookBlockingError("blocked by B", "hook-b"),
                null, null, null, null, null, null, null,
                GenericHook.HookOutcome.BLOCKING, null, null, null, null, null, null, null, null));

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
        StreamingToolExecutor exec = new StreamingToolExecutor(registryWith(stub), ctx, null, null, hooks);
        exec.setTelemetry(new Telemetry());
        AgentState state = new AgentState("system-prompt");
        exec.setAgentState(state);

        exec.add(new ToolUseBlock("toolu_multi_1", "multi_stub", JSON.createObjectNode()));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_multi_1"))
            .as("multi_stub 正常执行 error flag 必须为 false（IMP-C2 后 isError 由执行器推导）")
            .isFalse();
        List<AttachmentMessageDto> blockingAtts = state.attachments().stream()
            .filter(a -> "hook_blocking_error".equals(a.type()))
            .toList();
        assertThat(blockingAtts)
            .as("N 个 blocking hook → N 个 hook_blocking_error 附件 (CC toolHooks.ts:105-115)")
            .hasSize(2);
        assertThat(blockingAtts.stream().map(AttachmentMessageDto::content))
            .containsExactlyInAnyOrder("blocked by A", "blocked by B");
    }
}
