package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.util.AbortControllerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8-03 · DEL-31/32 后 3 工具改投文件型 mailbox 的契约测试。
 *
 * <p>WHY 本测试验证意图（规则九，对齐 CC 实际 TS 源码行为，不信注释）：
 * <ul>
 *   <li>{@link SendMessageTool} 必须写 {@link com.nexusai.application.agent.team.TeammateMailbox}
 *       文件型收件箱，且信封 {@code text} = 原始内容（CC SendMessageTool.ts:140-161 handleMessage）
 *       而非 TeamMessage 全字段 JSON —— 这是 S-9 修正目标：CC 跨进程消费侧
 *       （attachments.ts:3532）读 from/text 即可消费；</li>
 *   <li>shutdown_request 类型信封 {@code text} = 结构化消息 JSON
 *       {@code {type:'shutdown_request', requestId, from, reason, timestamp}}
 *       （CC SendMessageTool.ts:276-296 handleShutdownRequest + teammateMailbox.ts:720-728 schema）；</li>
 *   <li>{@link TeamCreateTool} 必须写 team 配置文件（CC TeamCreateTool.ts:157-166 writeTeamFileAsync
 *       → {@code {configHome}/teams/{team}/config.json}，teamHelpers.ts:66-68 getTeamFilePath）；</li>
 *   <li>{@link TeamDeleteTool} 必须删除 team 目录（CC TeamDeleteTool.ts cleanupTeamDirectories）。</li>
 * </ul>
 *
 * <p>RED 证据：本测试引用 {@code new SendMessageTool()} 无参构造 / {@code new TeamCreateTool(TeamHelpers, TaskService)} /
 * {@code new TeamDeleteTool(TeamHelpers)} —— DEL-31/32 改造前这些构造不存在（旧构造注入 TeamMessageBus），
 * test-compile 失败即 RED。
 */
class TeamToolsFileMailboxTest {

    private static final String TEAM = "research-team";

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // configHome 指向临时目录: {configHome}/teams/{team}/inboxes/{agent}.json
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
    }

    private Path inboxPath(String agent) {
        return tempDir.resolve("teams").resolve(TEAM).resolve("inboxes").resolve(agent + ".json");
    }

    private Path teamConfigPath() {
        return tempDir.resolve("teams").resolve(TEAM).resolve("config.json");
    }

    private SendMessageTool newSendMessageTool() {
        return new SendMessageTool(new TeamHelpers());
    }

    private ToolUseBlock block(String name, ObjectNode input) {
        return new ToolUseBlock(UUID.randomUUID().toString(), name, input);
    }

    private ToolUseContext ctx() {
        return ToolUseContext.of(UUID.randomUUID(), "");
    }

    /**
     * 带 appState 桥的 ToolUseContext · 对齐 CC ToolUseContext.getAppState/setAppState
     * （TeamCreateTool 写 teamContext / TeamDeleteTool 读 teamContext 用）。
     */
    private ToolUseContext appStateCtx(Map<String, Object> appState) {
        return ToolUseContext.of(
                UUID.randomUUID(), "",
                PermissionMode.DEFAULT,
                java.util.List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP,
                java.util.List.of(), null, PermissionMode.DEFAULT, Map.of(),
                false, "", null, null, null, null,
                prev -> appState,
                updater -> {
                    Map<String, Object> next = updater.apply(appState);
                    appState.clear();
                    appState.putAll(next);
                },
                null, null);
    }

    /** appState 内存载体（LinkedHashMap 可变 · getAppState/setAppState 桥共享同一实例）。 */
    private Map<String, Object> appState() {
        return new LinkedHashMap<>();
    }

    /**
     * appState.teamContext 结构 · 对齐 CC TeamCreateTool.ts:194-212 setAppState(teamContext) 落盘形状
     * （IMP-G1 已写 teamContext.teamName / TeamDeleteTool 读）。SendMessage 经
     * {@code getTeamName(appState.teamContext)}（SendMessageTool.ts:156）取 team 名。
     */
    private Map<String, Object> teamContext() {
        Map<String, Object> teamContext = new LinkedHashMap<>();
        teamContext.put("teamName", TEAM);
        teamContext.put("leadAgentId", "team-lead@" + TEAM);
        teamContext.put("teammates", new LinkedHashMap<>());
        return teamContext;
    }

    /** 带 teamContext 的 appStateCtx · SendMessage 解析 team 名（CC getTeamName(appState.teamContext)）。 */
    private ToolUseContext teamCtx() {
        Map<String, Object> appState = appState();
        appState.put("teamContext", teamContext());
        return appStateCtx(appState);
    }

    @Test
    void sendMessage_plainText_writesCcEnvelopeRawTextToFile() throws Exception {
        // WHY: CC handleMessage 信封 text = 原始内容（非 TeamMessage 全字段 JSON）——
        //      CC 跨进程消费侧（attachments.ts:3532）从 from/text 直接消费。
        // IMP-G2（⊕-08/⊕-09）：to 为裸 teammate 名（name@team 已拒），team 名取 teamContext；
        //      string 消息走 handleMessage（SendMessageTool.ts:876-881），信封含 summary。
        ObjectNode input = json.createObjectNode();
        input.put("to", "mate");
        input.put("message", "hello mate");
        input.put("summary", "hello mate");
        SendMessageTool tool = newSendMessageTool();
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(inboxPath("mate")).exists();
        JsonNode messages = json.readTree(Files.readString(inboxPath("mate")));
        assertThat(messages).hasSize(1);
        JsonNode envelope = messages.get(0);
        assertThat(envelope.get("text").asText()).isEqualTo("hello mate");
        assertThat(envelope.get("summary").asText()).isEqualTo("hello mate");
        assertThat(envelope.get("from").asText()).isEqualTo("team-lead"); // getAgentName() null → TEAM_LEAD_NAME
        assertThat(envelope.get("read").asBoolean()).isFalse();
        assertThat(envelope.get("timestamp").asText()).isNotBlank();
    }

    @Test
    void sendMessage_shutdownRequest_writesStructuredJsonToFile() throws Exception {
        // WHY: CC handleShutdownRequest text = JSON.stringify(shutdown_request 结构化消息)
        //      （teammateMailbox.ts:831-863 sendShutdownRequestToMailbox 同款）。
        // IMP-G2（⊕-08）：message 为结构化对象 {type:'shutdown_request', reason}（SendMessageTool.ts:46-51）。
        ObjectNode input = json.createObjectNode();
        input.put("to", "mate");
        ObjectNode msg = input.putObject("message");
        msg.put("type", "shutdown_request");
        msg.put("reason", "wrap up");
        SendMessageTool tool = newSendMessageTool();
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode messages = json.readTree(Files.readString(inboxPath("mate")));
        JsonNode text = json.readTree(messages.get(0).get("text").asText());
        assertThat(text.get("type").asText()).isEqualTo("shutdown_request");
        assertThat(text.get("requestId").asText()).isNotBlank();
        assertThat(text.get("from").asText()).isNotBlank();
        assertThat(text.get("reason").asText()).isEqualTo("wrap up");
        assertThat(text.get("timestamp").asText()).isNotBlank();
    }

    @Test
    void teamCreate_writesTeamConfigFile() throws Exception {
        // WHY: CC TeamCreateTool writeTeamFileAsync → {configHome}/teams/{team}/config.json，
        //      team 配置落盘供 swarm 跨进程发现（teamHelpers.ts:66-68 getTeamFilePath）。
        //      config.json 结构对齐 CC TeamFile（teamHelpers.ts:64-90）：name/createdAt/members[]，
        //      而非旧版 team_name/created_at 漂移键（D-7 结构漂移）。
        TeamHelpers helpers = new TeamHelpers();
        TeamCreateTool tool = new TeamCreateTool(helpers, new TaskService());
        ObjectNode input = json.createObjectNode();
        input.put("team_name", TEAM);
        AgentToolResult<?> result = tool.execute(block("TeamCreate", input), null);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(teamConfigPath()).exists();
        JsonNode config = json.readTree(Files.readString(teamConfigPath()));

        // CC TeamFile.name（camelCase）· 旧 team_name 键已移除
        assertThat(config.get("name").asText()).isEqualTo(TEAM);
        assertThat(config.has("team_name")).as("旧漂移键 team_name 不应存在").isFalse();

        // CC TeamFile.createdAt 为 number（epoch 毫秒），非 ISO 字符串
        assertThat(config.get("createdAt").isNumber()).as("createdAt 应为 number 类型（CC Date.now()）").isTrue();
        assertThat(config.get("createdAt").asLong()).isPositive();
        assertThat(config.has("created_at")).as("旧漂移键 created_at 不应存在").isFalse();

        // CC TeamFile.members 数组（lead 成员）· 缺失则 team 成员模型残缺
        assertThat(config.get("members").isArray()).as("config.json 必须含 members 数组").isTrue();
        JsonNode lead = config.get("members").get(0);
        // IMP-G1：team-lead@ 前缀（CC TeamCreateTool.ts:146 formatAgentId(TEAM_LEAD_NAME, name)）
        assertThat(lead.get("agentId").asText()).isEqualTo("team-lead@" + TEAM);
        assertThat(lead.get("name").asText()).isEqualTo("team-lead"); // CC TEAM_LEAD_NAME（constants.ts:1）
        assertThat(lead.get("joinedAt").isNumber()).as("member.joinedAt 应为 number（CC Date.now()）").isTrue();
        assertThat(lead.get("tmuxPaneId").asText()).isEmpty();
        assertThat(lead.get("subscriptions").isArray()).isTrue();
    }

    @Test
    void teamDelete_removesTeamConfigDir() throws Exception {
        // WHY: CC TeamDeleteTool cleanupTeamDirectories 删除 team 目录（含 config.json + inboxes/）
        //      —— 用户拍板"清理 team 目录"语义，不残留磁盘。
        // IMP-G1：team 名从 appState.teamContext 取（CC TeamDeleteTool.ts:74），不再有 team_name 输入。
        TeamHelpers helpers = new TeamHelpers();
        Map<String, Object> appState = appState();
        TeamCreateTool create = new TeamCreateTool(helpers, new TaskService());
        ObjectNode input = json.createObjectNode();
        input.put("team_name", TEAM);
        create.execute(block("TeamCreate", input), appStateCtx(appState));
        assertThat(teamConfigPath()).exists();
        // create 已写 teamContext → delete 从 context 取 team 名
        assertThat(appState.get("teamContext")).as("TeamCreate 必须 setAppState(teamContext)").isNotNull();

        TeamDeleteTool del = new TeamDeleteTool(helpers);
        AgentToolResult<?> result = del.execute(block("TeamDelete", json.createObjectNode()), appStateCtx(appState));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(teamConfigPath()).doesNotExist();
        // CC TeamDeleteTool.ts:118-124 清 teamContext
        assertThat(appState.get("teamContext")).as("TeamDelete 必须清 teamContext").isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // W8-GAP-02 · shutdown_response approve/reject/abort 路径
    // ════════════════════════════════════════════════════════════════════

    /** 注册一个 agentName=mate 的 in-process teammate loop（含生命周期 abortController）。 */
    private SpawnInProcess spawnMateLoop(String taskId, AbortControllerFactory.AbortControllerRef abort) {
        TaskFrameworkService tfs = new TaskFrameworkService(new SdkEventQueue());
        SpawnInProcess spawner = new SpawnInProcess(tfs);
        InProcessTeammateTaskState state = new InProcessTeammateTaskState(
            taskId,
            new TeammateIdentity("mate@" + TEAM, "mate", TEAM, null, false, "parent-session"),
            "You are mate", null, false, "default", null,
            new ArrayList<>(), new HashSet<>(), new ArrayList<>(),
            false, false, 0, 0,
            abort, null, null, new ArrayList<>());
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentId("mate@" + TEAM);
        loop.setAgentName("mate");
        loop.setTeamName(TEAM);
        loop.setTaskId(taskId);
        loop.setAbortController(abort);
        loop.setTaskState(state);
        spawner.registry().register(state, loop, null);
        return spawner;
    }

    @Test
    void shutdownResponse_approve_writesShutdownApprovedAndAbortsTeammate() throws Exception {
        // WHY: CC handleShutdownApproval（SendMessageTool.ts:330-366）——批准必须①先写 shutdown_approved
        //      到 team-lead mailbox（leader 侧知晓退出，:330-346）、②abort 本 in-process teammate 的
        //      生命周期 abortController（:356-357，审批即退出整个 teammate，非本轮 work abort）。缺①则
        //      leader 无感知；缺②则模型批准后队友不退出（循环不终止）——两者都是 shutdown 闭环失败。
        // IMP-G2（⊕-08）：shutdown_response 为结构化 message {type, request_id, approve}（SendMessageTool.ts:52-57）。
        AbortControllerFactory.AbortControllerRef abort = AbortControllerFactory.create();
        SendMessageTool tool = newSendMessageTool();
        tool.setSpawnInProcess(spawnMateLoop("t00000001", abort));

        ObjectNode input = json.createObjectNode();
        input.put("to", "team-lead");
        ObjectNode msg = input.putObject("message");
        msg.put("type", "shutdown_response");
        msg.put("approve", true);
        msg.put("request_id", "shutdown-1700000000000@mate");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode text = json.readTree(
            json.readTree(Files.readString(inboxPath("team-lead"))).get(0).get("text").asText());
        assertThat(text.get("type").asText()).isEqualTo("shutdown_approved");
        assertThat(text.get("requestId").asText()).isEqualTo("shutdown-1700000000000@mate");
        assertThat(text.get("from").asText()).isEqualTo("mate");
        assertThat(abort.aborted().get()).isTrue();
    }

    @Test
    void shutdownResponse_approve_withoutRegistry_stillSucceeds() throws Exception {
        // WHY: CC :362-364（找不到 task/abortController 仅 warning）+ :392-398（仍返回 success）——
        //      审批确认必须送达 leader（mailbox 写成功即不可失败），任务定位失败不允许吞掉确认。
        SendMessageTool tool = newSendMessageTool(); // 未注入 spawnInProcess

        ObjectNode input = json.createObjectNode();
        input.put("to", "team-lead");
        ObjectNode msg = input.putObject("message");
        msg.put("type", "shutdown_response");
        msg.put("approve", true);
        msg.put("request_id", "shutdown-1700000000000@mate");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode text = json.readTree(
            json.readTree(Files.readString(inboxPath("team-lead"))).get(0).get("text").asText());
        assertThat(text.get("type").asText()).isEqualTo("shutdown_approved");
    }

    @Test
    void shutdownResponse_reject_writesShutdownRejectedWithoutAbort() throws Exception {
        // WHY: CC handleShutdownRejection（SendMessageTool.ts:408-423）——拒绝只写 shutdown_rejected
        //      到 team-lead mailbox（reason 回显给 leader），不 abort（拒绝 = 队友继续工作）。
        AbortControllerFactory.AbortControllerRef abort = AbortControllerFactory.create();
        SendMessageTool tool = newSendMessageTool();
        tool.setSpawnInProcess(spawnMateLoop("t00000002", abort));

        ObjectNode input = json.createObjectNode();
        input.put("to", "team-lead");
        ObjectNode msg = input.putObject("message");
        msg.put("type", "shutdown_response");
        msg.put("approve", false);
        msg.put("request_id", "shutdown-1700000000000@mate");
        msg.put("reason", "busy with a task");
        AgentToolResult<?> result = tool.execute(block("SendMessage", input), teamCtx());

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode text = json.readTree(
            json.readTree(Files.readString(inboxPath("team-lead"))).get(0).get("text").asText());
        assertThat(text.get("type").asText()).isEqualTo("shutdown_rejected");
        assertThat(text.get("requestId").asText()).isEqualTo("shutdown-1700000000000@mate");
        assertThat(text.get("reason").asText()).isEqualTo("busy with a task");
        assertThat(abort.aborted().get()).isFalse();
    }

    @Test
    void shutdownResponse_wrongRecipient_isRejected() {
        // WHY: CC validateInput :694-703 shutdown_response 必须发给 team-lead（其他收件人 = 契约违规）。
        // IMP-G2（⊕-09/⊕-08）：校验在 validateInput 语义层（toolExecution.ts:683-733 阶段），execute 不再内联。
        SendMessageTool tool = newSendMessageTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "mate");
        ObjectNode msg = input.putObject("message");
        msg.put("type", "shutdown_response");
        msg.put("approve", true);
        msg.put("request_id", "shutdown-1700000000000@mate");
        Tool.ValidationResult result = tool.validateInput(input, ctx());
        assertThat(result.ok()).isFalse();
    }

    @Test
    void shutdownResponse_rejectWithoutReason_isRejected() {
        // WHY: CC validateInput :705-715 拒绝时必须带非空 reason（否则 leader 无从知晓拒绝原因）。
        SendMessageTool tool = newSendMessageTool();
        ObjectNode input = json.createObjectNode();
        input.put("to", "team-lead");
        ObjectNode msg = input.putObject("message");
        msg.put("type", "shutdown_response");
        msg.put("approve", false);
        msg.put("request_id", "shutdown-1700000000000@mate");
        Tool.ValidationResult result = tool.validateInput(input, ctx());
        assertThat(result.ok()).isFalse();
    }
}
