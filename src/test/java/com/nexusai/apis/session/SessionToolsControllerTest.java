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
import com.alibaba.fastjson2.JSON;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    /** 会话行（disabled_tools / brief_mode / unattended_mode 的真实读取源）。 */
    private com.nexusai.repository.session.entity.SessionRecord sessionRow;

    @BeforeEach
    void setUp() {
        controller = new SessionToolsController();
        sessionService = mock(SessionService.class);
        toolRegistry = mock(ToolRegistry.class);
        ReflectionTestUtils.setField(controller, "sessionService", sessionService);
        ReflectionTestUtils.setField(controller, "toolRegistry", toolRegistry);
        // [V330] 面板与模型同源读取走静态桥 —— 测试必须注入同一份会话行，否则禁用集恒为空。
        sessionRow = new com.nexusai.repository.session.entity.SessionRecord();
        sessionRow.setId("sess-1");
        com.nexusai.repository.session.mapper.SessionMapper sessionMapper =
            mock(com.nexusai.repository.session.mapper.SessionMapper.class);
        when(sessionMapper.selectOneById(any())).thenReturn(sessionRow);
        when(sessionMapper.selectImToolOverrides(any())).thenReturn(null);
        doAnswer(inv -> {
            sessionRow.setDisabledTools(JSON.toJSONString(inv.getArgument(1)));
            return null;
        }).when(sessionService).setDisabledTools(any(), any());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        // 隔离静态桥（coordinator 关 + bare 关 + 禁用集清空）——防跨测试/跨类污染
        LlmAgentLoop.setCoordinatorMode(new CoordinatorMode(() -> false, () -> null));
        MemoryBareModeConfig.reset();
        SessionToolDisableConfig.reset();
        // reset 之后再注入（顺序反了会把 mapper 清掉，禁用集恒为空）。
        SessionToolDisableConfig.setSessionMapper(sessionMapper);
        com.nexusai.application.agent.config.SessionModeState.setSessionMapper(sessionMapper);
        // 工具池装配会解析工作目录；standalone 测试无 DB 解析源，给测试会话一个真实目录。
        com.nexusai.common.SessionProjectRoot.setForSession("sess-1",
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.setCoordinatorMode(null);
        MemoryBareModeConfig.reset();
        SessionToolDisableConfig.reset();
        com.nexusai.application.agent.config.SessionModeState.reset();
        com.nexusai.common.SessionProjectRoot.clearSession("sess-1");
    }

    /** 设定会话生效禁用集（走 SessionToolDisableConfig 的列语义：非空数组 = 显式集合）。 */
    private void givenDisabled(String... names) {
        sessionRow.setDisabledTools(names.length == 0 ? null : JSON.toJSONString(List.of(names)));
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
        givenDisabled("Bash");

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
    @DisplayName("[structured-output 2026-11-20] GET 不含 StructuredOutput（内部 dispatch 工具，模型也不可见）")
    void list_excludesStructuredOutput() throws Exception {
        // WHY: 面板与模型同源。StructuredOutput 属 SPECIAL_TOOLS，模型 tools 数组经 ToolRegistry
        //   过滤恒不含它（交互式协调者的白名单只保留「候选」，不决定可见）；面板若照旧列出，
        //   就会出现「面板有、模型拿不到」的幽灵条目（真机缺陷）。
        //   变异点：去掉 buildList 的该过滤 → 列表多出 StructuredOutput → 红。
        givenAllTools(tool("Bash"), tool(com.nexusai.application.agent.tool.ToolNameConstants
            .SYNTHETIC_OUTPUT_TOOL_NAME));
        when(sessionService.getDisabledTools("sess-1")).thenReturn(Set.of());

        mockMvc.perform(get("/api/v1/sessions/sess-1/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Bash"));
    }

    @Test
    @DisplayName("PATCH .../tools/Bash {enabled:false} → 200 + 持久化禁用（V34 列）")
    void patch_disablePersists() throws Exception {
        // WHY: 禁用会话内生效必须持久化（V34 列 disabled_tools，跨 turn/重开生效）。
        givenAllTools(tool("Bash"), tool("Read"));
        givenDisabled();

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
        givenDisabled("Bash");

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

    @Test
    @DisplayName("[tool-categories] PATCH {category:MCP, mcpServer:rea} → 只禁用 rea 的工具，其它 MCP server 不受影响")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void patch_mcpServer_disablesOnlyThatServer() throws Exception {
        // WHY: 三级联动的中间一级。变异点：忽略 mcpServer → 整类 255 个 MCP 工具一起被关 → 红；
        //   或过滤条件写反（只留非该 server 的）→ 红。
        //
        // ⚠️ 判据取「写入集合」（setDisabledTools 入参），⛔ 不取返回列表的 disabled 标志：
        //   本单测里 SessionMapper 静态桥未注入 ⇒ SessionToolDisableConfig 读回恒为空集，
        //   返回列表的 disabled 永远是 false（这是既有测试环境事实，不是本功能的行为）。
        givenAllTools(tool("mcp__rea__inspect_plist"), tool("mcp__rea__list_names"),
            tool("mcp__other__do_thing"), tool("Bash"));
        when(sessionService.getDisabledTools("sess-1")).thenReturn(Set.of());

        mockMvc.perform(patch("/api/v1/sessions/sess-1/tools")
                .contentType(APPLICATION_JSON)
                .content("{\"category\":\"MCP\",\"mcpServer\":\"rea\",\"enabled\":false}"))
            .andExpect(status().isOk())
            // 返回整表（按工具名排序），每条带 mcpServer（非 MCP 为 null）
            .andExpect(jsonPath("$[0].name").value("Bash"))
            .andExpect(jsonPath("$[0].mcpServer").value(nullValue()))
            .andExpect(jsonPath("$[1].name").value("mcp__other__do_thing"))
            .andExpect(jsonPath("$[1].mcpServer").value("other"))
            .andExpect(jsonPath("$[2].name").value("mcp__rea__inspect_plist"))
            .andExpect(jsonPath("$[2].mcpServer").value("rea"))
            .andExpect(jsonPath("$[3].name").value("mcp__rea__list_names"))
            .andExpect(jsonPath("$[3].mcpServer").value("rea"));

        ArgumentCaptor<Set> captor = ArgumentCaptor.forClass(Set.class);
        verify(sessionService).setDisabledTools(eq("sess-1"), captor.capture());
        assertEquals(Set.of("mcp__rea__inspect_plist", "mcp__rea__list_names"), captor.getValue(),
            "只应写入 rea 的两个工具：其它 server 与 SYS 工具不得被连带禁用");
    }

    @Test
    @DisplayName("[tool-categories] PATCH {category:MCP, enabled:false}（无 mcpServer）→ 整个 MCP 类")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void patch_mcpCategoryWithoutServer_disablesWholeCategory() throws Exception {
        givenAllTools(tool("mcp__rea__inspect_plist"), tool("mcp__other__do_thing"), tool("Bash"));
        when(sessionService.getDisabledTools("sess-1")).thenReturn(Set.of());

        mockMvc.perform(patch("/api/v1/sessions/sess-1/tools")
                .contentType(APPLICATION_JSON)
                .content("{\"category\":\"MCP\",\"enabled\":false}"))
            .andExpect(status().isOk());

        ArgumentCaptor<Set> captor = ArgumentCaptor.forClass(Set.class);
        verify(sessionService).setDisabledTools(eq("sess-1"), captor.capture());
        assertEquals(Set.of("mcp__rea__inspect_plist", "mcp__other__do_thing"), captor.getValue(),
            "整类 = 两个 MCP server 的全部工具；SYS 工具不得被连带禁用");
    }

    @Test
    @DisplayName("[tool-categories] mcpServer 与 category≠MCP 组合 → 400（不静默忽略）")
    void patch_mcpServerWithNonMcpCategory_returns400() throws Exception {
        givenAllTools(tool("Bash"));
        when(sessionService.getDisabledTools("sess-1")).thenReturn(Set.of());

        mockMvc.perform(patch("/api/v1/sessions/sess-1/tools")
                .contentType(APPLICATION_JSON)
                .content("{\"category\":\"SYS\",\"mcpServer\":\"rea\",\"enabled\":false}"))
            .andExpect(status().isBadRequest());
        verify(sessionService, never()).setDisabledTools(eq("sess-1"), any(Set.class));
    }

    @Test
    @DisplayName("[V329] 面板与模型同源：无人值守会话的 AskUserQuestion 显示 disabled=true（原因=unattended）")
    void list_unattended_SessionMarksAskDisabled() throws Exception {
        // WHY：模型 schema 已把 Ask 摘除；面板若仍显示「可用」，用户会以为能弹卡。
        //   面板保留该行（可看到原因），但 disabled=true。
        com.nexusai.repository.session.entity.SessionRecord rec =
                new com.nexusai.repository.session.entity.SessionRecord();
        rec.setId("sess-1");
        rec.setUnattendedMode(1);
        com.nexusai.repository.session.mapper.SessionMapper mapper =
                mock(com.nexusai.repository.session.mapper.SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(rec);
        com.nexusai.application.agent.config.SessionModeState.setSessionMapper(mapper);
        try {
            givenAllTools(tool("AskUserQuestion"), tool("Bash"));
            when(sessionService.getDisabledTools("sess-1")).thenReturn(Set.of());

            mockMvc.perform(get("/api/v1/sessions/sess-1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("AskUserQuestion"))
                .andExpect(jsonPath("$[0].disabled").value(true))
                .andExpect(jsonPath("$[1].name").value("Bash"))
                .andExpect(jsonPath("$[1].disabled").value(false));
        } finally {
            com.nexusai.application.agent.config.SessionModeState.reset();
        }
    }
}
