package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * getTask 严格校验定向测试 · 对齐 CC tasks.ts:333-339 TaskSchema().safeParse()
 *
 * <p>对齐 CC getTask 的 zod 等价严格校验语义：读任务文件时对原始 JSON 做 TaskSchema
 * 等价校验，校验失败记 debug 日志 + 返回 null（Java 侧 Optional.empty()）。彻底替换
 * 旧 Jackson 宽松反序列化路径（缺 status/description/blocks/blockedBy 时被 Task 紧凑
 * 构造默认值静默兜底，Task.java:143-147；非法状态被 IOException 吞掉无校验日志）。
 *
 * <p><b>WHY (意图验证)</b>: CC getTask 对非法任务文件返回 null 而非宽松兜底——缺字段 /
 * 非法状态的任务不得以默认值形态流入 getTask/listTasks（否则任务系统会对不存在或损坏的
 * 任务做错误的状态推进）。防回归旧宽松 readValue 路径（TaskFileStorage.java:111/134 已删除）。
 *
 * <p>参考 CC 源码（grep 自验，非注释）：
 * <ul>
 *   <li>{@code TaskStatusSchema = z.enum(['pending','in_progress','completed'])} — tasks.ts:71-74</li>
 *   <li>{@code id/subject/description: z.string()}、{@code status: TaskStatusSchema()}、
 *       {@code blocks/blockedBy: z.array(z.string())}、{@code activeForm/owner:
 *       z.string().optional()}、{@code metadata: z.record(...).optional()} — tasks.ts:76-89</li>
 *   <li>{@code const parsed = TaskSchema().safeParse(data)}；{@code if (!parsed.success) {
 *       logForDebugging(...); return null }} — tasks.ts:333-339</li>
 *   <li>{@code return results.filter((t): t is Task => t !== null)} — tasks.ts:454-456</li>
 * </ul>
 */
class R32D_TaskGetValidateTest {

    @TempDir
    Path tempDir;

    private TaskService service() {
        return new TaskService(tempDir);
    }

    private Path taskFile(String taskListId, String taskId) {
        return tempDir.resolve("tasks").resolve(taskListId).resolve(taskId + ".json");
    }

    private void writeTask(String taskListId, String taskId, String json) throws IOException {
        Files.createDirectories(taskFile(taskListId, taskId).getParent());
        Files.writeString(taskFile(taskListId, taskId), json);
    }

    /**
     * 合法小写 CC 磁盘文件形状（对齐 CC tasks.ts:76-89 TaskSchema 的 storage 形态：
     * status 严格小写 pending/in_progress/completed，owner:null 省略键）。
     * DC-3 移除 parseStatusStrict 大写容忍后，本小写 fixture 为唯一合法读路径形状。
     */
    private static final String VALID_LOWER_JSON = """
        {
          "id": "1",
          "subject": "编写测试",
          "description": "覆盖 getTask 严格校验",
          "activeForm": "编写测试",
          "owner": null,
          "status": "in_progress",
          "blocks": [],
          "blockedBy": [],
          "metadata": {}
        }
        """;

    /** 重写 id 字段值（让磁盘文件名与 JSON id 自洽） */
    private static String withId(String json, String id) {
        return json.replace("\"id\": \"1\"", "\"id\": \"" + id + "\"");
    }

    /** 移除某个 JSON 字段行（模拟缺字段文件；不含末行 metadata） */
    private static String removeField(String json, String fieldName) {
        return json.lines()
            .filter(line -> !line.trim().startsWith("\"" + fieldName + "\""))
            .collect(Collectors.joining("\n"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // 合法路径（不得误伤 Java 自身 round-trip）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createTask → getTask round-trip：Java 写路径（小写 status + owner 省略）必须可读回")
    void javaWritePathRoundTrip() {
        TaskService service = service();
        String id = service.createTask("list-1", Task.create("写代码", "完成功能"));

        Optional<Task> read = service.getTask("list-1", id);

        assertThat(read).isPresent();
        assertThat(read.get().id()).isEqualTo(id);
        assertThat(read.get().status()).isEqualTo(Task.TaskStatus.PENDING);
        assertThat(read.get().subject()).isEqualTo("写代码");
        assertThat(read.get().owner()).isNull();
    }

    @Test
    @DisplayName("DC-3：大写 Java 遗留状态文件被拒（IN_PROGRESS → getTask 空，对齐 CC 严格 z.enum 小写）")
    void upperCaseJavaStatusRejected() throws IOException {
        // DC-3 移除 parseStatusStrict 大写容忍：存量大写旧文件已迁移为小写（OPD-TS-05 ⊕a 收口），
        // 大写 IN_PROGRESS/PENDING/COMPLETED 一律拒绝，对齐 CC tasks.ts:71-74 严格 z.enum 大小写敏感。
        String[] upper = {"IN_PROGRESS", "PENDING", "COMPLETED"};
        for (int i = 0; i < upper.length; i++) {
            writeTask("list-1", String.valueOf(i + 1),
                withId(VALID_LOWER_JSON.replace("\"in_progress\"", "\"" + upper[i] + "\""), String.valueOf(i + 1)));
        }

        for (int i = 0; i < upper.length; i++) {
            String id = String.valueOf(i + 1);
            assertThat(service().getTask("list-1", id))
                .as("大写 status=%s 应被拒绝（CC z.enum 严格小写）", upper[i])
                .isEmpty();
        }
    }

    @Test
    @DisplayName("合法小写 CC 状态文件可读：pending/in_progress/completed")
    void lowerCaseCcStatusAccepted() throws IOException {
        writeTask("list-1", "1", withId(VALID_LOWER_JSON, "1"));
        writeTask("list-1", "2", withId(VALID_LOWER_JSON.replace("\"in_progress\"", "\"pending\""), "2"));
        writeTask("list-1", "3", withId(VALID_LOWER_JSON.replace("\"in_progress\"", "\"completed\""), "3"));

        TaskService service = service();
        assertThat(service.getTask("list-1", "1").get().status()).isEqualTo(Task.TaskStatus.IN_PROGRESS);
        assertThat(service.getTask("list-1", "2").get().status()).isEqualTo(Task.TaskStatus.PENDING);
        assertThat(service.getTask("list-1", "3").get().status()).isEqualTo(Task.TaskStatus.COMPLETED);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 非法路径（CC 返回 null，不得宽松兜底）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("非法状态 deleted/inprogress/done/open/resolved/foo/Pending → getTask 空（CC 严格 z.enum 无 alias）")
    void invalidStatusRejected() throws IOException {
        String[] bad = {"deleted", "inprogress", "done", "open", "resolved", "foo", "DELETED", "Pending"};
        TaskService service = service();
        for (int i = 0; i < bad.length; i++) {
            String id = String.valueOf(i + 1);
            writeTask("list-1", id,
                withId(VALID_LOWER_JSON.replace("\"in_progress\"", "\"" + bad[i] + "\""), id));
        }

        for (int i = 0; i < bad.length; i++) {
            String id = String.valueOf(i + 1);
            assertThat(service.getTask("list-1", id))
                .as("status=%s 应被拒绝", bad[i])
                .isEmpty();
        }
    }

    @Test
    @DisplayName("缺必填字段 status/description/blocks/blockedBy/id/subject → getTask 空（旧实现被默认值兜底，本项修复点）")
    void missingRequiredFieldsRejected() throws IOException {
        String[] missing = {"status", "description", "blocks", "blockedBy", "id", "subject"};
        TaskService service = service();
        for (int i = 0; i < missing.length; i++) {
            String id = String.valueOf(i + 1);
            writeTask("list-1", id, withId(removeField(VALID_LOWER_JSON, missing[i]), id));
        }

        for (int i = 0; i < missing.length; i++) {
            String id = String.valueOf(i + 1);
            assertThat(service.getTask("list-1", id))
                .as("缺字段 %s 应被拒绝", missing[i])
                .isEmpty();
        }
    }

    @Test
    @DisplayName("类型错误：subject 为数字 / blocks 为字符串 / blockedBy 含非字符串 / status 为数字 → getTask 空")
    void wrongTypeRejected() throws IOException {
        writeTask("list-1", "1", withId(VALID_LOWER_JSON.replace("\"subject\": \"编写测试\"", "\"subject\": 42"), "1"));
        writeTask("list-1", "2", withId(VALID_LOWER_JSON.replace("\"blocks\": [],", "\"blocks\": \"x\","), "2"));
        writeTask("list-1", "3", withId(VALID_LOWER_JSON.replace("\"blockedBy\": [],", "\"blockedBy\": [\"a\", 2],"), "3"));
        writeTask("list-1", "4", withId(VALID_LOWER_JSON.replace("\"status\": \"in_progress\"", "\"status\": 5"), "4"));

        TaskService service = service();
        assertThat(service.getTask("list-1", "1")).as("subject 数字应被拒绝").isEmpty();
        assertThat(service.getTask("list-1", "2")).as("blocks 字符串应被拒绝").isEmpty();
        assertThat(service.getTask("list-1", "3")).as("blockedBy 含非字符串应被拒绝").isEmpty();
        assertThat(service.getTask("list-1", "4")).as("status 数字应被拒绝").isEmpty();
    }

    @Test
    @DisplayName("metadata 为数组 → getTask 空（对齐 z.record().optional()）")
    void metadataArrayRejected() throws IOException {
        writeTask("list-1", "1", VALID_LOWER_JSON.replace("\"metadata\": {}", "\"metadata\": [1, 2]"));

        assertThat(service().getTask("list-1", "1")).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 文档化偏差 / 合法边界
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("owner:null 正常读取（concerns#2 文档化偏差：Java 写路径写 owner:null）")
    void ownerNullAccepted() throws IOException {
        writeTask("list-1", "1", VALID_LOWER_JSON);

        Optional<Task> task = service().getTask("list-1", "1");

        assertThat(task).isPresent();
        assertThat(task.get().owner()).isNull();
    }

    @Test
    @DisplayName("多余未知键正常（对齐 zod strip + Jackson FAIL_ON_UNKNOWN_PROPERTIES=false）")
    void unknownKeysIgnored() throws IOException {
        String withExtra = VALID_LOWER_JSON.replace("\"metadata\": {}", "\"metadata\": {},\n  \"extraKey\": 123,\n  \"unknown\": {\"nested\": true}");
        writeTask("list-1", "1", withExtra);

        Optional<Task> task = service().getTask("list-1", "1");

        assertThat(task).isPresent();
        assertThat(task.get().id()).isEqualTo("1");
        assertThat(task.get().status()).isEqualTo(Task.TaskStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("listTasks 剔除非法文件（对齐 CC listTasks 逐文件 getTask + filter(null)，tasks.ts:454-456）")
    void listTasksFiltersInvalidFiles() throws IOException {
        writeTask("list-1", "1", withId(VALID_LOWER_JSON, "1"));                      // 合法
        writeTask("list-1", "2", withId(VALID_LOWER_JSON.replace("\"in_progress\"", "\"done\""), "2"));            // 非法状态
        writeTask("list-1", "3", withId(removeField(VALID_LOWER_JSON, "description"), "3"));                       // 缺必填

        List<Task> tasks = service().listTasks("list-1");

        assertThat(tasks).extracting(Task::id).containsExactly("1");
        assertThat(tasks).hasSize(1);
    }
}
