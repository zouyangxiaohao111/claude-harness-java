package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.classifier.DenialTracker;
import com.nexusai.application.agent.permission.classifier.SafeToolWhitelist;
import com.nexusai.application.agent.permission.classifier.YoloClassifier;
import com.nexusai.application.agent.permission.classifier.YoloClassifierResult;
import com.nexusai.application.agent.permission.hook.AbortException;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.util.AutoModeState;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [OPD-WF3-DC-v4-07] abort 语义对齐测试 · 对齐 CC permissions.ts abort 主题
 * （入口预检 :1163-1165 + acceptEdits catch 重抛 :650-655 + transcriptTooLong/denial-limit
 * headless 抛 AbortError :826-828/:1024-1026）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>: CC 在 headless 自动模式 + 拒绝超限 / transcript 超长
 * 时抛 AbortError 直接<b>中止 agent</b>；Java 旧实现把 AbortException 在 catch(Exception) 里
 * 吞掉转 iron-gate deny（不中止，headless 中止场景 agent 继续运行 —— 浪费 token 且拒绝语义
 * 丢失）。这些测试钉死一个不变量：<b>AbortException 必须透传中止，绝不转 deny / 绝不落入分类器</b>。
 *
 * <p>覆盖：
 * <ol>
 *   <li>入口 abort 预检（对齐 CC :1163-1165）：abort 信号已取消 → check 抛 AbortException，
 *       不跑 10 层管线；未取消 → 正常返回（负控）</li>
 *   <li>transcriptTooLong headless（CC :826-828）→ AbortException 透传（非 iron-gate deny）</li>
 *   <li>denial-limit headless（CC :1024-1026）→ AbortException 透传（非 iron-gate deny）</li>
 *   <li>acceptEdits fast-path 工具抛 AbortException（CC :650-655）→ 透传（不落入分类器）</li>
 *   <li>回归：非 Abort 异常仍 iron-gate deny / 落入分类器（R3 fail-closed 不破）</li>
 * </ol>
 *
 * <p>构造方式与 AcceptEditsFastPathTest 相同：真实 10 层管线 + 注入 4 个 classifier 依赖
 * （package-private 字段）+ 可控 fake classifier + 可控工具 stub。
 *
 * @since permissions_v4 IMP-1
 */
@DisplayName("[OPD-WF3-DC-v4-07] abort 语义对齐（AbortException 透传中止，不转 deny）")
class PermissionPipelineAbortTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CALL_ID = "call_abort_001";

    private PermissionPipeline pipeline;
    private FakeYoloClassifier fakeClassifier;
    private ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        pipeline = new PermissionPipeline();
        fakeClassifier = new FakeYoloClassifier();
        pipeline.autoModeGate = new AutoModeGate(true);
        pipeline.safeToolWhitelist = new SafeToolWhitelist();
        pipeline.denialTracker = new DenialTracker(3, 20);
        pipeline.yoloClassifier = fakeClassifier;
        ctx = newCtx(null, null);
    }

    @AfterEach
    void tearDown() {
        AutoModeState.resetForTesting();
    }

    // ─────────────────────────────── 构造辅助 ───────────────────────────────

    /** 17 参 ToolUseContext（abortController null → NOOP；permCtx 可为 null）。 */
    private static ToolUseContext newCtx(AbortController abortController,
                                         ToolPermissionContext permCtx) {
        return new ToolUseContext(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", abortController, List.of(),
            permCtx, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null);
    }

    /** headless permCtx（shouldAvoidPermissionPrompts=true + 指定 mode）。 */
    private static ToolPermissionContext headlessPermCtx(PermissionMode mode) {
        return new ToolPermissionContext(
            mode, Map.of(), Map.of(), Map.of(), Map.of(),
            false, false, Map.of(), true, false, null);
    }

    private static ToolUseBlock newCall(String id, String toolName, JsonNode input) {
        return new ToolUseBlock(id, toolName, input);
    }

    // ─────────────────────── 1. 入口 abort 预检（CC :1163-1165） ───────────────────────

    @Test
    @DisplayName("入口预检：abort 信号已取消 → check 抛 AbortException（不跑 10 层）")
    void entry_abortedSignal_throwsAbortException() {
        // WHY: CC hasPermissionsToUseToolInner 入口 `if (context.abortController.signal.aborted)
        //   { throw new AbortError() }`。旧 Java 无入口预检，aborted 状态仍跑 10 层管线
        //   （纯 Allow 路径不感知 abort）→ 用户中止意图丢失。M-118/OPD-WF3-01-15。
        AbortController aborted = new AbortController();
        aborted.abort();
        ToolUseContext abortedCtx = newCtx(aborted, null);
        JsonNode input = JSON.createObjectNode().put("command", "anything");
        AskTool tool = new AskTool("CustomAbortTool", input);

        assertThatThrownBy(() -> pipeline.check(
                tool, newCall(CALL_ID, tool.name(), input), input, abortedCtx,
                ToolPermissionContext.strict(PermissionMode.DEFAULT)))
            .as("入口 abort 预检：abort 信号已取消 → 抛 AbortException 中止 agent")
            .isInstanceOf(AbortException.class);
        assertThat(fakeClassifier.classifyCallCount)
            .as("abort 预检在 10 层/分类器之前 → 分类器绝不调用")
            .isZero();
    }

    @Test
    @DisplayName("负控：未取消 → check 正常返回（预检不误伤）")
    void entry_notAborted_returnsNormalResult() {
        // WHY: 预检只在 abort 信号已取消时触发；未取消必须正常走管线，不误伤正常流程。
        JsonNode input = JSON.createObjectNode().put("command", "safe op");
        fakeClassifier.queueResult(YoloClassifierResult.allowed("classifier allowed", "fake-model"));
        AskTool tool = new AskTool("CustomAbortTool", input);

        PermissionResult result = pipeline.check(
            tool, newCall(CALL_ID, tool.name(), input), input, ctx,
            ToolPermissionContext.strict(PermissionMode.AUTO));

        assertThat(result).as("未取消 → 正常返回决策（不抛）").isNotNull();
        assertThat(fakeClassifier.classifyCallCount).as("未取消 → 分类器可被调用").isEqualTo(1);
    }

    // ─────────────────────── 2. transcriptTooLong headless（CC :826-828） ───────────────────────

    @Test
    @DisplayName("transcriptTooLong + headless → AbortException 透传（不转 iron-gate deny）")
    void transcriptTooLong_headless_abortPropagates() {
        // WHY: CC :822-829 —— headless + transcriptTooLong 是永久条件（transcript 只增不减），
        //   deny-retry-deny 浪费 token 且永远到不了 denial-limit abort → 直接抛 AbortError 中止。
        //   旧 Java :565 catch(Exception) 把 AbortException 吞掉转 iron-gate deny（不中止）。
        //   M-090/OPD-WF3-01-18。
        JsonNode input = JSON.createObjectNode().put("command", "write something");
        fakeClassifier.queueResult(new YoloClassifierResult(null, true,
            "transcript exceeded classifier context window", false, true,
            "fake-model", null, 0L, null, null, YoloClassifierResult.STAGE_FAST,
            null, null, null, null, null, null, null, null));
        ToolUseContext headlessCtx = newCtx(null, headlessPermCtx(PermissionMode.AUTO));
        AskTool tool = new AskTool("CustomAbortTool", input);

        assertThatThrownBy(() -> pipeline.check(
                tool, newCall(CALL_ID, tool.name(), input), input, headlessCtx,
                headlessPermCtx(PermissionMode.AUTO)))
            .as("transcriptTooLong headless → AbortException 中止 agent（绝不转 deny）")
            .isInstanceOf(AbortException.class);
    }

    // ─────────────────────── 3. denial-limit headless（CC :1024-1026） ───────────────────────

    @Test
    @DisplayName("denial-limit + headless → AbortException 透传（不转 iron-gate deny）")
    void denialLimit_headless_abortPropagates() {
        // WHY: CC :1018-1027 —— headless + 拒绝超限抛 AbortError 中断，不再僵持无人响应的 Ask。
        //   旧 Java :565 catch(Exception) 把 AbortException 吞掉转 iron-gate deny。
        //   M-112/OPD-AM-03。
        pipeline.denialTracker = new DenialTracker(3, 1); // maxTotal=1 → 首次拒绝即超限
        JsonNode input = JSON.createObjectNode().put("command", "risky op");
        fakeClassifier.queueResult(YoloClassifierResult.blocked("blocked by classifier", "fake-model"));
        ToolUseContext headlessCtx = newCtx(null, headlessPermCtx(PermissionMode.AUTO));
        AskTool tool = new AskTool("CustomAbortTool", input);

        assertThatThrownBy(() -> pipeline.check(
                tool, newCall(CALL_ID, tool.name(), input), input, headlessCtx,
                headlessPermCtx(PermissionMode.AUTO)))
            .as("denial-limit headless → AbortException 中止 agent（绝不转 deny）")
            .isInstanceOf(AbortException.class);
    }

    // ─────────────────────── 4. acceptEdits fast-path AbortException（CC :650-655） ───────────────────────

    @Test
    @DisplayName("acceptEdits fast-path 工具抛 AbortException → 透传（不落入分类器）")
    void acceptEdits_toolThrowsAbortException_propagates() {
        // WHY: CC :650-657 —— catch(e){ if (e instanceof AbortError || e instanceof APIUserAbortError)
        //   { throw e } }。用户中止意图必须透传，不落入分类器。
        //   旧 Java :412 catch(Exception) 吞掉 AbortException 落入分类器（中止意图丢失）。M-079/△-20。
        JsonNode input = JSON.createObjectNode().put("command", "edit file");
        ToolUseContext autoCtx = newCtx(null, ToolPermissionContext.strict(PermissionMode.AUTO));
        AbortOnAcceptEditsTool tool = new AbortOnAcceptEditsTool("CustomAbortTool", input);

        assertThatThrownBy(() -> pipeline.check(
                tool, newCall(CALL_ID, tool.name(), input), input, autoCtx,
                ToolPermissionContext.strict(PermissionMode.AUTO)))
            .as("acceptEdits fast-path AbortException → 透传中止 agent（不落入分类器）")
            .isInstanceOf(AbortException.class);
        assertThat(fakeClassifier.classifyCallCount)
            .as("AbortException 透传 → 分类器绝不调用")
            .isZero();
    }

    // ─────────────────────── 5. 回归：非 Abort 异常不破 fail-closed ───────────────────────

    @Test
    @DisplayName("回归：acceptEdits 工具抛普通异常 → 落入分类器（CC :650-655 fall through 不破）")
    void acceptEdits_toolThrowsGenericException_fallsToClassifier() {
        // WHY: 只有 AbortException 才透传；普通异常仍 fall through 到分类器（CC :654 注释
        //   "If the acceptEdits check fails, fall through to the classifier"）。
        JsonNode input = JSON.createObjectNode().put("command", "edit file");
        fakeClassifier.queueResult(YoloClassifierResult.allowed("after exception", "fake-model"));
        ToolUseContext autoCtx = newCtx(null, ToolPermissionContext.strict(PermissionMode.AUTO));
        ThrowOnAcceptEditsTool tool = new ThrowOnAcceptEditsTool("CustomAbortTool", input);

        PermissionResult result = pipeline.check(
            tool, newCall(CALL_ID, tool.name(), input), input, autoCtx,
            ToolPermissionContext.strict(PermissionMode.AUTO));

        assertThat(result).as("普通异常 → 落入分类器放行").isInstanceOf(PermissionResult.Allow.class);
        assertThat(fakeClassifier.classifyCallCount).as("分类器被调用 1 次").isEqualTo(1);
    }

    @Test
    @DisplayName("回归：分类器同步抛普通异常 → 仍 iron-gate deny（R3 fail-closed 不破）")
    void classifier_syncGenericException_ironGateDeny() {
        // WHY: 只有 AbortException 才透传；其它异常仍走 [S12 R3] iron-gate deny（fail-closed）。
        //   证明 catch(AbortException) 前置没有改变普通异常降级语义。
        JsonNode input = JSON.createObjectNode().put("command", "risky op");
        fakeClassifier.syncError = new IllegalStateException("classifier network failed");
        ToolUseContext autoCtx = newCtx(null, ToolPermissionContext.strict(PermissionMode.AUTO));
        AskTool tool = new AskTool("CustomAbortTool", input);

        PermissionResult result = pipeline.check(
            tool, newCall(CALL_ID, tool.name(), input), input, autoCtx,
            ToolPermissionContext.strict(PermissionMode.AUTO));

        assertThat(result).as("普通异常 → iron-gate deny（fail-closed）").isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .isInstanceOfSatisfying(PermissionDecisionReason.Classifier.class, c ->
                assertThat(c.reason()).isEqualTo("Classifier unavailable"));
    }

    // ─────────────────── 工具 stub ───────────────────

    /** 恒定 Ask 工具（DEFAULT/ACCEPT_EDITS 都 Ask）→ 走分类器决策链。 */
    private static class AskTool implements Tool {
        protected final String name;
        protected final JsonNode input;

        AskTool(String name, JsonNode input) {
            this.name = name;
            this.input = input;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "abort stub";
        }

        @Override
        public AgentToolResult<?> execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "ok");
        }

        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public PermissionResult checkPermissions(JsonNode in, ToolUseContext ctx) {
            return new PermissionResult.Ask(
                "need permission", new PermissionDecisionReason.Other("ask"), List.of(),
                null, null, null, false, null, List.of());
        }
    }

    /** ACCEPT_EDITS 模式抛 AbortException 的工具（DEFAULT 模式 Ask）。 */
    private static final class AbortOnAcceptEditsTool extends AskTool {
        AbortOnAcceptEditsTool(String name, JsonNode input) {
            super(name, input);
        }

        @Override
        public PermissionResult checkPermissions(JsonNode in, ToolUseContext ctx) {
            if (ctx != null && ctx.permissionMode() == PermissionMode.ACCEPT_EDITS) {
                throw new AbortException("Agent aborted: user cancelled acceptEdits check");
            }
            return new PermissionResult.Ask(
                "need permission", new PermissionDecisionReason.Other("ask"), List.of(),
                null, null, null, false, null, List.of());
        }
    }

    /** ACCEPT_EDITS 模式抛普通异常的工具（DEFAULT 模式 Ask）→ fall through 到分类器。 */
    private static final class ThrowOnAcceptEditsTool extends AskTool {
        ThrowOnAcceptEditsTool(String name, JsonNode input) {
            super(name, input);
        }

        @Override
        public PermissionResult checkPermissions(JsonNode in, ToolUseContext ctx) {
            if (ctx != null && ctx.permissionMode() == PermissionMode.ACCEPT_EDITS) {
                throw new IllegalStateException("acceptEdits check boom");
            }
            return new PermissionResult.Ask(
                "need permission", new PermissionDecisionReason.Other("ask"), List.of(),
                null, null, null, false, null, List.of());
        }
    }

    /** Fake YoloClassifier：不调 LLM，行为可控（AcceptEditsFastPathTest 同款 + syncError）。 */
    private static final class FakeYoloClassifier implements YoloClassifier {
        final java.util.ArrayDeque<YoloClassifierResult> queue = new java.util.ArrayDeque<>();
        volatile int classifyCallCount = 0;
        volatile RuntimeException syncError = null; // 非 null → classify 同步抛（普通异常路径）

        void queueResult(YoloClassifierResult r) {
            queue.add(r);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public CompletableFuture<YoloClassifierResult> classify(
                String toolName, JsonNode input,
                List<ChatMessageDto> transcript, ToolUseContext ctx) {
            classifyCallCount++;
            if (syncError != null) {
                throw syncError;
            }
            YoloClassifierResult r = queue.isEmpty()
                ? YoloClassifierResult.allowed("default allow", "fake-model")
                : queue.poll();
            return CompletableFuture.completedFuture(r);
        }

        @Override
        public CompletableFuture<YoloClassifierResult> classifyTextAction(
                String userText, List<ChatMessageDto> transcript, ToolUseContext ctx) {
            // 本测试不触发 handoff text-action → 恒 allow 兜底
            return CompletableFuture.completedFuture(YoloClassifierResult.allowed(
                "fake-text-action-not-used", "fake-model"));
        }
    }
}
