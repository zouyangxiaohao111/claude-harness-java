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
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-9] DenialTracker per-agent 隔离 · 对齐 CC {@code localDenialTracking}
 * （Open-ClaudeCode/src/utils/permissions/permissions.ts:556-558 +
 * forkedAgent.ts:420-422 + denialTracking.ts）。
 *
 * <p><b>CC 语义（grep 自验，2026-08-18）</b>：
 * <ul>
 *   <li>permissions.ts:556-558 {@code context.localDenialTracking ?? appState.denialTracking
 *       ?? createDenialTrackingState()} —— 子代理本地态优先，主 agent 回落全局 appState</li>
 *   <li>forkedAgent.ts:420-422 非 share 子代理 {@code localDenialTracking = createDenialTrackingState()}
 *       —— 每个 async 子代理独立计数</li>
 *   <li>denialTracking.ts:24-38 recordDenial 双计数 / recordSuccess 只清 consecutive</li>
 * </ul>
 *
 * <p><b>WHY（OPD-WF3-01-14 拍板：引入隔离）</b>：旧实现统一全局 bean 计数，并发子代理的
 * 拒绝互相污染、可能误触发 fallback（WF3-01 探查风险）。本测试钉死"子代理拒绝不得污染
 * 全局 bean；主 agent（无 localDenialTracking）仍回落全局 bean"。
 */
class DenialTrackerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_CUSTOM = "CustomBash";
    private static final String CALL_ID = "call_imp9_001";

    private PermissionPipeline pipeline;
    private FakeYoloClassifier fakeClassifier;
    private DenialTracker globalDenialTracker;
    private JsonNode input;

    @BeforeEach
    void setUp() {
        pipeline = new PermissionPipeline();
        fakeClassifier = new FakeYoloClassifier();
        pipeline.autoModeGate = new AutoModeGate(true);
        pipeline.safeToolWhitelist = new SafeToolWhitelist();
        globalDenialTracker = new DenialTracker(3, 20);
        pipeline.denialTracker = globalDenialTracker;
        pipeline.yoloClassifier = fakeClassifier;
        input = JSON.createObjectNode().put("command", "ls -la");
    }

    @AfterEach
    void tearDown() {
        com.nexusai.infra.util.AutoModeState.resetForTesting();
    }

    // ─────────────────── per-agent 隔离（OPD-WF3-01-14） ───────────────────

    @Test
    @DisplayName("子代理 localDenialTracking → per-agent 独立计数，不污染全局 bean（CC permissions.ts:556-558）")
    void subagentLocalDenialTracking_isolatedFromGlobalBean() {
        // 子代理 ctx 携带 per-agent localDenialTracking（CC :420-422 非 share 新建独立状态）
        ToolUseContext subCtx = ctxWithLocalDenialTracking(new HashMap<>());
        fakeClassifier.queueResult(YoloClassifierResult.blocked("blocked subagent", "fake-model", 1));

        PermissionResult r = check(subCtx, PermissionMode.AUTO);

        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
        assertThat(globalDenialTracker.getConsecutiveDenials())
            .as("子代理拒绝必须隔离在 localDenialTracking（per-agent），不得污染全局 bean（appState 等价）")
            .isZero();
        assertThat(globalDenialTracker.getTotalDenials())
            .as("全局 total 同样不得被子代理污染")
            .isZero();
    }

    @Test
    @DisplayName("主 agent（localDenialTracking=null）→ 回落全局 bean 计数（appState.denialTracking 等价）")
    void mainAgentNoLocalState_usesGlobalBean() {
        ToolUseContext mainCtx = ctxWithLocalDenialTracking(null);
        fakeClassifier.queueResult(YoloClassifierResult.blocked("blocked main", "fake-model", 1));

        PermissionResult r = check(mainCtx, PermissionMode.AUTO);

        assertThat(r).isInstanceOf(PermissionResult.Deny.class);
        assertThat(globalDenialTracker.getConsecutiveDenials())
            .as("主 agent 无 localDenialTracking → 回落全局 bean（CC appState.denialTracking 语义）")
            .isEqualTo(1);
    }

    // ─────────────────── forLocalState 单元（per-agent 计数载体） ───────────────────

    @Test
    @DisplayName("forLocalState 绑定可变 Map → recordDenial/recordSuccess 就地写回（CC persistDenialState Object.assign）")
    void forLocalState_writesBackToMutableMap() {
        Map<String, Object> local = new HashMap<>();
        DenialTracker tracker = DenialTracker.forLocalState(local);

        tracker.recordDenial();
        tracker.recordDenial();

        assertThat(local.get("consecutiveDenials"))
            .as("recordDenial 双计数就地写回 localDenialTracking（CC permissions.ts:967-968 Object.assign）")
            .isEqualTo(2);
        assertThat(local.get("totalDenials")).isEqualTo(2);

        tracker.recordSuccess();

        assertThat(local.get("consecutiveDenials"))
            .as("recordSuccess 只清 consecutive 并就地写回（CC denialTracking.ts:32-38）")
            .isEqualTo(0);
        assertThat(local.get("totalDenials"))
            .as("recordSuccess 不清 total")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("forLocalState 从既有 Map 恢复计数（CC 三态解析 permissions.ts:556-558）")
    void forLocalState_seedsFromExistingMap() {
        Map<String, Object> local = new HashMap<>();
        local.put("consecutiveDenials", 2);
        local.put("totalDenials", 5);

        DenialTracker tracker = DenialTracker.forLocalState(local);

        assertThat(tracker.getConsecutiveDenials()).isEqualTo(2);
        assertThat(tracker.getTotalDenials()).isEqualTo(5);
        assertThat(tracker.shouldFallbackToPrompting()).isFalse();
    }

    @Test
    @DisplayName("forLocalState 绑定不可变 Map（Map.of）→ recordDenial 不抛异常，计数留在实例（隔离不依赖写回）")
    void forLocalState_immutableMap_noThrow() {
        DenialTracker tracker = DenialTracker.forLocalState(Map.of());

        DenialTracker.FallbackSnapshot snapshot = tracker.recordDenial();

        assertThat(snapshot.fallback()).isFalse();
        assertThat(tracker.getConsecutiveDenials()).isEqualTo(1);
        assertThat(tracker.getTotalDenials()).isEqualTo(1);
    }

    @Test
    @DisplayName("per-agent 与全局实例互不影响（子代理计数不读不写全局 bean）")
    void perAgentAndGlobal_fullyIsolated() {
        DenialTracker global = new DenialTracker(3, 20);
        DenialTracker agentA = DenialTracker.forLocalState(new HashMap<>());
        DenialTracker agentB = DenialTracker.forLocalState(new HashMap<>());

        agentA.recordDenial();
        agentA.recordDenial();

        assertThat(agentA.getConsecutiveDenials()).isEqualTo(2);
        assertThat(agentB.getConsecutiveDenials())
            .as("另一个子代理的计数不受 agentA 影响（forkedAgent.ts:420-422 每子代理独立 createDenialTrackingState）")
            .isZero();
        assertThat(global.getConsecutiveDenials())
            .as("全局 bean 不受任一子代理影响")
            .isZero();

        global.recordDenial();
        assertThat(agentA.getConsecutiveDenials())
            .as("全局拒绝也不回灌子代理本地态")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("per-agent 独立累计到熔断阈值（子代理内连续拒绝触发 fallback，CC :556-558 本地态承载）")
    void perAgent_accumulatesToFallback_independent() {
        DenialTracker agent = DenialTracker.forLocalState(new HashMap<>());

        agent.recordDenial();
        agent.recordDenial();
        assertThat(agent.shouldFallbackToPrompting()).isFalse();

        agent.recordDenial();
        assertThat(agent.shouldFallbackToPrompting())
            .as("连续 3 次拒绝 → per-agent 本地态派生 fallback（CC denialTracking.ts:40-45）")
            .isTrue();
        // 全局 bean 完全不受影响
        assertThat(globalDenialTracker.shouldFallbackToPrompting()).isFalse();
    }

    // ─────────────────── ToolCheckCache 生产接入（OPD-WF3-DC-v4-02） ───────────────────

    @Test
    @DisplayName("PermissionPipeline.check 入口 clear ToolCheckCache → 上一 call 陈旧项不残留（per-call isolation）")
    void checkEntry_clearsToolCheckCache() {
        // 预置一个"上一 call"的陈旧缓存项（?-DC-2 跨调用残留场景；工具名不同，1c 不会覆盖它）
        ToolCheckCache.put("SomeStaleTool",
            new PermissionResult.Passthrough("stale", null, List.of(), null, null));
        assertThat(ToolCheckCache.get("SomeStaleTool")).as("预置陈旧项成功").isNotNull();

        // 任意一次 pipeline.check（CustomBash，1c 只 put 自己工具名的条目）
        check(ctxWithLocalDenialTracking(null), PermissionMode.DEFAULT);

        assertThat(ToolCheckCache.get("SomeStaleTool"))
            .as("check 入口 ToolCheckCache.clear() → 上一 call 的陈旧条目被清空（对齐 Javadoc per-call 语义）")
            .isNull();
    }

    // ─────────────────── helpers ───────────────────

    private PermissionResult check(ToolUseContext ctx, PermissionMode mode) {
        return pipeline.check(tool(TOOL_CUSTOM),
            new ToolUseBlock(CALL_ID, TOOL_CUSTOM, input), input, ctx, permCtx(mode));
    }

    private static ToolPermissionContext permCtx(PermissionMode mode) {
        return ToolPermissionContext.strict(mode);
    }

    /** 构造带指定 localDenialTracking 的 ToolUseContext（45 参构造 · 其余字段默认）。 */
    private ToolUseContext ctxWithLocalDenialTracking(Map<String, Object> localDenialTracking) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            false, null, null, null, null, null, false, false,
            localDenialTracking, null, null, null, null, null);
    }

    private Tool tool(String name) {
        JsonNode schema = JSON.createObjectNode();
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
            @Override public boolean isMcp() { return false; }
            @Override public boolean requiresUserInteraction() { return false; }
            @Override public PermissionResult checkPermissions(JsonNode in, ToolUseContext ctx) {
                return new PermissionResult.Passthrough("stub passthrough", null, List.of(), null, null);
            }
        };
    }

    /** Fake YoloClassifier: 不调 LLM，行为可控。 */
    static class FakeYoloClassifier implements YoloClassifier {
        final java.util.ArrayDeque<YoloClassifierResult> queue = new java.util.ArrayDeque<>();

        void queueResult(YoloClassifierResult r) {
            queue.add(r);
        }

        @Override
        public CompletableFuture<YoloClassifierResult> classify(
                String toolName, JsonNode input, List<ChatMessageDto> transcript, ToolUseContext ctx) {
            YoloClassifierResult r = queue.poll();
            if (r == null) {
                r = YoloClassifierResult.allowed("queue empty fallback", "fake-model");
            }
            return CompletableFuture.completedFuture(r);
        }

        @Override
        public CompletableFuture<YoloClassifierResult> classifyTextAction(
                String userText, List<ChatMessageDto> transcript, ToolUseContext ctx) {
            return CompletableFuture.completedFuture(YoloClassifierResult.allowed(
                "fake-text-action-not-used", "fake-model"));
        }

        @Override
        public boolean isAvailable() { return true; }
    }
}
