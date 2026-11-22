package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.messaging.MessageRecipientResolver;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.team.InProcessTeammateTaskState;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B2 · SendMessageTool 的 teammate 分支：live → 入队到常驻 wait 循环（下一 tool 轮消费），
 * 退役 → in-process 重拉，未知名 → not-found；⛔ 任何一条都不写 teammate 文件邮箱。
 *
 * <p>WHY（验证意图）：teammate 的唯一消费点是
 * {@link AutonomousAgentLoop#waitForNextPromptOrShutdown()} 的第 1 优先级
 * {@code pendingUserMessages}（公共入口 {@link AutonomousAgentLoop#injectUserMessage}）。
 * 若把消息写进 {@link AgentNameRegistry} 待办队列（子代理才 drain），teammate 永远看不到
 * ——「入队成功、消息静默滞留」。本用例直接断言消息进了 loop 的镜像、且 inbox 文件不存在。
 */
@DisplayName("SendMessageTool · teammate 路由与投递（队列 / 重拉 / 不写邮箱）")
class SendMessageToolTeammateTest {

    private static final String TEAM = "research-team";
    private static final String SELF = "sess-aaaaaaa1";
    private static final String AGENT_ID = "mate@" + TEAM;

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    private SendMessageTool tool;
    private AgentNameRegistry agents;
    private AutonomousAgentLoop loop;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        SessionCwdHolder.set(SELF, tempDir.toAbsolutePath().normalize().toString());
        // BackgroundTask 的 outputFile 走 taskOutputPath → getOriginalCwdLayer（有会话时必须可解，
        // 否则 fail-loud）；把 originalCwd 槽也钉到临时目录。
        SessionCwdHolder.setOriginalCwd(SELF, tempDir.toAbsolutePath().normalize().toString());
        writeTeamConfig("team-lead", "mate");

        agents = new AgentNameRegistry();
        // 模拟 B1：spawn 时按 (lead 会话, 裸名) 注册，agentId = name@team
        agents.register(SELF, "mate", AGENT_ID);

        loop = newLoop();
        SpawnInProcess spawn = new SpawnInProcess((TaskFrameworkService) null);
        spawn.registry().register(loop.taskState(), loop, null);

        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.getTask(AGENT_ID)).thenReturn(Optional.of(runningTask()));

        MessageRecipientResolver resolver = new MessageRecipientResolver();
        resolver.setAgentNameRegistry(agents);

        tool = new SendMessageTool(new TeamHelpers());
        tool.setRecipientResolver(resolver);
        tool.setAgentNameRegistry(agents);
        tool.setSpawnInProcess(spawn);
        tool.setBackgroundTaskRunner(runner);
        tool.setSessionDirResolver(id -> tempDir);
        // B3 的重拉实现：生产 = SpawnInProcessTeammateRelauncher；这里用桩验证 B2 的
        // 「退役 → 重拉」分流（不是伪造成功：无 relauncher 时 resumeTeammate 会返回
        // resume_failed，见生产代码）。桩按 B3 合同返回实际重放条数。
        tool.setTeammateRelauncher((name, team, session, agentType, prompt, resumeMessages) ->
            com.nexusai.application.agent.subagent.TeammateRelauncher.TeammateRelaunchResult.resumed(
                name + "@" + team, resumeMessages == null ? 0 : resumeMessages.size()));
    }

    @AfterEach
    void tearDown() {
        SessionCwdHolder.clear(SELF);
        SessionCwdHolder.clearOriginalCwd(SELF);
        TaskSystemConfig.clearForTest();
        System.clearProperty("nexusai.task.config-dir");
    }

    @Test
    @DisplayName("live teammate → 入队到 wait 循环（下一 tool 轮），不写 inbox、不写子代理队列")
    void liveTeammate_queuesToNextToolRound_notMailbox() throws Exception {
        AgentToolResult<?> result = tool.execute(block("mate", "hello mate", "greeting"),
            teamCtx(teamContext()));

        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).isTrue();
        assertThat(out.get("delivery").asText()).isEqualTo("queued");
        assertThat(out.get("agentId").asText()).isEqualTo(AGENT_ID);
        assertThat(out.get("message").asText()).isEqualTo(
            "Message queued for delivery to mate at its next tool round.");

        assertThat(loop.messages())
            .as("消息必须进入 teammate wait 循环的 pendingUserMessages 镜像")
            .anyMatch(m -> m.contains("<teammate-message") && m.contains("hello mate"));
        assertThat(inboxPath("mate")).as("teammate 投递不得再写文件邮箱").doesNotExist();
        assertThat(agents.hasPending(AGENT_ID))
            .as("不得双写 AgentNameRegistry 队列（teammate 无消费者）").isFalse();
    }

    @Test
    @DisplayName("退役 teammate → 走 in-process 重拉分支（不是 subagent transcript resume），不写 inbox")
    void retiredTeammate_relaunches() throws Exception {
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.getTask(AGENT_ID)).thenReturn(Optional.empty());
        tool.setBackgroundTaskRunner(runner);
        agents.retireAgent(SELF, "mate", AGENT_ID, "completed", null);

        AgentToolResult<?> result = tool.execute(block("mate", "continue please", "continue"),
            teamCtx(teamContext()));

        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).isTrue();
        assertThat(out.get("agentId").asText()).isEqualTo(AGENT_ID);
        assertThat(out.get("message").asText())
            .contains("was not running; resumed it as an in-process teammate");
        assertThat(inboxPath("mate")).as("重拉分支不得写文件邮箱").doesNotExist();
    }

    @Test
    @DisplayName("未知名 → not-found Error，不写任何 mailbox")
    void unknownName_notFound_noMailbox() {
        AgentToolResult<?> result = tool.execute(block("ghost", "hi", "hi"), teamCtx(teamContext()));

        assertThat(String.valueOf(result.data()))
            .startsWith("Error: No agent named 'ghost' is reachable");
        assertThat(inboxPath("ghost")).doesNotExist();
    }

    @Test
    @DisplayName("team-lead → 仍写 lead inbox（不是可拉起的代理，保留邮箱语义）")
    void teamLead_staysMailbox() throws Exception {
        AgentToolResult<?> result = tool.execute(block("team-lead", "hello lead", "status"),
            teamCtx(teamContext()));

        JsonNode out = json.readTree(String.valueOf(result.data()));
        assertThat(out.get("success").asBoolean()).isTrue();
        assertThat(out.get("message").asText()).isEqualTo("Message sent to team-lead's inbox");
        assertThat(inboxPath("team-lead")).exists();
        assertThat(Files.readString(inboxPath("team-lead"))).contains("hello lead");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private ToolUseBlock block(String to, String message, String summary) {
        ObjectNode input = json.createObjectNode();
        input.put("to", to);
        input.put("message", message);
        input.put("summary", summary);
        return new ToolUseBlock(UUID.randomUUID().toString(), "SendMessage", input);
    }

    private Path inboxPath(String agent) {
        return tempDir.resolve("teams").resolve(TEAM).resolve("inboxes").resolve(agent + ".json");
    }

    private Map<String, Object> teamContext() {
        Map<String, Object> appState = new LinkedHashMap<>();
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("teamName", TEAM);
        tc.put("leadAgentId", "team-lead@" + TEAM);
        Map<String, Object> mate = new LinkedHashMap<>();
        mate.put("name", "mate");
        Map<String, Object> mates = new LinkedHashMap<>();
        mates.put(AGENT_ID, mate);
        tc.put("teammates", mates);
        appState.put("teamContext", tc);
        return appState;
    }

    private void writeTeamConfig(String... memberNames) {
        TeamHelpers helpers = new TeamHelpers();
        ObjectNode config = json.createObjectNode();
        config.put("name", TEAM);
        config.put("createdAt", System.currentTimeMillis());
        config.put("leadAgentId", "team-lead@" + TEAM);
        config.put("leadSessionId", SELF);
        ArrayNode members = config.putArray("members");
        for (String name : memberNames) {
            ObjectNode member = members.addObject();
            member.put("agentId", name + "@" + TEAM);
            member.put("name", name);
            member.put("joinedAt", System.currentTimeMillis());
            member.put("tmuxPaneId", "");
            member.set("subscriptions", json.createArrayNode());
        }
        helpers.writeConfig(TEAM, config.toString());
    }

    private ToolUseContext teamCtx(Map<String, Object> appState) {
        return ToolUseContext.of(
            com.nexusai.application.agent.subagent.AgentContext.createAgentId(), SELF,
            PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP,
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

    private AutonomousAgentLoop newLoop() {
        AutonomousAgentLoop l = new AutonomousAgentLoop();
        l.setAgentId(AGENT_ID);
        l.setAgentName("mate");
        l.setTeamName(TEAM);
        l.setTaskId("t12345678");
        l.setTaskListId(SELF);
        l.setTaskState(taskState("t12345678"));
        return l;
    }

    private InProcessTeammateTaskState taskState(String taskId) {
        TeammateIdentity identity = new TeammateIdentity(
            AGENT_ID, "mate", TEAM, null, false, SELF);
        return new InProcessTeammateTaskState(
            taskId, identity, "initial", null,
            false, "default", null,
            new ArrayList<>(), new HashSet<>(), new ArrayList<>(),
            false, false, 0, 0,
            null, null, null, new ArrayList<>());
    }

    private BackgroundTask runningTask() {
        return new BackgroundTask(
            "t12345678", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING,
            "mate: initial", null, System.currentTimeMillis(), null, null,
            tempDir.resolve("t12345678.output").toString(), 0L, false, null, true);
    }
}
