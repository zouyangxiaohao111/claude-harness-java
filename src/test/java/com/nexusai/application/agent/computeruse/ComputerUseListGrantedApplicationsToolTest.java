package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code mcp__computer-use__list_granted_applications} 的单测（Phase 1c）。
 *
 * <p><b>WHY（规则 9）</b>：这个工具是模型的<b>自省通道</b>——模型靠它知道
 * "我现在能操作哪些应用 / 有哪些授权位 / 坐标是哪种约定"。若返回形状缺字段
 * （尤其坐标模式），模型就会按错误的坐标约定给坐标 ⇒ 点错位置。
 *
 * <p>另外两条：①只读（不能有副作用）；②CCB 的描述明写 "plus the active grant flags
 * and coordinate mode"，但它的 handler <b>没吐 coordinateMode</b> ⇒ 本仓补齐，
 * 让描述与实现一致。
 */
class ComputerUseListGrantedApplicationsToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    private SessionMapper sessionMapper;
    private SessionRecord record;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(SessionMapper.class);
        record = new SessionRecord();
        record.setId(SESSION);
        when(sessionMapper.selectOneById(SESSION)).thenReturn(record);
        when(sessionMapper.update(any(SessionRecord.class))).thenReturn(1);
    }

    private void setGrants(String json) {
        record.setComputerUseGrants(json);
    }

    @Test
    @DisplayName("工具面逐字对齐 CCB：name / description / 无参 schema")
    void toolSurfaceMatchesCcbVerbatim() {
        ComputerUseListGrantedApplicationsTool tool =
                new ComputerUseListGrantedApplicationsTool(null, ComputerUseCoordinateMode.PIXELS);

        assertThat(tool.name()).isEqualTo("mcp__computer-use__list_granted_applications");
        assertThat(tool.description())
                .isEqualTo("List the applications currently in the session allowlist, plus the active "
                        + "grant flags and coordinate mode. No side effects.");
        assertThat(tool.inputSchema().path("properties").isEmpty()).isTrue();
        assertThat(tool.inputSchema().path("required").isEmpty()).isTrue();
        assertThat(tool.isReadOnly(JSON.createObjectNode())).isTrue();
        assertThat(tool.isConcurrencySafe(JSON.createObjectNode())).isTrue();
    }

    @Test
    @DisplayName("返回 {allowedApps, grantFlags, coordinateMode} 三个键")
    void returnsAppsFlagsAndCoordinateMode() {
        setGrants(new ComputerUseGrants(
                List.of(new ComputerUseGrant("chrome", "Chrome", 1750000000000L, "read"),
                        new ComputerUseGrant("notepad", "记事本", 1750000000001L, "full")),
                new ComputerUseGrantFlags(true, false, true)).toJson());
        ComputerUseGrantStore store = new ComputerUseGrantStore(sessionMapper);
        ComputerUseListGrantedApplicationsTool tool =
                new ComputerUseListGrantedApplicationsTool(store, ComputerUseCoordinateMode.NORMALIZED_0_100);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__list_granted_applications",
                JSON.createObjectNode()), ToolUseContext.of(null, SESSION));

        JsonNode payload = (JsonNode) ((ToolResult<?>) result).data();
        assertThat(payload.path("allowedApps")).hasSize(2);
        assertThat(payload.path("allowedApps").get(0).path("appId").asText()).isEqualTo("chrome");
        assertThat(payload.path("allowedApps").get(0).path("displayName").asText()).isEqualTo("Chrome");
        assertThat(payload.path("allowedApps").get(0).path("tier").asText()).isEqualTo("read");
        assertThat(payload.path("allowedApps").get(1).path("tier").asText()).isEqualTo("full");

        assertThat(payload.path("grantFlags").path("clipboardRead").asBoolean()).isTrue();
        assertThat(payload.path("grantFlags").path("clipboardWrite").asBoolean()).isFalse();
        assertThat(payload.path("grantFlags").path("systemKeyCombos").asBoolean()).isTrue();

        assertThat(payload.path("coordinateMode").asText())
                .as("描述承诺了 coordinate mode ⇒ 必须真的吐出来")
                .isEqualTo("normalized_0_100");
    }

    @Test
    @DisplayName("未授权时返回空列表 + 全 false 授权位（不是 null）")
    void emptyGrantsReturnEmptyShape() {
        setGrants(ComputerUseGrants.empty().toJson());
        ComputerUseGrantStore store = new ComputerUseGrantStore(sessionMapper);
        ComputerUseListGrantedApplicationsTool tool =
                new ComputerUseListGrantedApplicationsTool(store, ComputerUseCoordinateMode.PIXELS);

        var result = tool.execute(new ToolUseBlock("c1", "mcp__computer-use__list_granted_applications",
                JSON.createObjectNode()), ToolUseContext.of(null, SESSION));

        JsonNode payload = (JsonNode) ((ToolResult<?>) result).data();
        assertThat(payload.path("allowedApps").isArray()).isTrue();
        assertThat(payload.path("allowedApps")).isEmpty();
        assertThat(payload.path("grantFlags").path("clipboardRead").asBoolean()).isFalse();
        assertThat(payload.path("grantFlags").path("clipboardWrite").asBoolean()).isFalse();
        assertThat(payload.path("grantFlags").path("systemKeyCombos").asBoolean()).isFalse();
        assertThat(payload.path("coordinateMode").asText()).isEqualTo("pixels");
    }
}
