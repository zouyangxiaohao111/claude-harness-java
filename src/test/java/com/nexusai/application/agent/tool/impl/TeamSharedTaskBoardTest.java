package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.subagent.AutonomousAgentLoop;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.InProcessTeammateTaskState;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.util.AbortControllerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P0-2 · B1] 队长与队员必须在**同一块任务白板**上 —— 团队模式主干判据。
 *
 * <p><b>WHY（规则九 · 意图）</b>：任务板目录 = {@code {configHome}/tasks/{taskListId}}
 * （{@code TaskService.getTaskListId}）。队长侧走解析优先级 4（{@code leaderTeamNames} 会话桶），
 * 队员侧走优先级 2（{@code identity.teamName()}）。改动前 {@code TeamCreateTool} 调的是
 * {@code setLeaderTeamName(taskListId)} **1 参**重载 ⇒ 会话键恒 null ⇒ WARN 跳过 ⇒ **优先级 4
 * 在生产无任何写入点** ⇒ 队长落 {@code {tasks}/{sessionId}}、队员落 {@code {tasks}/{team}}
 * ⇒ 队长 TaskList 看不到队员任务、队员也认领不到队长的任务（主干断裂）。
 *
 * <p>本测试锁定的正是这条主干，而不只是「某个方法被调用过」：
 * <ol>
 *   <li>建队后队长的 taskListId = {@code sanitizeName(team)}（不再是会话 id）；</li>
 *   <li>同队队员（identity.teamName = team）解析到**同一个** taskListId；</li>
 *   <li>磁盘上两侧任务写在**同一目录**；</li>
 *   <li>队长 TaskList 能看见队员建的任务（同一块白板的可观察结果）；</li>
 *   <li>解散后队长回到 {@code {tasks}/{sessionId}}，且 {@code tasks/{team}} 目录已消失。</li>
 * </ol>
 */
class TeamSharedTaskBoardTest {

    /** 队长会话（与 team 名不同 ⇒ 可鉴别「落 team 目录」还是「落 session 目录」）。 */
    private static final String LEAD_SESSION = "sess-p02-lead";
    /** 队员会话（独立于队长会话 —— 队员侧只能靠 identity 归因）。 */
    private static final String TEAMMATE_SESSION = "sess-p02-mate";
    /** 团队名（sanitizeName 后不变，便于比路径）。 */
    private static final String TEAM = "p02-team";

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
        System.setProperty("nexusai.experimental.agent-teams", "true");
    }

    @AfterEach
    void tearDown() {
        // leaderTeamNames 是 JVM 级 static map ⇒ 必须按本用例会话键清（0 参重载清不掉任何键）。
        TaskService.clearLeaderTeamName(LEAD_SESSION);
        TaskService.clearLeaderTeamName(TEAMMATE_SESSION);
        TaskSystemConfig.clearForTest();
    }

    private ToolUseContext leadCtx() {
        return ToolUseContext.of(UUID.randomUUID(), LEAD_SESSION);
    }

    private ToolUseContext teammateCtx() {
        return ToolUseContext.of(UUID.randomUUID(), TEAMMATE_SESSION)
            .withTeammateIdentity(new TeammateIdentity(
                "mate@" + TEAM, "mate", TEAM, null, false, LEAD_SESSION));
    }

    private static ToolUseBlock block(String name, ObjectNode input) {
        return new ToolUseBlock(UUID.randomUUID().toString(), name, input);
    }

    private ObjectNode taskInput(String subject) {
        ObjectNode node = json.createObjectNode();
        node.put("subject", subject);
        node.put("description", "d-" + subject);
        return node;
    }

    /** 建队 + 返回队名（建队后 leaderTeamNames 已登记 LEAD_SESSION → TEAM）。 */
    private void createTeam(TaskService svc) {
        TeamCreateTool create = new TeamCreateTool(new TeamHelpers(), svc);
        ObjectNode input = json.createObjectNode();
        input.put("team_name", TEAM);
        AgentToolResult<?> result = create.execute(block("TeamCreate", input), leadCtx());
        assertThat(String.valueOf(result.data()))
            .as("建队不应失败（前置：本用例断言的是建队后的任务板归属）")
            .contains("\"team_name\":\"" + TEAM + "\"");
    }

    @Test
    @DisplayName("主干判据: 队长与队员解析到**同一个** taskListId（= sanitizeName(team)，非会话 id）")
    void leaderAndTeammate_resolveToSameTaskListId() {
        TaskService svc = new TaskService();
        createTeam(svc);

        String leaderListId = TaskService.getTaskListId(LEAD_SESSION, null);
        assertThat(leaderListId)
            .as("队长侧必须落到 team 任务板（改动前落 LEAD_SESSION ⇒ 红）")
            .isEqualTo(TeamHelpers.sanitizeName(TEAM));

        ToolUseContext teammate = teammateCtx();
        String teammateListId = TaskService.getTaskListId(
            teammate.sessionId(), teammate.teammateIdentity());
        assertThat(teammateListId)
            .as("队员侧走优先级 2（identity.teamName()）—— 必须与队长同一 id")
            .isEqualTo(leaderListId);
    }

    @Test
    @DisplayName("主干判据: 两侧任务落**同一目录**，且队长 TaskList 看得见队员的任务")
    void leaderTaskList_seesTeammateTask_sameDirectory() throws Exception {
        TaskService svc = new TaskService();
        createTeam(svc);

        TaskCreateTool create = new TaskCreateTool(svc, null);
        create.execute(block("TaskCreate", taskInput("leader-task")), leadCtx());
        create.execute(block("TaskCreate", taskInput("mate-task")), teammateCtx());

        Path boardDir = tempDir.resolve("tasks").resolve(TeamHelpers.sanitizeName(TEAM));
        assertThat(boardDir).as("两侧任务必须落同一目录 {tasks}/{sanitizeName(team)}").isDirectory();
        assertThat(Files.list(boardDir).filter(p -> p.toString().endsWith(".json")).count())
            .as("队长 1 个 + 队员 1 个 = 同目录 2 个任务文件")
            .isEqualTo(2);

        // 队长 TaskList（工具路径，与 REST ?sessionId= 同源）必须看见队员的任务
        TaskListTool listTool = new TaskListTool(svc);
        ToolResult<?> listed = listTool.execute(block("TaskList", json.createObjectNode()), leadCtx());
        String rendered = String.valueOf(listed.data());
        assertThat(rendered).as("队长 TaskList 必须看得见队员建的任务").contains("mate-task");
        assertThat(rendered).as("队长自己的任务自然也在同一块板上").contains("leader-task");
    }

    @Test
    @DisplayName("⭐主干判据: 队员可认领**队长建的任务**（走真实 tryAutoClaimAndExecute 认领路径）")
    void teammate_claimsLeaderTask_viaRealAutoClaimPath() {
        // WHY（规则九）：B1「用一块白板」的**终点**不是「目录字符串相等」，而是「队员在真实认领
        //   路径上能拿到队长建的那条活」。原实现：队长写 {tasks}/{sessionId}、队员按优先级 2 读
        //   {tasks}/{team} ⇒ findAvailableTask 恒空 ⇒ 队员永远不干活（主干断裂的可观测后果）。
        //   本用例不直接 setOwner，而是驱动生产认领入口 AutonomousAgentLoop.tryAutoClaimAndExecute
        //   （= `AutonomousAgentLoop.java:645 getTaskListId(null, taskState.identity())` 那条腿）。
        TaskService svc = new TaskService();
        createTeam(svc);

        // 队长建任务（无 owner → 可被认领）
        TaskCreateTool create = new TaskCreateTool(svc, null);
        ToolResult<?> created =
            create.execute(block("TaskCreate", taskInput("leader-task")), leadCtx());
        assertThat(String.valueOf(created.data())).as("队长建任务不应失败").contains("leader-task");

        // 队员：真实认领入口
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setTaskService(svc);
        loop.setAgentName("mate");
        loop.setTaskState(new InProcessTeammateTaskState(
            "t-claim",
            new TeammateIdentity("mate@" + TEAM, "mate", TEAM, null, false, LEAD_SESSION),
            "work", null, false, "default", null,
            new ArrayList<>(), new HashSet<>(), new ArrayList<>(),
            false, false, 0, 0, AbortControllerFactory.create(), null, null, new ArrayList<>()));

        java.util.Optional<String> claimed = loop.tryAutoClaimAndExecute();

        assertThat(claimed)
            .as("⭐ 队员必须认领到队长建的任务（同目录 ⇒ findAvailableTask 命中）")
            .isPresent();

        // 认领的**可观测产物**（不是中间字段自称）：同一目录里的那条任务 owner/status 已落盘
        List<Task> board = svc.listTasks(TeamHelpers.sanitizeName(TEAM));
        assertThat(board).as("同一块白板上只有队长那一条任务").hasSize(1);
        assertThat(board.get(0).owner()).as("认领后 owner = 队员名").isEqualTo("mate");
        assertThat(board.get(0).status())
            .as("认领后置 in_progress（inProcessRunner.ts:630-633）")
            .isEqualTo(Task.TaskStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("解散后: 队长回到 {tasks}/{sessionId}，且 tasks/{team} 目录已消失")
    void afterTeamDelete_leaderFallsBackToSessionList() {
        TaskService svc = new TaskService();
        createTeam(svc);
        Path boardDir = tempDir.resolve("tasks").resolve(TeamHelpers.sanitizeName(TEAM));
        assertThat(boardDir).isDirectory();

        TeamDeleteTool del = new TeamDeleteTool(new TeamHelpers());
        AgentToolResult<?> result = del.deleteTeamByName(TEAM, LEAD_SESSION, 0, "test-p02");

        assertThat(String.valueOf(result.data()))
            .as("无活跃成员 ⇒ 解散应成功")
            .contains("Cleaned up directories and worktrees for team");
        assertThat(TaskService.getTaskListId(LEAD_SESSION, null))
            .as("解散后 leader 绑定必须被清（原 0 参 clearLeaderTeamName() 清不掉任何键）")
            .isEqualTo(LEAD_SESSION);
        assertThat(boardDir).as("team 任务目录必须被删").doesNotExist();
    }

    @Test
    @DisplayName("反向断言: 未建队时队长仍落 {tasks}/{sessionId}（本改动不得影响非团队路径）")
    void withoutTeam_leaderStillUsesSessionList() {
        TaskService svc = new TaskService();
        TaskCreateTool create = new TaskCreateTool(svc, null);
        create.execute(block("TaskCreate", taskInput("solo-task")), leadCtx());

        assertThat(TaskService.getTaskListId(LEAD_SESSION, null)).isEqualTo(LEAD_SESSION);
        assertThat(tempDir.resolve("tasks").resolve(LEAD_SESSION)).as("未建队 ⇒ 落会话目录").isDirectory();
    }
}
