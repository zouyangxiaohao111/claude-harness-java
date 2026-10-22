package com.nexusai.apis.mcp;

import com.nexusai.domain.mcp.McpServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [2026-10-22 契约放宽] MCP start 的 `?sessionId=` 由「必填」改为「可选」。
 *
 * <p>WHY（规则九 · 测试验证意图）：sessionId 的**唯一**用途是 `claude/channel` 白名单（门序[3]）
 * 与 channel handler 的会话归属；不声明 channel 能力的 server 用不上它。硬必填会让设置页在
 * 「没有活动会话」时启动任何 MCP server 都返回 400（实测：「sessionId is required
 * (POST /api/v1/mcp/{id}/start)」）。
 *
 * <p>验收条件：不传 / 空白 sessionId ⇒ 仍以 202 启动，并把 null 交给 service（既有「无会话路径」
 * 语义：channel 门序[3] fail-closed，工具照常）；传了则原样透传。
 */
@ExtendWith(MockitoExtension.class)
class McpServerControllerStartTest {

    @Mock private McpServerService mcpServerService;

    private McpServerController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new McpServerController();
        Field f = McpServerController.class.getDeclaredField("mcpServerService");
        f.setAccessible(true);
        f.set(controller, mcpServerService);
        when(mcpServerService.start(any(), any())).thenReturn(null);
    }

    @Test
    @DisplayName("不传 sessionId → 仍 202 启动（交给 service 的 sessionId 为 null）")
    void startWithoutSessionId_isAccepted() {
        ResponseEntity<?> res = controller.start("m1", null);

        assertThat(res.getStatusCode().value()).isEqualTo(202);
        verify(mcpServerService).start("m1", null);
    }

    @Test
    @DisplayName("空白 sessionId → 归一为 null，不把空白串透传下去")
    void blankSessionId_isNormalizedToNull() {
        ResponseEntity<?> res = controller.start("m1", "   ");

        assertThat(res.getStatusCode().value()).isEqualTo(202);
        verify(mcpServerService).start("m1", null);
    }

    @Test
    @DisplayName("传了 sessionId → 原样透传（channel 白名单按该会话判定）")
    void sessionId_isPassedThrough() {
        ResponseEntity<?> res = controller.start("m1", "sess-abc");

        assertThat(res.getStatusCode().value()).isEqualTo(202);
        verify(mcpServerService).start("m1", "sess-abc");
    }
}
