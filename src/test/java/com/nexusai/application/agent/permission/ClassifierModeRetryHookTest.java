package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.classifier.DenialTracker;
import com.nexusai.application.agent.permission.classifier.SafeToolWhitelist;
import com.nexusai.application.agent.permission.classifier.YoloClassifier;
import com.nexusai.application.agent.permission.classifier.YoloClassifierResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
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
 * permissions_v3 WF-1 · DEL-WF1-01 · Classifier 无 mode 字段后 retry 链路 4 项测试。
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC
 * {@code Open-ClaudeCode/src/services/tools/toolExecution.ts:1075-1101} retry hook
 * 触发条件 {@code decisionReason.classifier === 'auto-mode'}。CC 类型契约
 * {@code types/permissions.ts:303-307} 分类器变体仅 {@code {type:'classifier', classifier, reason}}
 * 无 mode 字段, auto-mode 语义由 {@code classifier} 字段值 {@code 'auto-mode'} 承载
 * （CC 构造侧 permissions.ts:907/923）。Java 端 {@link PermissionDecisionReason.Classifier}
 * record 已删除 {@code mode} 字段（DEL-WF1-01）: {@link PermissionPipeline} 的 auto-mode
 * classify 路径必须用 2-arg ctor 写出 {@code classifier='auto-mode'} (PermissionPipeline.java:438),
 * 否则 {@code classifier} 为其他值 → retry hook 永远不触发。
 *
 * <h2>4 项测试</h2>
 * <ol>
 *   <li><b>classifier_ctor_propagatesFields</b> — record 层:2-arg ctor
 *       (classifier, reason) 两个字段都正确传入 (验证 retry hook 触发条件字段契约)</li>
 *   <li><b>classifier_ctor_noModeField</b> — record 层:反射验证无 {@code mode} 字段 /
 *       {@code mode()} accessor（DEL-WF1-01 删除的契约断言）</li>
 *   <li><b>pipeline_createsClassifierWithAutoModeOnDeny</b> — Pipeline 层:
 *       classifier 拒绝路径写 {@code classifier='auto-mode'} (本任务复验核心)</li>
 *   <li><b>pipeline_createsClassifierWithAutoModeOnAllow</b> — Pipeline 层:
 *       classifier 放行路径写 {@code classifier='auto-mode'} (本任务复验核心)</li>
 * </ol>
 *
 * <h2>测试方法</h2>
 * <p>Pipeline 层测试通过构造 {@link PermissionPipeline} + 直接注入
 * {@code autoModeGate / denialTracker / safeToolWhitelist / yoloClassifier} 4 个
 * classifier 依赖(同包访问 package-private 字段),调
 * {@link PermissionPipeline#check} 走通 classifier 路径。
 *
 * <p>为了让 Pipeline 走到 classifier 路径(需先有 {@link PermissionResult.Ask}),
 * 使用 {@link PassthroughStubTool} 让 tool.checkPermissions 返回
 * {@link PermissionResult.Passthrough} → Layer 1c 返回 null → 所有 deny/ask/allow
 * 规则未命中 → Layer 3 兜底返回 Ask → Pipeline 触发 classifier 评估。
 */
class ClassifierModeRetryHookTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_CUSTOM = "CustomBash";
    private static final String CALL_ID = "call_c_mode_001";

    private PermissionPipeline pipeline;
    private FakeYoloClassifier fakeClassifier;
    private ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        pipeline = new PermissionPipeline();
        fakeClassifier = new FakeYoloClassifier();
        // [直接注入] PermissionPipeline 4 个 classifier 依赖为 package-private 字段
        // (s04 PR @Autowired(required=false) 兼容),测试同包可直接赋值。
        pipeline.autoModeGate = new AutoModeGate(true); // 启用 auto mode
        pipeline.safeToolWhitelist = new SafeToolWhitelist(); // 默认空集合,自定义 tool 不命中
        pipeline.denialTracker = new DenialTracker(3, 20); // 默认阈值,初始 DISABLED 状态 OK
        pipeline.yoloClassifier = fakeClassifier; // 可控的 fake classifier
        // ToolUseContext: 最小字段足够跑通 check() (Pipeline 各层大多不需要 ctx 内部字段)
        UUID agentId = UUID.randomUUID();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        ctx = new ToolUseContext(agentId, sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null);
    }

    private JsonNode input() {
        return JSON.createObjectNode().put("command", "rm -rf /tmp/foo");
    }

    private ToolUseBlock call() {
        return new ToolUseBlock(CALL_ID, TOOL_CUSTOM, input());
    }

    private ToolPermissionContext permCtx() {
        // 空规则集 + AUTO mode → 所有 deny/ask/allow rule 都未命中，且命中 [S12 R1] 入口门
//   （非 auto/plan+active 模式 Ask 不进分类器）
        return ToolPermissionContext.strict(PermissionMode.AUTO);
    }

    // ─────────── 测试 1: record 2-arg ctor ───────────

    @Test
    @DisplayName("classifier_ctor_propagatesFields: 2-arg ctor (classifier, reason) 两个字段全部正确传入 record")
    void classifier_ctor_propagatesFields() {
        // 2-arg ctor: (classifier, reason) → 两个字段都存进 record (对齐 CC
        //   types/permissions.ts:303-307 {type:'classifier', classifier, reason})
        PermissionDecisionReason.Classifier classifier =
            new PermissionDecisionReason.Classifier("auto-mode", "dangerous command");

        // 验证两个字段都通过 accessor 拿到 (不是 null,不是默认值,不是被丢弃)
        assertThat(classifier.classifier())
            .as("classifier 字段 == 'auto-mode' (CC permissions.ts:907/923 构造侧) — retry hook 触发条件核心字段")
            .isEqualTo("auto-mode");
        assertThat(classifier.reason())
            .as("reason 字段 == 构造器传入的理由描述")
            .isEqualTo("dangerous command");
    }

    // ─────────── 测试 2: [WF-1 DEL-WF1-01] mode 字段已删除 ───────────
    // 原 R32-b13 B9 引入 mode 字段, permissions_v3 WF-1 按用户拍板 OD-WF1-02 删除,
    // auto-mode 语义落入 classifier 字段. 此处反射验证 record 无 mode accessor.

    @Test
    @DisplayName("classifier_ctor_noModeField: record 无 mode() accessor (DEL-WF1-01 删除契约)")
    void classifier_ctor_noModeField() throws Exception {
        PermissionDecisionReason.Classifier c =
            new PermissionDecisionReason.Classifier("auto-mode", "legacy reason");

        assertThat(c.classifier()).isEqualTo("auto-mode");
        assertThat(c.reason()).isEqualTo("legacy reason");

        // 反射验证 mode() accessor 不存在 (字段删除后无影子方法残留)
        assertThatThrownBy(() -> c.getClass().getMethod("mode"))
            .as("DEL-WF1-01 删除 mode 字段后, mode() accessor 必须不存在 (抛 NoSuchMethodException)")
            .isInstanceOf(NoSuchMethodException.class);
    }

    // ─────────── 测试 3: Pipeline classifier deny 路径 classifier='auto-mode' ───────────

    @Test
    @DisplayName("pipeline_createsClassifierWithAutoModeOnDeny: classifier DENY 时 classifier='auto-mode' (本次改动核心)")
    void pipeline_createsClassifierWithAutoModeOnDeny() {
        // [WF-1 核心] classifier 拒绝 → PermissionPipeline 必须写出
        //   Classifier(classifier='auto-mode', reason=...) (CC permissions.ts:905-911)
        // 而不是保留旧 mode 字段 → retry hook 触发条件落在 classifier 字段上。
        fakeClassifier.queueResult(YoloClassifierResult.blocked("dangerous rm -rf", "fake-model"));

        PermissionResult result = pipeline.check(
            new PassthroughStubTool(TOOL_CUSTOM), call(), input(), ctx, permCtx());

        // 验证 Pipeline 返回 Deny,且 reason 是 Classifier 类型 + classifier='auto-mode'
        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        PermissionResult.Deny deny = (PermissionResult.Deny) result;
        assertThat(deny.reason()).isInstanceOf(PermissionDecisionReason.Classifier.class);

        PermissionDecisionReason.Classifier reason =
            (PermissionDecisionReason.Classifier) deny.reason();
        assertThat(reason.classifier())
            .as("[WF-1 核心] classifier 字段必须 == 'auto-mode' (CC retry hook 触发条件)")
            .isEqualTo("auto-mode");
        assertThat(reason.reason())
            .as("[S12] decisionReason.reason = classifierResult.reason（CC permissions.ts:907-909）")
            .isEqualTo("dangerous rm -rf");
        assertThat(deny.message())
            .as("[S12] deny 消息 = buildYoloRejectionMessage(reason) 前缀（CC messages.ts:267-282）")
            .startsWith("Permission for this action has been denied. Reason: dangerous rm -rf");

        // 防御性断言: classifier deny 路径必须调 denialTracker.recordDenial()（1 次拒绝不熔断）
        assertThat(pipeline.denialTracker.shouldFallbackToPrompting())
            .as("[防御性] classifier deny 路径必须调 denialTracker.recordDenial()")
            .isFalse();
    }

    // ─────────── 测试 4: Pipeline classifier allow 路径 classifier='auto-mode' ───────────

    @Test
    @DisplayName("pipeline_createsClassifierWithAutoModeOnAllow: classifier ALLOW 时 classifier='auto-mode' (本次改动核心)")
    void pipeline_createsClassifierWithAutoModeOnAllow() {
        // [WF-1 核心] classifier 放行 → PermissionPipeline 必须写出
        //   Classifier(classifier='auto-mode', reason=...) (CC permissions.ts:918-925)
        // ALLOW 路径同样需要 classifier='auto-mode' (CC 对齐: auto-mode 上下文无论
        // allow/deny 都写 classifier: 'auto-mode',retry hook 触发条件只看 classifier 字段)
        fakeClassifier.queueResult(YoloClassifierResult.allowed("looks safe", "fake-model"));

        PermissionResult result = pipeline.check(
            new PassthroughStubTool(TOOL_CUSTOM), call(), input(), ctx, permCtx());

        // 验证 Pipeline 返回 Allow,且 reason 是 Classifier 类型 + classifier='auto-mode'
        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        PermissionResult.Allow allow = (PermissionResult.Allow) result;
        assertThat(allow.reason()).isInstanceOf(PermissionDecisionReason.Classifier.class);

        PermissionDecisionReason.Classifier reason =
            (PermissionDecisionReason.Classifier) allow.reason();
        assertThat(reason.classifier())
            .as("[WF-1 核心] classifier 字段必须 == 'auto-mode' (CC retry hook 触发条件)")
            .isEqualTo("auto-mode");
        assertThat(reason.reason())
            .as("[S12] decisionReason.reason = classifierResult.reason（CC permissions.ts:920-925）")
            .isEqualTo("looks safe");
    }

    // ──────────────────── helpers ────────────────────

    /**
     * Fake YoloClassifier: 不调 LLM,行为可控。
     */
    private static class FakeYoloClassifier implements YoloClassifier {
        final java.util.ArrayDeque<YoloClassifierResult> queue = new java.util.ArrayDeque<>();
        volatile YoloClassifierResult nextResult;
        volatile int classifyCallCount = 0;

        void queueResult(YoloClassifierResult r) {
            queue.add(r);
        }

        @Override
        public CompletableFuture<YoloClassifierResult> classify(
                String toolName, JsonNode input, List<com.nexusai.model.session.dto.ChatMessageDto> transcript,
                ToolUseContext ctx
        ) {
            classifyCallCount++;
            YoloClassifierResult r = queue.poll();
            if (r == null) r = nextResult;
            return CompletableFuture.completedFuture(r);
        }

        @Override
        public CompletableFuture<YoloClassifierResult> classifyTextAction(
                String userText, List<com.nexusai.model.session.dto.ChatMessageDto> transcript, ToolUseContext ctx) {
            // [IMP-SUB-25 R3] 测试 stub：handoff user-text action 在本测试不触发 → 恒 allow 兜底
            return CompletableFuture.completedFuture(YoloClassifierResult.allowed(
                "fake-text-action-not-used", "fake-model"));
        }

        @Override
        public boolean isAvailable() { return true; }
    }

    /**
     * 工具 stub: {@code checkPermissions} 返回 {@link PermissionResult.Passthrough},
     * 让 Pipeline 走到 Layer 3 兜底 Ask → 触发 classifier 路径。
     */
    private static class PassthroughStubTool implements Tool {
        private final String name;

        PassthroughStubTool(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }

        @Override public String description() { return "stub for classifier path test"; }

        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }

        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "ok");
        }

        // [关键] 返回 Passthrough (不是默认的 Allow) → Layer 1c 不会 short-circuit,
        // Pipeline 才会兜底到 Layer 3 的 Ask,触发 classifier 评估。
        @Override
        public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
            return new PermissionResult.Passthrough(
                "stub passthrough", null, List.of(), null,null);
        }
    }
}