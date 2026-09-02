package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.classifier.DenialTracker;
import com.nexusai.application.agent.permission.classifier.SafeToolWhitelist;
import com.nexusai.application.agent.permission.classifier.YoloClassifier;
import com.nexusai.application.agent.permission.classifier.YoloClassifierResult;
import com.nexusai.application.agent.tool.Tool;
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

/**
 * [S10 X-14] acceptEdits fast-path 测试 · 对齐 CC permissions.ts:600-656
 * （真实 {@code tool.checkPermissions({mode:'acceptEdits'})}，旧硬编码启发式由本路径
 * 取代，O49 删除归 S13）。
 *
 * <p>覆盖（验收标准 §5-2）：
 * <ol>
 *   <li>工具 acceptEdits 模式放行 → Allow（decisionReason=Mode(auto)）+ 分类器不调用 +
 *       recordSuccess 断连拒链（CC :620-622/:641-648）</li>
 *   <li>工具两种模式都 Ask → 落入分类器（CC :654 fall through）</li>
 *   <li>Agent 工具排除（CC :600-602）→ 直接走分类器</li>
 *   <li>REPL 工具排除（CC :603）→ 直接走分类器</li>
 *   <li>fast-path 抛异常 → 落入分类器（CC :650-655）</li>
 *   <li>updatedInput 透传（CC :643 acceptEditsResult.updatedInput ?? input）</li>
 * </ol>
 *
 * <p>构造方式与 R32B12_AutoClassifierR1R4Test 相同：真实 10 层管线 + 注入 4 个
 * classifier 依赖（package-private 字段）+ 可控 fake classifier + 可控工具 stub。
 */
@DisplayName("[S10 X-14] acceptEdits fast-path（真实 checkPermissions mode=acceptEdits）")
class AcceptEditsFastPathTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CALL_ID = "call_fastpath_001";

    private PermissionPipeline pipeline;
    private FakeYoloClassifier fakeClassifier;
    private DenialTracker denialTracker;
    private ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        pipeline = new PermissionPipeline();
        fakeClassifier = new FakeYoloClassifier();
        pipeline.autoModeGate = new AutoModeGate(true);
        pipeline.safeToolWhitelist = new SafeToolWhitelist();
        denialTracker = new DenialTracker(3, 20);
        pipeline.denialTracker = denialTracker;
        pipeline.yoloClassifier = fakeClassifier;
        ctx = new ToolUseContext(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        AutoModeState.resetForTesting();
    }

    @Test
    @DisplayName("acceptEdits 放行 → Allow(Mode(auto)) + updatedInput 透传 + 分类器不调用 + recordSuccess")
    void acceptEditsAllows_allowFastPath() {
        fakeClassifier.queueResult(YoloClassifierResult.allowed(
            "should not be reached", "fake-model"));
        denialTracker.recordDenial(); // 制造连续拒绝，验证 fast-path recordSuccess 断链

        JsonNode input = JSON.createObjectNode().put("file_path", "/tmp/x.txt");
        JsonNode modified = JSON.createObjectNode().put("file_path", "/tmp/x.txt").put("extra", "edited");
        Tool tool = new ModeAwareTool("EditFile", input, modified);

        PermissionResult result = pipeline.check(
            tool, new ToolUseBlock(CALL_ID, tool.name(), input), input, ctx,
            ToolPermissionContext.strict(PermissionMode.AUTO));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        PermissionResult.Allow allow = (PermissionResult.Allow) result;
        assertThat(allow.reason())
            .as("CC :644-647 decisionReason = {type:'mode', mode:'auto'}")
            .isEqualTo(new PermissionDecisionReason.Mode(PermissionMode.AUTO));
        assertThat(allow.updatedInput())
            .as("CC :643 updatedInput = acceptEditsResult.updatedInput ?? input")
            .isEqualTo(modified);
        assertThat(fakeClassifier.classifyCallCount)
            .as("acceptEdits fast-path 命中 → 不调分类器（CC :620-640）")
            .isZero();
        assertThat(denialTracker.getConsecutiveDenials())
            .as("CC :620-622 recordSuccess 断连拒链")
            .isZero();
    }

    @Test
    @DisplayName("两种模式都 Ask → 落入分类器（CC :654 fall through）")
    void acceptEditsAlsoAsks_fallsToClassifier() {
        fakeClassifier.queueResult(YoloClassifierResult.allowed(
            "classifier allowed", "fake-model"));
        JsonNode input = JSON.createObjectNode().put("command", "custom op");
        Tool tool = new ModeAwareTool("CustomTool", input, null); // acceptEdits 下仍 Ask

        PermissionResult result = pipeline.check(
            tool, new ToolUseBlock(CALL_ID, tool.name(), input), input, ctx,
            ToolPermissionContext.strict(PermissionMode.AUTO));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("分类器放行归因（fast-path 未命中）")
            .isEqualTo(new PermissionDecisionReason.Classifier("auto-mode",
                "classifier allowed"));
        assertThat(fakeClassifier.classifyCallCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Agent 工具排除 → 直接走分类器（CC :600-602）")
    void agentTool_skipsFastPath() {
        fakeClassifier.queueResult(YoloClassifierResult.allowed(
            "agent allowed", "fake-model"));
        JsonNode input = JSON.createObjectNode().put("command", "agent op");
        Tool tool = new ModeAwareTool("Agent", input, JSON.createObjectNode().put("ok", true));

        PermissionResult result = pipeline.check(
            tool, new ToolUseBlock(CALL_ID, tool.name(), input), input, ctx,
            ToolPermissionContext.strict(PermissionMode.AUTO));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(fakeClassifier.classifyCallCount)
            .as("Agent 的 checkPermissions 在 acceptEdits 返回 allow 会静默绕过分类器 → 必须排除")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("REPL 工具排除 → 直接走分类器（CC :603）")
    void replTool_skipsFastPath() {
        fakeClassifier.queueResult(YoloClassifierResult.allowed(
            "repl allowed", "fake-model"));
        JsonNode input = JSON.createObjectNode().put("command", "repl op");
        Tool tool = new ModeAwareTool("REPL", input, JSON.createObjectNode().put("ok", true));

        PermissionResult result = pipeline.check(
            tool, new ToolUseBlock(CALL_ID, tool.name(), input), input, ctx,
            ToolPermissionContext.strict(PermissionMode.AUTO));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(fakeClassifier.classifyCallCount)
            .as("REPL 代码可在内部工具调用间做 VM 逃逸 → 必须排除（CC :598-599 注释）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("fast-path 抛异常 → 落入分类器（CC :650-655）")
    void fastPathThrows_fallsToClassifier() {
        fakeClassifier.queueResult(YoloClassifierResult.allowed(
            "after exception", "fake-model"));
        JsonNode input = JSON.createObjectNode().put("command", "boom");
        Tool tool = new ThrowingTool("ExplosiveTool", input);

        PermissionResult result = pipeline.check(
            tool, new ToolUseBlock(CALL_ID, tool.name(), input), input, ctx,
            ToolPermissionContext.strict(PermissionMode.AUTO));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(fakeClassifier.classifyCallCount)
            .as("CC :650-655 acceptEdits 检查失败 → fall through 到分类器")
            .isEqualTo(1);
    }

    // ─────────────────── 工具 stub ───────────────────

    /**
     * 模式感知工具：默认（非 ACCEPT_EDITS）返回 Ask；ACCEPT_EDITS 模式返回 Allow
     * （携带 {@code acceptEditsUpdatedInput}，CC Edit 工具语义：acceptEdits 模式放行
     * CWD 内编辑）。
     */
    private static final class ModeAwareTool implements Tool {
        private final String name;
        private final JsonNode input;
        private final JsonNode acceptEditsUpdatedInput;

        ModeAwareTool(String name, JsonNode input, JsonNode acceptEditsUpdatedInput) {
            this.name = name;
            this.input = input;
            this.acceptEditsUpdatedInput = acceptEditsUpdatedInput;
        }

        @Override
        public String name() {
            return name;
        }


        @Override
        public com.nexusai.application.agent.tool.AgentToolResult execute(ToolUseBlock call) {
            return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "ok");
        }
        @Override
        public String description() {
            return "mode-aware stub";
        }

        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public PermissionResult checkPermissions(JsonNode in, ToolUseContext ctx) {
            // acceptEditsUpdatedInput != null → acceptEdits 模式放行（携带修改后 input）；
            // null → 两种模式都 Ask（fast-path 不命中，落入分类器）
            if (ctx != null && ctx.permissionMode() == PermissionMode.ACCEPT_EDITS
                    && acceptEditsUpdatedInput != null) {
                return new PermissionResult.Allow(
                    acceptEditsUpdatedInput, new PermissionDecisionReason.Other("acceptEdits allow"),
                    null, false, null, null);
            }
            return new PermissionResult.Ask(
                "need permission", new PermissionDecisionReason.Other("ask"), List.of(),
                null, null, null, false, null, List.of());
        }
    }

    /** 仅 1c 层 Ask；ACCEPT_EDITS 模式抛异常（fast-path 失败路径）。 */
    private static final class ThrowingTool implements Tool {
        private final String name;
        private final JsonNode input;

        ThrowingTool(String name, JsonNode input) {
            this.name = name;
            this.input = input;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "throwing stub";
        }

        @Override
        public com.nexusai.application.agent.tool.AgentToolResult execute(ToolUseBlock call) {
            return com.nexusai.application.agent.tool.ToolResult.success(call.id(), "ok");
        }


        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode();
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

    /** Fake YoloClassifier：不调 LLM，行为可控（R32B12 同款）。 */
    private static final class FakeYoloClassifier implements YoloClassifier {
        final java.util.ArrayDeque<YoloClassifierResult> queue = new java.util.ArrayDeque<>();
        volatile int classifyCallCount = 0;

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
            YoloClassifierResult r = queue.isEmpty()
                ? YoloClassifierResult.allowed("default allow", "fake-model")
                : queue.poll();
            return CompletableFuture.completedFuture(r);
        }

        @Override
        public CompletableFuture<YoloClassifierResult> classifyTextAction(
                String userText, List<ChatMessageDto> transcript, ToolUseContext ctx) {
            // [IMP-SUB-25 R3] 测试 stub：handoff user-text action 在本测试不触发 → 恒 allow 兜底
            return CompletableFuture.completedFuture(YoloClassifierResult.allowed(
                "fake-text-action-not-used", "fake-model"));
        }
    }
}
