package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S6 CCJ-T6-14] PostToolUseFailure hook 载荷补 error/is_interrupt.
 *
 * <p>验证单元 (RED→GREEN): 工具失败路径 executePostToolUseFailure 构建的 hook stdin
 * JSON 含 {@code error} (工具错误文本, coreSchemas.ts:455 必传) + {@code is_interrupt}
 * (abort 错误分类 → true, 普通错误 → false, coreSchemas.ts:456) + {@code tool_use_id}
 * (coreSchemas.ts:448 必传, 经 data map 承载).
 */
@DisplayName("[IMP-HOOKS-S6 CCJ-T6-14] PostToolUseFailure 载荷 error/is_interrupt/tool_use_id")
class PostToolUseFailurePayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 覆写 execute 的 stub：捕获 jsonInput，不启动真实进程。 */
    static class StubCommandExecutor extends CommandHookExecutor {
        final AtomicReference<String> capturedJsonInput = new AtomicReference<>();

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                                             String jsonInput, String pluginRoot, String pluginId,
                                                             String skillRoot, Integer hookIndex,
                                                             boolean forceSyncExecution, AbortController parentAbort) {
            capturedJsonInput.set(jsonInput);
            return new CommandHookExecutor.CommandHookResult("{}", "", jsonInput, 0, false, false);
        }

        @Override
        public CommandHookExecutor.CommandHookResult execute(CommandHook hook, HookEvent hookEvent, String hookName,
                                                             String jsonInput, String pluginRoot, String pluginId,
                                                             String skillRoot, Integer hookIndex,
                                                             boolean forceSyncExecution, AbortController parentAbort,
                                                             long defaultTimeoutMs, String hookCwd) {
            return execute(hook, hookEvent, hookName, jsonInput, pluginRoot, pluginId, skillRoot,
                hookIndex, forceSyncExecution, parentAbort);
        }
    }

    /** settings 配 1 条 PostToolUseFailure:Bash command hook → registry（含 stub executor）。 */
    private HookRegistry registryWithFailureHook(StubCommandExecutor stub) {
        HooksSettings settings = new HooksSettings(key -> null);
        settings.loadFromSource(HookSource.USER_SETTINGS.name(), List.of(
            new IndividualHookConfig(HookEventType.POST_TOOL_USE_FAILURE,
                new CommandHook("echo stub", null, null, null, null, null, null, null),
                "Bash", HookSource.USER_SETTINGS, null)
        ));
        HooksConfigSnapshot snapshot = new HooksConfigSnapshot(settings);
        snapshot.captureHooksConfigSnapshot();
        HookMatcherEngine engine = new HookMatcherEngine(snapshot, new PermissionRuleValueParser());
        HookRegistry registry = new HookRegistry();
        registry.setHooksConfigSnapshot(snapshot);
        registry.setHookMatcherEngine(engine);
        registry.setCommandHookExecutor(stub);
        return registry;
    }

    private static ToolUseContext ctx() {
        return ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT);
    }

    private static JsonNode readJson(String jsonInput) throws Exception {
        return JSON.readTree(jsonInput);
    }

    @Test
    @DisplayName("abort 错误 → 载荷含 error 文本 + is_interrupt=true + tool_use_id")
    void abortFailure_payloadHasErrorAndIsInterrupt() throws Exception {
        StubCommandExecutor stub = new StubCommandExecutor();
        HookRegistry registry = registryWithFailureHook(stub);
        ToolUseContext ctx = ctx();

        // [P-25] isInterrupt 显式入参（旧 errorCategory=="abort" 字符串匹配已删）— abort 场景传 true
        ToolResult<?> errorResult = ToolResult.error("tu-9", "user pressed stop", "abort");
        GenericHook.HookResult outcome = registry.executePostToolUseFailure(
            "Bash", JSON.createObjectNode(), errorResult, ctx, false, true);

        assertThat(outcome).isNotNull();
        JsonNode payload = readJson(stub.capturedJsonInput.get());
        assertThat(payload.get("hook_event_name").asText()).isEqualTo("PostToolUseFailure");
        assertThat(payload.get("tool_name").asText()).isEqualTo("Bash");
        assertThat(payload.get("error").asText())
            .as("载荷 error = 工具错误文本 (ToolResult.error data=message, coreSchemas.ts:455)")
            .isEqualTo("user pressed stop");
        assertThat(payload.get("is_interrupt").asBoolean())
            .as("abort 错误分类 → is_interrupt=true (CC isInterrupt = error instanceof AbortError)")
            .isTrue();
        assertThat(payload.get("tool_use_id").asText())
            .as("tool_use_id 必传不丢 (coreSchemas.ts:448, data map 承载)")
            .isEqualTo("tu-9");
    }

    @Test
    @DisplayName("普通错误 → is_interrupt=false")
    void normalError_payloadIsInterruptFalse() throws Exception {
        StubCommandExecutor stub = new StubCommandExecutor();
        HookRegistry registry = registryWithFailureHook(stub);
        ToolUseContext ctx = ctx();

        ToolResult<?> errorResult = ToolResult.error("tu-10", "command not found", "execution");
        registry.executePostToolUseFailure("Bash", JSON.createObjectNode(), errorResult, ctx, false, false);

        JsonNode payload = readJson(stub.capturedJsonInput.get());
        assertThat(payload.get("error").asText()).isEqualTo("command not found");
        assertThat(payload.get("is_interrupt").asBoolean())
            .as("非 abort 错误分类 → is_interrupt=false")
            .isFalse();
        assertThat(payload.get("tool_use_id").asText()).isEqualTo("tu-10");
    }
}
