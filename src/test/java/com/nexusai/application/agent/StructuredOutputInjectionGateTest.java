package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultApplier;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SyntheticOutputTool;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [2026-11-20 对齐 CC] StructuredOutput 的注入门 + 「无调用期拒绝」契约。
 *
 * <p><b>CC 真源（用户实测结论）</b>：
 * <ul>
 *   <li>注入条件 = {@code jsonSchema} 存在 <b>且</b>（非交互会话 或 后台会话）；否则白名单里有也不注入；</li>
 *   <li>工具 isEnabled 恒 true，execute <b>没有</b>调用期拒绝；交互式带 json-schema 静默忽略；</li>
 *   <li>白名单保留它，但白名单只决定候选。</li>
 * </ul>
 *
 * <p>本测试覆盖验收 a~e：
 * <ol>
 *   <li>a 交互式协调者：模型 tools 不含 StructuredOutput（/tools 侧见 SessionToolsControllerTest）；</li>
 *   <li>b 非交互 + jsonSchema：注入且可调用，输出落到 structured_output；</li>
 *   <li>c 非交互但无 jsonSchema：不注入；</li>
 *   <li>d 普通会话不变；</li>
 *   <li>e deny 优先。</li>
 * </ol>
 */
@DisplayName("[2026-11-20] StructuredOutput 注入门（CC：jsonSchema && 非交互/后台）")
class StructuredOutputInjectionGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SO = ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME;

    @BeforeEach
    void isolate() {
        com.nexusai.test.support.SessionProjectRootTestSupport.declareNoDatabase();
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> null));
        MemoryBareModeConfig.reset();
    }

    @AfterEach
    void reset() {
        LlmAgentLoop.setCoordinatorMode(null);
        MemoryBareModeConfig.reset();
        com.nexusai.test.support.SessionProjectRootTestSupport.clearNoDatabase();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private static Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.isEnabled()).thenReturn(true);
        return t;
    }

    /** 12 参构造器：availableTools + isNonInteractiveSession（copyWith 会原样继承）。 */
    private static ToolUseContext tuc(List<Tool> available, boolean nonInteractive) {
        return tuc(available, nonInteractive, null);
    }

    private static ToolUseContext tuc(List<Tool> available, boolean nonInteractive,
                                      ToolPermissionContext permCtx) {
        return new ToolUseContext(
            com.nexusai.application.agent.subagent.AgentContext.createAgentId(),
            "sess-" + UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT,
            Map.of(),
            available,
            null,
            AbortController.NOOP,
            List.of(),
            permCtx,
            PermissionMode.DEFAULT,
            Map.of(),
            nonInteractive);
    }

    private static ObjectNode answerSchema() {
        ObjectNode s = JSON.createObjectNode();
        s.put("type", "object");
        s.putObject("properties").putObject("answer").put("type", "string");
        s.putArray("required").add("answer");
        return s;
    }

    private static List<String> schemaNames(ArrayNode schema) {
        return java.util.stream.StreamSupport.stream(schema.spliterator(), false)
            .map(n -> n.path("function").path("name").asText())
            .toList();
    }

    // ── a 交互式协调者 ──────────────────────────────────────────────────

    @Test
    @DisplayName("a. 交互式协调者：模型 tools 不含 StructuredOutput，注入门为假")
    void interactiveCoordinator_notInjected() {
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> true, () -> "1"));
        List<Tool> pool = List.of(
            tool("Agent"), tool("TaskStop"), tool("SendMessage"), tool("SessionCreate"),
            tool(SO), tool("Bash"), tool("Read"));

        ToolUseContext ctx = tuc(pool, false);
        ArrayNode schema = LlmAgentLoop.llmToolsArray(ctx, QuerySource.USER);

        assertThat(schemaNames(schema))
            .as("交互式协调者模型 tools 不含 StructuredOutput（SPECIAL_TOOLS 未放宽）")
            .doesNotContain(SO)
            .contains("Agent", "TaskStop", "SendMessage", "SessionCreate");
        assertThat(LlmAgentLoop.shouldInjectStructuredOutput(answerSchema(), ctx))
            .as("交互式会话注入门恒假（即使调用方传了 jsonSchema）")
            .isFalse();
    }

    // ── b 非交互 + jsonSchema ───────────────────────────────────────────

    @Test
    @DisplayName("b. 非交互 + jsonSchema：注入、可调用、输出落到 structured_output")
    void nonInteractiveWithSchema_injectedAndCallable() {
        // 走生产赋值链：headless 入口（cron / 后台 / -p 等价）调用 setNonInteractiveSession(true)，
        // base TUC 构造时经 resolveNonInteractiveSession 解析后盖到 TUC 上。
        LlmAgentLoop loop = new LlmAgentLoop(mock(com.nexusai.infra.llm.LlmProviderFactory.class));
        loop.setNonInteractiveSession(true);
        boolean nonInteractive = loop.resolveNonInteractiveSession(
            "sess-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(nonInteractive).as("headless 入口置位 → run 判定为非交互").isTrue();

        ToolUseContext ctx = tuc(List.of(tool("Bash"), tool(SO)), nonInteractive);
        ObjectNode jsonSchema = answerSchema();

        assertThat(LlmAgentLoop.shouldInjectStructuredOutput(jsonSchema, ctx)).isTrue();

        ArrayNode tools = JSON.createArrayNode();
        LlmAgentLoop.applyStructuredOutputEnablement(tools, jsonSchema, ctx);
        assertThat(schemaNames(tools)).as("注入门放行 → tools 数组含 StructuredOutput").containsExactly(SO);
        assertThat(tools.get(0).path("function").path("parameters").path("properties")
            .path("answer").path("type").asText())
            .as("注入的是 schema 专用实例（parameters = 本次 jsonSchema）").isEqualTo("string");

        // 可调用：非交互上下文 execute 成功（无调用期拒绝 —— 已被删除）
        SyntheticOutputTool soTool = new SyntheticOutputTool(jsonSchema);
        ObjectNode input = JSON.createObjectNode().put("answer", "yes");
        ToolResult<Map<String, Object>> result = (ToolResult<Map<String, Object>>) soTool.execute(
            new ToolUseBlock("toolu-b", SO, input), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("非交互调用成功").isFalse();
        assertThat(ToolResult.presentationMeta(result).get("answer")).isEqualTo("yes");

        // 输出落到 structured_output：attachment(type=structured_output) + state 载体
        AgentState state = new AgentState("system",
            "sess-" + UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.subagent.AgentContext.createAgentId());
        ToolResultApplier.apply(result, state.rawMessages(), state, "toolu-b");
        List<AttachmentMessageDto> attachments = state.attachments();
        assertThat(attachments).hasSize(1);
        AttachmentMessageDto att = attachments.get(0);
        assertThat(att.type()).isEqualTo("structured_output");
        assertThat(att.structuredData().get("answer")).isEqualTo("yes");
        assertThat(state.takeStructuredOutput("toolu-b").get("answer")).isEqualTo("yes");
    }

    // ── c 非交互但无 jsonSchema ─────────────────────────────────────────

    @Test
    @DisplayName("c. 非交互但无 jsonSchema：不注入")
    void nonInteractiveWithoutSchema_notInjected() {
        ToolUseContext ctx = tuc(List.of(tool("Bash"), tool(SO)), true);

        assertThat(LlmAgentLoop.shouldInjectStructuredOutput(null, ctx)).isFalse();
        ArrayNode tools = JSON.createArrayNode();
        LlmAgentLoop.applyStructuredOutputEnablement(tools, null, ctx);
        assertThat(tools).as("无 jsonSchema → tools 数组零追加").isEmpty();
    }

    // ── d 普通会话不变 ──────────────────────────────────────────────────

    @Test
    @DisplayName("d. 普通会话（协调者关、交互式）：池原样、不含 StructuredOutput、注入门为假")
    void normalSession_unchanged() {
        ToolUseContext ctx = tuc(List.of(tool("Bash"), tool("Read"), tool(SO)), false);

        ArrayNode schema = LlmAgentLoop.llmToolsArray(ctx, QuerySource.USER);

        assertThat(schemaNames(schema))
            .as("普通会话工具面不变（Bash/Read 保留，SPECIAL StructuredOutput 仍被剔除）")
            .contains("Bash", "Read")
            .doesNotContain(SO);
        assertThat(LlmAgentLoop.shouldInjectStructuredOutput(answerSchema(), ctx)).isFalse();
    }

    // ── e deny 优先 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("e. deny 优先：非交互 + jsonSchema 但 deny StructuredOutput → 不注入")
    void denyWins_overInjectionGate() {
        PermissionRule deny = new PermissionRule(
            PermissionRuleSource.USER_SETTINGS,
            PermissionBehavior.DENY,
            PermissionRuleValue.wholeTool(SO));
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT,
            Map.of(),
            Map.of(PermissionRuleSource.USER_SETTINGS, Set.of(deny)),
            Map.of(),
            Map.of());
        ToolUseContext ctx = tuc(List.of(tool("Bash"), tool(SO)), true, permCtx);

        assertThat(LlmAgentLoop.shouldInjectStructuredOutput(answerSchema(), ctx))
            .as("deny 在注入点之前生效 → 不注入（否则 deny 有后门）")
            .isFalse();
        ArrayNode schema = LlmAgentLoop.llmToolsArray(ctx, QuerySource.USER);
        assertThat(schemaNames(schema)).doesNotContain(SO).contains("Bash");
    }
}
