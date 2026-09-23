package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.LeaderPermissionConfirmBridge;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.team.LeaderPermissionBridge;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.SessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TeamCreateTool · isEnabled() agent-swarms 门控 + IMP-G1 对齐行为测试。
 *
 * <p>WHY（规则九，验证意图）：
 * <ul>
 *   <li>门控：CC tools.ts:228 {@code isAgentSwarmsEnabled() ? [getTeamCreateTool(), ...] : []}，
 *       未开启时不进 LLM schema（isAgentSwarmsEnabled.ts:24-44）；</li>
 *   <li>IMP-G1（对齐 CC TeamCreateTool.ts，唯一事实来源）：
 *       <ul>
 *         <li>team-lead@ 前缀（:146 formatAgentId(TEAM_LEAD_NAME, name)，agentId.ts:38-40）；</li>
 *         <li>重名自动换名 generateUniqueTeamName（:64-72,143），不失败；</li>
 *         <li>无字符集校验（CC 仅 :96-105 校验 team_name 非空，无 isValidTeamName）；</li>
 *         <li>输出仅 {team_name, team_file_path, lead_agent_id}（:52-56 Output），无
 *             member_count/created_at/message；</li>
 *         <li>每 leader 一 team 守卫（:132-140 appState.teamContext?.teamName）。</li>
 *       </ul></li>
 * </ul>
 */
class TeamCreateToolTest {

    @TempDir
    Path tempDir;

    /**
     * {@link #appStateCtx} 的会话标识 —— 必须与用例里 mock/verify 的 UUID **逐字一致**。
     *
     * <p>⚠ 该字面量此前是**损坏的控制字符 U+0002**（与 master 逐字节相同），而 mock 桩的是
     * {@code 00000000-…-0001} ⇒ **桩永不命中** ⇒ `create_alreadyLeading_readsFromSessionStore`
     * 与 `create_setTeamContext_writesToSessionStore` 长期空转并恒红。本批在 coordinator 拍板下
     * 改回真 UUID（这两条用例**首次真正执行**）。
     */
    private static final String APPSTATE_CTX_SESSION_ID = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void setUp() {
        // configHome 指向临时目录：TeamHelpers 文件委托需要可写 configHome
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        // [P0-2 · B1] TeamCreate 现在会把 leader→team 绑定登记进 TaskService.leaderTeamNames
        //   （JVM 级 static Map）。不按本用例的会话键清 ⇒ 泄漏给同类后续用例与同 JVM 的其它测试类。
        TaskService.clearLeaderTeamName(APPSTATE_CTX_SESSION_ID);
        TaskSystemConfig.clearForTest();
    }

    private TeamCreateTool newTool() {
        return new TeamCreateTool(new TeamHelpers(), new TaskService());
    }

    private ToolUseBlock block(String name, com.fasterxml.jackson.databind.node.ObjectNode input) {
        return new ToolUseBlock(UUID.randomUUID().toString(), name, input);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode input(String... keyValues) {
        com.fasterxml.jackson.databind.node.ObjectNode node = new ObjectMapper().createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.put(keyValues[i], keyValues[i + 1]);
        }
        return node;
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
    @DisplayName("leadAgentId 使用 team-lead@ 前缀（CC TeamCreateTool.ts:146 formatAgentId(TEAM_LEAD_NAME, name)）")
    void create_usesTeamLeadPrefix() throws Exception {
        // WHY: CC 确定性 team-lead@{team}（agentId.ts:38-40），Java-only "lead@" 前缀会使
        //      config.json leadAgentId / 输出 lead_agent_id / isTeamLead / mailbox 路由全链路失配（v2 OPD HIGH）。
        TeamCreateTool tool = newTool();
        AgentToolResult<?> result = tool.execute(block("TeamCreate", input("team_name", "my-project")), null);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("创建 team 不应失败").isFalse();
        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("lead_agent_id").asText()).isEqualTo("team-lead@my-project");
        // config.json leadAgentId 同源
        JsonNode config = new ObjectMapper().readTree(
                Files.readString(tempDir.resolve("teams/my-project/config.json")));
        assertThat(config.get("leadAgentId").asText()).isEqualTo("team-lead@my-project");
        assertThat(config.get("members").get(0).get("agentId").asText()).isEqualTo("team-lead@my-project");
    }

    @Test
    @DisplayName("重名自动换名不失败（CC TeamCreateTool.ts:64-72,143 generateUniqueTeamName）")
    void create_duplicateName_autoRenames() throws Exception {
        // WHY: CC 同名 team 已存在 → generateWordSlug() 新名（:71），不报错；
        //      Java-only 重名报错（:130-133）是删除候选 ⊕-02。
        TeamCreateTool tool = newTool();
        AgentToolResult<?> first = tool.execute(block("TeamCreate", input("team_name", "dup")), null);
        assertThat(LlmAgentLoop.isToolErrorData(first.data())).as("首次创建不应失败").isFalse();
        String firstTeam = new ObjectMapper().readTree((String) first.data()).get("team_name").asText();
        assertThat(firstTeam).isEqualTo("dup");

        AgentToolResult<?> second = tool.execute(block("TeamCreate", input("team_name", "dup")), null);
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).as("重名创建不应失败（CC 自动换名）").isFalse();
        String secondTeam = new ObjectMapper().readTree((String) second.data()).get("team_name").asText();
        assertThat(secondTeam).as("重名必须自动换名，不得沿用同名").isNotEqualTo("dup");
        assertThat(secondTeam).matches("[a-z]+-[a-z]+-[a-z]+");
    }

    @Test
    @DisplayName("无字符集校验（CC TeamCreateTool.ts:96-105 仅校验 team_name 非空，删除 isValidTeamName）")
    void create_noCharacterSetValidation() throws Exception {
        // WHY: CC 无 isValidTeamName a-zA-Z0-9_- 校验（EV-G3-010）；team 目录路径由
        //      sanitizeName 处理非字母数字（teamHelpers.ts:100-102），工具层不拒绝。
        TeamCreateTool tool = newTool();
        AgentToolResult<?> result = tool.execute(block("TeamCreate", input("team_name", "my.team!")), null);
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("含特殊字符的 team_name 不应被字符集校验拒绝").isFalse();
        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.get("team_name").asText()).isEqualTo("my.team!");
    }

    @Test
    @DisplayName("输出仅 {team_name, team_file_path, lead_agent_id}（CC TeamCreateTool.ts:52-56 Output）")
    void create_outputHasOnlyCcFields() throws Exception {
        // WHY: CC 输出无 member_count/created_at/message（EV-G3-028 删除候选 ⊕-04）；
        //      LLM 需要看到 team_file_path 而非 Java-only 字段。
        TeamCreateTool tool = newTool();
        AgentToolResult<?> result = tool.execute(block("TeamCreate", input("team_name", "out-check")), null);
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode output = new ObjectMapper().readTree((String) result.data());
        assertThat(output.has("team_name")).as("必须含 team_name").isTrue();
        assertThat(output.has("team_file_path")).as("必须含 team_file_path").isTrue();
        assertThat(output.has("lead_agent_id")).as("必须含 lead_agent_id").isTrue();
        assertThat(output.has("member_count")).as("CC 输出无 member_count").isFalse();
        assertThat(output.has("created_at")).as("CC 输出无 created_at").isFalse();
        assertThat(output.has("message")).as("CC 输出无 message").isFalse();
        assertThat(output.get("team_file_path").asText()).endsWith("config.json");
    }

    @Test
    @DisplayName("每 leader 一 team 守卫（CC TeamCreateTool.ts:132-140 appState.teamContext?.teamName）")
    void create_oneTeamPerLeaderGuard() throws Exception {
        // WHY: CC :136-140 已领导 team 时拒绝再建（"A leader can only manage one team at a time"）；
        //      未对齐时同一 leader 可建多个 team，AppState teamContext 语义被破坏。
        // 注：isToolErrorData 前缀表（LlmAgentLoop.java:9515-9560）不含 "Already leading"——
        //   拒绝经「消息内容 + 未创建 team 目录」双断言验证（意图：守卫触发阻止建 team）。
        Map<String, Object> appState = new LinkedHashMap<>();
        appState.put("teamContext", Map.of("teamName", "existing-team"));
        TeamCreateTool tool = newTool();
        AgentToolResult<?> result = tool.execute(block("TeamCreate", input("team_name", "new-team")), appStateCtx(appState));
        assertThat(((String) result.data())).as("已领导 team 时必须返回拒绝消息").contains("Already leading team \"existing-team\"");
        assertThat(Files.exists(tempDir.resolve("teams/new-team"))).as("守卫拒绝后不得创建 team 目录").isFalse();
    }

    @Test
    @DisplayName("创建成功写 appState.teamContext（供 TeamDelete 从 context 取 team 名）")
    void create_writesTeamContextToAppState() {
        // WHY: CC TeamCreateTool.ts:194-212 setAppState(teamContext)；TeamDeleteTool.ts:74
        //      从 appState.teamContext?.teamName 取 team 名 —— create 必须写 context 否则 delete 找不到 team。
        Map<String, Object> appState = new LinkedHashMap<>();
        TeamCreateTool tool = newTool();
        AgentToolResult<?> result = tool.execute(block("TeamCreate", input("team_name", "ctx-team")), appStateCtx(appState));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Object> teamContext = (Map<String, Object>) appState.get("teamContext");
        assertThat(teamContext).as("create 必须 setAppState(teamContext)").isNotNull();
        assertThat(teamContext.get("teamName")).isEqualTo("ctx-team");
        assertThat(teamContext.get("leadAgentId")).isEqualTo("team-lead@ctx-team");
    }

    // ═══════════════════════ [A4] 会话级 teamContext（sessions.team_context 列）═══════════════════════

    @Test
    @DisplayName("每 leader 一 team 守卫：从会话 store 读现有 team（A4，变异点：仍走 no-op appState 则守卫失效）")
    void create_alreadyLeading_readsFromSessionStore() throws Exception {
        // WHY: [A4] 会话列承载后守卫须从 sessions.team_context 读——上一回合 TeamCreate 落列，
        //   若只读 appState（per-request no-op，LlmAgentLoop appStateRef 每轮重建）则跨回合守卫失效，
        //   同一 leader 可建多个 team（CC TeamCreateTool.ts:132-140 每 leader 一 team 语义被破坏）。
        // 注：isToolErrorData 前缀表（LlmAgentLoop.java:9515-9560）不含 "Already leading"——
        //   守卫拒绝经「消息内容 + 未创建 team 目录」双断言验证（意图：store 读触发守卫）。
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getTeamContext("00000000-0000-0000-0000-000000000001"))
                .thenReturn(Map.of("teamName", "existing-team"));
        TeamCreateTool tool = newTool();
        ReflectionTestUtils.setField(tool, "sessionService", sessionService);

        // appState 空（模拟 per-request no-op appState），store 有 team → 仍拒绝
        AgentToolResult<?> result = tool.execute(block("TeamCreate", input("team_name", "new-team")),
                appStateCtx(new LinkedHashMap<>()));
        assertThat((String) result.data()).as("store 有 team 时创建必须返回拒绝消息")
                .contains("Already leading team \"existing-team\"");
        // 守卫拒绝 → 不得创建 team 目录（变异点：守卫失效则目录被创建 → 红）
        assertThat(Files.exists(tempDir.resolve("teams/new-team")))
                .as("守卫拒绝后不得创建 team 目录")
                .isFalse();
    }

    @Test
    @DisplayName("创建成功写会话 store（A4：setTeamContext 落 sessions.team_context 列，跨工具/回合存活）")
    void create_setTeamContext_writesToSessionStore() {
        // WHY: [A4] TeamCreate 须把 teamContext 写会话列（对齐 CC appState.teamContext 稳定态），
        //   否则 SendMessage/TeamDelete 跨回合读不到 team（LlmAgentLoop per-request no-op appState）。
        //   变异点：只写 appState → store 无落库 → 跨工具断链。
        SessionService sessionService = mock(SessionService.class);
        TeamCreateTool tool = newTool();
        ReflectionTestUtils.setField(tool, "sessionService", sessionService);

        Map<String, Object> appState = new LinkedHashMap<>();
        AgentToolResult<?> result = tool.execute(block("TeamCreate", input("team_name", "store-team")),
                appStateCtx(appState));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(sessionService).setTeamContext(eq("00000000-0000-0000-0000-000000000001"), captor.capture());
        assertThat(captor.getValue().get("teamName")).isEqualTo("store-team");
        assertThat(captor.getValue().get("leadAgentId")).isEqualTo("team-lead@store-team");
        assertThat(captor.getValue().get("teammates")).isInstanceOf(Map.class);
    }

    // ═══════════════════════ [T2] leader 权限表面按会话注册 ═══════════════════════

    @Test
    @DisplayName("[T2] 建 team 成功 ⇒ 按 leadSessionId 注册 leader 确认表面（分桶键与 dispatcher 读侧同键）")
    void create_registersLeaderConfirmSurfacePerSession() throws Exception {
        // WHY: LeaderPermissionBridge 改按会话分桶后，「谁在什么时候注册本会话的表面」必须落地到
        //   生产入口，否则分桶表恒空 ⇒ SwarmLeaderPermissionDispatcher 取不到 setter ⇒ leader inbox
        //   权限请求恒自动 deny（团队权限断裂）。本用例钉死注册点 = 建 team 成功（leader 角色取得时），
        //   且桶键 == config.json leadSessionId（dispatcher 用同一键查）。
        //   变异点：删掉 TeamCreateTool 里的 registerSetter 调用 ⇒ 本断言 RED。
        TeamCreateTool tool = newTool();
        LeaderPermissionConfirmBridge bridge = new LeaderPermissionConfirmBridge();
        bridge.setWs(mock(org.springframework.messaging.simp.SimpMessagingTemplate.class));
        tool.setLeaderPermissionConfirmBridge(bridge);

        AgentToolResult<?> result = tool.execute(block("TeamCreate", input("team_name", "t2-team")),
                appStateCtx(new LinkedHashMap<>()));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).as("创建 team 不应失败").isFalse();

        // config 落盘的 leadSessionId 与本用例 ctx.sessionId 同源
        String leadSessionId = new TeamHelpers().leadSessionId("t2-team");
        assertThat(leadSessionId).isEqualTo(APPSTATE_CTX_SESSION_ID);

        assertThat(LeaderPermissionBridge.getLeaderToolUseConfirmQueue(leadSessionId))
                .as("建 team 后本 leader 会话必须已注册确认表面（否则 dispatcher 恒自动 deny）")
                .isNotNull();
        assertThat(LeaderPermissionBridge.getLeaderToolUseConfirmQueue("some-other-session"))
                .as("⛔ 注册不得全局可见（会话分桶）").isNull();

        bridge.unregisterSetter(leadSessionId);
    }

    @Test
    @DisplayName("[T2] 无显式会话（ctx=null）⇒ 不注册、不造幻影会话桶")
    void create_withoutExplicitSession_skipsRegistration() {
        // WHY: ctx.sessionId()==null 时 config 的 leadSessionId 为空串（[fail-loud 2026-09-22]
        //   已不再用任务级兜底键顶替 —— 见 create_withoutSession_doesNotFabricateLeadSessionId），
        //   它**不是会话键**；若拿它当桶键注册，会造出一个**永远无人清理**（会话删除钩子按真实
        //   会话 id 清）也无人查询的幻影桶 ⇒ 常驻 JVM 泄漏。故此时必须跳过注册（fail-loud info 留痕）。
        TeamCreateTool tool = newTool();
        LeaderPermissionConfirmBridge bridge = new LeaderPermissionConfirmBridge();
        bridge.setWs(mock(org.springframework.messaging.simp.SimpMessagingTemplate.class));
        tool.setLeaderPermissionConfirmBridge(bridge);

        int before = LeaderPermissionBridge.toolUseConfirmQueueSessionBucketCount();
        AgentToolResult<?> result = tool.execute(block("TeamCreate", input("team_name", "nosess-team")), null);
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();

        assertThat(LeaderPermissionBridge.toolUseConfirmQueueSessionBucketCount())
                .as("无会话 → 桶数不得增加（⛔ 不造幻影会话桶）").isEqualTo(before);
    }

    // ═════════════════ [fail-loud 2026-09-22] 无会话不得伪造 leadSessionId ═════════════════

    @Test
    @DisplayName("[fail-loud] 无显式会话 ⇒ config.json leadSessionId 落空串（⛔ 不落进程级兜底 UUID）")
    void create_withoutSession_doesNotFabricateLeadSessionId() throws Exception {
        // WHY（规则九）：config.json 的 leadSessionId 是**会话槽**，且是 TeamController.spawnMember
        //   反查出 teammate 的 Leader 会话（SpawnContext.parentSessionId）的唯一来源。改前此处回落
        //   TaskService.getTaskListId(null, identity)（最终 = **进程级共享 UUID**，形态上是一个合格
        //   UUID，永不返回 null/blank）并 node.put 落盘 ⇒ 等于**在磁盘上制造**一个「看起来合法」的
        //   伪会话键：此后 spawnMember 拿它当 Leader 会话喂下去，只会让下游 CwdResolution/
        //   SessionStorage 按「未知会话」在链路深处炸（报错点离根因很远）。
        //   变异点：把 leaderSessionKey 改回 TaskService.getTaskListId(null, ctx.teammateIdentity())
        //   ⇒ 本断言 RED（config 里出现 non-blank 的伪键）。
        String teamName = "nolead-team";
        TeamCreateTool tool = newTool();

        AgentToolResult<?> result = tool.execute(block("TeamCreate", input("team_name", teamName)), null);
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
                .as("无会话建 team 本身不失败（只在**会话槽**上不伪造）").isFalse();

        TeamHelpers helpers = new TeamHelpers();
        String raw = helpers.readConfig(teamName);
        assertThat(raw).as("config.json 必须已落盘").isNotNull();
        assertThat(raw)
                .as("⭐ 无会话 ⇒ leadSessionId 必须是**空串**（确定性、非伪造，下游 isBlank 可直接识别）")
                .contains("\"leadSessionId\":\"\"");
        assertThat(helpers.leadSessionId(teamName))
                .as("按 team 反查会话键必须得 null（⛔ 不是「看似合法的伪会话键」）").isNull();
        assertThat(raw)
                .as("⛔ 尤其不得落 TaskService 的进程级兜底 UUID（改前行为）")
                .doesNotContain(TaskService.getTaskListId(null, null));

        // 对侧（真会话）由既有用例 taskListIdExplicit 覆盖：config 落 ctx.sessionId() 本身
        //   （TaskListIdExplicitResolutionTest 断言5 / 本类 create_registersLeaderConfirmSurfacePerSession）。
    }
}
