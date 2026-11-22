package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.config.ImUnattendedConfig;
import com.nexusai.application.agent.config.SessionModeState;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolSearch 候选池 ⇄ 无人值守（V329）。
 *
 * <h2>WHY</h2>
 * 无人值守会话的 AskUserQuestion 已从 LLM schema 摘除；若 ToolSearch 仍能从<b>原始池</b>
 * 把它搜出来，模型会拿到一个「schema 里不存在」的 tool_reference（下一轮 400），
 * 或绕过模式限制真的执行。本测试钉住「精确名 / select: 都不许找回」，
 * 以及「另一会话不受影响」的隔离。
 *
 * <p>变异点：候选池退回 {@code ctx.availableTools()}（不过滤无人值守）→ 用例 RED。
 */
class ToolSearchUnattendedExclusionTest {

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
        ImUnattendedConfig.installChecker(null);
    }

    /** 让 mapper 按 sessionId 返回不同记录（真实记录，不造假对象）。 */
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

    private static SessionRecord session(String id, Integer unattended) {
        SessionRecord r = new SessionRecord();
        r.setId(id);
        r.setUnattendedMode(unattended);
        r.setDisabledTools("[]"); // 显式全开：本测试只关注无人值守剔除
        return r;
    }

    private ToolSearchTool.ToolSearchOutput execute(String query, String sessionId, List<Tool> tools) {
        JsonNode input = MAPPER.createObjectNode().put("query", query).put("max_results", 5);
        ToolUseBlock call = new ToolUseBlock("toolu_1", "ToolSearch", input);
        ToolUseContext ctx = ToolUseContext.of(
                com.nexusai.application.agent.subagent.AgentContext.createAgentId(), sessionId, PermissionMode.DEFAULT,
                tools, "", AbortController.NOOP, List.of(), null, null, Map.of(), false, "");
        return (ToolSearchTool.ToolSearchOutput) tool.execute(call, ctx).data();
    }

    @Test
    @DisplayName("无人值守会话：select:AskUserQuestion 不得找回（schema 已摘除）")
    void selectCannotFindAskWhenUnattended() {
        givenSessions(session("sess-u", 1));
        List<Tool> pool = List.of(deferredTool("AskUserQuestion"), deferredTool("Read"));

        ToolSearchTool.ToolSearchOutput out = execute("select:AskUserQuestion", "sess-u", pool);

        assertThat(out.matches()).isEmpty();
    }

    @Test
    @DisplayName("无人值守会话：精确名（关键词快路径）也搜不到 AskUserQuestion")
    void exactNameCannotFindAskWhenUnattended() {
        givenSessions(session("sess-u", 1));
        List<Tool> pool = List.of(deferredTool("AskUserQuestion"), deferredTool("Read"));

        ToolSearchTool.ToolSearchOutput out = execute("AskUserQuestion", "sess-u", pool);

        assertThat(out.matches()).isEmpty();
    }

    @Test
    @DisplayName("普通会话：AskUserQuestion 仍可被 select（模式只作用于显式开启的会话）")
    void attendedSessionStillFindsAsk() {
        givenSessions(session("sess-n", 0));
        List<Tool> pool = List.of(deferredTool("AskUserQuestion"), deferredTool("Read"));

        ToolSearchTool.ToolSearchOutput out = execute("select:AskUserQuestion", "sess-n", pool);

        assertThat(out.matches()).containsExactly("AskUserQuestion");
    }

    @Test
    @DisplayName("跨会话隔离：A 无人值守不影响 B 的候选池")
    void sessionIsolation() {
        givenSessions(session("sess-u", 1), session("sess-n", 0));
        List<Tool> pool = List.of(deferredTool("AskUserQuestion"));

        assertThat(execute("select:AskUserQuestion", "sess-u", pool).matches()).isEmpty();
        assertThat(execute("select:AskUserQuestion", "sess-n", pool).matches())
                .containsExactly("AskUserQuestion");
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
