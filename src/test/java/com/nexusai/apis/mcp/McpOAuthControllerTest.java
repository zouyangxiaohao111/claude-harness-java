package com.nexusai.apis.mcp;

import com.nexusai.domain.mcp.McpOAuthFlowService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.infra.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MCP 管理页 OAuth2 REST（start/poll/logout）· standalone MockMvc，不连库。
 */
class McpOAuthControllerTest {

    private McpOAuthFlowService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(McpOAuthFlowService.class);
        mvc = MockMvcBuilders.standaloneSetup(new McpOAuthController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /{id}/oauth/start → authorizeUrl/state/redirectUri/interval/expiresIn")
    void start() throws Exception {
        when(service.start("mcp-1")).thenReturn(new McpOAuthFlowService.StartResult(
                "https://as/authorize?state=st-1&redirect_uri=http%3A%2F%2Flocalhost%3A3118%2Fcallback",
                "st-1", "http://localhost:3118/callback", 2, 300));

        mvc.perform(post("/api/v1/mcp/mcp-1/oauth/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("st-1"))
                .andExpect(jsonPath("$.redirectUri").value("http://localhost:3118/callback"))
                .andExpect(jsonPath("$.interval").value(2))
                .andExpect(jsonPath("$.expiresIn").value(300));
    }

    @Test
    @DisplayName("GET /{id}/oauth/poll?state= → 三态 + expiresAt")
    void poll() throws Exception {
        when(service.poll("mcp-1", "st-1"))
                .thenReturn(new McpOAuthFlowService.PollResult("AUTHORIZED", null, 123L));

        mvc.perform(get("/api/v1/mcp/mcp-1/oauth/poll").param("state", "st-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AUTHORIZED"))
                .andExpect(jsonPath("$.expiresAt").value(123));
    }

    @Test
    @DisplayName("GET /{id}/oauth/poll 缺 state → 400（不落全局 500）")
    void pollMissingState() throws Exception {
        mvc.perform(get("/api/v1/mcp/mcp-1/oauth/poll"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("DELETE /{id}/oauth → revoked=true")
    void logout() throws Exception {
        when(service.logout("mcp-1")).thenReturn(new McpOAuthFlowService.RevokedResult(true));

        mvc.perform(delete("/api/v1/mcp/mcp-1/oauth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
    }

    @Test
    @DisplayName("POST /{id}/oauth/logout（契约别名）→ revoked=true")
    void logoutPostAlias() throws Exception {
        when(service.logout("mcp-1")).thenReturn(new McpOAuthFlowService.RevokedResult(true));

        mvc.perform(post("/api/v1/mcp/mcp-1/oauth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
    }

    @Test
    @DisplayName("未知 server → 404")
    void unknownServer() throws Exception {
        when(service.start("ghost")).thenThrow(new NotFoundException("MCP server ghost not found"));

        mvc.perform(post("/api/v1/mcp/ghost/oauth/start"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
