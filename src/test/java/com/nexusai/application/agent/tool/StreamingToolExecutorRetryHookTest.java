package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionDeniedHookExecutor;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.RetryMessageFactory;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 retry hook 只由 transcript-classifier 的 auto-mode Deny 触发；Allow 不得误触发。
 */
class StreamingToolExecutorRetryHookTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RETRY_MESSAGE =
        "The PermissionDenied hook indicated this command is now approved. You may retry it if you would like.";

    @Test
    void streamingToolExecutor_onAutoModeDeny_invokesRetryHooks() {
        PermissionResult.Deny deny = new PermissionResult.Deny(
            "classifier denied",
            new PermissionDecisionReason.Classifier("auto-mode", "unsafe command"),
            "tool-use-deny");
        Fixture fixture = fixture(deny);

        fixture.executor.add(call("tool-use-deny"));
        List<ToolResult> results = fixture.executor.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(fixture.executor.getResultErrorFlags().get("tool-use-deny")).isTrue();
        assertThat(fixture.hookCalls).hasValue(1);
        assertThat(fixture.toolCalls).hasValue(0);
        assertThat(fixture.emittedMessages).singleElement().satisfies(message -> {
            assertThat(message.role()).isEqualTo(Role.user);
            assertThat(message.content()).isEqualTo(RETRY_MESSAGE);
            assertThat(message.isMeta()).isTrue();
        });
    }

    @Test
    void streamingToolExecutor_onAutoModeAllow_doesNotInvokeRetryHooks() {
        PermissionResult.Allow allow = new PermissionResult.Allow(
            input(),
            new PermissionDecisionReason.Classifier("auto-mode", "safe command"),
            "tool-use-allow", false, null, List.of());
        Fixture fixture = fixture(allow);

        fixture.executor.add(call("tool-use-allow"));
        List<ToolResult> results = fixture.executor.getRemainingResults();

        assertThat(results).hasSize(1);
        assertThat(fixture.executor.getResultErrorFlags().get("tool-use-allow")).isFalse();
        assertThat(fixture.hookCalls).hasValue(0);
        assertThat(fixture.toolCalls).hasValue(1);
        assertThat(fixture.emittedMessages).isEmpty();
    }

    private static Fixture fixture(PermissionResult decision) {
        AtomicInteger hookCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        List<ChatMessageDto> emittedMessages = new CopyOnWriteArrayList<>();

        HookRegistry hooks = new HookRegistry();
        hooks.register("retry", event -> {
            hookCalls.incrementAndGet();
            return GenericHook.HookResult.withRetry();
        }, HookEventType.PERMISSION_DENIED);

        Tool tool = new StubTool(toolCalls);
        ToolRegistry registry = new ToolRegistry().register(tool);
        FixedPermissionPipeline pipeline = new FixedPermissionPipeline(decision);
        ToolPermissionGate gate = new ToolPermissionGate(
            pipeline, Mockito.mock(PermissionPrompter.class), null, null);
        ToolUseContext context = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(tool), "", new AbortController(), List.of(),
            ToolPermissionContext.strict(PermissionMode.DEFAULT), PermissionMode.DEFAULT);

        StreamingToolExecutor executor = new StreamingToolExecutor(
            registry, context,
            (result, toolUseId) -> {
                if (result instanceof ToolResult<?> tr) {
                    emittedMessages.addAll(tr.newMessages());
                }
            }, gate, hooks);
        executor.setTranscriptClassifierEnabled(true);
        executor.setPermissionDeniedHookExecutor(
            new com.nexusai.application.agent.permission.PermissionDeniedHookExecutor(hooks));
        executor.setRetryMessageFactory(
            new com.nexusai.application.agent.permission.RetryMessageFactory());

        return new Fixture(executor, hookCalls, toolCalls, emittedMessages);
    }

    private static ToolUseBlock call(String id) {
        return new ToolUseBlock(id, "Bash", input());
    }

    private static JsonNode input() {
        return JSON.createObjectNode().put("command", "rm temp.txt");
    }

    private record Fixture(
        StreamingToolExecutor executor,
        AtomicInteger hookCalls,
        AtomicInteger toolCalls,
        List<ChatMessageDto> emittedMessages
    ) {}

    private static final class FixedPermissionPipeline extends PermissionPipeline {
        private final PermissionResult result;

        private FixedPermissionPipeline(PermissionResult result) {
            this.result = result;
        }

        @Override
        public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                      ToolUseContext ctx, ToolPermissionContext permissionContext) {
            return result;
        }
    }

    private static final class StubTool implements Tool {
        private final AtomicInteger calls;

        private StubTool(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public String name() {
            return "Bash";
        }

        @Override
        public String description() {
            return "retry hook test tool";
        }

        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public AgentToolResult execute(ToolUseBlock call) {
            calls.incrementAndGet();
            return ToolResult.success(call.id(), "ok");
        }
    }
}
