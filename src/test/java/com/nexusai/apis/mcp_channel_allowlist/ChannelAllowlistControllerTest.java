package com.nexusai.apis.mcp_channel_allowlist;

import com.nexusai.domain.mcp_channel_allowlist.ChannelAllowlistService;
import com.nexusai.model.mcp_channel_allowlist.ChannelAllowlistEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Q-37 ledger 白名单 REST 端点意图测试（impl-I-3 T3）。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: Q-37 拍板白名单落 DB 表 + REST 管理接口，
 * 前端开关写入。本测试钉死 web 端点契约（GET list / POST 201 / DELETE 204），
 * 独立命名空间 {@code /api/v1/mcp/channel-allowlist} 不与既有 {@code /api/v1/mcp} 冲突。
 */
class ChannelAllowlistControllerTest {

    private ChannelAllowlistController controller;
    private ChannelAllowlistService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new ChannelAllowlistController();
        service = mock(ChannelAllowlistService.class);
        ReflectionTestUtils.setField(controller, "channelAllowlistService", service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/v1/mcp/channel-allowlist → 200 + 白名单条目列表")
    void list_returnsAllowlistEntries() throws Exception {
        when(service.listAll()).thenReturn(List.of(
            new ChannelAllowlistEntry("anthropic", "slack", "2026-08-11T00:00:00Z")));

        mockMvc.perform(get("/api/v1/mcp/channel-allowlist"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].marketplace").value("anthropic"))
            .andExpect(jsonPath("$[0].plugin").value("slack"));
    }

    @Test
    @DisplayName("POST /api/v1/mcp/channel-allowlist {marketplace, plugin} → 201 + 落库条目")
    void create_returnsCreatedEntry() throws Exception {
        when(service.create("anthropic", "slack"))
            .thenReturn(new ChannelAllowlistEntry("anthropic", "slack", "2026-08-11T00:00:00Z"));

        mockMvc.perform(post("/api/v1/mcp/channel-allowlist")
                .contentType(APPLICATION_JSON)
                .content("{\"marketplace\":\"anthropic\",\"plugin\":\"slack\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.marketplace").value("anthropic"))
            .andExpect(jsonPath("$.plugin").value("slack"));
        verify(service).create("anthropic", "slack");
    }

    @Test
    @DisplayName("DELETE /api/v1/mcp/channel-allowlist/{id} → 204")
    void delete_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/mcp/channel-allowlist/cal-1234abcd"))
            .andExpect(status().isNoContent());
        verify(service).delete(anyString());
    }
}
