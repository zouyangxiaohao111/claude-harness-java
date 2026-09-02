package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 metadata-nested · TaskUpdateTool 嵌套 metadata 递归保留定向测试.
 *
 * <p>WHY 本测试验证意图（对齐 CC TaskUpdateTool.ts:59-60 metadata: z.record(z.string(), z.unknown())
 * 原样存储 + tasks.ts:300 jsonStringify 逐字保留嵌套对象/数组 + :200-211 null 删键合并）：
 * TaskUpdate 的 metadata 更新中嵌套对象/数组必须递归转换为 Map/List 结构（而非旧 Java 的
 * asText() 降级为空串），null 值删键、非 null 值原样保留。若未来 jsonNodeToObject 又把嵌套节点
 * 降级为字符串，本测试即失败（测试验证意图而非仅行为）。
 */
class TaskUpdateToolMetadataNestedTest {

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // TaskUpdateTool call() 内逐次 getTaskListId()：mock getTask/updateTask("tl-1",...) 要命中必须返回 "tl-1"
        System.setProperty("nexusai.taskListId", "tl-1");
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
        System.clearProperty("nexusai.taskListId");
    }

    @Test
    @DisplayName("嵌套 metadata 经 execute() 合并写入 partialUpdates.metadata：嵌套对象为 LinkedHashMap、嵌套数组为 List，null 删键、标量保持类型")
    void execute_preservesNestedMetadataStructure() throws Exception {
        // WHY: CC TaskUpdateTool.ts:200-211 对 metadata 值原样存储（z.unknown），null 删除键；
        // 嵌套 ObjectNode/ArrayNode 若走 asText() 兜底会变空串 ''，结构被静默降级。
        TaskService taskService = mock(TaskService.class);
        // 现有任务 metadata 含 drop 键（本次经输入 metadata drop=null 删除）+ keep 键（保留）
        Task existing = new Task("t-1", "subject", "desc", null, null,
            Task.TaskStatus.PENDING, List.of(), List.of(),
            new LinkedHashMap<>(Map.of("drop", "to-be-removed", "keep", "kept-value")));
        when(taskService.getTask("tl-1", "t-1")).thenReturn(Optional.of(existing));
        when(taskService.updateTask(any(), any(), anyMap())).thenReturn(Optional.of(existing));

        TaskUpdateTool tool = new TaskUpdateTool(taskService, null); // null HookRegistry → 跳过 TaskCompleted hooks
        ToolUseBlock call = new ToolUseBlock("call-1", "TaskUpdate", nestedMetadataInput());

        @SuppressWarnings("unchecked")
        ToolResult<String> result = (ToolResult<String>) tool.execute(call);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();

        // 捕获传给 taskPersistence.updateTask 的 partialUpdates，断言其 metadata 结构
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> updatesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskService).updateTask(eq("tl-1"), eq("t-1"), updatesCaptor.capture());
        Map<String, Object> partialUpdates = updatesCaptor.getValue();
        assertThat(partialUpdates).containsKey("metadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) partialUpdates.get("metadata");
        assertThat(metadata).isNotNull();

        // 断言 1：嵌套对象值必须是 LinkedHashMap（非空串 String）
        assertThat(metadata.get("nested")).isInstanceOf(LinkedHashMap.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) metadata.get("nested");
        assertThat(nested).containsEntry("a", 1).containsEntry("b", "x");

        // 断言 2：嵌套数组值必须是 List
        assertThat(metadata.get("tags")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) metadata.get("tags");
        assertThat(tags).containsExactly("docs", "api");

        // 断言 3：标量值保持原类型转换（int / long / double / boolean / text）
        assertThat(metadata.get("priority")).isEqualTo("high");
        assertThat(metadata.get("count")).isEqualTo(3);              // int → Integer
        assertThat(metadata.get("big")).isEqualTo(5_000_000_000L);   // long(>int) → Long
        assertThat(metadata.get("ratio")).isEqualTo(1.5);            // double → Double
        assertThat(metadata.get("enabled")).isEqualTo(true);         // boolean → Boolean

        // 断言 4：null 删键（CC:203-204）——drop 从现有 metadata 移除、note 不产生键；keep 保留
        assertThat(metadata).doesNotContainKey("drop");
        assertThat(metadata).doesNotContainKey("note");
        assertThat(metadata).containsEntry("keep", "kept-value");

        // 断言 5：合并后 metadata 序列化仍含嵌套键结构（磁盘存储 jsonStringify 逐字保留，tasks.ts:300）
        String serialized = AbstractTaskTool.JSON.writeValueAsString(metadata);
        assertThat(serialized).contains("\"nested\":{\"a\":1,\"b\":\"x\"}");
        assertThat(serialized).contains("\"tags\":[\"docs\",\"api\"]");
    }

    /** 构造含嵌套 metadata 的 TaskUpdate 输入（taskId + metadata，对齐 inputSchema） */
    private ObjectNode nestedMetadataInput() {
        ObjectNode input = json.createObjectNode();
        input.put("taskId", "t-1");

        ObjectNode metadata = json.createObjectNode();
        metadata.put("priority", "high");               // text 标量
        metadata.put("count", 3);                        // int 标量
        metadata.put("big", 5_000_000_000L);             // long 标量（超 int 范围）
        metadata.put("ratio", 1.5);                      // double 标量
        metadata.put("enabled", true);                   // boolean 标量
        metadata.putNull("note");                        // null 删键（无现有键，no-op）
        metadata.putNull("drop");                        // null 删键（删除现有 drop 键）
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
