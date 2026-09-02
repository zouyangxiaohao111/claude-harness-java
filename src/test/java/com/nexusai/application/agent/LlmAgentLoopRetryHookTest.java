package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionDeniedHookExecutor;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionResult.Ask;
import com.nexusai.application.agent.permission.RetryMessageFactory;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session C-2 Phase 3 Task C-2] {@code transcriptClassifierEnabled} 配置开关测试
 * · 对齐 {@link com.nexusai.application.agent.LlmAgentLoop#transcriptClassifierEnabled}
 * 字段 (line 394) 的语义: M3.4 决策后默认 true (生产 @Value :true + application.yml:117
 * enabled: true, 与 CC 运行时 feature 行为对齐), 关闭时才注入 retry hook 链路.
 *
 * <p><b>WHY 这组测试重要</b>:
 * <ul>
 *   <li>{@link com.nexusai.application.agent.tool.StreamingToolExecutorRetryHookTest}
 *       (Phase 2) 只验证 transcriptClassifierEnabled=true 的路径触发 retry hook,
 *       <b>未验证 false 时早返分支</b></li>
 *   <li>本测试覆盖 {@code maybeFirePermissionDeniedRetry} (StreamingToolExecutor line 2052)
 *       的早返条件: {@code if (!transcriptClassifierEnabled) return;}</li>
 *   <li>如果接线失误把 false 传成 true, 或反之 (AgentLoopContext.buildStreamingExecutor
 *       :1351-1356 setTranscriptClassifierEnabled), 会让 retry hook 在不该触发时触发 /
 *       该触发时不触发 — 本测试守住这条路径</li>
 * </ul>
 *
 * <p><b>3 项断言</b>:
 * <ol>
 *   <li>{@code transcriptClassifierEnabledFalse_retryHookNotTriggered} —
 *       transcriptClassifierEnabled=false + classifier DENY → retry hook <b>不</b>被调,
 *       resultingMessages <b>不</b>含 isMeta 消息 (CC: feature flag 关闭时不启用 retry 链路)</li>
 *   <li>{@code transcriptClassifierEnabledTrue_retryHookTriggered} —
 *       transcriptClassifierEnabled=true + classifier DENY → retry hook 被调,
 *       resultingMessages 含 1 条 isMeta=true ChatMessageDto (回归基线 — 与
 *       StreamingToolExecutorRetryHookTest 一致, 但这里直接构造 Deny 决策不走 classifier)</li>
 *   <li>{@code transcriptClassifierEnabledFalse_classifierAllow_noOp_noSideEffect} —
 *       transcriptClassifierEnabled=false + classifier ALLOW → 工具正常执行, 零 retry hook 调用,
 *       零 isMeta 消息 (false 配置下整个 retry 链路完全是 no-op, 无额外开销)</li>
 * </ol>
 */
class LlmAgentLoopRetryHookTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RETRY_MESSAGE =
        "The PermissionDenied hook indicated this command is now approved. You may retry it if you would like.";
    private static final String TOOL_NAME = "Bash";

    // ═══════════════════════════════════════════════════════════════════════
    // 1. transcriptClassifierEnabled=false → retry hook 不触发
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("transcriptClassifierEnabled=false_retryHookNotTriggered: 配置关闭时 retry hook 完全早返")
    void transcriptClassifierEnabledFalse_retryHookNotTriggered() {
        Fixture fixture = newFixture(false, buildDenyDecision());

        fixture.executor.add(new ToolUseBlock("toolu_cfg_false_1", TOOL_NAME, buildInput()));
        fixture.executor.getRemainingResults();

        // 关键断言 1: classifier DENY 路径仍阻断工具 (与配置无关)
        assertThat(fixture.toolCalls.get())
            .as("classifier DENY 路径必须阻断 tool.execute")
            .isEqualTo(0);

        // 关键断言 2: 配置关闭时 retry hook 不被调 (CC: feature flag 关闭 → no-op)
        assertThat(fixture.retryHookCalls.get())
            .as("transcriptClassifierEnabled=false 时 retry hook 必须 0 次调用 (maybeFirePermissionDeniedRetry line 2052 早返)")
            .isEqualTo(0);

        // 关键断言 3: 零 isMeta 消息注入
        assertThat(fixture.emittedMessages)
            .as("transcriptClassifierEnabled=false 时 resultingMessages 必须为空, 无 isMeta 注入")
            .isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. transcriptClassifierEnabled=true → retry hook 触发 (回归基线)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("transcriptClassifierEnabled=true_retryHookTriggered: 配置开启时 retry hook 触发并注入 isMeta")
    void transcriptClassifierEnabledTrue_retryHookTriggered() {
        Fixture fixture = newFixture(true, buildDenyDecision());

        fixture.executor.add(new ToolUseBlock("toolu_cfg_true_1", TOOL_NAME, buildInput()));
        fixture.executor.getRemainingResults();

        // 关键断言 1: retry hook 被调
        assertThat(fixture.retryHookCalls.get())
            .as("transcriptClassifierEnabled=true + classifier DENY → retry hook 必须调 1 次")
            .isEqualTo(1);

        // 关键断言 2: 1 条 isMeta=true ChatMessageDto
        assertThat(fixture.emittedMessages)
            .as("transcriptClassifierEnabled=true → resultingMessages 含 1 条 isMeta retry 消息")
            .hasSize(1);
        ChatMessageDto retryMsg = fixture.emittedMessages.get(0);
        assertThat(retryMsg.role()).isEqualTo(Role.user);
        assertThat(retryMsg.content()).isEqualTo(RETRY_MESSAGE);
        assertThat(retryMsg.isMeta()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. transcriptClassifierEnabled=false + classifier ALLOW → 零 retry, 工具正常
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("transcriptClassifierEnabledFalse_classifierAllow_noOp_noSideEffect: 配置关闭 + ALLOW → 工具正常, 零 retry 开销")
    void transcriptClassifierEnabledFalse_classifierAllow_noOp_noSideEffect() {
        Fixture fixture = newFixture(false, buildAllowDecision());

        fixture.executor.add(new ToolUseBlock("toolu_cfg_false_allow_1", TOOL_NAME, buildInput()));
        List<ToolResult> results = fixture.executor.getRemainingResults();

        // 关键断言 1: 工具正常执行 (ALLOW 路径, 配置无关)
        assertThat(fixture.toolCalls.get())
            .as("classifier ALLOW 路径必须执行 tool.execute, 不受 transcriptClassifierEnabled 影响")
            .isEqualTo(1);
        assertThat(results).hasSize(1);
        assertThat(fixture.executor.getResultErrorFlags().get("toolu_cfg_false_allow_1"))
            .as("ALLOW 路径工具结果 error flag 必须为 false（IMP-C2 后 isError 由执行器推导）")
            .isFalse();

        // 关键断言 2: 配置关闭时 retry hook 完全零开销 (ALLOW 路径本来就不会触发 retry,
        //   false 配置确保 Allow 也不触发 retry chain — 零额外 hook executor 调用)
        assertThat(fixture.retryHookCalls.get())
            .as("配置关闭时 retry hook 必须 0 次调用, 不论 Allow/Deny")
            .isEqualTo(0);
        assertThat(fixture.emittedMessages).isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 辅助构造
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 构造 Decision 带 classifier='auto-mode' (对齐 PermissionPipeline.java:438 写法,
     * CC permissions.ts:907/923 构造侧).
     */
    private static PermissionResult buildDenyDecision() {
        return new PermissionResult.Deny(
            "classifier denied",
            new PermissionDecisionReason.Classifier("auto-mode", "unsafe command"),
            "tool-use-deny"
        );
    }

    private static PermissionResult buildAllowDecision() {
        return new PermissionResult.Allow(
            buildInput(),
            new PermissionDecisionReason.Classifier("auto-mode", "safe command"),
            "tool-use-allow",
            false, null, List.of()
        );
    }

    private static JsonNode buildInput() {
        return JSON.createObjectNode().put("command", "ls -la");
    }

    /**
     * 构造固定 decision pipeline + hookRegistry + executor 集合, 直接驱动单条 tool call.
     */
    private static Fixture newFixture(boolean transcriptClassifierEnabled, PermissionResult decision) {
        AtomicInteger retryHookCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        List<ChatMessageDto> emittedMessages = new CopyOnWriteArrayList<>();

        HookRegistry hookRegistry = new HookRegistry();
        hookRegistry.register("retry", event -> {
            retryHookCalls.incrementAndGet();
            return GenericHook.HookResult.withRetry();
        }, HookEventType.PERMISSION_DENIED);

        // 固定 decision 的 stub pipeline: 不走 classifier, 直接返回传入 decision
        Tool stubTool = new Tool() {
            @Override public String name() { return TOOL_NAME; }
            @Override public String description() { return "stub"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                toolCalls.incrementAndGet();
                return ToolResult.success(call.id(), "ok");
            }
        };

        ToolRegistry registry = new ToolRegistry().register(stubTool);

        // [构造] ToolPermissionGate + 固定 decision 的 pipeline
        ToolPermissionGate gate = new ToolPermissionGate(
            new com.nexusai.application.agent.permission.PermissionPipeline() {
                @Override
                public PermissionResult check(Tool tool, ToolUseBlock call, JsonNode input,
                                              ToolUseContext ctx, ToolPermissionContext permCtx) {
                    return decision;
                }
            },
            Mockito.mock(PermissionPrompter.class),
            null, null
        );

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(stubTool), "", new AbortController(), List.of(),
            ToolPermissionContext.strict(PermissionMode.DEFAULT), PermissionMode.DEFAULT
        );

        StreamingToolExecutor executor = new StreamingToolExecutor(
            registry, ctx,
            (result, toolUseId) -> {
                if (result instanceof ToolResult<?> tr) {
                    emittedMessages.addAll(tr.newMessages());
                }
            },
            gate,
            hookRegistry
        );

        // [Phase 2 接线三件套] 对齐 AgentLoopContext.buildStreamingExecutor :1352-1356
        executor.setPermissionDeniedHookExecutor(new PermissionDeniedHookExecutor(hookRegistry));
        executor.setRetryMessageFactory(new RetryMessageFactory());
        executor.setTranscriptClassifierEnabled(transcriptClassifierEnabled);

        return new Fixture(executor, retryHookCalls, toolCalls, emittedMessages);
    }

    private record Fixture(
            StreamingToolExecutor executor,
            AtomicInteger retryHookCalls,
            AtomicInteger toolCalls,
            List<ChatMessageDto> emittedMessages) {}
}