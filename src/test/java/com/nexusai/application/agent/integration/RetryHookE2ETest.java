package com.nexusai.application.agent.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionDeniedHookExecutor;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionResult.Ask;
import com.nexusai.application.agent.permission.RetryMessageFactory;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.classifier.DenialTracker;
import com.nexusai.application.agent.permission.classifier.SafeToolWhitelist;
import com.nexusai.application.agent.permission.classifier.YoloClassifier;
import com.nexusai.application.agent.permission.classifier.YoloClassifierResult;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.AggregatedHookResult;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session C-2 Phase 3 Task C-1] Retry hook 端到端集成测试 · 验证完整链路:
 * <pre>
 *   YoloClassifier.DENY
 *     → PermissionPipeline.check 走 auto-mode 路径
 *     → 返回 PermissionResult.Deny(mode='auto-mode')
 *     → ToolPermissionGate.check 返 DENY + result
 *     → StreamingToolExecutor.executeAsync 主循环触发 maybeFirePermissionDeniedRetry
 *     → PermissionDeniedHookExecutor.executePermissionDeniedHooks → HookRegistry.executeEvent
 *     → Hook 返回 AggregatedHookResult(retry=true)
 *     → RetryMessageFactory.createRetryMessage → isMeta=true ChatMessageDto
 *     → extendedResultHandler 包装为 ExtendedToolResult.newMessages → AgentState.messages 注入
 *     → 后续 AnthropicSdkProvider.buildMessageParams 把该消息作为 role=user 发送（isMeta 不出现）
 * </pre>
 *
 * <p><b>WHY 这条链路值得独立测试</b>:
 * <ul>
 *   <li>{@link com.nexusai.application.agent.tool.StreamingToolExecutorRetryHookTest}
 *       (Phase 2) 用 {@code FixedPermissionPipeline} 直接构造 Deny 决策, 跳过 classifier,
 *       不验证 auto-mode 路径</li>
 *   <li>{@link com.nexusai.application.agent.permission.ClassifierModeRetryHookTest}
 *       (Session C P0-3) 只验证 pipeline 写出 mode='auto-mode', 不验证 retry hook 真的触发</li>
 *   <li>本测试是两者拼起来: 真实 classifier → 真实 pipeline → 真实 executor → 真实 hook</li>
 * </ul>
 *
 * <p><b>3 项断言</b>:
 * <ol>
 *   <li>{@code fullChain_classifierDeny_emitsIsMetaRetryMessage} — 完整链路: classifier DENY
 *       → resultingMessages 包含 1 条 isMeta=true ChatMessageDto, content 是 retry 文本</li>
 *   <li>{@code fullChain_classifierDeny_doesNotInvokeTool} — classifier DENY 路径下
 *       工具不执行 (Deny 阻断, retry hook 不复活它)</li>
 *   <li>{@code fullChain_classifierDeny_retryHookFiresExactlyOnce} — classifier DENY
 *       路径下 retry hook 被调恰好 1 次 (即使多次 add tool 也不会重复触发)</li>
 * </ol>
 */
class RetryHookE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RETRY_MESSAGE =
        "The PermissionDenied hook indicated this command is now approved. You may retry it if you would like.";
    private static final String TOOL_NAME = "Bash";

    private FakeYoloClassifier fakeClassifier;
    private PermissionPipeline pipeline;
    private HookRegistry hookRegistry;
    private AtomicInteger retryHookCalls;
    private List<ChatMessageDto> emittedMessages;

    @BeforeEach
    void setUp() {
        // [真实链路] FakeYoloClassifier + 真实 PermissionPipeline + 真实 auto mode 组件
        fakeClassifier = new FakeYoloClassifier();
        fakeClassifier.queueDenyResult("rm -rf is dangerous", 0.95, 1, 50);
        pipeline = new PermissionPipeline();
        // [WHY 反射] PermissionPipeline 4 个 classifier 依赖是 package-private 字段
        //   (s04 PR @Autowired(required=false) 兼容), 但本测试在 integration 子包,
        //   无法直接访问. 用反射注入模拟 Spring 容器行为 (与 ClassifierModeRetryHookTest
        //   同包访问模式对齐, 仅跨包时多一层反射开销).
        setPackagePrivate(pipeline, "autoModeGate", new AutoModeGate(true));
        setPackagePrivate(pipeline, "safeToolWhitelist", new SafeToolWhitelist());
        setPackagePrivate(pipeline, "denialTracker", new DenialTracker(3, 20));
        setPackagePrivate(pipeline, "yoloClassifier", fakeClassifier);

        // [真实链路] HookRegistry + retry hook 返回 retry=true
        retryHookCalls = new AtomicInteger(0);
        hookRegistry = new HookRegistry();
        hookRegistry.register("retry_hook", event -> {
            retryHookCalls.incrementAndGet();
            return GenericHook.HookResult.withRetry();
        }, HookEventType.PERMISSION_DENIED);

        emittedMessages = new CopyOnWriteArrayList<>();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 测试 1: 完整链路 classifier DENY → isMeta retry 消息注入
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fullChain_classifierDeny_emitsIsMetaRetryMessage: classifier DENY → isMeta=true ChatMessageDto 注入")
    void fullChain_classifierDeny_emitsIsMetaRetryMessage() {
        StreamingToolExecutor exec = createStreamingExecutor(true);

        // 触发完整链路: add tool → executor 主循环 → pipeline.check → classifier DENY → retry hook
        exec.add(new ToolUseBlock("toolu_e2e_1", TOOL_NAME, input()));
        List<ToolResult> results = exec.getRemainingResults();

        // 关键断言 1: 工具被 Deny 阻断 (error result)
        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_e2e_1"))
            .as("classifier DENY 路径必须让结果 error flag=true（IMP-C2 后 isError 由执行器推导）")
            .isTrue();

        // 关键断言 2: retry hook 被调用
        assertThat(retryHookCalls.get())
            .as("classifier DENY 路径下 PermissionDenied hook 必须被调 1 次")
            .isEqualTo(1);

        // 关键断言 3: resultingMessages 包含 1 条 isMeta=true retry 消息
        assertThat(emittedMessages)
            .as("retry hook 返回 retry=true → resultingMessages 含 1 条 ChatMessageDto")
            .hasSize(1);
        ChatMessageDto retryMsg = emittedMessages.get(0);
        assertThat(retryMsg.role())
            .as("retry 消息 role=user (Provider 看到 user message)")
            .isEqualTo(Role.user);
        assertThat(retryMsg.content())
            .as("retry 消息 content 是 CC 真源原文 (对齐 toolExecution.ts:1093-1098)")
            .isEqualTo(RETRY_MESSAGE);
        assertThat(retryMsg.isMeta())
            .as("retry 消息 isMeta=true (对齐 CC createUserMessage({isMeta:true}))")
            .isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 测试 2: classifier DENY 路径下工具不执行
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fullChain_classifierDeny_doesNotInvokeTool: classifier DENY 阻断 + retry 不复活")
    void fullChain_classifierDeny_doesNotInvokeTool() {
        AtomicInteger toolInvocations = new AtomicInteger(0);
        StreamingToolExecutor exec = createStreamingExecutor(true, toolInvocations);

        exec.add(new ToolUseBlock("toolu_e2e_2", TOOL_NAME, input()));
        List<ToolResult> results = exec.getRemainingResults();

        // 关键断言: 工具未被实际执行 (Deny 阻断优先于 retry hook)
        assertThat(toolInvocations.get())
            .as("classifier DENY 路径必须阻断 tool.execute (retry hook 不复活已被拒绝的工具)")
            .isEqualTo(0);
        // 结果仍是 error (含 classifier deny 原因)
        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_e2e_2")).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 测试 3: retry hook 每个 DENY 调用恰好 1 次 (去重)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fullChain_classifierDeny_retryHookFiresExactlyOnce: 每个 DENY 工具 retry hook 调 1 次")
    void fullChain_classifierDeny_retryHookFiresExactlyOnce() {
        // [S12 R4] 队列共 3 个 DENY（BeforeEach 1 + 本测试 2），仅排 2 个 tool call → 2 次
        //   classifier DENY；连续 3 次拒绝达 CC
        //   shouldFallbackToPrompting 阈值 (denialTracking.ts:40-45, maxConsecutive=3)
        //   → 第 3 个拒绝触发超限回退 ask（CC handleDenialLimitExceeded, permissions.ts:984-1058）,
        //   不再是 deny → 不触发 retry hook。此处验证 2 个 classifier DENY 的去重语义
        //   （超限回退路径由 R32B12_AutoClassifierR1R4Test 覆盖）。
        fakeClassifier.queueDenyResult("rm -rf 1", 0.9, 1, 50);
        fakeClassifier.queueDenyResult("rm -rf 2", 0.9, 1, 50);
        StreamingToolExecutor exec = createStreamingExecutor(true);

        exec.add(new ToolUseBlock("toolu_e2e_3a", TOOL_NAME, input()));
        exec.add(new ToolUseBlock("toolu_e2e_3b", TOOL_NAME, input()));
        exec.getRemainingResults();

        assertThat(retryHookCalls.get())
            .as("2 个独立 classifier DENY tool call → retry hook 调 2 次 (每个工具 1 次)")
            .isEqualTo(2);
        assertThat(emittedMessages)
            .as("2 个 DENY → resultingMessages 含 2 条 isMeta=true retry 消息")
            .hasSize(2);
    }
    // ═══════════════════════════════════════════════════════════════════════
    // 测试 4 [reflector-C F2 返工]: PermissionDenied hook 载荷 tool_input = hook 更新后 input
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fullChain_classifierDeny_permissionDeniedHookReceivesHookUpdatedInput: hook 收到的 tool_input 是 PreToolUse hook 全替换后的 input")
    void fullChain_classifierDeny_permissionDeniedHookReceivesHookUpdatedInput() {
        // [RED 依据 · reflector-C F2] 旧实现传 t.call.input() (原始 input), CC 传
        // processedInput (hook 处理后 input, toolExecution.ts:834-838/:931-932/:1083-1088)
        // → 权限决策用的 input (effectiveCall.input()) 与 PermissionDenied hook 载荷 input 不一致.
        AtomicReference<JsonNode> deniedInput = new AtomicReference<>();
        hookRegistry.register("capture_denied_input", event -> {
            deniedInput.set(event.input());
            return GenericHook.HookResult.withRetry();
        }, HookEventType.PERMISSION_DENIED);
        // PreToolUse hook 整体替换 input (CC hookUpdatedInput 全替换语义) —
        // 必须走类型化 registerPreToolUse 总线 (generic 总线结果不回填 AHR.updatedInput)
        hookRegistry.registerPreToolUse("update_input_hook",
            (toolName, input, hookCtx) -> new AggregatedHookResult(
                null, null, false, null, null, null, null, null,
                null, Map.of("command", "rm -rf /tmp/hook-updated"),
                null, null, null, null, null, null));
        StreamingToolExecutor exec = createStreamingExecutor(true);
        exec.add(new ToolUseBlock("toolu_e2e_updated", TOOL_NAME, input()));
        exec.getRemainingResults();

        assertThat(deniedInput.get())
            .as("PermissionDenied hook 必须收到 hook 更新后的 tool_input (对齐 CC processedInput)")
            .isNotNull();
        assertThat(deniedInput.get().path("command").asText())
            .as("tool_input.command 必须是 PreToolUse hook 全替换后的值, 而非原始 LLM input")
            .isEqualTo("rm -rf /tmp/hook-updated");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 测试 5 [reflector-C F3 返工]: PermissionDenied hook 载荷 permission_mode = CC 小写字面量
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fullChain_classifierDeny_permissionModeUsesCcLowercaseLiteral: permission_mode 用 CC 小写字面量 default (非枚举名 DEFAULT)")
    void fullChain_classifierDeny_permissionModeUsesCcLowercaseLiteral() {
        // [RED 依据 · reflector-C F3] 旧实现传 ctx.permissionMode().name() → "DEFAULT",
        // CC 传 toolPermissionContext.mode 小写字面量 (toolExecution.ts:918/:1087,
        // coreSchemas.ts:391 permission_mode 字面量) → 依赖 permission_mode==='default'
        // 分支的 hook 配置会失效.
        AtomicReference<String> deniedMode = new AtomicReference<>();
        AtomicReference<Object> deniedDataMode = new AtomicReference<>();
        hookRegistry.register("capture_denied_mode", event -> {
            deniedMode.set(event.permissionMode());
            deniedDataMode.set(event.data().get("permission_mode"));
            return GenericHook.HookResult.withRetry();
        }, HookEventType.PERMISSION_DENIED);

        StreamingToolExecutor exec = createStreamingExecutor(true);
        exec.add(new ToolUseBlock("toolu_e2e_mode", TOOL_NAME, input()));
        exec.getRemainingResults();

        assertThat(deniedMode.get())
            .as("PermissionDenied hook 的 permission_mode 必须是小写字面量 (对齐 CC permission_mode 字面量)")
            .isEqualTo("default");
        assertThat(deniedDataMode.get())
            .as("HookEvent.data.permission_mode 必须是小写字面量 default, 而非枚举名 DEFAULT")
            .isEqualTo("default");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 测试 6 [P-AL-04 REQ-C-C1]: retry hook signal 桥接 — 请求取消后 retry hook 跳过
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fullChain_cancelDuringClassifier_retryHookSkipped: 权限决策后取消 → retry hook 跳过 (CC toolExecution.ts:1088 signal)")
    void fullChain_cancelDuringClassifier_retryHookSkipped() {
        // [RED 依据 · P-AL-04 / REQ-C-C1] CC 第 7 参传 toolUseContext.abortController.signal
        // (toolExecution.ts:1088) → executeHooks 入口 signal.aborted 早返 (hooks.ts:2015-2017):
        // 请求取消时 PermissionDenied retry hook 整体跳过, 不注入 isMeta. Java 旧实现
        // StreamingToolExecutor:2109 第 7 参传 null → hook 仍完整执行并注入 isMeta (偏离 CC).
        //
        // 取消时机选在 classifier 评估期间 (PermissionPipeline:167 classify 持有 ctx):
        // · 不能选"执行前" — gate.check 入口 isAborted 短路 (ToolPermissionGate:514)
        //   会让决策不经过 classifier, 触达不到 retry 路径 (测试失去意义);
        // · classifier 内取消后: pipeline 返回 Classifier(auto-mode) Deny → gate Deny 分支
        //   (:493-510 无 abort 重检) → executor deny 分支 (:1494) → retry hook 触发点 —
        //   恰好落在"决策后、hook 前"的取消窗口, 与 CC signal 检查点同语义.
        CancellingFakeYoloClassifier cancelling = new CancellingFakeYoloClassifier();
        cancelling.queueDenyResult("rm -rf is dangerous", 0.95, 1, 50);
        StreamingToolExecutor exec = createStreamingExecutor(true, new AtomicInteger(), cancelling);

        exec.add(new ToolUseBlock("toolu_e2e_cancel", TOOL_NAME, input()));
        List<ToolResult> results = exec.getRemainingResults();

        // 关键断言 1: 工具仍被 Deny 阻断 (取消不改变已产出的权限决策)
        assertThat(results).hasSize(1);
        assertThat(exec.getResultErrorFlags().get("toolu_e2e_cancel"))
            .as("取消发生在决策后, 工具结果仍必须是 error (Deny 阻断)")
            .isTrue();
        // 关键断言 2: retry hook 未被调用 (signal 已取消 → 早返跳过, 对齐 CC hooks.ts:2015-2017)
        assertThat(retryHookCalls.get())
            .as("请求取消后 PermissionDenied retry hook 必须跳过 (CC signal.aborted 早返)")
            .isZero();
        // 关键断言 3: 无 isMeta retry 消息注入 (CC 早返不产出 hookSaysRetry)
        assertThat(emittedMessages)
            .as("取消后不得注入 isMeta retry 消息 (对齐 CC 早返后 resultingMessages 无 retry push)")
            .isEmpty();
    }

    /**
     * Fake 变体: classifier 评估期间取消 ctx 的 AbortController —
     * 模拟"权限决策已产出、retry hook 尚未执行"窗口内的用户取消.
     */
    private static class CancellingFakeYoloClassifier extends FakeYoloClassifier {
        @Override
        public CompletableFuture<YoloClassifierResult> classify(
                String toolName, JsonNode input, List<ChatMessageDto> transcript, ToolUseContext ctx) {
            if (ctx != null && ctx.abortController() != null) {
                ctx.abortController().abort("user_cancelled_during_classifier");
            }
            return super.classify(toolName, input, transcript, ctx);
        }
    }


    private StreamingToolExecutor createStreamingExecutor(
            boolean transcriptClassifierEnabled,
            AtomicInteger toolInvocationCounter) {
        return createStreamingExecutor(transcriptClassifierEnabled, toolInvocationCounter, fakeClassifier);
    }

    private StreamingToolExecutor createStreamingExecutor(
            boolean transcriptClassifierEnabled,
            AtomicInteger toolInvocationCounter,
            YoloClassifier classifier) {
        // [P-AL-04] 注入指定 classifier (setUp 已注入默认 fakeClassifier; 取消场景需
        //   CancellingFakeYoloClassifier — 复用 setUp 的反射注入路径, 同包私有字段)
        setPackagePrivate(pipeline, "yoloClassifier", classifier);
        // [真实链路] ToolPermissionGate 包装 PermissionPipeline (含 auto mode)
        ToolPermissionGate gate = new ToolPermissionGate(
            pipeline,
            Mockito.mock(PermissionPrompter.class), // prompter 不需要 (auto-mode 不弹窗)
            new AutoModeGate(true),
            new DenialTracker(3, 20)
        );

        // [真实链路] Stub tool: Bash, checkPermissions 返 Passthrough 让 pipeline 走到
        //   Layer 3 兜底 Ask → 触发 classifier auto-mode 评估
        Tool bash = new Tool() {
            @Override public String name() { return TOOL_NAME; }
            @Override public String description() { return "bash tool for e2e test"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                toolInvocationCounter.incrementAndGet();
                return ToolResult.success(call.id(), "should not reach");
            }
            @Override public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
                // Passthrough → Pipeline 走完 10 层规则 → Layer 3 兜底 Ask → classifier 评估
                return new PermissionResult.Passthrough(
                    "stub passthrough", null, List.of(), null,null);
            }
        };

        ToolRegistry registry = new ToolRegistry().register(bash);

        ToolUseContext ctx = ToolUseContext.of(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(bash), "", new AbortController(), List.of(),
            // [S12 R1] auto 模式入口门：非 auto/plan+active 的 Ask 不进分类器
            ToolPermissionContext.strict(PermissionMode.AUTO), PermissionMode.DEFAULT
        );

        StreamingToolExecutor exec = new StreamingToolExecutor(
            registry, ctx,
            (result, toolUseId) -> {
                // 收集 extendedResultHandler 路径的 newMessages
                if (result instanceof ToolResult<?> tr) {
                    emittedMessages.addAll(tr.newMessages());
                }
            },
            gate,
            hookRegistry
        );

        // [Phase 2 接线] 三件套注入, 对齐 AgentLoopContext.buildStreamingExecutor :1352-1356
        exec.setPermissionDeniedHookExecutor(new PermissionDeniedHookExecutor(hookRegistry));
        exec.setRetryMessageFactory(new RetryMessageFactory());
        exec.setTranscriptClassifierEnabled(transcriptClassifierEnabled);

        return exec;
    }

    private StreamingToolExecutor createStreamingExecutor(boolean transcriptClassifierEnabled) {
        return createStreamingExecutor(transcriptClassifierEnabled, new AtomicInteger());
    }


    private JsonNode input() {
        return JSON.createObjectNode().put("command", "rm -rf /tmp/foo");
    }

    /**
     * 反射注入 PermissionPipeline 包私有字段. 测试在 integration 子包, 无直接访问权.
     */
    private static void setPackagePrivate(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("setPackagePrivate failed: " + fieldName, e);
        }
    }

    /**
     * Fake YoloClassifier: 不调 LLM, 行为可控.
     */
    private static class FakeYoloClassifier implements YoloClassifier {
        final java.util.ArrayDeque<YoloClassifierResult> queue = new java.util.ArrayDeque<>();

        void queueDenyResult(String reason, double confidence, int inputTokens, int outputTokens) {
            // [S06] YoloClassifierResult 对齐 CC 布尔 shouldBlock（⊕-02）—— deny 便捷工厂删除
            queue.add(YoloClassifierResult.blocked(reason, "fake-model"));
        }

        @Override
        public CompletableFuture<YoloClassifierResult> classify(
                String toolName, JsonNode input, List<ChatMessageDto> transcript, ToolUseContext ctx) {
            YoloClassifierResult r = queue.poll();
            if (r == null) {
                // [防御性] 队列空时返回 allow (避免 NPE), 测试依赖精确排队 1 个 deny
                return CompletableFuture.completedFuture(YoloClassifierResult.allowed("queue empty fallback", "fake-model"));
            }
            return CompletableFuture.completedFuture(r);
        }

        @Override
        public CompletableFuture<YoloClassifierResult> classifyTextAction(
                String userText, List<ChatMessageDto> transcript, ToolUseContext ctx) {
            // [IMP-SUB-25 R3] 测试 stub：handoff user-text action 在本测试不触发 → 恒 allow 兜底
            return CompletableFuture.completedFuture(YoloClassifierResult.allowed(
                "fake-text-action-not-used", "fake-model"));
        }

        @Override public boolean isAvailable() { return true; }
    }
}