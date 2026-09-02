package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OD-8 · 四 record（TaskGetOutput/TaskData/TaskListOutput/TaskSummary）@JsonInclude(NON_NULL)
 * 序列化契约测试.
 *
 * <p><b>WHY (意图验证)</b>: CC 结构化 data 经 jsonStringify（= JSON.stringify 纯包装，
 * slowOperations.ts:170-191）序列化时省略 undefined——TaskListTool.ts:81
 * {@code owner: task.owner} 在未分配时值为 undefined，键被省略；outputSchema 中 owner 为
 * {@code z.string().optional()}（TaskListTool.ts:23）。Java {@code null} ≈ CC
 * {@code undefined}（Task.java:32 同口径），四 record 加 {@code @JsonInclude(NON_NULL)}
 * 后 null 字段不再写出（Task.java:54 仓库既有先例）。
 *
 * <p>纯单元测试（无 Spring 上下文），直接构造 record + 原生 ObjectMapper 序列化，
 * 参照 TaskFileStorageSerializationTest 模式（Task.java 同款注解断言）。
 */
class TaskGetListRecordsSerializationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("TaskSummary owner==null → JSON 无 owner 键；owner 非空 → 有键")
    void taskSummary_ownerNull_omitsOwnerKey_ownerPresent_includesKey() throws Exception {
        // CC TaskListTool.ts:23 owner: z.string().optional() + :81 owner: task.owner
        // （未分配 = undefined → jsonStringify 省略键）；Java null ≈ undefined → 省略。
        TaskListTool.TaskSummary noOwner = new TaskListTool.TaskSummary(
            "t-1", "subject", "in_progress", null, List.of());
        JsonNode noOwnerNode = json.readTree(json.writeValueAsString(noOwner));
        assertThat(noOwnerNode.has("owner")).isFalse();
        assertThat(noOwnerNode.get("id").asText()).isEqualTo("t-1");

        TaskListTool.TaskSummary withOwner = new TaskListTool.TaskSummary(
            "t-2", "subject", "in_progress", "alice", List.of());
        JsonNode withOwnerNode = json.readTree(json.writeValueAsString(withOwner));
        assertThat(withOwnerNode.has("owner")).isTrue();
        assertThat(withOwnerNode.get("owner").asText()).isEqualTo("alice");
    }

    @Test
    @DisplayName("TaskData 无 owner 字段（CC TaskGet schema 无 owner）；null 字段省略、非 null 写出")
    void taskData_nullFieldsOmitted_nonNullFieldsWritten() throws Exception {
        // CC TaskGetTool.ts:22-31 task 对象 {id, subject, description, status, blocks,
        // blockedBy} 无 owner 字段；NON_NULL 对齐 CC 省略 undefined 的一般语义：
        // null 字段（description/blocks/blockedBy）不写出。
        TaskGetTool.TaskData sparse = new TaskGetTool.TaskData(
            "t-1", "subject", null, "in_progress", null, null);
        JsonNode node = json.readTree(json.writeValueAsString(sparse));
        assertThat(node.has("owner")).isFalse(); // CC TaskGet schema 无 owner 键
        assertThat(node.has("description")).isFalse();
        assertThat(node.has("blocks")).isFalse();
        assertThat(node.has("blockedBy")).isFalse();
        assertThat(node.get("id").asText()).isEqualTo("t-1");
        assertThat(node.get("status").asText()).isEqualTo("in_progress");

        TaskGetTool.TaskData full = new TaskGetTool.TaskData(
            "t-2", "subject", "desc", "completed", List.of("t-9"), List.of());
        JsonNode fullNode = json.readTree(json.writeValueAsString(full));
        assertThat(fullNode.get("description").asText()).isEqualTo("desc");
        assertThat(fullNode.get("blocks").get(0).asText()).isEqualTo("t-9");
        assertThat(fullNode.get("blockedBy").size()).isZero();
    }

    @Test
    @DisplayName("TaskListOutput tasks 始终写出（空列表 [] 不受 NON_NULL 影响）")
    void taskListOutput_tasksAlwaysWritten() throws Exception {
        // CC TaskListTool.ts:16-27 tasks 为 required 数组（未找到场景不存在，空列表 → []）；
        // NON_NULL 不删空数组（Task.java:35-38 同口径）。
        TaskListTool.TaskListOutput out = new TaskListTool.TaskListOutput(List.of());
        JsonNode node = json.readTree(json.writeValueAsString(out));
        assertThat(node.has("tasks")).isTrue();
        assertThat(node.get("tasks").size()).isZero();
    }

    @Test
    @DisplayName("TaskGetOutput task 非空 → 有键（task 承载详情对象）")
    void taskGetOutput_nonNullTask_includesTaskKey() throws Exception {
        TaskGetTool.TaskGetOutput withTask = new TaskGetTool.TaskGetOutput(
            new TaskGetTool.TaskData("t-1", "s", "d", "pending", List.of(), List.of()));
        JsonNode node = json.readTree(json.writeValueAsString(withTask));
        assertThat(node.has("task")).isTrue();
        assertThat(node.get("task").get("id").asText()).isEqualTo("t-1");
    }

    @Test
    @DisplayName("TaskGetOutput task==null → 显式 task 键保留（CC TaskGetTool.ts:78-83 data:{task:null}）")
    void taskGetOutput_nullTask_keepsTaskKeyWithNull() throws Exception {
        // CC 未找到时返回 { data: { task: null } }（JSON.stringify 保留 null，仅省略 undefined）；
        // Java TaskGetOutput 不加 NON_NULL → task 键始终写出，null 时输出 "task":null。
        TaskGetTool.TaskGetOutput noTask = new TaskGetTool.TaskGetOutput(null);
        JsonNode node = json.readTree(json.writeValueAsString(noTask));
        assertThat(node.has("task")).isTrue();
        assertThat(node.get("task").isNull()).isTrue();
    }
}
