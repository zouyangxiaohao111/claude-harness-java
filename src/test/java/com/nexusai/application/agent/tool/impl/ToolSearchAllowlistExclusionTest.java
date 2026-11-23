package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.config.SessionModeState;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.SessionToolVisibility;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.test.support.SessionProjectRootTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolSearch 候选池 ⇄ 本轮应用 allowlist（P0 缺口）。
 *
 * <p>只设应用选择、没有任何会话 disabled 时，候选池若不看 allowlist 仍是全量 ——
 * 模型可 select 出未选应用的工具，tool_reference 进历史后下一轮 400。
 * 变异点：去掉 ToolSearchTool 的 hasMcpAllowlist 判据 → 本类 RED。
 */
class ToolSearchAllowlistExclusionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ToolSearchTool tool = new ToolSearchTool();

    @BeforeEach
    void setUp() {
        SessionProjectRootTestSupport.declareNoDatabase();
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
    }

    @AfterEach
    void tearDown() {
        SessionProjectRootTestSupport.clearNoDatabase();
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
    }

    private static SessionRecord session(String id) {
        SessionRecord r = new SessionRecord();
        r.setId(id);
        r.setDisabledTools("[]"); // 显式全开：本测试只关注 allowlist 剔除
        return r;
    }

    private static void givenSessions(SessionRecord... records) {
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            for (SessionRecord r : records) {
                if (r.getId() != null && r.getId().equals(id)) {
                    return r;
                }
            }
            return null;
        });
        SessionModeState.setSessionMapper(mapper);
        SessionToolDisableConfig.setSessionMapper(mapper);
    }

    private ToolSearchTool.ToolSearchOutput execute(String query, String sessionId,
                                                    List<Tool> tools, List<String> selectedApps) {
        // 显式构造「disabled 为空、只有 allowlist」的快照：这正是 ToolSearch 缺口所在的组合
        //   （capture 产出的快照通常带 Brief/IM 禁用项，会掩盖该分支）。
        SessionToolVisibility.Snapshot snap = new SessionToolVisibility.Snapshot(
            sessionId, java.util.Set.of(), false,
            SessionToolVisibility.normalizeSelectedApps(selectedApps));
        ToolUseContext ctx = ToolUseContext.of(
                com.nexusai.application.agent.subagent.AgentContext.createAgentId(), sessionId,
                PermissionMode.DEFAULT, tools, "", AbortController.NOOP, List.of(), null, null,
                Map.of(), false, "")
            .withRequestExtras(SessionToolVisibility.attach(Map.of(), snap));
        JsonNode input = MAPPER.createObjectNode().put("query", query).put("max_results", 5);
        ToolUseBlock call = new ToolUseBlock("toolu_1", "ToolSearch", input);
        return (ToolSearchTool.ToolSearchOutput) tool.execute(call, ctx).data();
    }

    @Test
    @DisplayName("选 A：select:未选应用的工具不得找回")
    void selectCannotFindUnselectedAppTool() {
        givenSessions(session("sess-a"));
        List<Tool> pool = List.of(deferredTool("mcp__A__t1"), deferredTool("mcp__B__t1"));

        assertThat(execute("select:mcp__B__t1", "sess-a", pool, List.of("A")).matches())
            .isEmpty();
        assertThat(execute("select:mcp__A__t1", "sess-a", pool, List.of("A")).matches())
            .containsExactly("mcp__A__t1");
    }

    @Test
    @DisplayName("BR 是内置工具不是应用：应用 allowlist 不裁它（ToolSearch 可找回）")
    void builtinBrowserTool_notGatedByAppAllowlist() {
        givenSessions(session("sess-a"));
        List<Tool> pool = List.of(
            deferredTool("mcp__nexusai-in-chrome__read_page"),
            deferredTool("mcp__A__t1"));

        assertThat(execute("select:mcp__nexusai-in-chrome__read_page", "sess-a",
            pool, List.of("A")).matches())
            .containsExactly("mcp__nexusai-in-chrome__read_page");
    }

    @Test
    @DisplayName("未指定（null）：候选池不裁剪，两个都在")
    void noSelection_keepsAllMatchable() {
        givenSessions(session("sess-a"));
        List<Tool> pool = List.of(deferredTool("mcp__A__t1"), deferredTool("mcp__B__t1"));

        assertThat(execute("select:mcp__B__t1", "sess-a", pool, null).matches())
            .containsExactly("mcp__B__t1");
    }

    private static Tool deferredTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "tool " + name; }
            @Override public JsonNode inputSchema() { return null; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean shouldDefer(JsonNode input) { return true; }
        };
    }
}
