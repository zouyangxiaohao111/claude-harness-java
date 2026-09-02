package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.classifier.ClassifierUsage;
import com.nexusai.application.agent.permission.classifier.DenialTracker;
import com.nexusai.application.agent.permission.classifier.PromptLengths;
import com.nexusai.application.agent.permission.classifier.SafeToolWhitelist;
import com.nexusai.application.agent.permission.classifier.TurnClassifierStats;
import com.nexusai.application.agent.permission.classifier.YoloClassifier;
import com.nexusai.application.agent.permission.classifier.YoloClassifierResult;
import com.nexusai.application.agent.telemetry.Telemetry;
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
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-7 · OPD-WF3-01-13/16] tengu_auto_mode_decision 字段补齐 + addToTurnClassifierDuration 耗时遥测。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：
 * <ul>
 *   <li><b>字段补齐</b>：CC 主分类器事件（permissions.ts:733-812）携带 30+ 字段
 *       （classifierModel/usage 4 字段/durationMs/promptLengths 3 字段/stage/stage1+2 各
 *       usage+durationMs+requestId+msgId）。旧 Java 仅发射 decision/toolName/fastPath/
 *       consecutiveDenials/totalDenials 核心字段（OPD-WF3-01-13 拍板：补全字段）。
 *       —— 本测试钉死：主路径事件必须携带 CC 完整字段集。</li>
 *   <li><b>mcp 归一化</b>：CC {@code toolName: sanitizeToolNameForAnalytics(tool.name)}
 *       （metadata.ts:70-77）——mcp__* → mcp_tool（PII 防护）。旧 Java 直传原始 MCP 名。</li>
 *   <li><b>fast-path 事件</b>：acceptEdits/allowlist（CC :626-640/:666-677）仅
 *       decision/toolName/inProtectedNamespace/agentMsgId/confidence='high'/fastPath，
 *       <b>无</b> classifier 字段（consecutiveDenials 等不发射）。</li>
 *   <li><b>addToTurnClassifierDuration</b>：CC :814-816 每次分类器调用后把 durationMs
 *       累计到回合级耗时（state.ts:627-630）——本测试钉死累计侧行为。</li>
 * </ul>
 *
 * <p>构造方式与 PermissionPipelineAbortTest 相同：真实 10 层管线 + 注入 classifier 依赖
 * （package-private 字段）+ spy telemetry + 真实 TurnClassifierStats。
 *
 * @since permissions_v4 IMP-7
 */
@DisplayName("[IMP-7] tengu_auto_mode_decision 字段补齐 + 耗时累计遥测")
class PermissionPipelineTelemetryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CALL_ID = "call_telemetry_001";
    private static final String SESSION_ID = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final long DURATION_MS = 1500L;

    private PermissionPipeline pipeline;
    private FakeYoloClassifier fakeClassifier;
    private SpyTelemetry spy;
    private TurnClassifierStats stats;
    private ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        pipeline = new PermissionPipeline();
        fakeClassifier = new FakeYoloClassifier();
        spy = new SpyTelemetry();
        stats = new TurnClassifierStats();
        pipeline.autoModeGate = new AutoModeGate(true);
        pipeline.safeToolWhitelist = new SafeToolWhitelist();
        pipeline.denialTracker = new DenialTracker(3, 20);
        pipeline.yoloClassifier = fakeClassifier;
        pipeline.telemetry = spy;
        pipeline.turnClassifierStats = stats;
        ctx = newCtx(SESSION_ID, null, ToolPermissionContext.strict(PermissionMode.AUTO));
    }

    @AfterEach
    void tearDown() {
        AutoModeState.resetForTesting();
    }

    // ─────────────────── 构造辅助 ───────────────────

    private static ToolUseContext newCtx(String sessionId, com.nexusai.application.agent.tool.AbortController abortController,
                                         ToolPermissionContext permCtx) {
        return new ToolUseContext(UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", abortController, List.of(),
            permCtx, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null);
    }

    private static ToolUseBlock newCall(String id, String toolName, JsonNode input) {
        return new ToolUseBlock(id, toolName, input);
    }

    /** 带全量 CC 字段的分类器结果（stage2 thinking + usage + promptLengths + stage1/2 明细）。 */
    private static YoloClassifierResult richClassifierResult() {
        return new YoloClassifierResult(
            "thinking trace",
            true,                          // shouldBlock → blocked decision + denial 计数前瞻
            "blocked by classifier",
            false, false,                  // unavailable / transcriptTooLong
            "fake-model",
            new ClassifierUsage(100, 200, 300, 400),   // usage
            DURATION_MS,                   // durationMs
            new PromptLengths(500L, 1500L, 200L),      // promptLengths
            null,                          // errorDumpPath
            YoloClassifierResult.STAGE_THINKING,       // stage=2
            new ClassifierUsage(50, 100, 150, 200),    // stage1Usage
            800L, "req_stage1", "msg_stage1",          // stage1DurationMs/requestId/msgId
            new ClassifierUsage(50, 100, 150, 200),    // stage2Usage
            700L, "req_stage2", "msg_stage2");         // stage2DurationMs/requestId/msgId
    }

    // ─────────────────── 1. 主分类器路径：字段补齐 + mcp 归一化 + 耗时累计 ───────────────────

    @Test
    @DisplayName("主分类器事件携带 CC 完整字段（30+）+ mcp 工具名归一化 + durationMs 累计到回合统计")
    void classifierMainPath_emitsFullFieldSet_sanitizesMcpName_accumulatesDuration() {
        // WHY: CC permissions.ts:733-812 主事件 30+ 字段。旧 Java 仅核心字段。
        //   mcp__* → mcp_tool（metadata.ts:70-77）；:814-816 durationMs 累计回合耗时。
        fakeClassifier.queueResult(richClassifierResult());
        JsonNode input = JSON.createObjectNode().put("command", "git push");
        AskTool tool = new AskTool("mcp__github__create_issue", input);

        PermissionResult result = pipeline.check(
            tool, newCall(CALL_ID, tool.name(), input), input, ctx,
            ToolPermissionContext.strict(PermissionMode.AUTO));

        assertThat(result).as("shouldBlock=true → Deny").isInstanceOf(PermissionResult.Deny.class);

        Map<String, Object> attrs = spy.recordedEvents.get("tengu_auto_mode_decision");
        assertThat(attrs).as("主分类器事件必须已发射").isNotNull();

        // CC 完整字段集（permissions.ts:733-812）
        assertThat(attrs)
            .containsKeys("decision", "toolName", "inProtectedNamespace",
                "classifierModel", "consecutiveDenials", "totalDenials",
                "classifierInputTokens", "classifierOutputTokens",
                "classifierCacheReadInputTokens", "classifierCacheCreationInputTokens",
                "classifierDurationMs",
                "classifierSystemPromptLength", "classifierToolCallsLength",
                "classifierUserPromptsLength",
                "classifierStage",
                "classifierStage1InputTokens", "classifierStage1OutputTokens",
                "classifierStage1CacheReadInputTokens", "classifierStage1CacheCreationInputTokens",
                "classifierStage1DurationMs", "classifierStage1RequestId", "classifierStage1MsgId",
                "classifierStage2InputTokens", "classifierStage2OutputTokens",
                "classifierStage2CacheReadInputTokens", "classifierStage2CacheCreationInputTokens",
                "classifierStage2DurationMs", "classifierStage2RequestId", "classifierStage2MsgId");

        // 关键字段值
        assertThat(attrs.get("toolName")).as("mcp__* → mcp_tool（PII 防护）").isEqualTo("mcp_tool");
        assertThat(attrs.get("classifierModel")).isEqualTo("fake-model");
        assertThat(attrs.get("classifierDurationMs")).isEqualTo(DURATION_MS);
        assertThat(attrs.get("classifierInputTokens")).isEqualTo(100);
        assertThat(attrs.get("classifierOutputTokens")).isEqualTo(200);
        assertThat(attrs.get("classifierCacheReadInputTokens")).isEqualTo(300);
        assertThat(attrs.get("classifierCacheCreationInputTokens")).isEqualTo(400);
        assertThat(attrs.get("classifierSystemPromptLength")).isEqualTo(500L);
        assertThat(attrs.get("classifierToolCallsLength")).isEqualTo(1500L);
        assertThat(attrs.get("classifierUserPromptsLength")).isEqualTo(200L);
        assertThat(attrs.get("classifierStage")).isEqualTo(YoloClassifierResult.STAGE_THINKING);
        assertThat(attrs.get("classifierStage1RequestId")).isEqualTo("req_stage1");
        assertThat(attrs.get("classifierStage1MsgId")).isEqualTo("msg_stage1");
        assertThat(attrs.get("classifierStage2RequestId")).isEqualTo("req_stage2");
        assertThat(attrs.get("classifierStage2MsgId")).isEqualTo("msg_stage2");
        // denial 计数（CC :744-749 shouldBlock → +1 前瞻）
        assertThat(attrs.get("consecutiveDenials")).isEqualTo(1);
        assertThat(attrs.get("totalDenials")).isEqualTo(1);
        // fast-path 字段绝不出现（CC 主事件无 confidence/fastPath）
        assertThat(attrs).doesNotContainKey("fastPath").doesNotContainKey("confidence");

        // addToTurnClassifierDuration（CC :814-816 → state.ts:627-630）
        assertThat(stats.getDurationMs(SESSION_ID)).as("回合分类器耗时累计").isEqualTo(DURATION_MS);
        assertThat(stats.getCount(SESSION_ID)).as("回合分类器调用次数").isEqualTo(1);
    }

    // ─────────────────── 2. fast-path（acceptEdits）：仅核心字段 + confidence/fastPath ───────────────────

    @Test
    @DisplayName("acceptEdits fast-path 事件仅核心字段 + confidence=high + fastPath，无 classifier 字段，不累计耗时")
    void acceptEditsFastPath_emitsCoreFields_noClassifierFields_noDurationAccumulation() {
        // WHY: CC :626-640 acceptEdits fast-path 事件只带 decision/toolName/inProtectedNamespace/
        //   agentMsgId/confidence='high'/fastPath；无 consecutiveDenials 等 classifier 字段；
        //   且 fast-path 不触发 :814-816 addToTurnClassifierDuration（仅主分类器路径累计）。
        JsonNode input = JSON.createObjectNode().put("command", "edit file");
        AcceptEditsAllowTool tool = new AcceptEditsAllowTool("mcp__github__create_issue", input);

        PermissionResult result = pipeline.check(
            tool, newCall(CALL_ID, tool.name(), input), input, ctx,
            ToolPermissionContext.strict(PermissionMode.AUTO));

        assertThat(result).as("acceptEdits fast-path → Allow").isInstanceOf(PermissionResult.Allow.class);

        Map<String, Object> attrs = spy.recordedEvents.get("tengu_auto_mode_decision");
        assertThat(attrs).as("fast-path 事件必须已发射").isNotNull();
        assertThat(attrs)
            .as("CC :626-640 核心字段")
            .containsKeys("decision", "toolName", "inProtectedNamespace", "confidence", "fastPath");
        assertThat(attrs.get("toolName")).isEqualTo("mcp_tool");
        assertThat(attrs.get("decision")).isEqualTo("allowed");
        assertThat(attrs.get("confidence")).isEqualTo("high");
        assertThat(attrs.get("fastPath")).isEqualTo("acceptEdits");
        // 无 classifier 字段（CC fast-path 事件不携带）
        assertThat(attrs).doesNotContainKey("classifierModel").doesNotContainKey("classifierDurationMs");

        // fast-path 不累计回合耗时（CC :814-816 仅主分类器路径）
        assertThat(stats.getDurationMs(SESSION_ID)).as("fast-path 不累计耗时").isZero();
        assertThat(stats.getCount(SESSION_ID)).as("fast-path 不计次数").isZero();
    }

    // ─────────────────── 3. 回合分桶隔离（多会话不互串）───────────────────

    @Test
    @DisplayName("TurnClassifierStats 按 sessionId 分桶：另一会话不污染")
    void turnClassifierStats_isolatesPerSession() {
        // WHY: CC STATE 是单进程全局（CLI 单会话）；web 后端多会话并发必须按 sessionId 分桶，
        //   否则一个会话的分类器耗时会污染另一会话的回合统计。
        fakeClassifier.queueResult(richClassifierResult());
        JsonNode input = JSON.createObjectNode().put("command", "git push");
        AskTool tool = new AskTool("mcp__github__create_issue", input);
        String otherSession = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        pipeline.check(tool, newCall(CALL_ID, tool.name(), input), input, ctx,
            ToolPermissionContext.strict(PermissionMode.AUTO));

        assertThat(stats.getDurationMs(SESSION_ID)).isEqualTo(DURATION_MS);
        assertThat(stats.getDurationMs(otherSession)).as("其它会话累计为 0").isZero();
        // reset 语义（CC resetTurnClassifierDuration）
        stats.reset(SESSION_ID);
        assertThat(stats.getDurationMs(SESSION_ID)).isZero();
        assertThat(stats.getCount(SESSION_ID)).isZero();
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
            return "telemetry stub";
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

    /** ACCEPT_EDITS 模式返回 Allow 的工具（DEFAULT 模式 Ask）→ 触发 acceptEdits fast-path。 */
    private static final class AcceptEditsAllowTool extends AskTool {
        AcceptEditsAllowTool(String name, JsonNode input) {
            super(name, input);
        }

        @Override
        public PermissionResult checkPermissions(JsonNode in, ToolUseContext ctx) {
            if (ctx != null && ctx.permissionMode() == PermissionMode.ACCEPT_EDITS) {
                return new PermissionResult.Allow(
                    in, new PermissionDecisionReason.Mode(PermissionMode.ACCEPT_EDITS),
                    null, false, null, null);
            }
            return new PermissionResult.Ask(
                "need permission", new PermissionDecisionReason.Other("ask"), List.of(),
                null, null, null, false, null, List.of());
        }
    }

    /** Fake YoloClassifier：不调 LLM，行为可控。 */
    private static final class FakeYoloClassifier implements YoloClassifier {
        final java.util.ArrayDeque<YoloClassifierResult> queue = new java.util.ArrayDeque<>();

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
            return CompletableFuture.completedFuture(queue.isEmpty()
                ? YoloClassifierResult.allowed("default allow", "fake-model")
                : queue.poll());
        }

        @Override
        public CompletableFuture<YoloClassifierResult> classifyTextAction(
                String userText, List<ChatMessageDto> transcript, ToolUseContext ctx) {
            return CompletableFuture.completedFuture(YoloClassifierResult.allowed(
                "fake-text-action-not-used", "fake-model"));
        }
    }

    /** Spy Telemetry · 双发 recordEvent + logOTelEvent 写入内存 Map 验证调用。 */
    static final class SpyTelemetry extends Telemetry {
        final Map<String, Map<String, Object>> recordedEvents = new ConcurrentHashMap<>();
        final Map<String, Map<String, Object>> otelEvents = new ConcurrentHashMap<>();

        @Override
        public void recordEvent(String eventName, Map<String, Object> metadata) {
            recordedEvents.put(eventName, new java.util.HashMap<>(metadata));
        }

        @Override
        public void logOTelEvent(String eventName, Map<String, ?> metadata) {
            otelEvents.put(eventName, new java.util.HashMap<>(metadata));
        }

        @Override
        public void logOTelEvent(String eventName) {
            otelEvents.put(eventName, Map.of());
        }
    }
}
