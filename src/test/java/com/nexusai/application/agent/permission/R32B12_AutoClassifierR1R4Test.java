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
import com.nexusai.model.session.dto.Role;
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
 * [S12] auto 分类器链 R1-R4 定向测试 · 对齐 CC permissions.ts:486-591/:658-927
 * （探查 T07 F1/F2/F3/F5/F6；EV-011/EV-020/EV-034/EV-036/EV-037/EV-039/EV-044）。
 *
 * <p><b>覆盖清单</b>：
 * <ol>
 *   <li><b>R1 入口门</b>：非 auto/plan+active 的 Ask 不进分类器（DEFAULT / PLAN 非 active）；
 *       plan+active 与 AUTO 进分类器</li>
 *   <li><b>R1 豁免</b>：1e requiresUserInteraction、1g safetyCheck 非 classifierApprovable、
 *       PowerShell 守卫 —— Ask 不被自动放行（CC permissions.ts:532-591）</li>
 *   <li><b>R1 allowlist</b>：安全工具 allow + decisionReason Mode(auto)（CC :658-686/:678-685）</li>
 *   <li><b>R2 全量消息</b>：生产调用传全量 messages（替换 List.of() 空转录）</li>
 *   <li><b>R3 fail-closed</b>：解析失败 → deny；abort/API error/异常 → iron-gate deny；
 *       transcriptTooLong → 回退 ask（CC :818-876）</li>
 *   <li><b>R4 熔断恢复</b>：连续 3 次拒绝 → 超限回退 ask + CIRCUIT_BROKEN；任意 allow →
 *       recordSuccess 恢复；total 上限 → 双计数清零（CC denialTracking.ts + permissions.ts:984-1058）</li>
 * </ol>
 *
 * <p>构造方式与 ClassifierModeRetryHookTest 相同：直接注入 4 个 classifier 依赖
 * （package-private 字段），真实 10 层 + 可控 fake classifier。
 */
class R32B12_AutoClassifierR1R4Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_CUSTOM = "CustomBash";
    private static final String TOOL_POWERSHELL = "PowerShell";
    private static final String CALL_ID = "call_r1r4_001";

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
        // infra.util.AutoModeState 为模块级静态（CC autoModeState.ts 单例），防跨用例污染
        com.nexusai.infra.util.AutoModeState.resetForTesting();
        // O52 已删旧清空方法（测试辅助，S13）；会话重启语义由 resetForTesting 承载
    }
    // ─────────────────── R1 入口门 ───────────────────

    @Test
    @DisplayName("R1: DEFAULT 模式 Ask 不进分类器（原 PermissionPipeline.java:153 无 mode 检查）")
    void defaultMode_askNotClassified() {
        PermissionResult result = check(permCtx(PermissionMode.DEFAULT), tool(TOOL_CUSTOM, false));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(fakeClassifier.classifyCallCount)
            .as("非 auto/plan+active 模式不得咨询分类器（CC permissions.ts:520-525）")
            .isZero();
    }

    @Test
    @DisplayName("R1: PLAN 且 auto 未 active → 不进分类器")
    void planWithoutActive_askNotClassified() {
        com.nexusai.infra.util.AutoModeState.setAutoModeActive(false);

        PermissionResult result = check(permCtx(PermissionMode.PLAN), tool(TOOL_CUSTOM, false));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(fakeClassifier.classifyCallCount).isZero();
    }

    @Test
    @DisplayName("R1: PLAN + auto active → 进分类器（CC mode==='plan' && isAutoModeActive()）")
    void planWithActive_classified() {
        com.nexusai.infra.util.AutoModeState.setAutoModeActive(true);
        fakeClassifier.queueResult(YoloClassifierResult.allowed(
            "plan allow", "fake-model"));

        PermissionResult result = check(permCtx(PermissionMode.PLAN), tool(TOOL_CUSTOM, false));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(fakeClassifier.classifyCallCount).isEqualTo(1);
    }

    @Test
    @DisplayName("R1: AUTO 模式进分类器")
    void autoMode_classified() {
        fakeClassifier.queueResult(YoloClassifierResult.allowed(
            "auto allow", "fake-model"));

        PermissionResult result = check(permCtx(PermissionMode.AUTO), tool(TOOL_CUSTOM, false));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(fakeClassifier.classifyCallCount).isEqualTo(1);
    }

    // ─────────────────── R1 豁免（AUTO 模式） ───────────────────

    @Test
    @DisplayName("R1 豁免: requiresUserInteraction 工具 → 保留 ask（CC permissions.ts:549-551）")
    void requiresUserInteraction_preserved() {
        PermissionResult result = check(permCtx(PermissionMode.AUTO),
            tool(TOOL_CUSTOM, false, true, null));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(fakeClassifier.classifyCallCount)
            .as("requiresUserInteraction 的 Ask 不得被自动放行")
            .isZero();
    }

    @Test
    @DisplayName("R1 豁免: 1g safetyCheck 非 classifierApprovable → 免疫自动放行（CC :532-548）")
    void safetyCheckNonApprovable_immune() throws Exception {
        // Java 架构现状：工具层 Ask 不通过 1c（CheckLayer1c_ToolCheck 只放行 Allow），
        //   1g 层恒产 classifierApprovable=true，WritePermissionChecker 的 false 面
        //   到不了 auto 块 —— 免疫分支用反射直调 tryAutoModeDecision 验证
        //   （CC 语义权限面见 concerns 登记）。
        PermissionResult.Ask nonApprovable = new PermissionResult.Ask(
            "sensitive path", new PermissionDecisionReason.SafetyCheck("~/.ssh", false),
            List.of(), null, null, null, false, null, null);
        java.lang.reflect.Method m = PermissionPipeline.class.getDeclaredMethod(
            "tryAutoModeDecision", PermissionResult.class, Tool.class, JsonNode.class,
            ToolUseContext.class, ToolPermissionContext.class);
        m.setAccessible(true);
        JsonNode input = JSON.createObjectNode().put("command", "ls -la");

        Object out = m.invoke(pipeline, nonApprovable, tool(TOOL_CUSTOM, false), input,
            ctx, permCtx(PermissionMode.AUTO));

        assertThat(out)
            .as("safetyCheck 非 classifierApprovable 必须原样保留（免疫全部自动放行路径）")
            .isSameAs(nonApprovable);
        assertThat(fakeClassifier.classifyCallCount)
            .as("免疫分支不得咨询分类器")
            .isZero();
    }

    @Test
    @DisplayName("R1 豁免: 1g safetyCheck classifierApprovable=true → 交分类器（CC :529-531）")
    void safetyCheckApprovable_classified() {
        // 真实 1g 层路径：write_file + 敏感路径 .ssh/ → Ask(SafetyCheck(SSH, true))
        //   → 非免疫 → allowlist 未命中（write_file 不在白名单）→ 分类器
        fakeClassifier.queueResult(YoloClassifierResult.allowed(
            "safety allow", "fake-model"));

        JsonNode input = JSON.createObjectNode().put("file_path", ".ssh/config");
        PermissionResult result = pipeline.check(
            tool("Write", false),
            new ToolUseBlock(CALL_ID, "Write", input), input, ctx,
            permCtx(PermissionMode.AUTO));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(fakeClassifier.classifyCallCount).isEqualTo(1);
    }

    @Test
    @DisplayName("R1 豁免: PowerShell 工具 → 跳过分类器保留 ask（CC :572-591，POWERSHELL_AUTO_MODE 默认 off）")
    void powerShell_preserved() {
        PermissionResult result = check(permCtx(PermissionMode.AUTO), tool(TOOL_POWERSHELL, false));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(fakeClassifier.classifyCallCount)
            .as("PowerShell 决策不得被自动放行（S05 交付语义供守卫消费）")
            .isZero();
    }

    @Test
    @DisplayName("R1 allowlist: 安全工具直接 allow + decisionReason Mode(auto)（CC :658-686/:678-685）")
    void safeTool_allowlisted() {
        PermissionResult result = check(permCtx(PermissionMode.AUTO), tool("Read", false));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        PermissionResult.Allow allow = (PermissionResult.Allow) result;
        assertThat(allow.reason())
            .as("allowlist allow 的 decisionReason = {type:'mode', mode:'auto'}（CC :681-684）")
            .isEqualTo(new PermissionDecisionReason.Mode(PermissionMode.AUTO));
        assertThat(fakeClassifier.classifyCallCount)
            .as("allowlist fast path 不调分类器")
            .isZero();
    }

    // ─────────────────── R2 全量消息 ───────────────────

    @Test
    @DisplayName("R2: 生产调用传全量 messages（替换 List.of() 空转录，CC permissions.ts:694-699）")
    void fullMessages_passedToClassifier() {
        ChatMessageDto userMsg = new ChatMessageDto(
            "u1", "s1", Role.user, "user", "请帮我看看这个项目", null,
            null, null, null, null, null, null, null, null, null, List.of(), List.of());
        ChatMessageDto asstMsg = new ChatMessageDto(
            "a1", "s1", Role.assistant, "assistant", "assistant 文本（防注入应排除）", null,
            List.of(), null, null, null, null, null, null, null, null, List.of(), List.of());
        ctx = new ToolUseContext(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", null, List.of(userMsg, asstMsg),
            null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null);
        fakeClassifier.queueResult(YoloClassifierResult.allowed(
            "allow", "fake-model"));

        check(permCtx(PermissionMode.AUTO), tool(TOOL_CUSTOM, false));

        assertThat(fakeClassifier.lastTranscript)
            .as("分类器必须收到 ctx 全量 messages（CC context.messages）")
            .hasSize(2)
            .containsExactly(userMsg, asstMsg);
    }

    // ─────────────────── R3 fail-closed ───────────────────

    @Test
    @DisplayName("R3: classifier unavailable → iron-gate deny（fail-closed，CC :843-876）")
    void classifierUnavailable_ironGateDeny() {
        fakeClassifier.queueResult(YoloClassifierResult.unavailable(
            "Classifier unavailable - blocking for safety", "fake-model", 1));

        PermissionResult result = check(permCtx(PermissionMode.AUTO), tool(TOOL_CUSTOM, false));

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        PermissionResult.Deny deny = (PermissionResult.Deny) result;
        assertThat(deny.message())
            .as("iron-gate deny 消息 = buildClassifierUnavailableMessage（CC messages.ts:288-298）")
            .contains("is temporarily unavailable");
        assertThat(deny.reason()).isInstanceOf(PermissionDecisionReason.Classifier.class);
        // unavailable 不记录 denial（CC :843-876 不调 recordDenial）
        assertThat(denialTracker.getConsecutiveDenials()).isZero();
    }

    @Test
    @DisplayName("R3: classifier 调用异常 → iron-gate deny（fail-closed 兜底）")
    void classifierException_ironGateDeny() {
        fakeClassifier.throwOnClassify = new RuntimeException("LLM provider down");

        PermissionResult result = check(permCtx(PermissionMode.AUTO), tool(TOOL_CUSTOM, false));

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        PermissionResult.Deny deny = (PermissionResult.Deny) result;
        assertThat(deny.message())
            .as("异常兜底消息含 The auto mode classifier 占位")
            .contains("The auto mode classifier is temporarily unavailable");
        assertThat(denialTracker.getConsecutiveDenials()).isZero();
    }

    @Test
    @DisplayName("R3: 解析失败（普通 deny）→ 拒绝消息 + recordDenial（CC :878-911）")
    void classifierDeny_rejectionMessageAndRecordDenial() {
        fakeClassifier.queueResult(YoloClassifierResult.blocked(
            "Invalid classifier response - blocking for safety", "fake-model", 1));

        PermissionResult result = check(permCtx(PermissionMode.AUTO), tool(TOOL_CUSTOM, false));

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        PermissionResult.Deny deny = (PermissionResult.Deny) result;
        assertThat(deny.message())
            .as("deny 消息 = buildYoloRejectionMessage（CC messages.ts:267-282）")
            .startsWith("Permission for this action has been denied. Reason: Invalid classifier response - blocking for safety");
        PermissionDecisionReason.Classifier reason =
            (PermissionDecisionReason.Classifier) deny.reason();
        assertThat(reason.classifier())
            .as("decisionReason.classifier 必须 == 'auto-mode'（retry hook 触发条件，toolExecution.ts:1078）")
            .isEqualTo("auto-mode");
        assertThat(reason.reason())
            .as("decisionReason.reason = classifierResult.reason（CC :907-909）")
            .isEqualTo("Invalid classifier response - blocking for safety");
        assertThat(denialTracker.getConsecutiveDenials())
            .as("普通 block 必须 recordDenial")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("R3: transcriptTooLong → 回退原 ask（CC :818-842，不 deny）")
    void transcriptTooLong_fallbackToPrompting() {
        fakeClassifier.queueResult(new YoloClassifierResult(
            null, true, "Context overflow risk", false, true, "none",
            null, 0L, null, null, 1,
            null, null, null, null, null, null, null, null));

        PermissionResult result = check(permCtx(PermissionMode.AUTO), tool(TOOL_CUSTOM, false));

        assertThat(result)
            .as("transcriptTooLong → 回退 prompting（保留原 Ask）")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(denialTracker.getConsecutiveDenials()).isZero();
    }

    // ─────────────────── R4 熔断恢复 ───────────────────

    @Test
    @DisplayName("R4: 连续 3 次拒绝 → 第 3 次超限回退 ask + CIRCUIT_BROKEN（CC :984-1058）")
    void threeConsecutiveDenials_thirdFallsBackToAsk() {
        for (int i = 0; i < 3; i++) {
            fakeClassifier.queueResult(YoloClassifierResult.blocked(
                "blocked " + i, "fake-model", 1));
        }
        PermissionResult r1 = check(permCtx(PermissionMode.AUTO), tool(TOOL_CUSTOM, false));
        PermissionResult r2 = check(permCtx(PermissionMode.AUTO), tool(TOOL_CUSTOM, false));
        PermissionResult r3 = check(permCtx(PermissionMode.AUTO), tool(TOOL_CUSTOM, false));

        assertThat(r1).isInstanceOf(PermissionResult.Deny.class);
        assertThat(r2).isInstanceOf(PermissionResult.Deny.class);
        assertThat(r3)
            .as("第 3 次连续拒绝触发超限 → 回退 prompting（ask 而非 deny，CC handleDenialLimitExceeded）")
            .isInstanceOf(PermissionResult.Ask.class);
        PermissionResult.Ask ask3 = (PermissionResult.Ask) r3;
        assertThat(ask3.reason())
            .as("回退 ask 的 decisionReason 携带 warning + 分类器理由（CC permissions.ts:1050-1057）")
            .isInstanceOf(PermissionDecisionReason.Classifier.class);
        assertThat(((PermissionDecisionReason.Classifier) ask3.reason()).reason())
            .contains("3 consecutive actions were blocked")
            .contains("Latest blocked action: blocked 2");
        assertThat(denialTracker.shouldFallbackToPrompting())
            .as("连续熔断 → 派生查询 fallback=true（回退 prompting 门控，CC denialTracking.ts:40-45）")
            .isTrue();
    }

    @Test
    @DisplayName("R4: CIRCUIT_BROKEN 后任意 allow 事件恢复分类器（CC permissions.ts:486-499）")
    void allowEvent_recoversCircuit() {
        // 3 次连续拒绝 → CIRCUIT_BROKEN
        for (int i = 0; i < 3; i++) {
            fakeClassifier.queueResult(YoloClassifierResult.blocked(
                "blocked " + i, "fake-model", 1));
            check(permCtx(PermissionMode.AUTO), tool(TOOL_CUSTOM, false));
        }
        assertThat(denialTracker.shouldFallbackToPrompting()).isTrue();

        // 任意 allow 事件（1c 工具自决 allow）→ recordSuccess → 断连拒链
        PermissionResult allowed = check(permCtx(PermissionMode.AUTO),
            toolWithCheckResult("AllowTool", new PermissionResult.Allow(
                JSON.createObjectNode(), new PermissionDecisionReason.Other("tool allow"),
                null, false, null, List.of())));
        assertThat(allowed).isInstanceOf(PermissionResult.Allow.class);
        assertThat(denialTracker.shouldFallbackToPrompting())
            .as("allow 事件 recordSuccess → 派生查询不再 fallback（熔断恢复）")
            .isFalse();
        assertThat(denialTracker.getConsecutiveDenials()).isZero();

        // 恢复后下一个 Ask 重新进分类器
        fakeClassifier.queueResult(YoloClassifierResult.allowed(
            "recovered", "fake-model"));
        PermissionResult r = check(permCtx(PermissionMode.AUTO), tool(TOOL_CUSTOM, false));
        assertThat(r).isInstanceOf(PermissionResult.Allow.class);
        assertThat(fakeClassifier.classifyCallCount).isEqualTo(4);
    }

    // ─────────────────── DenialTracker 单元（R4） ───────────────────

    @Test
    @DisplayName("R4: recordSuccess 只清 consecutive、total 保留（CC denialTracking.ts:32-38）")
    void recordSuccess_clearsConsecutiveOnly() {
        denialTracker.recordDenial();
        denialTracker.recordDenial();
        assertThat(denialTracker.getConsecutiveDenials()).isEqualTo(2);
        assertThat(denialTracker.getTotalDenials()).isEqualTo(2);

        denialTracker.recordSuccess();

        assertThat(denialTracker.getConsecutiveDenials())
            .as("recordSuccess 只清零 consecutive（CC :36-37）")
            .isZero();
        assertThat(denialTracker.getTotalDenials())
            .as("recordSuccess 不清 total（CC recordSuccess 语义）")
            .isEqualTo(2);
        assertThat(denialTracker.shouldFallbackToPrompting()).isFalse();
    }

    @Test
    @DisplayName("R4: total 达上限 → 双计数清零 + 立即恢复 ACTIVE（CC permissions.ts:1034-1040）")
    void totalLimit_resetsBothCounts() {
        DenialTracker tracker = new DenialTracker(100, 20); // 抬高 consecutive 阈值，单独测 total 路径
        DenialTracker.FallbackSnapshot last = null;
        for (int i = 0; i < 20; i++) {
            last = tracker.recordDenial();
        }
        assertThat(last.fallback())
            .as("第 20 次拒绝（total 达上限）必须触发回退")
            .isTrue();
        assertThat(last.totalDenials())
            .as("回退快照携带清零前 total（warning 文案用，CC :1003-1007）")
            .isEqualTo(20);
        assertThat(tracker.getTotalDenials())
            .as("hitTotalLimit → 双计数清零（CC :1034-1040）")
            .isZero();
        assertThat(tracker.getConsecutiveDenials()).isZero();
        assertThat(tracker.shouldFallbackToPrompting())
            .as("total 清零后派生查询不再 fallback")
            .isFalse();
    }

    // ─────────────────── helpers ───────────────────

    private PermissionResult check(ToolPermissionContext permCtx, Tool tool) {
        JsonNode input = JSON.createObjectNode().put("command", "ls -la");
        return pipeline.check(tool, new ToolUseBlock(CALL_ID, tool.name(), input), input, ctx, permCtx);
    }

    private static ToolPermissionContext permCtx(PermissionMode mode) {
        return ToolPermissionContext.strict(mode);
    }

    private Tool tool(String name, boolean mcp) {
        return tool(name, mcp, false, null);
    }

    private Tool tool(String name, boolean mcp, boolean requiresUserInteraction,
                      PermissionResult customCheck) {
        JsonNode schema = JSON.createObjectNode();
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub " + name; }
            @Override public JsonNode inputSchema() { return schema; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "ok");
            }
            @Override public boolean isMcp() { return mcp; }
            @Override public boolean requiresUserInteraction() { return requiresUserInteraction; }
            @Override public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
                if (customCheck != null) {
                    return customCheck;
                }
                return new PermissionResult.Passthrough("stub passthrough", null, List.of(), null, null);
            }
        };
    }

    /** 工具直接返回指定 checkPermissions 结果（1c 层短路）。 */
    private Tool toolWithCheckResult(String name, PermissionResult checkResult) {
        return tool(name, false, false, checkResult);
    }

    /** Fake YoloClassifier: 不调 LLM，行为可控。 */
    private static class FakeYoloClassifier implements YoloClassifier {
        final java.util.ArrayDeque<YoloClassifierResult> queue = new java.util.ArrayDeque<>();
        volatile RuntimeException throwOnClassify;
        volatile int classifyCallCount = 0;
        volatile List<ChatMessageDto> lastTranscript;

        void queueResult(YoloClassifierResult r) {
            queue.add(r);
        }

        @Override
        public CompletableFuture<YoloClassifierResult> classify(
                String toolName, JsonNode input, List<ChatMessageDto> transcript, ToolUseContext ctx
        ) {
            classifyCallCount++;
            lastTranscript = transcript;
            if (throwOnClassify != null) {
                CompletableFuture<YoloClassifierResult> f = new CompletableFuture<>();
                f.completeExceptionally(throwOnClassify);
                return f;
            }
            YoloClassifierResult r = queue.poll();
            if (r == null) {
                r = YoloClassifierResult.allowed("queue empty fallback", "fake-model");
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

        @Override
        public boolean isAvailable() { return true; }
    }
}
