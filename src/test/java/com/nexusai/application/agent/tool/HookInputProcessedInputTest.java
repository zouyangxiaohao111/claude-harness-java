package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.permission.InputSanitizer;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.AggregatedHookResult;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PostToolUseHook;
import com.nexusai.application.agent.permission.hook.PreToolUseHook;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S6 E9·CCJ-T6-22/13/20] 工具链 hook 入参 = processedInput 全链统一.
 *
 * <p>验证单元 (RED→GREEN):
 * <ol>
 *   <li>PreToolUse hook 载荷断言内部字段被 strip (CC toolExecution.ts:761-793 strip
 *       → runPreToolUseHooks 收 processedInput, toolHooks.ts:466-476)</li>
 *   <li>hookUpdatedInput 替换后 → tool.execute + PostToolUse + PostToolUseFailure 载荷
 *       全部收到替换值 (CC toolExecution.ts:837/1488/1705)</li>
 * </ol>
 *
 * <p>工具调用输入仍为 LLM 原值 (Java §3.4 既有语义: strip 仅用于校验/hook 面,
 * execute 保持原 input — 与 CC callInput 语义差异已在既有设计登记, 非本 Session 范围).
 */
@DisplayName("[IMP-HOOKS-S6 E9] 工具链 hook 入参 = processedInput 全链统一")
class HookInputProcessedInputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolUseContext baseCtx() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT);
    }

    private static ToolRegistry registryWith(Tool... tools) {
        ToolRegistry r = new ToolRegistry();
        for (Tool t : tools) r.register(t);
        return r;
    }

    private static Telemetry emptyTelemetry() {
        return new Telemetry();
    }

    private static Map<String, Object> jsonNodeToMap(JsonNode node) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        var iter = node.fields();
        while (iter.hasNext()) {
            var e = iter.next();
            map.put(e.getKey(), e.getValue());
        }
        return map;
    }

    @Test
    @DisplayName("[E9·CCJ-T6-22] PreToolUse hook 收到 strip 后 input (Bash _simulatedSedEdit 剥离)")
    void preToolUseHook_receivesStrippedInput() {
        AtomicReference<JsonNode> hookSeenInput = new AtomicReference<>();
        AtomicReference<JsonNode> toolSeenInput = new AtomicReference<>();
        // [P4 OPD-WF4-BC-04] 工具名对齐 CC BASH_TOOL_NAME='Bash' —— CC toolExecution.ts:762-773
        //   仅对 Bash 剥 _simulatedSedEdit；旧 _internal 前缀 + 全工具范围超剥已移除。
        Tool stub = new Tool() {
            @Override public String name() { return "Bash"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                toolSeenInput.set(call.input());
                return ToolResult.success(call.id(), "ok");
            }
        };
        HookRegistry hooks = new HookRegistry();
        hooks.registerPreToolUse("capture", (toolName, input, ctx) -> {
            hookSeenInput.set(input);
            return AggregatedHookResult.proceed();
        });
        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registryWith(stub), ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());
        exec.setInputSanitizer(new InputSanitizer());

        ObjectNode original = JSON.createObjectNode();
        original.put("command", "ls");
        original.put("_simulatedSedEdit", "must-not-reach-hook");
        exec.add(new ToolUseBlock("toolu_strip_1", "Bash", original));
        exec.getRemainingResults();

        assertThat(hookSeenInput.get())
            .as("PreToolUse hook 必须收到 strip 后 input (CC processedInput)")
            .isNotNull();
        assertThat(hookSeenInput.get().has("_simulatedSedEdit"))
            .as("hook 载荷不得含内部字段 (Bash _simulatedSedEdit 剥离, CC toolExecution.ts:762-773)")
            .isFalse();
        assertThat(hookSeenInput.get().get("command").asText()).isEqualTo("ls");
        // Java §3.4 既有语义: tool.execute 保持 LLM 原 input (strip 仅校验/hook 面)
        assertThat(toolSeenInput.get().has("_simulatedSedEdit")).isTrue();
    }

    @Test
    @DisplayName("[E9·CCJ-T6-13] hookUpdatedInput 替换 → tool.execute/PostToolUse/PostToolUseFailure 全链替换值")
    void hookUpdatedInput_reachesToolAndPostChains() {
        AtomicReference<JsonNode> toolSeenInput = new AtomicReference<>();
        AtomicReference<JsonNode> postSeenInput = new AtomicReference<>();
        AtomicReference<JsonNode> failureSeenInput = new AtomicReference<>();
        AtomicReference<Boolean> toolThrew = new AtomicReference<>(false);
        Tool stub = new Tool() {
            @Override public String name() { return "replace_stub"; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                toolSeenInput.set(call.input());
                if (Boolean.TRUE.equals(toolThrew.get())) {
                    throw new IllegalStateException("tool boom");
                }
                return ToolResult.success(call.id(), "ok");
            }
        };
        HookRegistry hooks = new HookRegistry();
        ObjectNode hookUpdated = JSON.createObjectNode();
        hookUpdated.put("replaced", "by_hook");
        hooks.registerPreToolUse("merger", (toolName, input, ctx) -> new AggregatedHookResult(
            null, null, false, null, "replaced", null, null,
            null, null, jsonNodeToMap(hookUpdated), null, null, null, null, null, null));
        hooks.registerPostToolUse("post-capture", (toolName, input, result, ctx, stopHookActive) -> {
            postSeenInput.set(input);
            return GenericHook.HookResult.proceed();
        });
        hooks.registerPostToolUse("failure-capture", new PostToolUseHook() {
            @Override
            public GenericHook.HookResult onPostToolUse(String toolName, JsonNode input, ToolResult result,
                                                        ToolUseContext ctx, boolean stopHookActive) {
                return GenericHook.HookResult.proceed();
            }
            @Override
            public GenericHook.HookResult onPostToolUseFailure(String toolName, JsonNode input,
                                                               ToolResult errorResult, ToolUseContext ctx,
                                                               boolean stopHookActive) {
                failureSeenInput.set(input);
                return GenericHook.HookResult.proceed();
            }
        });

        ToolUseContext ctx = baseCtx();
        StreamingToolExecutor exec = new StreamingToolExecutor(registryWith(stub), ctx, null, null, hooks);
        exec.setTelemetry(emptyTelemetry());

        ObjectNode original = JSON.createObjectNode();
        original.put("original", "from_llm");
        exec.add(new ToolUseBlock("toolu_replace_1", "replace_stub", original));
        exec.getRemainingResults();

        assertThat(toolSeenInput.get()).isNotNull();
        assertThat(toolSeenInput.get().get("replaced").asText())
            .as("tool.execute 必须收到 hook 替换后的 input (CC processedInput 收敛)")
            .isEqualTo("by_hook");
        assertThat(toolSeenInput.get().has("original")).isFalse();
        assertThat(postSeenInput.get())
            .as("PostToolUse hook 入参 = 生效 input (CC toolExecution.ts:1488)")
            .isNotNull();
        assertThat(postSeenInput.get().get("replaced").asText()).isEqualTo("by_hook");

        // 失败路径: 工具抛异常 → PostToolUseFailure hook 收到同一生效 input (CC :1705)
        toolThrew.set(true);
        failureSeenInput.set(null);
        exec.add(new ToolUseBlock("toolu_replace_2", "replace_stub", original));
        exec.getRemainingResults();
        assertThat(failureSeenInput.get())
            .as("PostToolUseFailure hook 入参 = 生效 input (CC toolExecution.ts:1705)")
            .isNotNull();
        assertThat(failureSeenInput.get().get("replaced").asText()).isEqualTo("by_hook");
    }
}
