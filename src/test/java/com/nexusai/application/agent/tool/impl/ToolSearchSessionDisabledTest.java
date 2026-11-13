package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.SessionToolCategory;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code ToolSearchTool.excludeSessionDisabled} 单测 —— 纯函数 + Mockito，<b>不启动 Spring</b>。
 *
 * <p><b>WHY</b>：会话禁用只剔了「发给模型的 tools schema」，没剔 ToolSearch 的候选池 ⇒
 * 模型看不见禁用工具的定义，却仍能 {@code select} 出它们；返回的 {@code tool_reference}
 * 进入历史后，下一轮 Anthropic 直接 400（{@code tool reference 'X' not found}）。
 * 本测试钉住「候选池必须与 schema 同源」这条契约。
 */
class ToolSearchSessionDisabledTest {

    private SessionMapper mapper;
    private SessionRecord record;

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "t " + name;
            }

            @Override
            public JsonNode inputSchema() {
                return null;
            }

            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @BeforeEach
    void setUp() {
        SessionToolCategory.reset();
        SessionToolDisableConfig.reset();

        mapper = mock(SessionMapper.class);
        record = new SessionRecord();
        record.setId("sess-1");
        when(mapper.selectOneById(any())).thenReturn(record);
        SessionToolDisableConfig.setSessionMapper(mapper);
        record.setDisabledTools("[\"mcp__computer-use__screenshot\",\"mcp__computer-use__activate_window\"]");
    }

    @AfterEach
    void tearDown() {
        SessionToolDisableConfig.reset();
        SessionToolCategory.reset();
    }

    @Test
    @DisplayName("会话禁用的工具从候选池剔除，其余保留")
    void disabledToolsAreExcluded() {
        List<Tool> pool = List.of(
            tool("mcp__computer-use__screenshot"),
            tool("mcp__computer-use__activate_window"),
            tool("mcp__computer-use__click_element"),
            tool("Bash"),
            tool("Read"));

        List<Tool> filtered = ToolSearchTool.excludeSessionDisabled(pool, "sess-1");

        assertThat(filtered).extracting(Tool::name)
            .containsExactly("mcp__computer-use__click_element", "Bash", "Read");
        assertThat(filtered).as("⛔ 被禁用的两个必须消失，否则能被 select 出来")
            .extracting(Tool::name)
            .doesNotContain("mcp__computer-use__screenshot", "mcp__computer-use__activate_window");
    }

    @Test
    @DisplayName("列 \"[]\"（用户显式全开）→ 原引用返回，一个都不剔")
    void explicitEmptyMeansNothingExcluded() {
        record.setDisabledTools("[]");
        List<Tool> pool = List.of(tool("mcp__computer-use__screenshot"), tool("Bash"));

        assertThat(ToolSearchTool.excludeSessionDisabled(pool, "sess-1")).isSameAs(pool);
    }

    @Test
    @DisplayName("sessionId 为 null → 原引用（无会话上下文不猜）")
    void nullSessionIsNoop() {
        List<Tool> pool = List.of(tool("mcp__computer-use__screenshot"));

        assertThat(ToolSearchTool.excludeSessionDisabled(pool, null)).isSameAs(pool);
    }

    @Test
    @DisplayName("SessionMapper 未注入 → 空禁用集 → 原引用（不因配置缺失把工具全剔光）")
    void noMapperIsNoop() {
        SessionToolDisableConfig.reset();
        List<Tool> pool = List.of(tool("mcp__computer-use__screenshot"), tool("Bash"));

        assertThat(ToolSearchTool.excludeSessionDisabled(pool, "sess-1")).isSameAs(pool);
    }

    @Test
    @DisplayName("空池 / null 池 → 原样返回，不抛")
    void emptyPoolIsSafe() {
        assertThat(ToolSearchTool.excludeSessionDisabled(List.of(), "sess-1")).isEmpty();
        assertThat(ToolSearchTool.excludeSessionDisabled(null, "sess-1")).isNull();
    }
}
