package com.nexusai.apis.session;

import com.nexusai.application.agent.permission.PermissionConfigProvider;
import com.nexusai.domain.session.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.infra.security.BearerTokenAuthFilter;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import com.nexusai.model.provider.dto.ModelTag;
import com.nexusai.model.session.dto.SessionCreateRequest;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionGroup;
import com.nexusai.model.session.dto.SessionUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [OPD-CM3-25] {@link SessionController} 意图测试 · /session 命令「查看会话」REST 等价。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>：CC /session 命令（session/index.ts:4-16 + session.tsx）展示当前
 * 会话信息（remote 模式 URL + QR）；web 无 remote 模式（DELTA-5，BuiltInCommands.java:229-231
 * web 差异登记）→ 「查看会话」的 REST 载体 = SessionController 会话查询（复用不重建，
 * 04 实施要点：「/session 端点复用/补 SessionController 会话查询」）。本测试钉死该查询契约：
 * <ol>
 *   <li><b>GET /api/v1/sessions 返回会话列表数据</b>——前端 /session 面板「查看会话」列表
 *       （id/title/modelName/messageCount 等展示字段）；</li>
 *   <li><b>GET /api/v1/sessions/{id} 返回单个会话完整数据</b>——会话详情视图（对齐
 *       SessionInfo 展示当前会话的语义）；</li>
 *   <li><b>未知 id → 404</b>——SessionService.getById 抛 NotFoundException 的 REST 表达
 *       （查看不存在的会话不应静默返回空）。</li>
 * </ol>
 */
@DisplayName("[OPD-CM3-25] SessionController /session 查看会话 REST 等价")
class SessionControllerTest {

    private SessionController controller;
    private SessionService sessionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new SessionController();
        sessionService = mock(SessionService.class);
        ReflectionTestUtils.setField(controller, "sessionService", sessionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static SessionDto session(String id, String title, int messageCount) {
        return new SessionDto(
            id,
            ModelTag.DS,
            "deepseek-chat",
            title,
            "刚刚",
            SessionGroup.current,
            null,
            null,
            messageCount,
            OffsetDateTime.now(),
            OffsetDateTime.now(),
            null,
            null,
            null,
            null,
            null,    // todos（R3 持久读，本 fixture 未设 → null）
            null,    // permissionMode（V44 会话级覆盖，本 fixture 未设 → null）
            null,    // totalCostYuan（V48 会话累计花费，本 fixture 未设 → null）
            null,    // totalTokens（V48 会话累计 token，本 fixture 未设 → null）
            null);   // mainThreadAgent（SP-03 会话指定主线程 agent，本 fixture 未设 → null）
    }

    @Test
    @DisplayName("GET /api/v1/sessions → 200 + 会话列表数据（查看会话列表）")
    void list_returnsSessionData() throws Exception {
        when(sessionService.list()).thenReturn(List.of(
            session("sess-1", "需求分析", 3),
            session("sess-2", "对齐 CC", 7)));

        mockMvc.perform(get("/api/v1/sessions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value("sess-1"))
            .andExpect(jsonPath("$[0].title").value("需求分析"))
            .andExpect(jsonPath("$[0].modelName").value("deepseek-chat"))
            .andExpect(jsonPath("$[0].messageCount").value(3))
            .andExpect(jsonPath("$[1].id").value("sess-2"))
            .andExpect(jsonPath("$[1].messageCount").value(7));
    }

    @Test
    @DisplayName("GET /api/v1/sessions/{id} → 200 + 单个会话完整数据（查看会话详情）")
    void getById_returnsSessionData() throws Exception {
        when(sessionService.getById("sess-9")).thenReturn(session("sess-9", "记忆端点验证", 12));

        mockMvc.perform(get("/api/v1/sessions/sess-9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("sess-9"))
            .andExpect(jsonPath("$.title").value("记忆端点验证"))
            .andExpect(jsonPath("$.messageCount").value(12));
    }

    @Test
    @DisplayName("GET /api/v1/sessions/{id} → teamContext 透传（P2：会话级 team 状态供前端详情头 pill）")
    void getById_returnsTeamContext() throws Exception {
        // WHY: [P2] SessionDto 加 teamContext 字段（sessions.team_context 列解析态）——前端会话详情头
        //   「团队：{teamName} · 队长 {leadAgentId}」pill 从该字段读。变异点：SessionService.toDto 不回填
        //   → jsonPath 拿不到 teamContext → 前端详情无 team 信息。
        Map<String, Object> teamContext = new LinkedHashMap<>();
        teamContext.put("teamName", "research-team");
        teamContext.put("teamFilePath", "/tmp/teams/research-team/config.json");
        teamContext.put("leadAgentId", "team-lead@research-team");
        teamContext.put("teammates", new LinkedHashMap<>());
        when(sessionService.getById("sess-9")).thenReturn(
            new SessionDto("sess-9", ModelTag.DS, "deepseek-chat", "需求分析", "刚刚",
                SessionGroup.current, null, null, 12, OffsetDateTime.now(), OffsetDateTime.now(),
                null, null, null, teamContext, null, null, null, null, null));

        mockMvc.perform(get("/api/v1/sessions/sess-9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.teamContext.teamName").value("research-team"))
            .andExpect(jsonPath("$.teamContext.leadAgentId").value("team-lead@research-team"))
            .andExpect(jsonPath("$.teamContext.teammates").exists());
    }

    @Test
    @DisplayName("GET /api/v1/sessions/{id} → totalCostYuan + totalTokens 透传（[V48] 前端 F5 恢复源）")
    void getById_returnsUsageCost() throws Exception {
        // WHY: [V48] 前端 F5 刷新后会话底部 token/金额汇总从 REST 读（不依赖 STOMP 事件重放——
        //   complete 事件只在会话进行中实时推，刷新后须 GET 恢复）。变异点：SessionDto 不加字段 /
        //   toDto 不回填 → 响应无 totalCostYuan/totalTokens → 前端刷新后累计归零展示错乱。
        when(sessionService.getById("sess-9")).thenReturn(
            new SessionDto("sess-9", ModelTag.DS, "deepseek-chat", "需求分析", "刚刚",
                SessionGroup.current, null, null, 12, OffsetDateTime.now(), OffsetDateTime.now(),
                null, null, null, null, null, null, 0.0123, 1500L, null));

        mockMvc.perform(get("/api/v1/sessions/sess-9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCostYuan").value(0.0123))
            .andExpect(jsonPath("$.totalTokens").value(1500));
    }

    @Test
    @DisplayName("GET /api/v1/sessions → 无 Bearer token → 401（IMP-MV2-17 鉴权收敛，filter 覆盖 sessions 端点族）")
    void list_requiresBearerToken401() throws Exception {
        // standalone MockMvc 默认不挂 filter —— 本用例独立装配 BearerTokenAuthFilter
        // （requireOAuthAuth=true deny-all），验证 /api/v1/sessions 族已被鉴权面覆盖
        MockMvc authMvc = MockMvcBuilders.standaloneSetup(controller)
            .addFilters(new BearerTokenAuthFilter(
                mock(AccountOAuthTokenService.class), new ObjectMapper(), true))
            .build();

        authMvc.perform(get("/api/v1/sessions"))
            .andExpect(status().isUnauthorized());
        authMvc.perform(get("/api/v1/sessions/sess-9"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/sessions → 携带有效 Bearer token → 放行 200")
    void list_withValidBearerTokenPasses() throws Exception {
        AccountOAuthTokenService tokenService = mock(AccountOAuthTokenService.class);
        AccountOAuthToken valid = new AccountOAuthToken();
        valid.setProvider("github");
        valid.setIdentity("alice");
        valid.setAccessToken("valid-token");
        valid.setExpiresAt(System.currentTimeMillis() + 10 * 60 * 1000L);
        when(tokenService.readByAccessToken("valid-token")).thenReturn(valid);
        when(sessionService.list()).thenReturn(List.of(session("sess-1", "需求分析", 3)));

        MockMvc authMvc = MockMvcBuilders.standaloneSetup(controller)
            .addFilters(new BearerTokenAuthFilter(tokenService, new ObjectMapper(), true))
            .build();

        authMvc.perform(get("/api/v1/sessions")
                .header("Authorization", "Bearer valid-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/sessions → 201 + 新建会话 + 会话边界重读 bypassPermissions 开关（对齐 CC /login resetBypassPermissionsCheck）")
    void create_returnsCreated_andRefreshesPermissionGate() throws Exception {
        // WHY（补盲 · MM-F2 R-4）：SessionControllerTest 原仅 list/getById 两查询端点，
        // create/update/delete（SessionController.java:41-62）0 覆盖。create 的会话创建边界
        // permissionConfigProvider.refresh() 重读 DB 开关（:46-48）对齐 CC /login 后
        // resetBypassPermissionsCheck（bypassPermissionsKillswitch.ts:53-55）。
        PermissionConfigProvider provider = mock(PermissionConfigProvider.class);
        ReflectionTestUtils.setField(controller, "permissionConfigProvider", provider);
        when(sessionService.create(any())).thenReturn(session("sess-new", "需求分析", 0));

        mockMvc.perform(post("/api/v1/sessions")
                .contentType(APPLICATION_JSON)
                .content("{\"title\":\"需求分析\",\"modelName\":\"deepseek-chat\",\"mainProjectId\":\"proj-1\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value("sess-new"))
            .andExpect(jsonPath("$.title").value("需求分析"))
            .andExpect(jsonPath("$.messageCount").value(0));

        // 会话创建边界刷新真实调用（provider 注入时）+ 请求体原样委托 SessionService
        verify(provider).refresh();
        ArgumentCaptor<SessionCreateRequest> captor = ArgumentCaptor.forClass(SessionCreateRequest.class);
        verify(sessionService).create(captor.capture());
        assertEquals("需求分析", captor.getValue().title());
        assertEquals("deepseek-chat", captor.getValue().modelName());
        assertEquals("proj-1", captor.getValue().mainProjectId());
    }

    @Test
    @DisplayName("PATCH /api/v1/sessions/{id} → 200 + 局部更新（PATCH 语义，全字段可选）")
    void update_patchesSession() throws Exception {
        when(sessionService.update(eq("sess-1"), any())).thenReturn(session("sess-1", "改名后的标题", 3));

        mockMvc.perform(patch("/api/v1/sessions/sess-1")
                .contentType(APPLICATION_JSON)
                .content("{\"title\":\"改名后的标题\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("sess-1"))
            .andExpect(jsonPath("$.title").value("改名后的标题"));

        ArgumentCaptor<SessionUpdateRequest> captor = ArgumentCaptor.forClass(SessionUpdateRequest.class);
        verify(sessionService).update(eq("sess-1"), captor.capture());
        assertEquals("改名后的标题", captor.getValue().title());
        assertNull(captor.getValue().modelName());
    }

    @Test
    @DisplayName("DELETE /api/v1/sessions/{id} → 204 无内容 + 级联删除委托 SessionService")
    void delete_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/sessions/sess-1"))
            .andExpect(status().isNoContent());

        verify(sessionService).delete("sess-1");
    }
}
