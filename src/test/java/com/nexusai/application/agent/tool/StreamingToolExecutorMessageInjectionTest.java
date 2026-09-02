package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookBlockingError;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H3 v3 对抗核验] Gap 1 残留缺口 · outcome.message() attachment 注入 AgentState.
 *
 * <p>WHY (规则九 意图验证): v2 对抗复验判 H3 PARTIAL — {@code processHookJSONOutput}
 * (hooks.ts:710-736) 生成的 message attachment (hook_success / hook_blocking_error) 存于
 * {@code HookResult.message()}, 但 {@code StreamingToolExecutor.injectPostToolUseHookAttachments}
 * 只消费 blockingError/additionalContext/preventContinuation/cancelled, 从不读
 * {@code outcome.message()} → hook 成功/阻塞的系统提醒静默丢失, "达 LLM" 只满足一半.
 *
 * <p>对齐 CC 真源 (services/tools/toolHooks.ts:89-108):
 * <ul>
 *   <li>{@code result.message} 必须 yield（注入 AgentState.attachments → messagesForLlm）</li>
 *   <li>hook_blocking_error attachment 除外 — blockingError 分支生成同一 attachment,
 *       跳过避免双显示 (#31301)</li>
 * </ul>
 */
@DisplayName("[H3-v3-fix] PostToolUse outcome.message() → AgentState.attachments")
class StreamingToolExecutorMessageInjectionTest {

    /** 镜像 StreamingToolExecutorDispatchTest#JSON (私有字段, 不跨类复用). */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("PostToolUse outcome.message() = hook_success → 注入 AgentState.attachments (CC toolHooks.ts:100-103)")
    void postToolUse_hookSuccessMessage_injectedIntoState() throws Exception {
        // WHY: processHookJSONOutput 无 blockingError 时 message 为 hook_success attachment.
        //      injectPostToolUseHookAttachments 不读 outcome.message() → 该 attachment 被丢弃,
        //      hook 成功系统提醒 (审计/UI) 丢失. 修复后必须注入 state.attachments().
        AgentState state = new AgentState("system prompt");
        HookRegistry hooks = new HookRegistry();
        hooks.registerPostToolUse("success", (toolName, input, result, ctx, stopActive) ->
            new GenericHook.HookResult(false, null, null, null,
                AttachmentMessageDto.hookSuccess("PostToolUse:stub", "toolu_s", "PostToolUse"),
                null, null, null, null,
                GenericHook.HookOutcome.SUCCESS, null, null, null, null, null, null, null, null));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub_done");
            }
        });
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, context(), null, null, hooks);
        exec.setAgentState(state);

        exec.add(call("m1", "stub"));
        List<ToolResult> results = exec.getRemainingResults();

        assertThat(exec.getResultErrorFlags().get("m1")).isFalse();
        assertThat(state.attachments())
            .as("hook_success message attachment 必须注入 AgentState.attachments (CC toolHooks.ts:100-103)")
            .anyMatch(a -> "hook_success".equals(a.type()));
    }

    @Test
    @DisplayName("PostToolUse outcome.message() = hook_blocking_error → 只注入一次 (CC toolHooks.ts:89-96 #31301 双显示规避)")
    void postToolUse_hookBlockingErrorMessage_notDoubleInjected() throws Exception {
        // WHY: CC 对 JSON {decision:"block"} hook 同时产出 {blockingError} 和
        //      {message: hook_blocking_error attachment}; runPostToolUseHooks 跳过 message 里的
        //      hook_blocking_error (blockingError 分支生成同一 attachment), 避免双显示 (#31301).
        //      若 Java 盲目 append outcome.message() 会双发 hook_blocking_error attachment.
        AgentState state = new AgentState("system prompt");
        HookRegistry hooks = new HookRegistry();
        AttachmentMessageDto msg = AttachmentMessageDto.hookBlockingError(
            "PostToolUse:stub", "toolu_b", "PostToolUse", "boom", "check.sh");
        hooks.registerPostToolUse("block", (toolName, input, result, ctx, stopActive) ->
            new GenericHook.HookResult(false,
                new HookBlockingError("boom", "check.sh"), null, null, msg,
                null, null, null, null,
                GenericHook.HookOutcome.SUCCESS, null, null, null, null, null, null, null, null));
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "stub_done");
            }
        });
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, context(), null, null, hooks);
        exec.setAgentState(state);

        exec.add(call("m2", "stub"));
        exec.getRemainingResults();

        assertThat(state.attachments().stream()
                .filter(a -> "hook_blocking_error".equals(a.type())).count())
            .as("hook_blocking_error 只注入一次 (CC #31301 双显示规避, blockingError 分支生成)")
            .isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════════
    // helpers · 镜像 StreamingToolExecutorDispatchTest (私有 helper 不跨类复用)
    // ════════════════════════════════════════════════════════════════════════

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    private static ToolUseContext context() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", new AbortController(), List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            current -> Collections.unmodifiableSet(java.util.Set.of()));
    }
}
