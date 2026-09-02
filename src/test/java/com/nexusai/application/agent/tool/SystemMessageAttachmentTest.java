package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.AggregatedHookResult;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PostToolUseHook;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S6 CCJ-T6-19] hook JSON 输出 {systemMessage} → hook_system_message
 * attachment (CC hooks.ts:2770-2780) · PreToolUse/PostToolUse/PostToolUseFailure 三链.
 */
@DisplayName("[IMP-HOOKS-S6 CCJ-T6-19] systemMessage → hook_system_message 附件三链")
class SystemMessageAttachmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolRegistry registryWith(Tool... tools) {
        ToolRegistry r = new ToolRegistry();
        for (Tool t : tools) r.register(t);
        return r;
    }

    private static long systemMessageAttachmentCount(AgentState state) {
        return state.attachments().stream()
            .filter(a -> "hook_system_message".equals(a.type()))
            .count();
    }

    @Test
    @DisplayName("PreToolUse/PostToolUse/PostToolUseFailure 三链各注入 1 个 hook_system_message")
    void systemMessage_attachedOnAllThreeChains() {
        AtomicBooleanTool tool = new AtomicBooleanTool();
        HookRegistry hooks = new HookRegistry();
        // Pre 链: [DEL-WF1-TY-02 v4] AHR.systemMessages 已删除 — systemMessage 经
        //   foldSystemMessages 就地折叠为 hook_system_message attachment 并入 message 通道
        //   (对齐 CC hooks.ts:2769-2780 逐结果 yield → 逐条注入)
        hooks.registerPreToolUse("pre-sysmsg", (toolName, input, ctx) -> new AggregatedHookResult(
            AggregatedHookResult.foldSystemMessages(null, List.of("pre chain system message"),
                "PreToolUse:sysmsg_stub", "toolu_sysmsg_1", "PreToolUse"),
            null, false, null, null, null, null,
            null, null, null, null, null, null, null, null, null));
        // Post 链: HookResult.systemMessages (3 字段, List)
        hooks.registerPostToolUse("post-sysmsg", (toolName, input, result, ctx, stopHookActive) ->
            new GenericHook.HookResult(false, null, List.of("post chain system message"), null,
                null, null, null, null, null,
                GenericHook.HookOutcome.SUCCESS, null, null, null, null, null, null, null, null));
        // Failure 链: onPostToolUseFailure 返回 HookResult.systemMessages
        hooks.registerPostToolUse("failure-sysmsg", new PostToolUseHook() {
            @Override
            public GenericHook.HookResult onPostToolUse(String toolName, JsonNode input, ToolResult result,
                                                        ToolUseContext ctx, boolean stopHookActive) {
                return GenericHook.HookResult.proceed();
            }
            @Override
            public GenericHook.HookResult onPostToolUseFailure(String toolName, JsonNode input,
                                                               ToolResult errorResult, ToolUseContext ctx,
                                                               boolean stopHookActive) {
                return new GenericHook.HookResult(false, null, List.of("failure chain system message"), null,
                    null, null, null, null, null,
                    GenericHook.HookOutcome.SUCCESS, null, null, null, null, null, null, null, null);
            }
        });

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
        StreamingToolExecutor exec = new StreamingToolExecutor(registryWith(tool), ctx, null, null, hooks);
        exec.setTelemetry(new Telemetry());
        AgentState state = new AgentState("system-prompt");
        exec.setAgentState(state);

        exec.add(new ToolUseBlock("toolu_sysmsg_1", "sysmsg_stub", JSON.createObjectNode()));
        exec.getRemainingResults();
        assertThat(systemMessageAttachmentCount(state))
            .as("Pre + Post 链各 1 个 hook_system_message")
            .isEqualTo(2);

        // 失败路径: 工具抛异常 → Failure 链注入
        tool.failNext = true;
        exec.add(new ToolUseBlock("toolu_sysmsg_2", "sysmsg_stub", JSON.createObjectNode()));
        exec.getRemainingResults();
        assertThat(systemMessageAttachmentCount(state))
            .as("第二次调用 (Pre + Failure 链) 后共 4 个 hook_system_message")
            .isEqualTo(4);
    }

    /** 可切换抛异常的桩工具. */
    private static final class AtomicBooleanTool implements Tool {
        volatile boolean failNext = false;

        @Override public String name() { return "sysmsg_stub"; }
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            if (failNext) {
                throw new IllegalStateException("sysmsg boom");
            }
            return ToolResult.success(call.id(), "ok");
        }
    }
}
