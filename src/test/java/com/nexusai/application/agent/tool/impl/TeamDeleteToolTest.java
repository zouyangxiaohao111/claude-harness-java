package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.InProcessTeammateTaskState;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.util.AbortControllerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TeamDeleteTool · isEnabled() agent-swarms 门控 + IMP-G1 对齐行为测试。
 *
 * <p>WHY（规则九，验证意图）：
 * <ul>
 *   <li>门控：CC tools.ts:228-230 TeamDelete 与 TeamCreate 同门；</li>
 *   <li>IMP-G1（对齐 CC TeamDeleteTool.ts，唯一事实来源）：
 *       <ul>
 *         <li>空 schema（:21 z.strictObject({})），team 名从 appState.teamContext?.teamName 取（:74），
 *             删除 Java-only 必填 team_name（⊕-05）；</li>
 *         <li>活跃成员守卫（:76-99）：非 lead 且 isActive!==false 的成员存在 → success=false，不删除；</li>
 *         <li>输出仅 {success, message, team_name}（:24-28），删除 Java-only existed（⊕-06）。</li>
 *       </ul></li>
 * </ul>
 */
class TeamDeleteToolTest {

    @TempDir
    Path tempDir;

    /**
     * {@link #appStateCtx} 的会话标识 —— 必须与用例里 mock/verify 的 UUID **逐字一致**。
     *
     * <p>⚠ 该字面量此前是**损坏的控制字符 U+0002**（与 master 逐字节相同），而 mock 桩的是
     * {@code 00000000-…-0001} ⇒ **桩永不命中** ⇒ `delete_readsTeamNameFromSessionStore` 长期
     * 空转并恒红。本批在 coordinator 拍板下改回真 UUID（该用例**首次真正执行**）。
     */
    private static final String APPSTATE_CTX_SESSION_ID = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
    }

    private TeamDeleteTool newTool() {
        return new TeamDeleteTool(new TeamHelpers());
    }

    private ToolUseBlock block(String name, ObjectNode input) {
        return new ToolUseBlock(UUID.randomUUID().toString(), name, input);
    }

    /** 带 appState 桥的 ToolUseContext · 对齐 CC ToolUseContext.getAppState/setAppState。 */
    private ToolUseContext appStateCtx(Map<String, Object> appState) {
        return ToolUseContext.of(
                UUID.randomUUID(), APPSTATE_CTX_SESSION_ID,
                PermissionMode.DEFAULT,
                List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP,
                List.of(), null, PermissionMode.DEFAULT, Map.of(),
                false, "", null, null, null, null,
                prev -> appState,
                updater -> {
                    Map<String, Object> next = updater.apply(appState);
                    appState.clear();
                    appState.putAll(next);
                },
                null, null);
    }

    private Path teamConfigPath(String team) {
        return tempDir.resolve("teams").resolve(team).resolve("config.json");
    }

    // ═══════════════════════ isEnabled 门控 ═══════════════════════

    @Test
    @DisplayName("默认未开启 agent-swarms → isEnabled()=false（LLM schema 不暴露，CC tools.ts:228）")
    void defaultDisabled_gateClosed() {
        assertThat(newTool().isEnabled())
                .as("默认（无 opt-in/flag/ant）时 isAgentSwarmsEnabled()==false → isEnabled()==false")
                .isFalse();
    }

    @Test
    @DisplayName("opt-in（nexusai.experimental.agent-teams=true）→ isEnabled()=true")
    void optInEnabled_gateOpen() {
        System.setProperty("nexusai.experimental.agent-teams", "true");
        assertThat(newTool().isEnabled())
                .as("opt-in 为真（1/true/yes/on 任一）→ isEnabled()=true")
                .isTrue();
    }

    @Test
    @DisplayName("flag（nexusai.agent-teams=true）→ isEnabled()=true")
    void flagEnabled_gateOpen() {
        System.setProperty("nexusai.agent-teams", "true");
        assertThat(newTool().isEnabled())
                .as("--agent-teams flag 等价物为真 → isEnabled()=true")
                .isTrue();
    }

    @Test
    @DisplayName("ant（nexusai.user.type=ant）→ 恒 true，无需 opt-in（CC agentSwarmsEnabled.ts:26）")
    void antAlwaysEnabled() {
        System.setProperty("nexusai.user.type", "ant");
        assertThat(newTool().isEnabled())
                .as("USER_TYPE=ant → 恒 true")
                .isTrue();
    }

    // ═══════════════════════ IMP-G1 对齐行为 ═══════════════════════

    @Test
    @DisplayName("空 schema + team 名从 context 取（CC TeamDeleteTool.ts:21,74）——无 context 时 no-op 输出 success=true")
    void delete_noContext_returnsNoOp() throws Exception {
        // WHY: CC inputSchema = z.strictObject({})，team 名取自 appState.teamContext；
        //      无 team 时返回 success=true + "No team name found, nothing to clean up"（:129-131）。
        TeamDeleteTool tool = newTool();
        AgentToolResult<?> result = tool.execute(block("TeamDelete", new ObjectMapper().createObjectNode()),
                appStateCtx(new LinkedHashMap<>()));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("success").asBoolean()).isTrue();
        assertThat(output.get("message").asText()).isEqualTo("No team name found, nothing to clean up");
        // 输出无 Java-only existed（⊕-06）
        assertThat(output.has("existed")).as("CC 输出无 existed").isFalse();
    }

    @Test
    @DisplayName("活跃成员守卫：非 lead 且 isActive!==false → success=false 不删除（CC TeamDeleteTool.ts:76-99）")
    void delete_activeMember_isBlocked() throws Exception {
        // WHY: CC :87-97 活跃成员（isActive !== false）存在 → 返回 {success:false, message, team_name}
        //      提示先 requestShutdown，直接删会丢运行中 teammate 数据/工作树（v2 OPD HIGH）。
        String team = "active-team";
        TeamHelpers helpers = new TeamHelpers();
        // 写 team 配置：lead + 一个活跃 teammate（name != team-lead，无 isActive → 活跃）
        ObjectNode config = new ObjectMapper().createObjectNode();
        config.put("name", team);
        config.put("leadAgentId", "team-lead@" + team);
        ArrayNode members = config.putArray("members");
        ObjectNode lead = members.addObject();
        lead.put("agentId", "team-lead@" + team);
        lead.put("name", "team-lead");
        ObjectNode mate = members.addObject();
        mate.put("agentId", "mate@" + team);
        mate.put("name", "mate");
        helpers.writeConfig(team, config.toString());
        assertThat(teamConfigPath(team)).exists();

        Map<String, Object> appState = new LinkedHashMap<>();
        appState.put("teamContext", Map.of("teamName", team));
        TeamDeleteTool tool = newTool();
        AgentToolResult<?> result = tool.execute(block("TeamDelete", new ObjectMapper().createObjectNode()),
                appStateCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("success").asBoolean()).as("活跃成员存在必须 success=false").isFalse();
        assertThat(output.get("message").asText()).contains("active member(s): mate");
        assertThat(output.get("team_name").asText()).isEqualTo(team);
        assertThat(teamConfigPath(team)).as("活跃成员存在时不得删除 team 目录").exists();
    }

    @Test
    @DisplayName("inactive 成员（isActive=false）不拦截删除（CC TeamDeleteTool.ts:87 仅 isActive!==false 活跃）")
    void delete_inactiveMember_doesNotBlock() throws Exception {
        // WHY: CC :87 activeMembers = nonLeadMembers.filter(m => m.isActive !== false) ——
        //      isActive===false 的 idle/dead 成员不拦截，可正常清理。
        String team = "idle-team";
        TeamHelpers helpers = new TeamHelpers();
        ObjectNode config = new ObjectMapper().createObjectNode();
        config.put("name", team);
        config.put("leadAgentId", "team-lead@" + team);
        ArrayNode members = config.putArray("members");
        ObjectNode lead = members.addObject();
        lead.put("agentId", "team-lead@" + team);
        lead.put("name", "team-lead");
        ObjectNode mate = members.addObject();
        mate.put("agentId", "mate@" + team);
        mate.put("name", "mate");
        mate.put("isActive", false);
        helpers.writeConfig(team, config.toString());

        Map<String, Object> appState = new LinkedHashMap<>();
        appState.put("teamContext", Map.of("teamName", team));
        TeamDeleteTool tool = newTool();
        AgentToolResult<?> result = tool.execute(block("TeamDelete", new ObjectMapper().createObjectNode()),
                appStateCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("success").asBoolean()).as("inactive 成员不拦截删除").isTrue();
        assertThat(teamConfigPath(team)).as("无活跃成员时必须删除 team 目录").doesNotExist();
        // CC :118-124 清 teamContext
        assertThat(appState.get("teamContext")).isNull();
    }

    @Test
    @DisplayName("输出仅 {success, message, team_name}（⊕-06 删 existed）——成功清理场景")
    void delete_success_outputMatchesCc() throws Exception {
        // WHY: CC TeamDeleteTool.ts:24-28 Output {success, message, team_name?}；删除 Java-only existed。
        String team = "del-team";
        Map<String, Object> appState = new LinkedHashMap<>();
        // 先用 TeamCreate 建 team（写 config + teamContext），再用 TeamDelete 清理
        TeamCreateTool create = new TeamCreateTool(new TeamHelpers(), new TaskService());
        ObjectNode createInput = new ObjectMapper().createObjectNode();
        createInput.put("team_name", team);
        create.execute(block("TeamCreate", createInput), appStateCtx(appState));
        assertThat(teamConfigPath(team)).exists();

        TeamDeleteTool tool = newTool();
        AgentToolResult<?> result = tool.execute(block("TeamDelete", new ObjectMapper().createObjectNode()),
                appStateCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("success").asBoolean()).isTrue();
        assertThat(output.get("message").asText()).contains("Cleaned up directories and worktrees for team");
        assertThat(output.get("team_name").asText()).isEqualTo(team);
        assertThat(output.has("existed")).as("CC 输出无 existed").isFalse();
        assertThat(teamConfigPath(team)).doesNotExist();
    }

    @Test
    @DisplayName("team 名从会话 store 读（A4：跨回合删除生效，变异点：只读 no-op appState 则删不到 team）")
    void delete_readsTeamNameFromSessionStore() throws Exception {
        // WHY: [A4] TeamDeleteTool.ts:74 team 名从 teamContext 取 —— 会话列承载后须从 store 读，
        //   TeamCreate 上一回合落列（LlmAgentLoop appStateRef 每轮重建，per-request no-op 读不到）
        //   → 跨回合 delete 才能删到 team。变异点：仍只读 appState → store 有 team 却删成 no-op。
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getTeamContext("00000000-0000-0000-0000-000000000001"))
                .thenReturn(Map.of("teamName", "store-del-team"));
        TeamDeleteTool tool = newTool();
        ReflectionTestUtils.setField(tool, "sessionService", sessionService);

        // 先写 store 指向的 team config（无活跃成员）
        TeamHelpers helpers = new TeamHelpers();
        ObjectNode config = new ObjectMapper().createObjectNode();
        config.put("name", "store-del-team");
        config.put("leadAgentId", "team-lead@store-del-team");
        config.putArray("members");
        helpers.writeConfig("store-del-team", config.toString());
        assertThat(teamConfigPath("store-del-team")).exists();

        // appState 空（模拟 per-request no-op appState），team 名从 store 取 → 应删除
        AgentToolResult<?> result = tool.execute(block("TeamDelete", new ObjectMapper().createObjectNode()),
                appStateCtx(new LinkedHashMap<>()));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("success").asBoolean()).as("store team 名须被清理").isTrue();
        assertThat(teamConfigPath("store-del-team")).as("store team 名须被删除").doesNotExist();
    }

    // ═══════════════════════ Batch2 T1 · wait_ms + terminate ═══════════════════════
    // ═══════════════════════ Batch2 T1 · wait_ms + terminate ═══════════════════════

    @Test
    @DisplayName("inputSchema 含 wait_ms（minimum 0 / maximum 30000，对齐 CC TeamDeleteTool.ts:29-40）")
    void inputSchema_hasWaitMs() {
        // WHY: CC inputSchema = z.strictObject({wait_ms: z.number().min(0).max(30000).optional()})——
        //      leader 需要 wait_ms 让活跃 teammate 确认 shutdown 后再清理，否则清理直接被活跃守卫拦截。
        JsonNode schema = newTool().inputSchema();
        JsonNode waitMs = schema.path("properties").path("wait_ms");
        assertThat(waitMs.isMissingNode()).as("schema 必须含 wait_ms").isFalse();
        assertThat(waitMs.path("type").asText()).isEqualTo("integer");
        assertThat(waitMs.path("minimum").asInt()).isEqualTo(0);
        assertThat(waitMs.path("maximum").asInt()).isEqualTo(30000);
    }

    @Test
    @DisplayName("wait_ms 越界（-1/40000）→ 用 0（log.warn 不抛，CC :144 input.wait_ms ?? 0）")
    void delete_waitMsOutOfRange_usesZero() throws Exception {
        // WHY: 越界/非法 wait_ms 应安全降级为 0（不抛、不阻塞），对齐 CC input.wait_ms ?? 0。
        String team = "oob-team";
        TeamHelpers helpers = new TeamHelpers();
        ObjectNode config = new ObjectMapper().createObjectNode();
        config.put("name", team);
        config.put("leadAgentId", "team-lead@" + team);
        ArrayNode members = config.putArray("members");
        ObjectNode lead = members.addObject();
        lead.put("agentId", "team-lead@" + team);
        lead.put("name", "team-lead");
        ObjectNode mate = members.addObject();
        mate.put("agentId", "mate@" + team);
        mate.put("name", "mate");
        helpers.writeConfig(team, config.toString());

        Map<String, Object> appState = new LinkedHashMap<>();
        appState.put("teamContext", Map.of("teamName", team));
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("wait_ms", 40000); // 越界
        AgentToolResult<?> result = newTool().execute(block("TeamDelete", input), appStateCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("success").asBoolean())
                .as("越界 wait_ms → 0，无 spawnInProcess → 无法终止 → success=false（不抛）").isFalse();
        assertThat(output.get("message").asText()).contains("Cannot cleanup team");
        assertThat(teamConfigPath(team)).as("活跃成员存在时不得删除 team 目录").exists();
    }

    @Test
    @DisplayName("wait_ms>0 + 活跃成员 + 无法终止（无 spawnInProcess）→ 不等待直接 fail（CC TeamDeleteTool.ts:176-195）")
    void delete_waitMsPositive_noTerminate_immediateFail() throws Exception {
        // WHY: CC :144 waitMs>0 && requested.length>0 才轮询等待；requested 空（无法 terminate）时
        //      直接 latest re-check → 活跃仍存在 → "Cannot cleanup team"（不空等 waitMs）。
        String team = "nospawn-team";
        TeamHelpers helpers = new TeamHelpers();
        ObjectNode config = new ObjectMapper().createObjectNode();
        config.put("name", team);
        config.put("leadAgentId", "team-lead@" + team);
        ArrayNode members = config.putArray("members");
        ObjectNode lead = members.addObject();
        lead.put("agentId", "team-lead@" + team);
        lead.put("name", "team-lead");
        ObjectNode mate = members.addObject();
        mate.put("agentId", "mate@" + team);
        mate.put("name", "mate");
        helpers.writeConfig(team, config.toString());

        Map<String, Object> appState = new LinkedHashMap<>();
        appState.put("teamContext", Map.of("teamName", team));
        ObjectNode input = new ObjectMapper().createObjectNode();
        input.put("wait_ms", 30000); // 有 wait_ms 但无法 terminate
        long start = System.currentTimeMillis();
        AgentToolResult<?> result = newTool().execute(block("TeamDelete", input), appStateCtx(appState));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).as("requested 空 → 不得空等 wait_ms").isLessThan(500);
        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("success").asBoolean()).isFalse();
        assertThat(output.get("message").asText()).contains("Cannot cleanup team");
    }

    @Test
    @DisplayName("活跃成员 terminate 经 mailbox shutdown_request 发送（对齐 CC TeamDeleteTool.ts:119-125 + InProcessBackend.ts:229-258）")
    void delete_waitMsZero_activeMember_terminatesViaMailbox() throws Exception {
        // WHY: CC :115-143 逐活跃成员 terminate（in-process → mailbox shutdown_request），返回 requested[]
        //      ；wait_ms==0 时 latest re-check 仍活跃 → "Shutdown requested ... Cleanup is blocked until
        //      they exit"（CC :176-195）。验证 terminate 确实把 shutdown_request 写入 teammate mailbox。
        String team = "term-team";
        // 注册一个存活 loop（agentName=mate）供 registry().findByAgentName 命中
        SpawnInProcess spawner = new SpawnInProcess(new TaskFrameworkService(new SdkEventQueue()));
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentId("mate@" + team);
        loop.setAgentName("mate");
        loop.setTeamName(team);
        InProcessTeammateTaskState state = new InProcessTeammateTaskState(
                "t-terminate", new TeammateIdentity("mate@" + team, "mate", team, null, false, "s"),
                "work", null, false, "default", null,
                new java.util.ArrayList<>(), new java.util.HashSet<>(), new java.util.ArrayList<>(),
                false, false, 0, 0, AbortControllerFactory.create(), null, null, new java.util.ArrayList<>());
        loop.setTaskState(state);
        spawner.registry().register(state, loop, null);

        TeamHelpers helpers = new TeamHelpers();
        ObjectNode config = new ObjectMapper().createObjectNode();
        config.put("name", team);
        config.put("leadAgentId", "team-lead@" + team);
        ArrayNode members = config.putArray("members");
        ObjectNode lead = members.addObject();
        lead.put("agentId", "team-lead@" + team);
        lead.put("name", "team-lead");
        ObjectNode mate = members.addObject();
        mate.put("agentId", "mate@" + team);
        mate.put("name", "mate");
        helpers.writeConfig(team, config.toString());

        Map<String, Object> appState = new LinkedHashMap<>();
        appState.put("teamContext", Map.of("teamName", team));
        TeamDeleteTool tool = newTool();
        tool.setSpawnInProcess(spawner);
        AgentToolResult<?> result = tool.execute(block("TeamDelete", new ObjectMapper().createObjectNode()),
                appStateCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("success").asBoolean()).isFalse();
        assertThat(output.get("message").asText()).contains("Shutdown requested for active teammate(s): mate");
        assertThat(output.get("message").asText()).contains("Cleanup is blocked until they exit");
        // terminate 通道：shutdown_request 已写入 teammate mailbox（InProcessBackend.ts:229-258）
        Path mailbox = tempDir.resolve("teams").resolve(team).resolve("inboxes").resolve("mate.json");
        assertThat(mailbox).as("shutdown_request 必须写入 teammate mailbox").exists();
        String content = Files.readString(mailbox);
        assertThat(content).contains("shutdown_request");
        assertThat(content).contains("Team cleanup requested by team lead");
        // 活跃成员存在 → 不得删除 team 目录
        assertThat(teamConfigPath(team)).exists();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [P0-6 · D1②] terminate 定位：全形 agentId 主用 + 裸名兜底；编号用 CC 原形
    // ════════════════════════════════════════════════════════════════════════

    /** 注册一个存活 loop（identity 的 agentId 可与 formatAgentId(member,team) 不一致）。 */
    private static void registerLoop(SpawnInProcess spawner, String team, String memberName,
                                     String identityAgentId, String identityTeamName) {
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentId(identityAgentId);
        loop.setAgentName(memberName);
        loop.setTeamName(identityTeamName);
        InProcessTeammateTaskState state = new InProcessTeammateTaskState(
            "t-" + memberName,
            new TeammateIdentity(identityAgentId, memberName, identityTeamName, null, false, "s"),
            "work", null, false, "default", null,
            new java.util.ArrayList<>(), new java.util.HashSet<>(), new java.util.ArrayList<>(),
            false, false, 0, 0, AbortControllerFactory.create(), null, null, new java.util.ArrayList<>());
        loop.setTaskState(state);
        spawner.registry().register(state, loop, null);
    }

    /** 写一个含 team-lead + 指定成员的 config.json（活跃成员守卫据此判定）。 */
    private void writeTeamWithMember(String team, String memberName, String memberAgentId)
            throws Exception {
        ObjectNode config = new ObjectMapper().createObjectNode();
        config.put("name", team);
        config.put("leadAgentId", "team-lead@" + team);
        ArrayNode members = config.putArray("members");
        ObjectNode lead = members.addObject();
        lead.put("agentId", "team-lead@" + team);
        lead.put("name", "team-lead");
        ObjectNode mate = members.addObject();
        mate.put("agentId", memberAgentId);
        mate.put("name", memberName);
        new TeamHelpers().writeConfig(team, config.toString());
    }

    @Test
    @DisplayName("[P0-6 · D1②] 全形 agentId 匹配落空 → 裸名兜底仍发出 shutdown_request（用户文案不降级）")
    void requestShutdown_fullAgentIdMiss_fallsBackToBareName() throws Exception {
        // WHY：主用定位键 = formatAgentId(memberName, teamName)（CC InProcessBackend.ts:211 的
        //   identity.agentId）。团队名归一化不一致时全形会落空 —— 落空若不兜底，用户可见文案会从
        //   「Shutdown requested…」降级成「Cannot cleanup…」（可观测回归）。本用例构造该不一致现场。
        String team = "norm-team";
        SpawnInProcess spawner = new SpawnInProcess(new TaskFrameworkService(new SdkEventQueue()));
        // identity 的 agentId/teamName 都是**旧名**，与 formatAgentId("mate","norm-team") 不一致
        registerLoop(spawner, team, "mate", "mate@legacy-name", "legacy-name");
        writeTeamWithMember(team, "mate", "mate@legacy-name");

        Map<String, Object> appState = new LinkedHashMap<>();
        appState.put("teamContext", Map.of("teamName", team));
        TeamDeleteTool tool = newTool();
        tool.setSpawnInProcess(spawner);

        AgentToolResult<?> result = tool.execute(block("TeamDelete", new ObjectMapper().createObjectNode()),
            appStateCtx(appState));

        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("success").asBoolean()).isFalse();
        assertThat(output.get("message").asText())
            .as("⭐ 全形落空必须走裸名兜底 ⇒ 文案仍是「Shutdown requested…」而非「Cannot cleanup…」")
            .contains("Shutdown requested for active teammate(s): mate")
            .doesNotContain("Cannot cleanup");

        Path mailbox = tempDir.resolve("teams").resolve(team).resolve("inboxes").resolve("mate.json");
        assertThat(mailbox).as("兜底命中 ⇒ shutdown_request 必须写入 teammate mailbox").exists();
        String content = Files.readString(mailbox);
        assertThat(content).contains("shutdown_request");
        // CC InProcessBackend.ts:225 编号原形 "shutdown-{全形 agentId}-{ts}"（含 @）；
        // 旧形 "shutdown-{member}-{ts}" 无 @ ⇒ parseRequestId 恒 null ⇒ 旧回落腿恒哑。
        assertThat(content)
            .as("requestId 必须是 CC 原形（含全形 agentId）")
            .contains("\\\"requestId\\\":\\\"shutdown-mate@norm-team-");
    }

    @Test
    @DisplayName("[P0-6 · D1②] 幂等守卫：已请求过 shutdown → 不重发、仍视为已请求（CC InProcessBackend.ts:222）")
    void requestShutdown_alreadyRequested_isIdempotent() throws Exception {
        String team = "idem-team";
        SpawnInProcess spawner = new SpawnInProcess(new TaskFrameworkService(new SdkEventQueue()));
        registerLoop(spawner, team, "mate", "mate@" + team, team);
        writeTeamWithMember(team, "mate", "mate@" + team);
        // 置 shutdownRequested=true（CC task.shutdownRequested 的 Java 等价读点）
        spawner.registry().findByAgentId("mate@" + team).orElseThrow().requestShutdown();

        Map<String, Object> appState = new LinkedHashMap<>();
        appState.put("teamContext", Map.of("teamName", team));
        TeamDeleteTool tool = newTool();
        tool.setSpawnInProcess(spawner);

        AgentToolResult<?> result = tool.execute(block("TeamDelete", new ObjectMapper().createObjectNode()),
            appStateCtx(appState));

        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("message").asText())
            .as("已请求过 → 仍视为「已请求」（守卫 return true），文案不降级")
            .contains("Shutdown requested for active teammate(s): mate");
        assertThat(Files.exists(tempDir.resolve("teams").resolve(team).resolve("inboxes").resolve("mate.json")))
            .as("幂等守卫 ⇒ **不得**重发 shutdown_request（mailbox 不产生新文件）")
            .isFalse();
    }
}
