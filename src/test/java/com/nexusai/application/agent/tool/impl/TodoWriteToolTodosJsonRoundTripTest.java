package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.tool.impl.TodoWriteTool.TodoItem;
import com.nexusai.application.agent.tool.impl.TodoWriteTool.TodoStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TodoWrite todos 规范形 JSON 往返测试 · [R3 持久升级] todosMapToJson/todosJsonToMap。
 *
 * <p><b>WHY 本测试验证意图</b>：规范形 {todoKey:[{content,status,activeForm}]} 是三通道契约
 * （DB 写 Step5.6 / doRun 回读注入 / Controller REST 读），往返坏 = reminder/DTO/DB 全链漂移：
 * <ul>
 *   <li><b>往返保 content/status/activeForm + status 小写</b>——status 大小写错读回即坏；</li>
 *   <li><b>fail-soft</b>——null/空白/解析失败/非对象 → 空 map（读侧不因坏列崩溃）；</li>
 *   <li><b>非法 status 条目跳过</b>——单条坏不坏整桶（对齐 CC z.enum 拒绝 + 隔离）；</li>
 *   <li><b>空桶键保留</b>——{@code {"sess-xxx":[]}} 往返键不丢（CC allDone 清空语义）。</li>
 * </ul>
 */
class TodoWriteToolTodosJsonRoundTripTest {

    @Test
    @DisplayName("往返：保 content/status/activeForm + status 小写")
    void roundTripPreservesFieldsAndLowercaseStatus() {
        Map<String, List<TodoItem>> map = new HashMap<>();
        map.put("sess-xxx", List.of(
            new TodoItem("A", TodoStatus.PENDING, "Doing A"),
            new TodoItem("B", TodoStatus.IN_PROGRESS, "Doing B")));
        map.put("agent-uuid", List.of(new TodoItem("sub", TodoStatus.COMPLETED, "Doing sub")));

        String json = TodoWriteTool.todosMapToJson(map);

        assertThat(json).isNotNull();
        assertThat(json)
            .as("序列化 status 必须小写（CC types.ts:4-6 值域）")
            .contains("\"status\":\"pending\"")
            .contains("\"status\":\"in_progress\"")
            .contains("\"status\":\"completed\"")
            .doesNotContain("PENDING")
            .doesNotContain("IN_PROGRESS");

        Map<String, List<TodoItem>> back = TodoWriteTool.todosJsonToMap(json);
        assertThat(back.keySet()).as("往返键集合必须完整").containsExactlyInAnyOrder("sess-xxx", "agent-uuid");

        List<TodoItem> bucket = back.get("sess-xxx");
        assertThat(bucket).hasSize(2);
        assertThat(bucket.get(0).content()).isEqualTo("A");
        assertThat(bucket.get(0).status()).isEqualTo(TodoStatus.PENDING);
        assertThat(bucket.get(0).activeForm()).isEqualTo("Doing A");
        assertThat(bucket.get(1).content()).isEqualTo("B");
        assertThat(bucket.get(1).status()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(bucket.get(1).activeForm()).isEqualTo("Doing B");

        List<TodoItem> sub = back.get("agent-uuid");
        assertThat(sub.get(0).status()).isEqualTo(TodoStatus.COMPLETED);
    }

    @Test
    @DisplayName("fail-soft：null/空白/解析失败/非对象 → 空 map（不抛）")
    void failSoftReturnsEmptyMap() {
        assertThat(TodoWriteTool.todosJsonToMap(null)).as("null → 空 map").isEmpty();
        assertThat(TodoWriteTool.todosJsonToMap("   ")).as("空白 → 空 map").isEmpty();
        assertThat(TodoWriteTool.todosJsonToMap("not-json{")).as("非法 JSON → 空 map（不抛）").isEmpty();
        assertThat(TodoWriteTool.todosJsonToMap("[1,2,3]")).as("非对象 JSON → 空 map").isEmpty();
        assertThat(TodoWriteTool.todosJsonToMap("42")).as("标量 JSON → 空 map").isEmpty();
    }

    @Test
    @DisplayName("非法 status 条目跳过，不坏整桶")
    void illegalStatusItemsSkipped() {
        Map<String, List<TodoItem>> back = TodoWriteTool.todosJsonToMap(
            "{\"sess-xxx\":["
            + "{\"content\":\"A\",\"status\":\"in_progress\",\"activeForm\":\"Doing A\"},"
            + "{\"content\":\"B\",\"status\":\"PENDING\",\"activeForm\":\"Doing B\"},"
            + "{\"content\":\"C\",\"status\":\"weird\",\"activeForm\":\"Doing C\"},"
            + "{\"status\":\"pending\",\"activeForm\":\"NoContent\"}"
            + "]}");

        assertThat(back.get("sess-xxx"))
            .as("仅合法条目保留（A），大写/非法 status、content 空白条目跳过")
            .hasSize(1);
        assertThat(back.get("sess-xxx").get(0).content()).isEqualTo("A");
        assertThat(back.get("sess-xxx").get(0).status()).isEqualTo(TodoStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("空桶 {\"sess-xxx\":[]} 往返键保留；全桶空 map → null")
    void emptyBucketKeyPreserved() {
        Map<String, List<TodoItem>> parsed = TodoWriteTool.todosJsonToMap("{\"sess-xxx\":[]}");
        assertThat(parsed).as("空数组桶键必须保留（CC allDone 清空语义）").containsKey("sess-xxx");
        assertThat(parsed.get("sess-xxx")).isEmpty();

        // 往返：空桶 map → 序列化仍保留键（非 null）
        String reJson = TodoWriteTool.todosMapToJson(parsed);
        assertThat(reJson).isEqualTo("{\"sess-xxx\":[]}");

        // 全桶空 map → null（对齐 disabled_tools 空集合→null 惯例）
        assertThat(TodoWriteTool.todosMapToJson(new HashMap<>())).as("全桶空 map → null").isNull();
        assertThat(TodoWriteTool.todosMapToJson(null)).as("null map → null").isNull();
    }
}
