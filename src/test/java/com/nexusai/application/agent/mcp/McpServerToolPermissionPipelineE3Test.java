package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.classifier.DenialTracker;
import com.nexusai.application.agent.permission.classifier.YoloClassifier;
import com.nexusai.application.agent.permission.classifier.YoloClassifierResult;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MCP-SEC-03] E3 集成测试：mcp__ 工具权限决策三场景（真实 {@link McpServerTool}
 * + 真实 {@link PermissionPipeline} 10 层管线）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: 单元测试
 * {@link McpServerToolCheckPermissionsTest} 只锁「checkPermissions 返 Passthrough」，
 * 未证明「管线级决策」——即 Passthrough 经 1c → 1d..2b → 第 3 层转 Ask（而非 Allow）。
 * 本测试锁三个管线级场景，编码安全底线：
 * <ol>
 *   <li><b>无规则 + DEFAULT → Ask</b>：mcp__ 工具不得被 1c 默认 Allow 短路静默放行
 *       （CC permissions.ts:1299-1318 第 3 层 passthrough→ask 兜底）。修复前
 *       {@code McpServerTool} 继承 {@code Tool.java:287-295} 默认 Allow → 1c 短路 →
 *       管线返 Allow，本测试会 FAIL（RED）。</li>
 *   <li><b>deny 规则 mcp__server / mcp__server__tool → Deny</b>：回归 1a 前置——deny
 *       规则（含 MCP whole-server / whole-tool 前缀匹配 {@link RuleQuery#toolMatchesRule}）
 *       在 Passthrough override 后仍先于 1c 命中。</li>
 *   <li><b>auto-mode → 进分类器</b>：AUTO 模式 mcp__ 工具 Ask 经
 *       {@code tryAutoModeDecision} 咨询分类器，分类器 allow → Classifier 归因 Allow；
 *       分类器未接线 → 保持 Ask（fail-closed，绝不静默 Allow）。</li>
 * </ol>
 *
 * <p>包选择：本测试置于 {@code com.nexusai.application.agent.mcp}（非 permission 包），
 * 以便直接构造 package-private 的真实 {@link McpServerTool}（MCP-SEC-02 修复落点），
 * 避免用桩工具替代真实 mcp__ 工具（桩无法复现修复前「默认 Allow → 1c 短路」的 RED 行为）。
 * auto-mode 分类器依赖经反射注入（与 H14V3 的 {@code inject} 模式一致，字段为
 * package-private {@code @Autowired(required=false)} 无 setter）。
 */
@DisplayName("[MCP-SEC-03] mcp__ 工具权限决策三场景（真实 McpServerTool + PermissionPipeline）")
class McpServerToolPermissionPipelineE3Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SERVER = "filesystem";
    private static final String TOOL = "read_file";
    private static final String MCP_FULL_NAME = "mcp__filesystem__read_file";

    @AfterEach
    void tearDown() {
        // 1c 层把 tool.checkPermissions 结果写入 ThreadLocal cache，防跨用例污染
        ToolCheckCache.clear();
    }

    // ────────────────────────────────────────────────────────────────────
    // 真实 mcp__ 工具 + 真实管线
    // ────────────────────────────────────────────────────────────────────

    /** 真实 McpServerTool（MCP-SEC-02 checkPermissions → Passthrough 落点）。 */
    private static McpServerTool realMcpTool() {
        return new McpServerTool(SERVER, TOOL, MCP_FULL_NAME, MAPPER.createObjectNode(), null, null, "read a file", null, new McpToolPool(new McpTransportFactory(), new ToolRegistry(), new JsonRpcMcpClient()));
    }

    private static JsonNode readInput() {
        return MAPPER.createObjectNode().put("file_path", "/tmp/notes.txt");
    }

    private static ToolUseContext ctx(ToolPermissionContext permCtx) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), permCtx.mode(),
            List.of(), "", AbortController.NOOP, List.of(), permCtx, permCtx.mode());
    }

    private static ToolPermissionContext noRules(PermissionMode mode) {
        return ToolPermissionContext.of(mode, Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static ToolPermissionContext denyRules(PermissionMode mode, String... denyToolNames) {
        Map<PermissionRuleSource, Set<PermissionRule>> deny =
            new EnumMap<>(PermissionRuleSource.class);
        Set<PermissionRule> rules = new java.util.LinkedHashSet<>();
        for (String name : denyToolNames) {
            rules.add(new PermissionRule(PermissionRuleSource.USER_SETTINGS,
                PermissionBehavior.DENY, PermissionRuleValue.wholeTool(name)));
        }
        deny.put(PermissionRuleSource.USER_SETTINGS, rules);
        return ToolPermissionContext.of(mode, Map.of(), deny, Map.of(), Map.of());
    }

    private PermissionResult check(PermissionPipeline pipeline, Tool tool,
                                   JsonNode input, ToolPermissionContext permCtx) {
        return pipeline.check(tool,
            new ToolUseBlock(UUID.randomUUID().toString(), tool.name(), input),
            input, ctx(permCtx), permCtx);
    }

    // ────────────────────────────────────────────────────────────────────
    // 场景 1：无规则 + DEFAULT → Ask（非 Allow）—— 核心 RED→GREEN
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("无规则 + DEFAULT mode → Ask（非 Allow；1c 不得默认放行 mcp__）")
    void noRule_defaultMode_returnsAsk_notAllow() {
        PermissionPipeline pipeline = new PermissionPipeline(null);
        McpServerTool tool = realMcpTool();
        ToolPermissionContext permCtx = noRules(PermissionMode.DEFAULT);

        PermissionResult result = check(pipeline, tool, readInput(), permCtx);

        // WHY: mcp__ 工具 checkPermissions→Passthrough（CC MCPTool.ts:56-61 不表态）
        //   → 1c 仅 Allow 短路（Passthrough 返 null）→ 1d..2b 未命中 →
        //   第 3 层 passthrough→ask 兜底（CC permissions.ts:1299-1318）。
        //   修复前 McpServerTool 继承 Tool 默认 Allow → 1c 直接短路 → 管线返 Allow，
        //   本断言即 FAIL（RED）。绝不允许 mcp__ 工具无规则静默执行。
        assertThat(result)
            .as("mcp__ 工具无规则 + DEFAULT 必须 Ask（弹窗），严禁 Allow 静默放行")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(result)
            .as("严禁回退为 Allow（1c 层短路放权升级）")
            .isNotInstanceOf(PermissionResult.Allow.class);
    }

    // ────────────────────────────────────────────────────────────────────
    // 场景 2：deny 规则 mcp__server / mcp__server__tool → Deny（回归 1a 前置）
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deny 规则 mcp__server（whole-server）→ Deny（1a MCP 前缀匹配前置）")
    void denyWholeServerRule_returnsDeny() {
        PermissionPipeline pipeline = new PermissionPipeline(null);
        McpServerTool tool = realMcpTool();
        ToolPermissionContext permCtx = denyRules(PermissionMode.DEFAULT, "mcp__filesystem");

        PermissionResult result = check(pipeline, tool, readInput(), permCtx);

        // WHY: 1a 层先于 1c；deny whole-server 规则经 RuleQuery.toolMatchesRule
        //   （MCP server-level match：rule "mcp__filesystem" 命中 "mcp__filesystem__read_file"）
        //   必须 Deny，绝不被 Passthrough override 之后的 1c/第 3 层软化。
        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .as("1a deny 归因必须为 Rule")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("deny 规则 mcp__server__tool（whole-tool）→ Deny（1a 精确匹配前置）")
    void denyWholeToolRule_returnsDeny() {
        PermissionPipeline pipeline = new PermissionPipeline(null);
        McpServerTool tool = realMcpTool();
        ToolPermissionContext permCtx = denyRules(PermissionMode.DEFAULT, MCP_FULL_NAME);

        PermissionResult result = check(pipeline, tool, readInput(), permCtx);

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ────────────────────────────────────────────────────────────────────
    // 场景 3：auto-mode → 进分类器（Ask → tryAutoModeDecision）
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AUTO mode + 分类器 allow → Classifier 归因 Allow（mcp__ 经分类器决策）")
    void autoMode_classifierAllow_returnsClassifierAllow() {
        PermissionPipeline pipeline = new PermissionPipeline(null);
        injectAutoMode(pipeline, new FakeYoloClassifier(
            YoloClassifierResult.allowed("mcp read_file is safe", "fake-model")));
        McpServerTool tool = realMcpTool();
        ToolPermissionContext permCtx = noRules(PermissionMode.AUTO);

        PermissionResult result = check(pipeline, tool, readInput(), permCtx);

        // WHY: auto-mode mcp__ 工具无规则 → 第 3 层 Ask → tryAutoModeDecision 进分类器
        //   （CC permissions.ts:520-927），分类器 allow → Allow + Classifier("YoloClassifier",
        //   "auto-mode")。若 mcp__ 工具仍默认 Allow（修复前），1c 短路 → 分类器永不覆盖
        //   （放权升级），本断言即 FAIL。
        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        PermissionDecisionReason reason = ((PermissionResult.Allow) result).reason();
        assertThat(reason).isInstanceOf(PermissionDecisionReason.Classifier.class);
        PermissionDecisionReason.Classifier c = (PermissionDecisionReason.Classifier) reason;
        assertThat(c.classifier())
            .as("auto-mode 分类器归因 classifier 必须为 'auto-mode'（CC permissions.ts:907，触发 retry hook）")
            .isEqualTo("auto-mode");
    }

    @Test
    @DisplayName("AUTO mode + 分类器未接线 → 保持 Ask（fail-closed，绝不静默 Allow）")
    void autoMode_classifierUnavailable_staysAsk() {
        PermissionPipeline pipeline = new PermissionPipeline(null);
        // 只注入 autoModeGate + denialTracker，不注入 yoloClassifier（分类器未接线）
        injectAutoMode(pipeline, null);
        McpServerTool tool = realMcpTool();
        ToolPermissionContext permCtx = noRules(PermissionMode.AUTO);

        PermissionResult result = check(pipeline, tool, readInput(), permCtx);

        // WHY: auto-mode 下分类器未接线（yoloClassifier==null）时，CC 语义保留 Ask
        //   （permissions.ts 分类器 unavailable 前不自动放行）——mcp__ 工具不得因
        //   auto-mode 而静默 Allow（fail-closed）。
        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(result).as("分类器未接线时 mcp__ 工具严禁 Allow 静默放行")
            .isNotInstanceOf(PermissionResult.Allow.class);
    }

    // ────────────────────────────────────────────────────────────────────
    // 负向对照：默认 Allow 的 mcp__ 桩工具 → 管线 Allow（证明判别力 + RED 机制）
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("对照：默认 Allow 的 mcp__ 工具（无 override）→ 管线 Allow（修复前 RED 机制）")
    void defaultAllowMcpStub_returnsAllow_documentsRedMechanism() {
        PermissionPipeline pipeline = new PermissionPipeline(null);
        Tool stub = new DefaultAllowMcpTool();
        ToolPermissionContext permCtx = noRules(PermissionMode.DEFAULT);

        PermissionResult result = check(pipeline, stub, readInput(), permCtx);

        // WHY: 本对照证明「若 mcp__ 工具 checkPermissions 回退为 Tool 默认 Allow
        //   （Tool.java:287-295），管线即返 Allow」——即修复前 McpServerTool 的行为，
        //   场景 1 断言（Ask 非 Allow）因此具备判别力，防止测试沦为恒真断言。
        assertThat(result)
            .as("默认 Allow 的 mcp__ 工具应被 1c 短路放行（RED 机制对照）")
            .isInstanceOf(PermissionResult.Allow.class);
    }

    // ────────────────────────────────────────────────────────────────────
    // 辅助：auto-mode 依赖注入 + 桩工具 + fake 分类器
    // ────────────────────────────────────────────────────────────────────

    /** 反射注入 autoModeGate + denialTracker +（可选）yoloClassifier（字段无 setter）。 */
    private static void injectAutoMode(PermissionPipeline pipeline, YoloClassifier classifier) {
        inject(pipeline, "autoModeGate", new AutoModeGate(true));
        inject(pipeline, "denialTracker", new DenialTracker(3, 20));
        inject(pipeline, "yoloClassifier", classifier);
    }

    private static void inject(PermissionPipeline pipeline, String field, Object value) {
        try {
            Field f = PermissionPipeline.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(pipeline, value);
        } catch (Exception e) {
            throw new IllegalStateException("反射注入 PermissionPipeline." + field + " 失败", e);
        }
    }

    /** 桩：mcp__ 工具但无 checkPermissions override → 继承 Tool 默认 Allow（修复前状态）。 */
    private static final class DefaultAllowMcpTool implements Tool {
        @Override public String name() { return MCP_FULL_NAME; }
        @Override public String description() { return "stub mcp tool (default allow)"; }
        @Override public JsonNode inputSchema() { return MAPPER.createObjectNode(); }
        @Override public AgentToolResult<?> execute(ToolUseBlock call) { return null; }
        @Override public boolean isMcp() { return true; }
        @Override public McpServerInfo mcpInfo() { return new McpServerInfo(SERVER, TOOL); }
        // 不 override checkPermissions → 继承 Tool.java:287-295 default Allow
    }

    /** Fake YoloClassifier：不调 LLM，返回预置结果。 */
    private static final class FakeYoloClassifier implements YoloClassifier {
        private final ArrayDeque<YoloClassifierResult> queue = new ArrayDeque<>();

        FakeYoloClassifier(YoloClassifierResult result) {
            queue.add(result);
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
            // [IMP-SUB-25 R3] 测试 stub：handoff user-text action 在本测试不触发 → 恒 allow 兜底
            return CompletableFuture.completedFuture(YoloClassifierResult.allowed(
                "fake-text-action-not-used", "fake-model"));
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
