package com.nexusai.apis.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.InProcessTeammateTaskRegistry;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeamStatusPublisher;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tool.impl.TeamCreateTool;
import com.nexusai.application.agent.tool.impl.TeamDeleteTool;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionKeys;
import com.nexusai.domain.session.SessionService;
import com.nexusai.eventbus.ws.TeamStatusEvent;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * TeamController REST 族意图测试 · nexusai/docs/team-panel-design.md §3.3。
 *
 * <p>WHY（CLAUDE.md 规则九，验证意图）：
 * <ul>
 *   <li><b>list</b>——GET /api/v1/teams 枚举 config.json 子目录，成员/teammateStatuses 装配；</li>
 *   <li><b>get 404</b>——未知 team 不能静默返回空；</li>
 *   <li><b>create</b>——复用 TeamCreateTool 落 config.json + 发布 "created" 状态（前端面板感知）；</li>
 *   <li><b>create 已领导</b>——每 leader 一 team 守卫（TeamCreateTool.ts:136-140）→ 409 且不建目录；</li>
 *   <li><b>delete</b>——无活跃成员 → 清理目录 + 清会话列 teamContext + 发布 "deleted"；</li>
 *   <li><b>delete 活跃成员</b>——拒删 409 + 目录保留（CC TeamDeleteTool.ts:176-195）；</li>
 *   <li><b>members</b>——append/remove 落 config + 发布 member_joined/member_left；</li>
 *   <li><b>inbox/read</b>——B1 已读回执：inbox 文件消息 read=true。</li>
 * </ul>
 */
class TeamControllerTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper json = new ObjectMapper();

    private TeamController controller;
    private TeamHelpers teamHelpers;
    private TeamStatusPublisher publisher;
    private SimpMessagingTemplate ws;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // configHome 指向临时目录（TeamHelpers 文件委托 + inbox 同根）
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        teamHelpers = new TeamHelpers();
        ws = mock(SimpMessagingTemplate.class);
        publisher = new TeamStatusPublisher();
        publisher.setTeamHelpers(teamHelpers);
        publisher.setWs(ws);
        controller = new TeamController();
        ReflectionTestUtils.setField(controller, "teamHelpers", teamHelpers);
        ReflectionTestUtils.setField(controller, "teamStatusPublisher", publisher);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        TaskSystemConfig.clearForTest();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** 基础 config.json（name/createdAt/leadAgentId/leadSessionId + members[0]=lead）。 */
    private ObjectNode baseConfig(String team) {
        ObjectNode config = json.createObjectNode();
        config.put("name", team);
        config.put("createdAt", System.currentTimeMillis());
        config.put("leadAgentId", "team-lead@" + team);
        config.put("leadSessionId", "sess-1");
        ArrayNode members = config.putArray("members");
        ObjectNode lead = members.addObject();
        lead.put("agentId", "team-lead@" + team);
        lead.put("name", "team-lead");
        lead.put("agentType", "team-lead");
        lead.put("joinedAt", System.currentTimeMillis());
        lead.put("tmuxPaneId", "");
        lead.put("cwd", tempDir.toString());
        lead.putArray("subscriptions");
        return config;
    }

    /** 造 team 配置：lead + 其余成员名（无 isActive → TeamDiscovery 判 running）。 */
    private void writeTeamConfig(String team, String... memberNames) {
        ObjectNode config = baseConfig(team);
        ArrayNode members = (ArrayNode) config.get("members");
        for (String name : memberNames) {
            if ("team-lead".equals(name)) {
                continue;
            }
            ObjectNode mate = members.addObject();
            mate.put("agentId", name + "@" + team);
            mate.put("name", name);
            mate.put("agentType", "worker");
            mate.put("joinedAt", System.currentTimeMillis());
            mate.put("tmuxPaneId", "");
            mate.putArray("subscriptions");
        }
        teamHelpers.writeConfig(team, config.toString());
    }

    private TeamCreateTool createTool() {
        TeamCreateTool tool = new TeamCreateTool(teamHelpers, new TaskService());
        tool.setTeamStatusPublisher(publisher);
        return tool;
    }

    /** [perm-timeout #136] 注入 mock SpawnInProcess → registry → findByAgentId 返回给定 loop。 */
    private void setSpawnInProcessMock(Optional<AutonomousAgentLoop> found) {
        SpawnInProcess spawn = mock(SpawnInProcess.class);
        InProcessTeammateTaskRegistry reg = mock(InProcessTeammateTaskRegistry.class);
        when(spawn.registry()).thenReturn(reg);
        when(reg.findByAgentId(anyString())).thenReturn(found);
        ReflectionTestUtils.setField(controller, "spawnInProcess", spawn);
    }

    // ── list / get ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/v1/teams → 枚举含 config.json 的 team（name/members/teammateStatuses）")
    void list_enumeratesTeams() throws Exception {
        // WHY: 前端多 team 场景经 GET /api/v1/teams 拉全量列表（design doc §3.2）。变异点：枚举漏 config
        //   过滤 → 非 team 目录混入；teammateStatuses 装配缺失 → 成员网格无状态。
        writeTeamConfig("alpha-team", "team-lead");
        writeTeamConfig("beta-team", "team-lead", "mate");

        mockMvc.perform(get("/api/v1/teams"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[*].name").value(hasItems("alpha-team", "beta-team")))
            // teammateStatuses 排除 team-lead：beta-team 只有 mate 一个 status
            .andExpect(jsonPath("$[?(@.name=='beta-team')].teammateStatuses.length()").value(hasItems(1)));
    }

    @Test
    @DisplayName("GET /api/v1/teams/{teamName} → 详情含 members 字段映射 + teammateStatuses 排除 lead")
    void get_returnsDetail() throws Exception {
        // WHY: 详情端点装配 config.json 的 team + members + teammateStatuses（design doc §3.3）。
        ObjectNode config = baseConfig("detail-team");
        ArrayNode members = (ArrayNode) config.get("members");
        ObjectNode mate = members.addObject();
        mate.put("agentId", "mate@detail-team");
        mate.put("name", "mate");
        mate.put("agentType", "worker");
        mate.put("model", "deepseek-chat");
        mate.put("isActive", true);
        mate.put("mode", "default");
        mate.put("color", "#ff0000");
        mate.put("cwd", tempDir.toString());
        mate.put("joinedAt", 123456789L);
        teamHelpers.writeConfig("detail-team", config.toString());

        mockMvc.perform(get("/api/v1/teams/detail-team"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("detail-team"))
            .andExpect(jsonPath("$.leadAgentId").value("team-lead@detail-team"))
            .andExpect(jsonPath("$.members.length()").value(2))
            .andExpect(jsonPath("$.members[1].name").value("mate"))
            .andExpect(jsonPath("$.members[1].isActive").value(true))
            .andExpect(jsonPath("$.members[1].mode").value("default"))
            .andExpect(jsonPath("$.members[1].joinedAt").value(123456789L))
            // teammateStatuses 排除 team-lead（TeamDiscovery 语义）
            .andExpect(jsonPath("$.teammateStatuses.length()").value(1))
            .andExpect(jsonPath("$.teammateStatuses[0].name").value("mate"))
            .andExpect(jsonPath("$.teammateStatuses[0].status").value("running"))
            .andExpect(jsonPath("$.teammateStatuses[0].color").value("#ff0000"));
    }

    @Test
    @DisplayName("GET /api/v1/teams/{teamName} → 未知 team → 404（不静默返回空）")
    void get_unknownTeam_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/teams/nonexistent"))
            .andExpect(status().isNotFound());
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/teams → 复用 TeamCreateTool 落 config.json + 返回 TeamDto + 发布 created")
    void create_persistsConfig_andPublishesCreated() throws Exception {
        // WHY: REST 创建复用 TeamCreateTool.execute（每 leader 一 team 守卫/自动换名/落库），
        //   "created" 状态须推 /topic/sessions/{leadSessionId}/team-status（前端面板感知 LLM/HTTP
        //   建 team，只推给创建者会话）。body 必须带 sessionId → config.leadSessionId 确定性可断言。
        ReflectionTestUtils.setField(controller, "teamCreateTool", createTool());

        mockMvc.perform(post("/api/v1/teams")
                .contentType(APPLICATION_JSON)
                .content("{\"teamName\":\"research-team\",\"description\":\"研究团队\",\"sessionId\":\"sess-1\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("research-team"))
            .andExpect(jsonPath("$.leadAgentId").value("team-lead@research-team"));

        // config.json 落盘（TeamCreateTool.buildConfigJson）
        assertThat(tempDir.resolve("teams/research-team/config.json")).exists();
        // created 状态已推送（B1/status STOMP）· 按 lead 会话：[session-id-short] ctx.sessionId 已 short
        //   直传（TeamController.create 不再 canonicalUuid 派生）→ config.leadSessionId = "sess-1"
        ArgumentCaptor<TeamStatusEvent> captor = ArgumentCaptor.forClass(TeamStatusEvent.class);
        verify(ws).convertAndSend(eq("/topic/sessions/sess-1/team-status"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("created");
        assertThat(captor.getValue().teamName()).isEqualTo("research-team");
    }

    @Test
    @DisplayName("POST /api/v1/teams → 已领导 team（会话 store）→ 409 且不建目录（每 leader 一 team 守卫）")
    void create_alreadyLeading_returns409() throws Exception {
        // WHY: CC TeamCreateTool.ts:136-140 每 leader 一 team 守卫——会话 store 有 team 时拒绝再建；
        //   REST 经 sessionId 派生存取 store。变异点：守卫失效 → 同一 leader 可建多个 team。
        SessionService sessionService = mock(SessionService.class);
        // [session-id-short] ctx.sessionId 已 short 直传 → getTeamContext 用 short 键
        when(sessionService.getTeamContext("sess-1")).thenReturn(Map.of("teamName", "existing-team"));
        TeamCreateTool tool = createTool();
        ReflectionTestUtils.setField(tool, "sessionService", sessionService);
        ReflectionTestUtils.setField(controller, "teamCreateTool", tool);

        mockMvc.perform(post("/api/v1/teams")
                .contentType(APPLICATION_JSON)
                .content("{\"teamName\":\"new-team\",\"sessionId\":\"sess-1\"}"))
            .andExpect(status().isConflict());

        // 守卫拒绝 → 不得创建 team 目录
        assertThat(tempDir.resolve("teams/new-team")).doesNotExist();
    }

    @Test
    @DisplayName("POST /api/v1/teams → teamName 缺失 → 400（ValidationException）")
    void create_missingTeamName_returns400() throws Exception {
        ReflectionTestUtils.setField(controller, "teamCreateTool", createTool());
        mockMvc.perform(post("/api/v1/teams")
                .contentType(APPLICATION_JSON)
                .content("{\"description\":\"no name\"}"))
            .andExpect(status().isBadRequest());
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /api/v1/teams/{teamName} → 无活跃成员 → 清目录 + 清会话列 teamContext + 发布 deleted")
    void delete_noActiveMembers_removesTeam_andPublishesDeleted() throws Exception {
        // WHY: REST 解散复用 TeamDeleteTool.deleteTeamByName（plan D4 单一事实源）；
        //   REST 路径无 ctx → 会话列 teamContext 由 sessionId 显式承担（design doc §3.3 解散语义）。
        SessionService sessionService = mock(SessionService.class);
        TeamDeleteTool deleteTool = new TeamDeleteTool(teamHelpers);
        deleteTool.setTeamStatusPublisher(publisher);
        ReflectionTestUtils.setField(deleteTool, "sessionService", sessionService);
        ReflectionTestUtils.setField(controller, "teamDeleteTool", deleteTool);

        writeTeamConfig("del-team", "team-lead");
        assertThat(tempDir.resolve("teams/del-team/config.json")).exists();

        mockMvc.perform(delete("/api/v1/teams/del-team?sessionId=sess-9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        // 目录已删（cleanupTeamDirectories）
        assertThat(tempDir.resolve("teams/del-team")).doesNotExist();
        // 会话列 teamContext 已清（REST 路径 sessionId 承担）
        verify(sessionService).clearTeamContext("sess-9");
        // deleted 状态已推送（stomp-lead-session 方案 3）：deleteTeamByName 预解析 sessionId=sess-9 →
        //   走 3 参 publish → /topic/sessions/sess-9/team-status（cleanup 删 config 后反查恒失败，靠预解析）
        ArgumentCaptor<TeamStatusEvent> captor = ArgumentCaptor.forClass(TeamStatusEvent.class);
        verify(ws).convertAndSend(eq("/topic/sessions/sess-9/team-status"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("deleted");
    }

    @Test
    @DisplayName("DELETE /api/v1/teams/{teamName} 不带 sessionId → 从 config.leadSessionId 反查清 teamContext（bug 修复）")
    void delete_withoutSessionId_fallsBackToConfigLeadSessionId() throws Exception {
        // WHY: 前端解散默认不带 sessionId → TeamController.delete 的 sid=null →
        //   deleteTeamByName 跳过 clearTeamContext → sessions.team_context 残留 → 前端面板不消失。
        //   [team-panel-backend-bugfix] 从 team config.leadSessionId（创建时写入）反查兜底清列。
        SessionService sessionService = mock(SessionService.class);
        TeamDeleteTool deleteTool = new TeamDeleteTool(teamHelpers);
        deleteTool.setTeamStatusPublisher(publisher);
        ReflectionTestUtils.setField(deleteTool, "sessionService", sessionService);
        ReflectionTestUtils.setField(controller, "teamDeleteTool", deleteTool);

        // writeTeamConfig 的 config.json 含 leadSessionId="sess-1"（:107 fixture）
        writeTeamConfig("noids-del", "team-lead");

        // 不带 sessionId query param → 前端实际调用形态
        mockMvc.perform(delete("/api/v1/teams/noids-del"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        assertThat(tempDir.resolve("teams/noids-del")).doesNotExist();
        // 关键断言：反查 config.leadSessionId("sess-1") 清理了该会话列的 teamContext
        verify(sessionService).clearTeamContext("sess-1");
        // [stomp-lead-session 方案 3] deleted 事件走 lead 会话：反查得到的 sess-1 预解析 →
        //   /topic/sessions/sess-1/team-status（config 已删，靠 cleanup 前预解析 + 3 参 publish）
        ArgumentCaptor<TeamStatusEvent> deletedCaptor = ArgumentCaptor.forClass(TeamStatusEvent.class);
        verify(ws).convertAndSend(eq("/topic/sessions/sess-1/team-status"), deletedCaptor.capture());
        assertThat(deletedCaptor.getValue().eventType()).isEqualTo("deleted");
    }

    @Test
    @DisplayName("DELETE /api/v1/teams/{teamName} → 活跃成员 → 409 + 目录保留（CC TeamDeleteTool.ts:176-195）")
    void delete_activeMember_returns409_andKeepsDir() throws Exception {
        // WHY: 活跃成员（isActive!==false）存在时拒删（先 shutdown_request）——直接删丢运行中 teammate。
        ReflectionTestUtils.setField(controller, "teamDeleteTool", new TeamDeleteTool(teamHelpers));
        writeTeamConfig("active-del", "team-lead", "mate"); // mate 无 isActive → 活跃

        mockMvc.perform(delete("/api/v1/teams/active-del"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value(containsString("active member(s): mate")));

        assertThat(tempDir.resolve("teams/active-del/config.json")).as("活跃成员存在时不得删除 team").exists();
    }

    // ── members（可选 join/leave）─────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/teams/{team}/members → append 落 config + 发布 member_joined")
    void addMember_appendsConfig_andPublishesJoined() throws Exception {
        // WHY: design doc §3.3 成员 join/leave 端点（可选）——面板成员网格增删后状态与 config 同步。
        writeTeamConfig("member-team", "team-lead");

        mockMvc.perform(post("/api/v1/teams/member-team/members")
                .contentType(APPLICATION_JSON)
                .content("{\"agentId\":\"mate@member-team\",\"name\":\"mate\",\"agentType\":\"worker\",\"color\":\"#00ff00\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("member-team"))
            .andExpect(jsonPath("$.members.length()").value(2));

        ArgumentCaptor<TeamStatusEvent> captor = ArgumentCaptor.forClass(TeamStatusEvent.class);
        // [stomp-lead-session 方案 3] 2 参 publish → config 反查 leadSessionId("sess-1") 按 lead 会话推送
        verify(ws).convertAndSend(eq("/topic/sessions/sess-1/team-status"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("member_joined");
    }

    @Test
    @DisplayName("DELETE /api/v1/teams/{team}/members/{agentId} → 移除落 config + 发布 member_left")
    void removeMember_removesFromConfig_andPublishesLeft() throws Exception {
        writeTeamConfig("member-team", "team-lead", "mate");

        mockMvc.perform(delete("/api/v1/teams/member-team/members/mate@member-team"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.members.length()").value(1));

        ArgumentCaptor<TeamStatusEvent> captor = ArgumentCaptor.forClass(TeamStatusEvent.class);
        // [stomp-lead-session 方案 3] 2 参 publish → config 反查 leadSessionId("sess-1") 按 lead 会话推送
        verify(ws).convertAndSend(eq("/topic/sessions/sess-1/team-status"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("member_left");
    }

    @Test
    @DisplayName("DELETE /api/v1/teams/{team}/members/{agentId} → 成员不在 team → 404")
    void removeMember_notInTeam_returns404() throws Exception {
        writeTeamConfig("member-team", "team-lead");
        mockMvc.perform(delete("/api/v1/teams/member-team/members/ghost@member-team"))
            .andExpect(status().isNotFound());
    }

    // ── kill（perm-timeout #136 · 对齐 CC InProcessTeammateTask kill → killInProcessTeammate）──────

    @Test
    @DisplayName("POST /teams/{team}/members/{agentId}/kill → kill 成功 → 200 + member_left 由 kill() 内部统一发（端到端不重复发）")
    void killMember_success_returnsOk_andPublishesLeft() throws Exception {
        // WHY: 前端成员卡「停止」按钮（CC InProcessTeammateTask.tsx:27-30 kill → killInProcessTeammate
        //   spawnInProcess.ts:227-328）经 REST 停止运行中 teammate；[team-panel-backend-bugfix2]
        //   member_left 已由 AutonomousAgentLoop.kill() 内部统一发布（REST + BackgroundTaskRunner 旁路
        //   一致，防双发），killMember 端点不再直接 publish，端到端只发一次。
        writeTeamConfig("kill-team", "team-lead", "worker");
        AutonomousAgentLoop loop = mock(AutonomousAgentLoop.class);
        when(loop.kill()).thenReturn(true);
        setSpawnInProcessMock(Optional.of(loop));

        mockMvc.perform(post("/api/v1/teams/kill-team/members/worker@kill-team/kill"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        verify(loop).kill();
        // member_left 由 AutonomousAgentLoop.kill() 内部发布，killMember 端点不得重复 publish（防双发）
        org.mockito.Mockito.verify(ws, org.mockito.Mockito.never()).convertAndSend(
            eq("/topic/sessions/sess-1/team-status"), org.mockito.ArgumentMatchers.any(Object.class));
    }

    @Test
    @DisplayName("POST /teams/{team}/members/{agentId}/kill → 非 running → 409 {success:false}（fail loud）")
    void killMember_notRunning_returns409() throws Exception {
        // WHY: AutonomousAgentLoop.kill() 守卫非 running 返回 false（CC spawnInProcess.ts:239-247
        //   守卫 type/status === 'running' 否则 no-op）。REST 不能谎报成功 → 409 fail loud。
        writeTeamConfig("kill-team", "team-lead", "worker");
        AutonomousAgentLoop loop = mock(AutonomousAgentLoop.class);
        when(loop.kill()).thenReturn(false);
        setSpawnInProcessMock(Optional.of(loop));

        mockMvc.perform(post("/api/v1/teams/kill-team/members/worker@kill-team/kill"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("teammate is not running"));
    }

    @Test
    @DisplayName("POST /teams/{team}/members/{agentId}/kill → agentId 未找到 → 404")
    void killMember_agentNotFound_returns404() throws Exception {
        // WHY: 前端 kill 传 name@team（registry.findByAgentId 匹配 identity.agentId 全形，
        //   InProcessTeammateTask.tsx:92-108）；registry 无匹配 loop → 404（不静默成功）。
        writeTeamConfig("kill-team", "team-lead", "worker");
        setSpawnInProcessMock(Optional.empty());

        mockMvc.perform(post("/api/v1/teams/kill-team/members/ghost@kill-team/kill"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /teams/{team}/members/{agentId}/kill → team 不存在 → 404")
    void killMember_teamNotFound_returns404() throws Exception {
        // WHY: 未知 team 不能静默返回（对齐 get/delete 404 契约）。
        setSpawnInProcessMock(Optional.empty());
        mockMvc.perform(post("/api/v1/teams/nonexistent/members/x@nonexistent/kill"))
            .andExpect(status().isNotFound());
    }

    // ── inbox / 已读回执（B1）────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/v1/teams/{team}/inbox/read → inbox 文件消息 read=true（B1 已读回执）")
    void inboxRead_marksMessagesAsRead() throws Exception {
        // WHY: design doc §2.2 已读回执——前端读消息流后标 read=true，未读计数归零。
        writeTeamConfig("inbox-team", "team-lead");
        TeammateMailbox.writeToMailbox("team-lead",
            new TeammateMailbox.TeammateMessage("mate", "hello from mate", TeammateMailbox.isoNow(), false, null, "hi"),
            "inbox-team");

        mockMvc.perform(post("/api/v1/teams/inbox-team/inbox/read"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        // inbox 文件消息 read=true（markMessagesAsRead 锁内写回）
        Path inbox = tempDir.resolve("teams/inbox-team/inboxes/team-lead.json");
        JsonNode messages = json.readTree(Files.readString(inbox));
        assertThat(messages.get(0).get("read").asBoolean()).as("已读回执必须标 read=true").isTrue();
    }

    @Test
    @DisplayName("GET /api/v1/teams/{team}/inbox → 返回 teammate 消息列表（B1 轮询兜底/初始未读态）")
    void inbox_returnsMessages() throws Exception {
        writeTeamConfig("inbox-team", "team-lead");
        TeammateMailbox.writeToMailbox("team-lead",
            new TeammateMailbox.TeammateMessage("mate", "hello", TeammateMailbox.isoNow(), false, null, null),
            "inbox-team");

        mockMvc.perform(get("/api/v1/teams/inbox-team/inbox"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].from").value("mate"))
            .andExpect(jsonPath("$[0].text").value("hello"))
            .andExpect(jsonPath("$[0].read").value(false));
    }
}
