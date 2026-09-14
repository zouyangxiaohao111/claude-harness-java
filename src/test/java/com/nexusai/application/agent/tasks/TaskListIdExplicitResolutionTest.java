package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.TaskCreateTool;
import com.nexusai.application.agent.tool.impl.TaskGetTool;
import com.nexusai.application.agent.tool.impl.TaskListTool;
import com.nexusai.application.agent.tool.impl.TaskUpdateTool;
import com.nexusai.application.agent.tool.impl.TeamCreateTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [S1-T11] 任务列表 ID 的「会话/身份」解析必须**显式**（无参重载已删除）。
 *
 * <p><b>WHY（规则九 · 意图）</b>：任务列表目录 = {@code {configHome}/tasks/{taskListId}}，是任务的
 * 物理归属轴。原实现存在无会话形参的重载 {@code TaskService.getTaskListId()}，它无任何会话/身份
 * 来源 ⇒ 1-5 级全 miss 后回退<b>全进程共享</b>的 UUID（{@code PROCESS_SESSION_ID}）。在单 JVM
 * 多会话（Web）形态下这与「会话级状态按会话隔离」直接冲突：A 会话的工具会把任务写进
 * **所有会话共享**的那一个桶，B 会话读到 A 的任务。
 *
 * <p>本测试锁定 5 件事：
 * <ol>
 *   <li><b>无参重载不存在</b>（反射枚举 {@code TaskService} 的 public static 方法）——把
 *       「静默退化为进程级共享列表」挡在<b>编译期</b>；</li>
 *   <li><b>显式会话形参 > 进程级兜底</b>：{@code getTaskListId("sess-A", null)} 返回 "sess-A"
 *       （**不是** {@code PROCESS_SESSION_ID}）；</li>
 *   <li><b>显式 teammate 身份 > 显式会话</b>（优先级 2 &gt; 优先级 6，对齐 CC tasks.ts:205-209）；</li>
 *   <li><b>工具侧真的把 ctx 传下去了</b>：TaskCreate/TaskGet/TaskList/TaskUpdate 四个工具的
 *       {@code execute(call, ctx)} 在 ctx 带 "sess-A" 时以 "sess-A" 解析列表 ID（不是进程兜底值）；</li>
 *   <li><b>TeamCreateTool 的归因键（cleanupKey）在 sessionId 非 null 时取 sessionId</b>
 *       （而不是 teammate 的 teamName / 进程兜底值）——该键进 config.json 的 leadSessionId，
 *       决定 {@code cleanupSessionTeams} 能否匹配（匹配不上就留下孤儿 team 配置）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>：断言 1 在无参重载存在时即红；断言 2/4/5 在把实参改回 {@code null}
 * （= 退回无参重载语义）时红。
 */
class TaskListIdExplicitResolutionTest {

    /** 本用例的会话（两个会话各自独立，用于「不是进程兜底」的鉴别）。 */
    private static final String SESSION_A = "sess-s1-t11-a";
    private static final String SESSION_B = "sess-s1-t11-b";

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void clearSyspropsAndSessionScopedState() {
        System.clearProperty("nexusai.taskListId");
        System.clearProperty("nexusai.team.name");
        System.clearProperty("nexusai.sessionId");
        TaskService.clearLeaderTeamName();
    }

    /** 显式 teammate 身份（纯数据 record；生产来源 = {@code ToolUseContext.teammateIdentity()}）。 */
    private static TeammateIdentity teammateIdentity(String teamName) {
        return new TeammateIdentity(
            "researcher@" + teamName, "researcher", teamName, "#ff0000", false, "leader-sess");
    }

    /** 只带 sessionId 的 TUC（TUC 的 compact ctor 强校验 sessionId 非 null）。 */
    private static ToolUseContext tuc(String sessionId) {
        return ToolUseContext.of(UUID.randomUUID(), sessionId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. 无参重载不存在（编译期守护）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("断言1: TaskService 不存在无参 getTaskListId()/getDefaultTaskListId() 重载（反射枚举）")
    void noNoArgOverloadExists() {
        // WHY: 无参重载无会话/身份来源 ⇒ 只能回退全进程共享 UUID。它**存在**本身即缺陷
        //   （任何新增调用点都会静默退化为跨会话共享）。删除后由编译器而非 reviewer 守护。
        assertThat(methodsNamed(TaskService.class, "getTaskListId"))
            .as("TaskService 上不得存在 getTaskListId 的 0 参重载")
            .allSatisfy(m -> assertThat(m.getParameterCount()).isGreaterThan(0));
        assertThat(methodsNamed(TaskSystemConfig.class, "getDefaultTaskListId"))
            .as("TaskSystemConfig 上不得存在 getDefaultTaskListId 的 0 参重载")
            .allSatisfy(m -> assertThat(m.getParameterCount()).isGreaterThan(0));
    }

    private static List<Method> methodsNamed(Class<?> type, String name) {
        return java.util.Arrays.stream(type.getDeclaredMethods())
            .filter(m -> m.getName().equals(name))
            .toList();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2/3. 解析语义（TaskService 层）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("断言2: 显式 sessionId ⇒ 返回该会话（**不得**回退进程级共享 UUID）")
    void explicitSession_winsOverProcessFallback() {
        Assumptions.assumeTrue(
            System.getenv("CLAUDE_CODE_TASK_LIST_ID") == null
                || System.getenv("CLAUDE_CODE_TASK_LIST_ID").isBlank(),
            "环境设置了 CLAUDE_CODE_TASK_LIST_ID（优先级 1），跳过默认回退断言");

        // 进程级兜底值 = 两形参皆 null 时的返回值（无会话上下文）
        String processFallback = TaskService.getTaskListId(null, null);
        assertThat(processFallback).isNotBlank();

        assertThat(TaskService.getTaskListId(SESSION_A, null))
            .as("显式会话形参（优先级 6）应压过进程级兜底")
            .isEqualTo(SESSION_A);
        assertThat(TaskService.getTaskListId(SESSION_A, null))
            .as("⭐ 不得返回进程级共享 UUID（跨会话共享桶）")
            .isNotEqualTo(processFallback);

        // 两会话各自独立（同一进程内不同会话不得同桶）
        assertThat(TaskService.getTaskListId(SESSION_B, null)).isEqualTo(SESSION_B);
        assertThat(TaskService.getTaskListId(SESSION_A, null))
            .isNotEqualTo(TaskService.getTaskListId(SESSION_B, null));
    }

    @Test
    @DisplayName("断言3: 显式 teammate identity.teamName（优先级 2）压过显式会话（优先级 6）")
    void explicitIdentity_winsOverSession() {
        Assumptions.assumeTrue(
            System.getenv("CLAUDE_CODE_TASK_LIST_ID") == null
                || System.getenv("CLAUDE_CODE_TASK_LIST_ID").isBlank(),
            "环境设置了 CLAUDE_CODE_TASK_LIST_ID（优先级 1），跳过优先级 2 断言");

        assertThat(TaskService.getTaskListId(SESSION_A, teammateIdentity("t-x")))
            .as("teammate 与 leader 共享任务列表（CC tasks.ts:205-208）")
            .isEqualTo("t-x");
        // ⭐ 鉴别力：同一 sessionId 下把 identity 置 null，结果必须**改变**（回落会话）
        assertThat(TaskService.getTaskListId(SESSION_A, null))
            .as("identity 置 null → 输出必须改变（回落显式会话）")
            .isEqualTo(SESSION_A);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. 工具调用点真的把 ctx 传下去了
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("断言4a: TaskListTool.execute(call, ctx) 以 ctx.sessionId() 解析列表 ID（非进程兜底）")
    void taskListTool_usesCtxSessionId() {
        TaskService svc = mock(TaskService.class);
        when(svc.listTasks(anyString())).thenReturn(List.of());

        new TaskListTool(svc).execute(
            new ToolUseBlock("c1", "TaskList", json.createObjectNode()), tuc(SESSION_A));

        verify(svc).listTasks(SESSION_A);
    }

    @Test
    @DisplayName("断言4b: TaskGetTool.execute(call, ctx) 以 ctx.sessionId() 解析列表 ID")
    void taskGetTool_usesCtxSessionId() {
        TaskService svc = mock(TaskService.class);
        when(svc.getTask(anyString(), anyString())).thenReturn(java.util.Optional.empty());

        new TaskGetTool(svc).execute(
            new ToolUseBlock("c1", "TaskGet", json.createObjectNode().put("taskId", "t-1")),
            tuc(SESSION_A));

        verify(svc).getTask(SESSION_A, "t-1");
    }

    @Test
    @DisplayName("断言4c: TaskCreateTool.execute(call, ctx) 以 ctx.sessionId() 创建任务（非进程兜底）")
    void taskCreateTool_usesCtxSessionId() {
        TaskService svc = mock(TaskService.class);
        when(svc.createTask(anyString(), any(Task.class))).thenReturn("t-1");

        new TaskCreateTool(svc, null).execute(
            new ToolUseBlock("c1", "TaskCreate",
                json.createObjectNode().put("subject", "s").put("description", "d")),
            tuc(SESSION_A));

        verify(svc).createTask(org.mockito.ArgumentMatchers.eq(SESSION_A), any(Task.class));
    }

    @Test
    @DisplayName("断言4d: TaskUpdateTool.execute(call, ctx) 以 ctx.sessionId() 读任务（非进程兜底）")
    void taskUpdateTool_usesCtxSessionId() {
        TaskService svc = mock(TaskService.class);
        when(svc.getTask(anyString(), anyString())).thenReturn(java.util.Optional.empty());

        new TaskUpdateTool(svc, null).execute(
            new ToolUseBlock("c1", "TaskUpdate", json.createObjectNode().put("taskId", "t-1")),
            tuc(SESSION_A));

        verify(svc).getTask(SESSION_A, "t-1");
    }

    @Test
    @DisplayName("断言4e: 工具侧 teammate 身份也走显式形参（identity 压过 ctx.sessionId()）")
    void taskListTool_usesCtxTeammateIdentity() {
        TaskService svc = mock(TaskService.class);
        when(svc.listTasks(anyString())).thenReturn(List.of());

        new TaskListTool(svc).execute(
            new ToolUseBlock("c1", "TaskList", json.createObjectNode()),
            tuc(SESSION_A).withTeammateIdentity(teammateIdentity("t-x")));

        verify(svc).listTasks("t-x");
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. TeamCreateTool 归因键（cleanupKey → config.json leadSessionId）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("断言5: TeamCreateTool 归因键 = ctx.sessionId()（sessionId 非 null 时不得回落身份/进程桶）")
    void teamCreateTool_cleanupKeyIsSessionId() {
        TeamHelpers helpers = mock(TeamHelpers.class);
        TaskService svc = mock(TaskService.class);
        when(helpers.teamExists(anyString())).thenReturn(false);
        when(helpers.configPath(anyString())).thenReturn(Path.of("target", "s1-t11-x", "config.json"));

        new TeamCreateTool(helpers, svc).execute(
            new ToolUseBlock("c1", "TeamCreate",
                json.createObjectNode().put("team_name", "s1t11team").put("description", "d")),
            // 同时带 teammate 身份：cleanupKey 的三元必须仍取 sessionId（会话优先）
            tuc(SESSION_A).withTeammateIdentity(teammateIdentity("t-x")));

        ArgumentCaptor<String> configJson = ArgumentCaptor.forClass(String.class);
        verify(helpers).writeConfig(anyString(), configJson.capture());
        assertThat(configJson.getValue())
            .as("config.json 的 leadSessionId 必须是会话 id（cleanupSessionTeams 的匹配键）")
            .contains("\"leadSessionId\":\"" + SESSION_A + "\"");
        assertThat(configJson.getValue())
            .as("⭐ 不得落 teammate 的 teamName / 进程级兜底值（否则清理侧永不相交）")
            .doesNotContain("\"leadSessionId\":\"t-x\"");
    }

}
