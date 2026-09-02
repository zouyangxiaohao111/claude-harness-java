package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-v2-tools-structured-output · TaskListTool 结构化 data 输出定向测试.
 *
 * <p>WHY 本测试验证意图（CC 双通道契约）：TaskList 返回 {@link TaskListTool.TaskListOutput}
 * 结构化 tasks 数组（data 层过滤 _internal + resolved blockedBy，对齐 CC TaskListTool.ts:77-89），
 * 空列表返回 { tasks: [] } 而非字符串 "No tasks found"；渲染行格式下沉
 * {@link TaskListTool#renderToolResultText}（对齐 CC TaskListTool.ts:91-115）。
 */
class TaskListToolStructuredOutputTest {

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // TaskListTool call() 内逐次 getTaskListId()：mock listTasks("tl-1") 要命中必须返回 "tl-1"
        System.setProperty("nexusai.taskListId", "tl-1");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.taskListId");
    }

    @Test
    @DisplayName("空列表返回 { tasks: [] } 结构化，非 'No tasks found' 字符串")
    void emptyList_returnsStructuredEmptyTasks() {
        // WHY: CC TaskListTool.ts:85-89 空列表返回 { data: { tasks: [] } }；'No tasks found'
        // 是 mapper 文本（CC TaskListTool.ts:97），不是 data。
        TaskService taskService = mock(TaskService.class);
        when(taskService.listTasks("tl-1")).thenReturn(List.of());

        TaskListTool tool = new TaskListTool(taskService);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskList", json.createObjectNode());

        @SuppressWarnings("unchecked")
        ToolResult<TaskListTool.TaskListOutput> result =
            (ToolResult<TaskListTool.TaskListOutput>) tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(result.data().tasks()).isEmpty();
        assertThat(TaskListTool.renderToolResultText(result.data())).isEqualTo("No tasks found");
    }

    @Test
    @DisplayName("data 层过滤 _internal 任务 + blockedBy 过滤 completed（resolved）")
    void filtersInternalAndResolvedBlockedBy() {
        // WHY: CC TaskListTool.ts:68-70 过滤 metadata._internal；:73-75 构建 resolvedTaskIds；
        // :82 blockedBy 在 data 层过滤 resolved（completed）任务。渲染格式归 mapper。
        TaskService taskService = mock(TaskService.class);
        Task completed = new Task("t-1", "Write docs", "desc", null, "alice",
            Task.TaskStatus.COMPLETED, List.of(), List.of(), Map.of());
        Task inProgress = new Task("t-2", "Run tests", "desc", null, null,
            Task.TaskStatus.IN_PROGRESS, List.of(), List.of("t-1"), Map.of());
        Task internal = new Task("t-9", "_internal task", "desc", null, null,
            Task.TaskStatus.PENDING, List.of(), List.of(), Map.of("_internal", true));
        when(taskService.listTasks("tl-1")).thenReturn(List.of(completed, inProgress, internal));

        TaskListTool tool = new TaskListTool(taskService);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskList", json.createObjectNode());

        @SuppressWarnings("unchecked")
        ToolResult<TaskListTool.TaskListOutput> result =
            (ToolResult<TaskListTool.TaskListOutput>) tool.execute(call);

        List<TaskListTool.TaskSummary> tasks = result.data().tasks();
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(tasks).hasSize(2); // _internal t-9 被过滤
        TaskListTool.TaskSummary t2 = tasks.stream()
            .filter(s -> s.id().equals("t-2"))
            .findFirst()
            .orElseThrow();
        assertThat(t2.owner()).isNull();
        assertThat(t2.blockedBy()).isEmpty(); // blockedBy t-1（completed）在 data 层被过滤

        // mapper 行格式逐字对齐 CC TaskListTool.ts:101-108：#id [status] subject(owner)
        assertThat(TaskListTool.renderToolResultText(result.data()))
            .isEqualTo("#t-1 [completed] Write docs (alice)\n"
                + "#t-2 [in_progress] Run tests");
    }

    @Test
    @DisplayName("toAutoClassifierInput 走接口默认返回空串（CC Tool.ts:767 默认 ''，未 override 语义）")
    void toAutoClassifierInput_usesInterfaceDefaultEmptyString() {
        // WHY: CC TaskListTool 未定义 toAutoClassifierInput → buildTool 合并 TOOL_DEFAULTS
        // （Tool.ts:767 `(_input?: unknown) => ''`）；yoloClassifier.ts:411/1021-1024
        // 空串跳过转录与分类（无安全相关性）。D-2 删除 override 后 TaskList 实例
        // 必须返回 ""（而非工具名 "TaskList"）。
        TaskListTool tool = new TaskListTool(mock(TaskService.class));

        assertThat(tool.toAutoClassifierInput(json.createObjectNode())).isEmpty();
    }
}
