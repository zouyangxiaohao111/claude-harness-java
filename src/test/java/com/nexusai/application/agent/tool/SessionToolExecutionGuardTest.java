package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.config.SessionModeState;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
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
 * 执行入口 ⇄ 会话可见集（V329）。
 *
 * <h2>WHY</h2>
 * schema 里看不见的工具不得在运行期执行（历史 tool_call / ToolSearch 找回 / 模型幻觉）。
 * 本测试钉住 {@code ToolRegistry.dispatch}：无人值守会话执行 AskUserQuestion、
 * Brief 关时执行 SendUserMessage，都必须是 "No such tool available"，且工具体<b>零执行</b>。
 * ToolSearch 自身豁免（否则 deferred 工具无法被找回）。
 *
 * <p>变异点：删掉会话守卫 → 工具体被调用（execute 抛异常）→ 用例 RED。
 */
class SessionToolExecutionGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ToolRegistry registry;
    private SessionRecord record;

    @BeforeEach
    void setUp() {
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
        record = new SessionRecord();
        record.setId("sess-u");
        record.setUnattendedMode(1);
        record.setBriefMode(0);
        record.setDisabledTools("[\"SendUserMessage\"]");
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(record);
        SessionModeState.setSessionMapper(mapper);
        SessionToolDisableConfig.setSessionMapper(mapper);
        // ToolUseContext.of 会解析工作目录；standalone 测试无 DB 解析源，给真实目录。
        com.nexusai.common.SessionProjectRoot.setForSession("sess-u",
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
        com.nexusai.common.SessionProjectRoot.clearSession("sess-u");
    }

    private ToolUseContext ctx(String sessionId, List<Tool> tools) {
        return ToolUseContext.of(com.nexusai.application.agent.subagent.AgentContext.createAgentId(), sessionId, PermissionMode.DEFAULT,
                tools, "", AbortController.NOOP, List.of(), null, null, Map.of(), false, "");
    }

    private static Tool blockedTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "blocked " + name; }
            @Override public JsonNode inputSchema() { return null; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                throw new AssertionError("工具体不得被执行: " + name);
            }
        };
    }

    @Test
    @DisplayName("无人值守会话执行 AskUserQuestion → No such tool available，且不进入工具体")
    void unattendedAskIsRejectedAtExecution() {
        Tool ask = blockedTool("AskUserQuestion");
        registry = ToolRegistry.from(List.of(ask));
        ToolUseBlock call = new ToolUseBlock("toolu_1", "AskUserQuestion", MAPPER.createObjectNode());

        ToolResult<?> r = registry.dispatch(call, ctx("sess-u", List.of(ask)));

        assertThat(String.valueOf(r.data())).isEqualTo("No such tool available: AskUserQuestion");
    }

    @Test
    @DisplayName("无头（无会话+非交互）执行 AskUserQuestion → No such tool available")
    void headlessNoSessionAskIsRejectedAtExecution() {
        Tool ask = blockedTool("AskUserQuestion");
        registry = ToolRegistry.from(List.of(ask));
        ToolUseBlock call = new ToolUseBlock("toolu_h", "AskUserQuestion", MAPPER.createObjectNode());
        com.nexusai.application.agent.tool.ToolUseContext headless = ToolUseContext.of(
            "agent-h", com.nexusai.common.SessionKeys.NO_SESSION,
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            List.of(ask), "", com.nexusai.application.agent.tool.AbortController.NOOP,
            List.of(), null, null, Map.of(), true, "");

        ToolResult<?> r = registry.dispatch(call, headless);

        assertThat(String.valueOf(r.data())).isEqualTo("No such tool available: AskUserQuestion");
    }

    @Test
    @DisplayName("Brief 关（SendUserMessage 在禁用集）→ 执行被拒，不进入工具体")
    void disabledSendUserMessageIsRejectedAtExecution() {
        Tool sum = blockedTool("SendUserMessage");
        registry = ToolRegistry.from(List.of(sum));
        ToolUseBlock call = new ToolUseBlock("toolu_2", "SendUserMessage", MAPPER.createObjectNode());

        ToolResult<?> r = registry.dispatch(call, ctx("sess-u", List.of(sum)));

        assertThat(String.valueOf(r.data())).isEqualTo("No such tool available: SendUserMessage");
    }

    @Test
    @DisplayName("另一会话（未无人值守、Brief 开）→ Ask 正常进入工具体（隔离）")
    void otherSessionIsNotAffected() {
        record.setUnattendedMode(0);
        record.setBriefMode(1);
        record.setDisabledTools("[]");
        Tool ask = new Tool() {
            @Override public String name() { return "AskUserQuestion"; }
            @Override public String description() { return "ask"; }
            @Override public JsonNode inputSchema() { return null; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), "asked");
            }
        };
        registry = ToolRegistry.from(List.of(ask));
        ToolUseBlock call = new ToolUseBlock("toolu_3", "AskUserQuestion", MAPPER.createObjectNode());

        ToolResult<?> r = registry.dispatch(call, ctx("sess-u", List.of(ask)));

        assertThat(String.valueOf(r.data())).isEqualTo("asked");
    }
}
