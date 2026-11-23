package com.nexusai.application.agent.app;

import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.app.dto.AppActionResult;
import com.nexusai.model.mcp.dto.McpRuntimeStatus;
import com.nexusai.model.mcp.dto.McpServerDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用启动/停止（会话级）：
 * start = 启用并连接 MCP server + 写会话 enabled_apps；失败如实返回原因且不改会话门；
 * stop = 仅从 enabled_apps 移除（不全局停 server）。
 */
@DisplayName("AppCatalogService · start/stop（会话级）")
class AppCatalogServiceLifecycleTest {

    private static final String SID = "sess-life";
    private McpServerService mcp;
    private SessionService sessions;
    private AppCatalogService svc;

    private static McpServerDto server(String name, boolean enabled, McpRuntimeStatus rt) {
        return new McpServerDto(
            "id-" + name, name, "cmd", List.of(), Map.of(), null, null, enabled, null,
            "stdio", "approved", null, false, null, null, null, null, null, null,
            false, rt);
    }

    @BeforeEach
    void setUp() {
        AppEnablement.reset();
        com.nexusai.application.agent.config.SessionEnabledAppsConfig.reset();
        mcp = mock(McpServerService.class);
        sessions = mock(SessionService.class);
        when(mcp.listAll()).thenReturn(List.of(
            server("codegraph", true, McpRuntimeStatus.stopped)));
        svc = new AppCatalogService(mcp,
            mock(com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper.class),
            mock(com.nexusai.domain.connector.ConnectorCatalog.class));
        ReflectionTestUtils.setField(svc, "sessionService", sessions);
    }

    @AfterEach
    void tearDown() {
        AppEnablement.reset();
        com.nexusai.application.agent.config.SessionEnabledAppsConfig.reset();
    }

    @Test
    @DisplayName("start 成功 ⇒ 调 enableAndStart，并把 key 写入会话 enabled_apps")
    void start_success_writesEnabledApps() {
        when(mcp.enableAndStart("id-codegraph", SID))
            .thenReturn(server("codegraph", true, McpRuntimeStatus.connected));

        AppActionResult r = svc.startApp("codegraph", SID);

        assertThat(r.started()).isTrue();
        assertThat(r.message()).isNull();
        verify(sessions).setEnabledApps(eq(SID), eq(List.of("codegraph")));
    }

    @Test
    @DisplayName("start 失败 ⇒ started=false + 如实原因，且不改会话 enabled_apps")
    void start_failure_doesNotTouchSession() {
        when(mcp.enableAndStart(any(), any()))
            .thenThrow(new RuntimeException("pending approval"));

        AppActionResult r = svc.startApp("codegraph", SID);

        assertThat(r.started()).isFalse();
        assertThat(r.message()).contains("pending approval");
        verify(sessions, never()).setEnabledApps(any(), any());
    }

    @Test
    @DisplayName("stop ⇒ 仅从会话 enabled_apps 移除，不全局停 server")
    void stop_removesFromSessionOnly() {
        AppActionResult r = svc.stopApp("codegraph", SID);

        assertThat(r.started()).isFalse();
        verify(sessions).setEnabledApps(eq(SID), eq(List.of()));
        verify(mcp, never()).stop(any());
    }

    @Test
    @DisplayName("目录字段：serverId / canStart / lastError")
    void appDtoCarriesLifecycleFields() {
        var app = svc.listApps().get(0);

        assertThat(app.serverId()).isEqualTo("id-codegraph");
        assertThat(app.canStart()).isTrue(); // 未连接 ⇒ 可启动
        assertThat(app.lastError()).isNull();
    }
}
