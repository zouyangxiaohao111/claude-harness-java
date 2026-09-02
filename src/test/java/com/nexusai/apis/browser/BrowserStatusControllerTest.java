package com.nexusai.apis.browser;

import com.nexusai.application.agent.browser.BrowserWsChannel;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NexusAI in Chrome 浏览器扩展连接状态 REST 意图测试。
 *
 * <p><b>WHY（规则九 · 前端状态真实化）</b>：browser-mcp-align 登记欠账——后端无连接查询端点，
 * ChromePanel 只能靠 localStorage 记忆显示「已安装」，误导用户（Chrome 浏览器装了 ≠ 扩展连上了）。
 * 本端点读 {@link BrowserWsChannel#hasSessionConnection()}（全局语义：一个扩展连接服务所有会话）
 * 真实透出 WS 连接状态，前端据此区分「浏览器已装 / 扩展已加载 / WS 已连接」。
 */
@DisplayName("[browser-status] NexusAI in Chrome 连接状态 REST")
class BrowserStatusControllerTest {

    private BrowserWsChannel channel;

    @BeforeEach
    void setUp() {
        channel = mock(BrowserWsChannel.class);
    }

    @Test
    @DisplayName("GET /api/v1/browser/status → 扩展已连接 connected=true")
    void status_connected_true() throws Exception {
        // WHEN: 全局扩展已连上 /ws/browser（hasSessionConnection=true）
        when(channel.hasSessionConnection()).thenReturn(true);
        MockMvc mockMvc = standalone(channel);

        mockMvc.perform(get("/api/v1/browser/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/browser/status → 扩展未连接 connected=false（fail loud 不误报）")
    void status_connected_false() throws Exception {
        // WHEN: 无全局连接（扩展未加载 / 未点 popup 连接）→ connected=false，前端据此提示
        when(channel.hasSessionConnection()).thenReturn(false);
        MockMvc mockMvc = standalone(channel);

        mockMvc.perform(get("/api/v1/browser/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    @DisplayName("直接调用：status() 返回 {connected:boolean}（无 MockMvc 路径依赖）")
    void status_directInvocation() {
        when(channel.hasSessionConnection()).thenReturn(true);
        BrowserStatusController c = new BrowserStatusController(channel);
        Map<String, Object> r = c.status();
        assertThat(r).containsEntry("connected", true);
    }

    private MockMvc standalone(BrowserWsChannel ch) {
        return MockMvcBuilders.standaloneSetup(new BrowserStatusController(ch))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }
}
