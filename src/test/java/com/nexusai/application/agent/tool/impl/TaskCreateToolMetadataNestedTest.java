package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 metadata-nested · TaskCreateTool 嵌套 metadata 递归保留定向测试.
 *
 * <p>WHY 本测试验证意图（对齐 CC TaskCreateTool.ts:28-31 metadata: z.record(z.string(), z.unknown())
 * 原样存储 + tasks.ts:300 jsonStringify 逐字保留嵌套对象/数组）：metadata 中的嵌套对象/数组
 * 必须递归转换为 Map/List 结构（而非旧 Java 的 asText() 降级为空串）。若未来 jsonNodeToObject
 * 又把嵌套节点降级为字符串，本测试即失败（测试验证意图而非仅行为）。
 */
class TaskCreateToolMetadataNestedTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("嵌套 metadata 经 execute() 写入 Task.metadata：嵌套对象为 LinkedHashMap、嵌套数组为 List，标量保持类型")
    void execute_preservesNestedMetadataStructure() throws Exception {
        // WHY: CC z.unknown 接受任意嵌套值（对象/数组）并原样落盘（tasks.ts:300）。
        // 旧 Java 对嵌套 ObjectNode/ArrayNode 走 asText() 兜底 → 空串 ''，结构被静默降级。
        TaskService taskService = mock(TaskService.class);
        when(taskService.createTask(any(), any())).thenReturn("t-5");

        TaskCreateTool tool = new TaskCreateTool(taskService, null); // null HookRegistry → 跳过 TaskCreated hooks
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskCreate", nestedMetadataInput());

        @SuppressWarnings("unchecked")
        ToolResult<TaskCreateTool.TaskCreateOutput> result =
            (ToolResult<TaskCreateTool.TaskCreateOutput>) tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();

        // 捕获传给 taskPersistence.createTask 的 Task，断言其 metadata 结构
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskService).createTask(any(), taskCaptor.capture());
        Map<String, Object> metadata = taskCaptor.getValue().metadata();
        assertThat(metadata).isNotNull();

        // 断言 1：嵌套对象值必须是 LinkedHashMap（非 String）
        assertThat(metadata.get("nested")).isInstanceOf(LinkedHashMap.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) metadata.get("nested");
        assertThat(nested).containsEntry("a", 1).containsEntry("b", "x");

        // 断言 2：嵌套数组值必须是 List
        assertThat(metadata.get("tags")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) metadata.get("tags");
        assertThat(tags).containsExactly("docs", "api");

        // 断言 4：标量值保持原类型转换（int / long / double / boolean / null / text）
        assertThat(metadata.get("priority")).isEqualTo("high");
        assertThat(metadata.get("count")).isEqualTo(3);              // int → Integer
        assertThat(metadata.get("big")).isEqualTo(5_000_000_000L);   // long(>int) → Long
        assertThat(metadata.get("ratio")).isEqualTo(1.5);            // double → Double
        assertThat(metadata.get("enabled")).isEqualTo(true);         // boolean → Boolean
        assertThat(metadata.get("note")).isNull();                   // null → null

        // 断言 3：磁盘 JSON 输出（TaskFileStorage 走 AbstractTaskTool.JSON.writeValueAsString）含嵌套键结构
        String serialized = AbstractTaskTool.JSON.writeValueAsString(taskCaptor.getValue());
        assertThat(serialized).contains("\"nested\":{\"a\":1,\"b\":\"x\"}");
        assertThat(serialized).contains("\"tags\":[\"docs\",\"api\"]");
        assertThat(serialized).contains("\"count\":3").contains("\"big\":5000000000");
    }

    /** 构造含嵌套 metadata 的 TaskCreate 输入（subject + description 必填，对齐 inputSchema） */
    private ObjectNode nestedMetadataInput() {
        ObjectNode input = json.createObjectNode();
        input.put("subject", "Write docs");
        input.put("description", "Write the docs");
        input.put("activeForm", "Writing docs");

        ObjectNode metadata = json.createObjectNode();
        metadata.put("priority", "high");               // text 标量
        metadata.put("count", 3);                        // int 标量
        metadata.put("big", 5_000_000_000L);             // long 标量（超 int 范围）
        metadata.put("ratio", 1.5);                      // double 标量
        metadata.put("enabled", true);                   // boolean 标量
        metadata.putNull("note");                        // null 标量
        ObjectNode nested = json.createObjectNode();
        nested.put("a", 1);
        nested.put("b", "x");
        metadata.set("nested", nested);                  // 嵌套对象
        ArrayNode tags = json.createArrayNode();
        tags.add("docs");
        tags.add("api");
        metadata.set("tags", tags);                      // 嵌套数组
        input.set("metadata", metadata);
        return input;
    }
}
