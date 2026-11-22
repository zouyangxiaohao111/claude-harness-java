package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.config.SessionModeState;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
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
 * [F5] 非交互 + <b>有 sessionId</b> 时也必须把 AskUserQuestion 从模型 tools 里剔除。
 *
 * <p>缺陷形态：剔除判据写成「无会话（NO_SESSION 哨兵）且非交互」⇒ 有会话的非交互执行
 * （后台/验证类）仍把 Ask 暴露给模型；模型一旦调用，运行期一定 Deny（无人可答），
 * 等于先把一扇关着的门画在墙上。
 *
 * <p>本测试同时钉住反方向：交互式会话（无论有无绑定）Ask 必须仍在 —— 不能为了修这一条
 * 把正常提问一起摘掉。
 */
@DisplayName("[F5] 非交互 + 有会话 ⇒ Ask 不可见（交互式仍可见）")
class AskVisibilityNonInteractiveTest {

    private static final String SID = "sess-noninteractive-ask";

    @BeforeEach
    void setUp() {
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
        SessionToolVisibility.resetGlobalFlags();
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> null, () -> null));
        MemoryBareModeConfig.reset();
        com.nexusai.common.SessionProjectRoot.setForSession(SID,
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());

        SessionRecord row = new SessionRecord();
        row.setId(SID);
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(row);
        when(mapper.selectImToolOverrides(any())).thenReturn(null);
        SessionToolDisableConfig.setSessionMapper(mapper);
        SessionModeState.setSessionMapper(mapper);
    }

    @AfterEach
    void tearDown() {
        SessionToolDisableConfig.reset();
        SessionModeState.reset();
        SessionToolVisibility.resetGlobalFlags();
        LlmAgentLoop.setCoordinatorMode(null);
        com.nexusai.common.SessionProjectRoot.clearSession(SID);
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public JsonNode inputSchema() { return null; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), name);
            }
        };
    }

    private List<String> schemaNames(boolean nonInteractive) {
        ToolUseContext tuc = ToolUseContext.of(
            com.nexusai.application.agent.subagent.AgentContext.createAgentId(), SID,
            PermissionMode.DEFAULT,
            List.of(tool("Bash"), tool("AskUserQuestion")),
            "", AbortController.NOOP, List.of(), null, null, Map.of(), nonInteractive, "");
        return LlmAgentLoop.sessionVisibleTools(tuc, QuerySource.USER).stream().map(Tool::name).toList();
    }

    @Test
    @DisplayName("非交互 + 有 sessionId ⇒ tools 数组不含 AskUserQuestion")
    void nonInteractiveWithSessionHidesAsk() {
        assertThat(schemaNames(true)).contains("Bash").doesNotContain("AskUserQuestion");
    }

    @Test
    @DisplayName("交互式 + 有 sessionId ⇒ Ask 仍在（不能误伤正常提问）")
    void interactiveWithSessionKeepsAsk() {
        assertThat(schemaNames(false)).contains("Bash", "AskUserQuestion");
    }
}
