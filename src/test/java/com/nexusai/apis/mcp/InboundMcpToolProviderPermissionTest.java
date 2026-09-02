package com.nexusai.apis.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.WebSocketPermissionPrompter;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入站权限门（全量权限管线）单元测试 · v4 对齐 CC 空上下文（OPD-WF8-02-GS-01）。
 *
 * <p><b>WHY（意图验证）</b>：v4 拍板以 CC 真源为准 —— 入站 CallTool 用
 * {@code getEmptyToolPermissionContext()}（Tool.ts:140-148）空上下文，<b>不合并全量
 * settings 规则</b>、不置 headless auto-deny 位（shouldAvoidPermissionPrompts=false）。
 * 旧 S06 契约（settings deny 规则作用于入站 + headless ask→deny）已移除。以下行为必须
 * 可观察：
 * <ol>
 *   <li><b>非交互 ask→deny 由非交互通道承载</b>：工具 checkPermissions 返回 Ask + 空上下文
 *       （无 headless 位）→ Ask 落入 interactive 分支 → mock prompter 无决策 → 兜底 deny
 *       （工具不执行）。</li>
 *   <li><b>settings deny 规则不再拦截入站</b>：空上下文无 deny 规则 → 工具自决 Allow → 执行
 *       （对齐 CC 空上下文，settings deny 不直接作用于入站）。</li>
 *   <li>allow 规则不再自动放行 Ask（空上下文无 allow 规则）→ Ask → deny。</li>
 *   <li>工具自决 Deny → 拒绝；Allow 携带 updatedInput → 执行输入取改写值（CC
 *       {@code updatedInput ?? input}）。</li>
 * </ol>
 *
 * <p>单元级直调 {@link InboundMcpToolProvider#call(String)}（Spring AI 入参形态 = JSON 字符串），
 * 不拉起 Spring/DB；真 gate（PermissionPipeline 无参 + mock prompter）+ 真 factory（空上下文），
 * 全链路经 gate 10 层管线。
 */
class InboundMcpToolProviderPermissionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 探针工具：checkPermissions 返回可配置结果；execute 记录调用计数与收到输入。 */
    private static final class ProbeTool implements Tool {

        private final String name;
        private final PermissionResult permissionResult;
        private int executeCount = 0;
        private JsonNode executedInput;

        ProbeTool(String name, PermissionResult permissionResult) {
            this.name = name;
            this.permissionResult = permissionResult;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "permission probe " + name;
        }

        @Override
        public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
            return permissionResult;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            ObjectNode schema = JSON.createObjectNode();
            schema.put("type", "object");
            return schema;
        }

        @Override
        public AgentToolResult<?> execute(ToolUseBlock call) {
            return execute(call, null);
        }

        @Override
        public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
            executeCount++;
            executedInput = call.input();
            JsonNode msg = call.input() == null ? null : call.input().get("msg");
            return ToolResult.success(call.id(), "exec:" + (msg == null ? "" : msg.asText()));
        }

        int executeCount() {
            return executeCount;
        }

        JsonNode executedInput() {
            return executedInput;
        }
    }

    /** 真 gate + 真 prompter + 真 factory（空上下文）：与生产装配同构（InboundMcpServerConfig 注入同型组件）。 */
    private static InboundMcpToolProvider provider(ProbeTool tool) {
        // [v4 OPD-WF8-02-GS-01] 空上下文无 headless 位 → Ask 落入 interactive 分支 → 真 prompter 经
        //   isNonInteractiveSession 拒绝（非 mock 返回 null 走 gate catch-all）。mock prompter 返回
        //   null 会让 InteractiveHandler 日志 NPE → gate catch → cancelAndAbort，掩盖真实非交互路径。
        PermissionPrompter prompter =
            new WebSocketPermissionPrompter(Mockito.mock(org.springframework.messaging.simp.SimpMessagingTemplate.class));
        ToolPermissionGate gate = ToolPermissionGate.createSpringBean(new PermissionPipeline(), prompter);
        return new InboundMcpToolProvider(tool, null, gate, new InboundPermissionContextFactory());
    }

    private static PermissionResult.Ask askResult() {
        return new PermissionResult.Ask("probe ask", null, List.of(),
            null, null, null, false, null, List.of());
    }

    private static PermissionResult.Allow allowResult(JsonNode updatedInput) {
        return new PermissionResult.Allow(updatedInput,
            new PermissionDecisionReason.Other("probe allow"), null, false, null, List.of());
    }

    private static PermissionResult.Deny denyResult() {
        return new PermissionResult.Deny("probe deny",
            new PermissionDecisionReason.Other("probe"), null);
    }

    // ── 非交互 ask → deny（v4 由 interactive 兜底，非 headless 位）────────────────

    @Test
    @DisplayName("非交互 ask→deny：工具 checkPermissions 返回 Ask → 空上下文（无 headless 位）→ Ask 兜底 deny（不执行工具）")
    void nonInteractiveAskIsAutoDenied() {
        ProbeTool tool = new ProbeTool("ask_probe", askResult());
        InboundMcpToolProvider p = provider(tool);

        assertThatThrownBy(() -> p.call("{\"msg\":\"hi\"}"))
            .as("非交互会话 Ask 必须拒绝（v4 空上下文无 headless 位，Ask 经 interactive 兜底 deny）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("denied by permission");
        assertThat(tool.executeCount())
            .as("Ask 被拒后工具不得执行")
            .isZero();
    }

    // ── settings deny 规则不再拦截入站（对齐 CC 空上下文）────────────────────────

    @Test
    @DisplayName("settings deny 规则不再拦截入站：空上下文无 deny 规则 → 工具自决 Allow → 执行（对齐 CC getEmptyToolPermissionContext）")
    void settingsDenyNoLongerBlocksInbound() {
        // WHY: v4 拍板 OPD-WF8-02-GS-01 —— 入站 MCP 不合并全量 settings 规则（CC CallTool 用空上下文），
        //   旧 S06 验收 #2（settings deny 规则经 factory 合并 → 1a 层拒绝）已移除。若 factory 仍合并规则，
        //   工具会被额外 deny 拦截，与 CC 源码行为不一致（EV-WF8-GS-106）。
        ProbeTool tool = new ProbeTool("no_deny_probe", allowResult(json("hi")));
        InboundMcpToolProvider p = provider(tool);

        String result = p.call("{\"msg\":\"hi\"}");
        assertThat(result)
            .as("空上下文无 deny 规则 → 工具自决 Allow 放行执行")
            .isEqualTo("exec:hi");
        assertThat(tool.executeCount()).isEqualTo(1);
    }

    // ── allow 规则不再自动放行 Ask（空上下文）──────────────────────────────────

    @Test
    @DisplayName("allow 规则不再自动放行 Ask：空上下文无 allow 规则 → Ask → deny（旧 S06 allow 规则放行契约移除）")
    void askWithoutAllowRuleIsDenied() {
        // WHY: v4 空上下文不合并 allow 规则 → 工具 checkPermissions 返回 Ask 时无 allow 规则覆盖，
        //   Ask 落入 interactive 分支 → mock prompter 无决策 → 兜底 deny（工具不执行）。
        //   旧 S06 契约（whole-tool allow 规则经 factory 合并 → 2b 层放行）已移除。
        ProbeTool tool = new ProbeTool("ask_no_allow_probe", askResult());
        InboundMcpToolProvider p = provider(tool);

        assertThatThrownBy(() -> p.call("{\"msg\":\"hi\"}"))
            .as("空上下文无 allow 规则 → Ask 必须拒绝（不执行）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("denied by permission");
        assertThat(tool.executeCount()).isZero();
    }

    // ── 工具自决 Deny（既有 mcp_perm_deny_probe 语义，1c/1d 层）─────────

    @Test
    @DisplayName("工具 checkPermissions Deny → 1d 层拒绝（isError），不执行")
    void toolCheckPermissionsDenyBlocksExecution() {
        ProbeTool tool = new ProbeTool("tool_deny_probe", denyResult());
        InboundMcpToolProvider p = provider(tool);

        assertThatThrownBy(() -> p.call("{\"msg\":\"hi\"}"))
            .as("工具自决 deny 必须拒绝（对齐既有 mcp_perm_deny_probe → isError 语义）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("denied by permission");
        assertThat(tool.executeCount()).isZero();
    }

    // ── ALLOW updatedInput → 执行输入取改写值（CC updatedInput ?? input）──

    @Test
    @DisplayName("ALLOW 携带 updatedInput → 执行输入用改写值（CC updatedInput ?? input）")
    void allowUpdatedInputIsUsedForExecution() {
        JsonNode rewritten = json("rewritten");
        ProbeTool tool = new ProbeTool("rewrite_probe", allowResult(rewritten));
        InboundMcpToolProvider p = provider(tool);

        String result = p.call("{\"msg\":\"original\"}");
        assertThat(result).as("执行输入必须取 updatedInput（改写值）").isEqualTo("exec:rewritten");
        assertThat(tool.executeCount()).isEqualTo(1);
        assertThat(tool.executedInput()).isNotNull();
        assertThat(tool.executedInput().get("msg").asText())
            .as("execute 收到的输入必须是改写后的 JsonNode")
            .isEqualTo("rewritten");
    }

    // ── 工厂契约：DEFAULT mode + 空三桶 + shouldAvoidPermissionPrompts=false ──────

    @Test
    @DisplayName("工厂契约：CC 空上下文 —— DEFAULT mode + 空三桶 + shouldAvoidPermissionPrompts=false（OPD-WF8-02-GS-01）")
    void factoryBuildsCcEmptyContext() {
        InboundPermissionContextFactory factory = new InboundPermissionContextFactory();
        ToolPermissionContext ctx = factory.build();

        assertThat(ctx.mode()).isEqualTo(com.nexusai.application.agent.permission.PermissionMode.DEFAULT);
        assertThat(ctx.shouldAvoidPermissionPrompts())
            .as("v4 对齐 CC：getEmptyToolPermissionContext 未置 shouldAvoidPermissionPrompts（false），"
                + "headless auto-deny 位由 isNonInteractiveSession 承载")
            .isFalse();
        assertThat(ctx.isAutoModeAvailable()).isFalse();
        assertThat(ctx.isBypassPermissionsModeAvailable()).isFalse();
        assertThat(ctx.alwaysAllowRules())
            .as("CC alwaysAllowRules={}——入站不合并 allow 规则")
            .isEmpty();
        assertThat(ctx.alwaysDenyRules())
            .as("CC alwaysDenyRules={}——settings deny 不直接作用于入站")
            .isEmpty();
        assertThat(ctx.alwaysAskRules())
            .as("CC alwaysAskRules={}")
            .isEmpty();
        assertThat(ctx.additionalWorkingDirectories()).isEmpty();
        assertThat(ctx.strippedDangerousRules()).isEmpty();
        assertThat(ctx.awaitAutomatedChecksBeforeDialog()).isFalse();
        assertThat(ctx.prePlanMode()).isNull();
        // 规则 source 键不得出现（空上下文无任何 source 桶）
        assertThat(ctx.alwaysDenyRules().containsKey(PermissionRuleSource.USER_SETTINGS))
            .as("空上下文必须无 USER_SETTINGS deny 桶（不合并全量规则）")
            .isFalse();
    }

    private static JsonNode json(String msg) {
        ObjectNode node = JSON.createObjectNode();
        node.put("msg", msg);
        return node;
    }
}
