package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.tasks.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P1 schema-strict · TaskGet/TaskList outputSchema 的 required/nullable 契约测试.
 *
 * <p><b>WHY (意图验证)</b>: 对齐 CC zod v4 toJSONSchema（默认 io='output'，
 * CC zodToJsonSchema.ts:20）序列化契约，而非仅断言字段存在：
 * <ul>
 *   <li>TaskGet（CC TaskGetTool.ts:20-33）：顶层 {@code task} 为 z.object 成员默认
 *       required → 根 required=[task]；task 对象 6 字段均无 .optional() → 内部全部
 *       required；{@code .nullable()} → anyOf:[taskObj,{type:"null"}]；普通 z.object
 *       输出 additionalProperties:false</li>
 *   <li>TaskList（CC TaskListTool.ts:16-27）：顶层 {@code tasks} 默认 required →
 *       根 required=[tasks]；item 内 owner 为 {@code z.string().optional()}（:23）→
 *       不在 item required；其余 4 字段 required；根与 item 均 additionalProperties:false</li>
 * </ul>
 *
 * <p>纯单元测试（无 Spring 上下文），直接 {@code new TaskGetTool(mock TaskService)}
 * / {@code new TaskListTool(mock TaskService)}（任务列表 ID 由 sysprop nexusai.taskListId=default 解析），
 * 参照 R32B7a2_ConfigToolImplContractTest 模式。
 */
class TaskGetListOutputSchemaContractTest {

    @BeforeEach
    void setUp() {
        // outputSchema 本身不依赖列表 ID，但保证 getTaskListId() 非回退值（避免意外依赖）
        System.setProperty("nexusai.taskListId", "default");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("nexusai.taskListId");
    }

    @Test
    @DisplayName("TaskGet outputSchema: 根 required=[task] + task nullable anyOf 表达")
    void taskGet_outputSchema_topLevelTaskRequiredAndNullable() {
        // WHY: CC TaskGetTool.ts:21 task 为 z.object 成员默认 required（序列化进根 required 数组），
        // :31 .nullable() 允许整个 task 为 null（任务未找到，CC TaskGetTool.ts:78-83）。
        // 若 Java 缺少根 required 或 nullable 表达，MCP/契约消费方会误判 task 必填且不可为 null。
        TaskGetTool tool = new TaskGetTool(mock(TaskService.class));

        JsonNode schema = tool.outputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean())
            .as("zod output 模式普通 z.object 输出 additionalProperties:false")
            .isFalse();

        JsonNode required = schema.get("required");
        assertThat(required).isNotNull();
        assertThat(required.isArray()).isTrue();
        assertThat(required).extracting(JsonNode::asText)
            .containsExactly("task");

        JsonNode task = schema.get("properties").get("task");
        assertThat(task).isNotNull();
        // CC TaskGetTool.ts:31 .nullable() → anyOf: [taskObj, {type:"null"}]
        JsonNode anyOf = task.get("anyOf");
        assertThat(anyOf).isNotNull();
        assertThat(anyOf.isArray()).isTrue();
        assertThat(anyOf).hasSize(2);
        JsonNode nullBranch = anyOf.get(1);
        assertThat(nullBranch.get("type").asText())
            .as("anyOf 第二分支必须为 {type:'null'}（zod .nullable() 序列化）")
            .isEqualTo("null");
        JsonNode taskObj = anyOf.get(0);
        assertThat(taskObj.get("type").asText()).isEqualTo("object");
        assertThat(taskObj.get("additionalProperties").asBoolean())
            .as("zod output 模式 task 内部对象也输出 additionalProperties:false")
            .isFalse();
    }

    @Test
    @DisplayName("TaskGet outputSchema: task 对象内部 6 字段全部 required")
    void taskGet_outputSchema_taskObjectSixFieldsAllRequired() {
        // WHY: CC TaskGetTool.ts:22-30 六个字段均无 .optional()（id/subject/description/status/blocks/blockedBy），
        // zod 序列化时全部进入 task 对象内部 required 数组。若 Java 缺任何一个，
        // 消费方按 schema 无法保证拿到完整任务详情。
        TaskGetTool tool = new TaskGetTool(mock(TaskService.class));

        JsonNode schema = tool.outputSchema();
        JsonNode taskObj = schema.get("properties").get("task").get("anyOf").get(0);
        JsonNode required = taskObj.get("required");
        assertThat(required).isNotNull();
        assertThat(required.isArray()).isTrue();
        assertThat(required).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("id", "subject", "description", "status", "blocks", "blockedBy");

        JsonNode taskProps = taskObj.get("properties");
        assertThat(taskProps).isNotNull();
        assertThat(taskProps.has("id")).as("id").isTrue();
        assertThat(taskProps.has("subject")).as("subject").isTrue();
        assertThat(taskProps.has("description")).as("description").isTrue();
        assertThat(taskProps.has("status")).as("status").isTrue();
        assertThat(taskProps.has("blocks")).as("blocks").isTrue();
        assertThat(taskProps.has("blockedBy")).as("blockedBy").isTrue();
    }

    @Test
    @DisplayName("TaskList outputSchema: 根 required=[tasks] + item required 含 4 字段不含 owner")
    void taskList_outputSchema_rootTasksRequiredAndItemRequiredExcludesOwner() {
        // WHY: CC TaskListTool.ts:17 顶层 tasks 为 z.object 成员默认 required；
        // :23 owner: z.string().optional() → 不在 item required（owner 可空，未分配任务）。
        // 若 Java 把 owner 误放入 required，消费方会要求每个任务必须有 owner → 契约过严。
        TaskListTool tool = new TaskListTool(mock(TaskService.class));

        JsonNode schema = tool.outputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean())
            .as("zod output 模式普通 z.object 输出 additionalProperties:false")
            .isFalse();

        JsonNode rootRequired = schema.get("required");
        assertThat(rootRequired).isNotNull();
        assertThat(rootRequired.isArray()).isTrue();
        assertThat(rootRequired).extracting(JsonNode::asText)
            .containsExactly("tasks");

        JsonNode taskItem = schema.get("properties").get("tasks").get("items");
        assertThat(taskItem).isNotNull();
        assertThat(taskItem.get("type").asText()).isEqualTo("object");
        assertThat(taskItem.get("additionalProperties").asBoolean())
            .as("zod output 模式 item 内部对象也输出 additionalProperties:false")
            .isFalse();

        JsonNode itemRequired = taskItem.get("required");
        assertThat(itemRequired).isNotNull();
        assertThat(itemRequired.isArray()).isTrue();
        assertThat(itemRequired).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("id", "subject", "status", "blockedBy");
        assertThat(itemRequired).extracting(JsonNode::asText)
            .as("owner 为 .optional()，不得出现在 required（CC TaskListTool.ts:23）")
            .doesNotContain("owner");

        JsonNode itemProps = taskItem.get("properties");
        assertThat(itemProps).isNotNull();
        assertThat(itemProps.has("id")).as("id").isTrue();
        assertThat(itemProps.has("subject")).as("subject").isTrue();
        assertThat(itemProps.has("status")).as("status").isTrue();
        assertThat(itemProps.has("owner")).as("owner").isTrue();
        assertThat(itemProps.has("blockedBy")).as("blockedBy").isTrue();
    }
}
