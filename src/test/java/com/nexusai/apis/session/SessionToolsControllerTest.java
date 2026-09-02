package com.nexusai.apis.session;

import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [gap29] SessionToolsController 意图测试 · GET/PATCH /api/v1/sessions/{sessionId}/tools。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>: 前端工具管理 UI 需要「当前会话可用工具列表 + 禁用开关」
 * （待前端对接 §29）——GET 返回经 bare/deny/coordinator 基链过滤的工具 + disabled 标志；
 * PATCH 会话级禁用/恢复（写入 V34 列 disabled_tools，随会话持久化）。
 * 变异点：
 * <ul>
 *   <li>GET 与 LLM schema 口径不一致（两套 bare/deny 逻辑）→ 列表与模型实际可见不一致 → 红</li>
 *   <li>禁用后工具从列表消失 → 前端无法恢复 → 红（本测试钉死「被禁工具保留列表 disabled=true」）</li>
 *   <li>PATCH 未知工具不报错 / 核心编排工具可禁用 → 前端误操作 / 会话锁死 → 红</li>
 * </ul>
 */
@DisplayName("[gap29] SessionToolsController 会话级工具列表 REST")
class SessionToolsControllerTest {

    private SessionToolsController controller;
    private SessionService sessionService;
    private ToolRegistry toolRegistry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new SessionToolsController();
        sessionService = mock(SessionService.class);
        toolRegistry = mock(ToolRegistry.class);
        ReflectionTestUtils.setField(controller, "sessionService", sessionService);
        ReflectionTestUtils.setField(controller, "toolRegistry", toolRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        // 隔离静态桥（coordinator 关 + bare 关 + 禁用集清空）——防跨测试/跨类污染
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> null));
        MemoryBareModeConfig.reset();
        SessionToolDisableConfig.reset();
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.setCoordinatorMode(null);
        MemoryBareModeConfig.reset();
        SessionToolDisableConfig.reset();
    }

    private static Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        when(t.isEnabled()).thenReturn(true);
        return t;
    }

    /** 预构建工具列表再 stub —— 避免在 when(...).thenReturn(...) 参数内调用 tool()（Mockito 禁止嵌套 stubbing）。 */
    private void givenAllTools(Tool... ts) {
        when(toolRegistry.all()).thenReturn(java.util.Arrays.asList(ts));
    }

    @Test
    @DisplayName("GET .../tools → 200 + 当前会话可见工具列表 + disabled 标志（被禁工具保留列表）")
    void list_returnsVisibleToolsWithDisabledFlag() throws Exception {
        // WHY: 被禁工具必须仍在列表（disabled=true）——否则前端无法恢复（PATCH enabled:true 无入口）。
        //   与 LLM schema 口径同源（sessionVisibleToolsBase），列表与模型实际可见一致。
        givenAllTools(tool("Bash"), tool("Read"), tool("Edit"), tool("WebSearch"));
        when(sessionService.getDisabledTools("sess-1")).thenReturn(Set.of("Bash"));

        mockMvc.perform(get("/api/v1/sessions/sess-1/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[0].name").value("Bash"))
            .andExpect(jsonPath("$[0].disabled").value(true))
            .andExpect(jsonPath("$[1].disabled").value(false))
            .andExpect(jsonPath("$[1].name").value("Edit"))
            .andExpect(jsonPath("$[3].name").value("WebSearch"));
    }

    @Test
    @DisplayName("GET bare 模式 → 仅 [Bash, Read, Edit] 可见（对齐 CC simpleTools tools.ts:287）")
    void list_bareMode_onlyBashReadEdit() throws Exception {
        // WHY: bare（Web 精简模式）LLM 工具池裁剪为 simpleTools=[Bash,Read,Edit]（tools.ts:287），
        //   工具管理列表必须与之一致（口径同源）。变异点：GET 未接 bare → WebSearch 出现 → 红。
        new MemoryBareModeConfig(true);
        givenAllTools(tool("Bash"), tool("Read"), tool("Edit"), tool("WebSearch"));
        when(sessionService.getDisabledTools("sess-1")).thenReturn(Set.of());

        mockMvc.perform(get("/api/v1/sessions/sess-1/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            // 工具名排序（稳定序）→ [Bash, Edit, Read]
            .andExpect(jsonPath("$[0].name").value("Bash"))
            .andExpect(jsonPath("$[1].name").value("Edit"))
            .andExpect(jsonPath("$[2].name").value("Read"));
    }

    @Test
    @DisplayName("PATCH .../tools/Bash {enabled:false} → 200 + 持久化禁用（V34 列）")
    void patch_disablePersists() throws Exception {
        // WHY: 禁用会话内生效必须持久化（V34 列 disabled_tools，跨 turn/重开生效）。
        givenAllTools(tool("Bash"), tool("Read"));
        when(sessionService.getDisabledTools("sess-1")).thenReturn(Set.of());

        mockMvc.perform(patch("/api/v1/sessions/sess-1/tools/Bash")
                .contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Bash"))
            .andExpect(jsonPath("$.disabled").value(true));

        // 写回禁用集合 {Bash}
        verify(sessionService).setDisabledTools(eq("sess-1"), any(Set.class));
    }

    @Test
    @DisplayName("PATCH .../tools/Bash {enabled:true} → 200 + 恢复（从禁用集合移除）")
    void patch_enableRestores() throws Exception {
        // WHY: 恢复 = 工具回到 LLM schema（从禁用集合移除）。变异点：恢复未移除 → 仍被剔除 → 红。
        givenAllTools(tool("Bash"), tool("Read"));
        when(sessionService.getDisabledTools("sess-1")).thenReturn(Set.of("Bash"));

        mockMvc.perform(patch("/api/v1/sessions/sess-1/tools/Bash")
                .contentType(APPLICATION_JSON)
                .content("{\"enabled\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Bash"))
            .andExpect(jsonPath("$.disabled").value(false));

        verify(sessionService).setDisabledTools(eq("sess-1"), any(Set.class));
    }

    @Test
    @DisplayName("PATCH 未知工具 → 404")
    void patch_unknownTool_returns404() throws Exception {
        // WHY: 前端误传工具名需明确失败（404），不能静默成功。
        givenAllTools(tool("Bash"));

        mockMvc.perform(patch("/api/v1/sessions/sess-1/tools/UnknownTool")
                .contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH 禁用核心编排工具 Agent → 400（防锁死会话，owner 拍板）")
    void patch_disableCoreTool_returns400() throws Exception {
        // WHY: Agent/TaskStop/SendMessage 是编排工具，禁用后无编排工具无法恢复（锁死会话），
        //   owner 拍板默认拒绝（400）。变异点：放行 → 会话永久锁死 → 红。
        givenAllTools(tool("Agent"), tool("Bash"));
        when(sessionService.getDisabledTools("sess-1")).thenReturn(Set.of());

        mockMvc.perform(patch("/api/v1/sessions/sess-1/tools/Agent")
                .contentType(APPLICATION_JSON)
                .content("{\"enabled\":false}"))
            .andExpect(status().isBadRequest());
    }
}
