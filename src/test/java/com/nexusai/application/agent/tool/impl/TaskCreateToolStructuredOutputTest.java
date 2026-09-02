package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-v2-tools-structured-output · TaskCreateTool 结构化 data 输出定向测试.
 *
 * <p>WHY 本测试验证意图（CC 双通道契约）：TaskCreate 的 execute() 返回结构化
 * {@link TaskCreateTool.TaskCreateOutput}（data 承载 task{id,subject}），渲染文本
 * 下沉 {@link TaskCreateTool#renderToolResultText}（对齐 CC TaskCreateTool.ts:121-128
 * call() + 130-137 mapToolResultToToolResultBlockParam + toolExecution.ts:1292）。
 * 若未来 execute() 又把渲染字符串当 data 返回，本测试即失败（测试验证意图而非仅行为）。
 */
class TaskCreateToolStructuredOutputTest {

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // TaskCreateTool call() 内逐次 getTaskListId()：保证解析结果为 "tl-1"（非回退 'tasklist'）
        System.setProperty("nexusai.taskListId", "tl-1");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.taskListId");
    }

    @Test
    @DisplayName("execute 返回 ToolResult<TaskCreateOutput>：data 承载结构化 task{id,subject} 而非渲染字符串")
    void execute_returnsStructuredTaskCreateOutput() {
        // WHY: 对齐 CC TaskCreateTool.ts:121-128 call() 返回 { data: { task: { id, subject } } }。
        // 旧 Java 把渲染文本 'Task #x created successfully: ...' 当 data 返回，outputSchema
        // 声明 task{id,subject} 但 data 是 String，形实不符（探查 ✗#3）。
        TaskService taskService = mock(TaskService.class);
        when(taskService.createTask(any(), any())).thenReturn("t-5");

        TaskCreateTool tool = new TaskCreateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskCreate",
            json.createObjectNode().put("subject", "Write docs"));

        @SuppressWarnings("unchecked")
        ToolResult<TaskCreateTool.TaskCreateOutput> result =
            (ToolResult<TaskCreateTool.TaskCreateOutput>) tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // data 必须是结构化 record，非 String
        assertThat(result.data()).isInstanceOf(TaskCreateTool.TaskCreateOutput.class);
        assertThat(result.data().task()).isNotNull();
        assertThat(result.data().task().id()).isEqualTo("t-5");
        assertThat(result.data().task().subject()).isEqualTo("Write docs");
    }

    @Test
    @DisplayName("mapper 文本对齐 CC TaskCreateTool.ts:135：'Task #id created successfully: subject'")
    void renderToolResultText_alignsCcMapper() {
        // WHY: CC 双通道（toolExecution.ts:1292）—— data 供 SDK 消费方解析，mapper content
        // 才是发往模型的 tool_result 文本。渲染文本必须逐字对齐 CC mapper。
        TaskCreateTool.TaskCreateOutput out =
            new TaskCreateTool.TaskCreateOutput(new TaskCreateTool.TaskRef("t-5", "Write docs"));

        assertThat(TaskCreateTool.renderToolResultText(out))
            .isEqualTo("Task #t-5 created successfully: Write docs");
        // toString 桥：production 路径 String.valueOf(result.data()) 仍产出 CC 渲染文本
        // （concerns#1，自包含不改 4 文件之外的接线）。
        assertThat(out.toString())
            .isEqualTo("Task #t-5 created successfully: Write docs");
    }

    @Test
    @DisplayName("searchHint 逐字对齐 CC TaskCreateTool.ts:50：'create a task in the task list'")
    void searchHint_matchesCcVerbatim() {
        // WHY: CC Tool.ts:378 searchHint 是工具定义契约成员，供 ToolSearch 关键词评分
        //   （ToolSearchTool.ts:282-285，命中 +4 分）。TaskCreateTool.ts:50 值
        //   'create a task in the task list' 必须在 Java override 上逐字保留；
        //   漏 override 则 Tool.searchHint() 默认 null（absent 语义）→ 本断言 RED。
        TaskCreateTool tool = new TaskCreateTool(mock(TaskService.class), null);
        assertThat(tool.searchHint())
            .as("CC TaskCreateTool.ts:50 searchHint")
            .isEqualTo("create a task in the task list");
    }

    @Test
    @DisplayName("空 subject 建任务成功（对齐 CC zod z.string() 无 min + createTask 无约束）：data.task.id 非空")
    void emptySubject_createsTaskSuccessfully() {
        // WHY: 对齐 CC TaskCreateTool.ts:81-90 call() + tasks.ts:284-308 createTask——
        // subject 由 z.string()（tasks.ts:78，无 min）校验，空串合法，照常创建任务。
        // 旧 Java blank-subject 拦截（TaskCreateTool.java:360-362）改变可观察行为（D-TC-1），
        // 删除后空 subject 必须走 CC 良性路径：成功返回 data.task{id, subject=''}。
        TaskService taskService = mock(TaskService.class);
        when(taskService.createTask(any(), any())).thenReturn("t-6");

        TaskCreateTool tool = new TaskCreateTool(taskService, null);
        ToolUseBlock call = new ToolUseBlock("call-2", "TaskCreate",
            json.createObjectNode().put("subject", ""));

        @SuppressWarnings("unchecked")
        ToolResult<TaskCreateTool.TaskCreateOutput> result =
            (ToolResult<TaskCreateTool.TaskCreateOutput>) tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse(); // 空 subject 不是错误：任务创建成功
        assertThat(result.data()).isInstanceOf(TaskCreateTool.TaskCreateOutput.class);
        assertThat(result.data().task()).isNotNull();
        assertThat(result.data().task().id()).isEqualTo("t-6"); // E3：ID=highest+1 照常返回
        assertThat(result.data().task().subject()).isEmpty();
        // mapper 文本对齐 CC TaskCreateTool.ts:135：'Task #id created successfully: ' + subject（空串→尾随空格）
        assertThat(TaskCreateTool.renderToolResultText(result.data()))
            .isEqualTo("Task #t-6 created successfully: ");
    }
}
