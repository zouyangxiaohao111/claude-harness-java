package com.nexusai.apis.market;

import com.nexusai.domain.connector.ConnectorOAuthService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.model.connector.dto.ConnectorDto;
import com.nexusai.model.connector.dto.ConnectorListResponse;
import com.nexusai.model.connector.dto.ManualCredentialRequest;
import com.nexusai.model.connector.dto.PollResponse;
import com.nexusai.model.connector.dto.RevokedResponse;
import com.nexusai.model.connector.dto.StartResponse;
import com.nexusai.model.connector.dto.StatusResponse;
import com.nexusai.model.connector.dto.TestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 连接器 7 个 REST mapping 的 controller 层契约（standalone MockMvc，不连库、不 @SpringBootTest）。
 *
 * <p>只验证 web 层：路由/参数绑定/JSON 形状/状态码（含 GlobalExceptionHandler 的 404/400）。
 * 业务编排由 service 测试覆盖（{@link com.nexusai.domain.connector.ConnectorOAuthServiceTest} 等）。
 */
class ConnectorControllerTest {

    private ConnectorOAuthService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ConnectorOAuthService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ConnectorController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/market/connectors → 默认 list(false)，返回 connectors[]")
    void listDefaultExcludesHidden() throws Exception {
        when(service.list(false)).thenReturn(new ConnectorListResponse(List.of(
                new ConnectorDto("notion", "notion", "Notion", "desc", "oauth2", "knowledge",
                        List.of(), false, "unconnected", null, null, false, "notion", "#000000",
                        "available", "supported", null,
                        "https://developers.notion.com/docs/mcp", "点击一键授权登录 Notion",
                        false, List.of(), true, "oauth2"))));

        mvc.perform(get("/api/market/connectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectors[0].key").value("notion"))
                .andExpect(jsonPath("$.connectors[0].authType").value("oauth2"))
                .andExpect(jsonPath("$.connectors[0].connected").value(false))
                .andExpect(jsonPath("$.connectors[0].hidden").value(false))
                .andExpect(jsonPath("$.connectors[0].icon").value("notion"))
                .andExpect(jsonPath("$.connectors[0].color").value("#000000"))
                .andExpect(jsonPath("$.connectors[0].phase").value("available"))
                .andExpect(jsonPath("$.connectors[0].supportTier").value("supported"))
                .andExpect(jsonPath("$.connectors[0].docUrl")
                        .value("https://developers.notion.com/docs/mcp"))
                .andExpect(jsonPath("$.connectors[0].authHint").value("点击一键授权登录 Notion"))
                .andExpect(jsonPath("$.connectors[0].hasCredentials").value(false))
                .andExpect(jsonPath("$.connectors[0].credentialFields").isArray())
                .andExpect(jsonPath("$.connectors[0].official").value(true))
                .andExpect(jsonPath("$.connectors[0].authMode").value("oauth2"));

        verify(service).list(false);
    }

    @Test
    @DisplayName("GET ?includeHidden=true → list(true)")
    void listIncludeHidden() throws Exception {
        when(service.list(true)).thenReturn(new ConnectorListResponse(List.of(
                new ConnectorDto("qcc", "qcc", "企查查", "desc", "oauth2", "professional",
                        List.of("mcp:tools"), false, "unconnected", null, null, true, "qcc", "#008CFF",
                        "available", "supported", null,
                        "https://agent.qcc.com/", "需要先填写 clientId/clientSecret",
                        false, List.of(), true, "oauth2"))));

        mvc.perform(get("/api/market/connectors").param("includeHidden", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectors[0].key").value("qcc"))
                .andExpect(jsonPath("$.connectors[0].hidden").value(true));

        verify(service).list(true);
    }

    @Test
    @DisplayName("POST /{id}/start → 返回 authorizeUrl/state/redirectUri/interval/expiresIn")
    void start() throws Exception {
        when(service.start("notion")).thenReturn(
                new StartResponse("https://auth.example/x", "state-1",
                        "http://127.0.0.1:34118/callback", 2, 300));

        mvc.perform(post("/api/market/connectors/notion/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").value("https://auth.example/x"))
                .andExpect(jsonPath("$.state").value("state-1"))
                .andExpect(jsonPath("$.interval").value(2))
                .andExpect(jsonPath("$.expiresIn").value(300));
    }

    @Test
    @DisplayName("GET /{id}/poll?state= → PENDING/AUTHORIZED/ERROR 三态原样透出")
    void poll() throws Exception {
        when(service.poll("notion", "state-1")).thenReturn(new PollResponse("AUTHORIZED", null, null));

        mvc.perform(get("/api/market/connectors/notion/poll").param("state", "state-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AUTHORIZED"));
    }

    @Test
    @DisplayName("GET /{id}/poll 缺 state → 400（不落全局 500 兜底）")
    void pollMissingStateIs400() throws Exception {
        mvc.perform(get("/api/market/connectors/notion/poll"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(service, org.mockito.Mockito.never())
                .poll(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("GET /{id}/status → connected/account/expiresAt/missingScopes")
    void statusEndpoint() throws Exception {
        when(service.status("notion")).thenReturn(
                new StatusResponse(true, "oauth2", null, 123L, List.of()));

        mvc.perform(get("/api/market/connectors/notion/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.authType").value("oauth2"))
                .andExpect(jsonPath("$.missingScopes").isArray());
    }

    @Test
    @DisplayName("POST /{id}/credentials → 请求体反序列化为 ManualCredentialRequest 并透传")
    void manualCredentials() throws Exception {
        when(service.saveManualCredentials(eq("notion"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new StatusResponse(true, "oauth2", null, null, List.of()));

        mvc.perform(post("/api/market/connectors/notion/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"authType":"oauth2","fields":{"clientId":"c1","clientSecret":"s1","accessToken":"t1"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true));

        ArgumentCaptor<ManualCredentialRequest> captor =
                ArgumentCaptor.forClass(ManualCredentialRequest.class);
        verify(service).saveManualCredentials(eq("notion"), captor.capture());
        assertThat(captor.getValue().authType()).isEqualTo("oauth2");
        assertThat(captor.getValue().fields()).containsEntry("clientId", "c1");
        assertThat(captor.getValue().fields()).containsEntry("accessToken", "t1");
    }

    @Test
    @DisplayName("POST /{id}/test → 返回 ok/message/latencyMs/probeLevel")
    void testEndpoint() throws Exception {
        when(service.test("notion")).thenReturn(
                new TestResponse(true, "MCP initialize 成功（tools=3）", 12L, "mcp_initialize",
                        List.of(new com.nexusai.model.connector.ConnectorToolInfo("search", "stub"))));

        mvc.perform(post("/api/market/connectors/notion/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.probeLevel").value("mcp_initialize"))
                .andExpect(jsonPath("$.latencyMs").value(12))
                .andExpect(jsonPath("$.tools[0].name").value("search"))
                .andExpect(jsonPath("$.tools[0].description").value("stub"))
                .andExpect(jsonPath("$.toolCount").value(1));
    }

    @Test
    @DisplayName("DELETE /{id} → revoked=true")
    void revoke() throws Exception {
        when(service.revoke("notion")).thenReturn(new RevokedResponse(true));

        mvc.perform(delete("/api/market/connectors/notion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
    }

    @Test
    @DisplayName("未知 key → 404（NotFoundException 经 GlobalExceptionHandler）")
    void unknownKeyIs404() throws Exception {
        when(service.status("ghost")).thenThrow(new NotFoundException("连接器不存在：ghost"));

        mvc.perform(get("/api/market/connectors/ghost/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("业务参数错误（ValidationException）→ 400")
    void semanticValidationIs400() throws Exception {
        when(service.start("qcc"))
                .thenThrow(new com.nexusai.infra.exception.ValidationException(
                        "连接器「企查查」需要先填写 clientId/clientSecret"));

        mvc.perform(post("/api/market/connectors/qcc/start"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("请求体不是合法 JSON → 400（Malformed JSON）")
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/market/connectors/notion/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
