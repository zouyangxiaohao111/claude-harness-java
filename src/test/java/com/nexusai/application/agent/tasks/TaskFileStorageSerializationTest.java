package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * disk-json-shape 定向测试 · 对齐 CC jsonStringify 省略 undefined 的磁盘 JSON 形状
 *
 * <p><b>WHY (意图验证)</b>: CC 落盘用 {@code jsonStringify(task, null, 2)}（= JSON.stringify
 * 纯包装，Open-ClaudeCode/src/utils/slowOperations.ts:170-191），undefined 字段被省略；
 * 新建任务 owner=undefined（TaskCreateTool.ts:86）。CC 读回经 {@code TaskSchema().safeParse}
 * （tasks.ts:333-339），zod optional 接受 undefined 拒绝 null——Java 若写出 {@code "owner":null}
 * 会致 CC 读入校验失败返回 null。本测试断言 Java 写盘省略 null 字段（Task record
 * {@code @JsonInclude(NON_NULL)}），与 CC 磁盘字节形状一致。
 *
 * <p>参考 CC 源码（grep 自验，非注释）：
 * <ul>
 *   <li>{@code export function jsonStringify(...) { return JSON.stringify(value, replacer, space) }}
 *       — slowOperations.ts:170-191</li>
 *   <li>{@code await writeFile(path, jsonStringify(task, null, 2))} — tasks.ts:300 createTask</li>
 *   <li>{@code await writeFile(path, jsonStringify(updated, null, 2))} — tasks.ts:365 updateTaskUnsafe</li>
 *   <li>{@code owner: undefined, blocks: [], blockedBy: []} — TaskCreateTool.ts:86-88</li>
 * </ul>
 */
class TaskFileStorageSerializationTest {

    @TempDir
    Path tempDir;

    /**
     * 写一个 owner=null 的新建任务 → 磁盘原始 JSON 不含 "owner" 键、无 ":null"
     *
     * <p>对齐 CC: TaskCreateTool.ts:86 {@code owner: undefined} → JSON.stringify 省略该键。
     * Java record {@code @JsonInclude(NON_NULL)}（对齐 ToolResult.java:55 先例）使 owner=null
     * 不再写出 {@code "owner":null}，避免 CC zod safeParse 拒绝 null（tasks.ts:333-339）。
     */
    @Test
    @DisplayName("writeTaskFile 产出 JSON：owner=null 省略 'owner' 键、可选字段无 ':null'（对齐 CC jsonStringify 省略 undefined）")
    void writeOmitsNullOwnerKey() throws Exception {
        TaskFileStorage storage = new TaskFileStorage(tempDir);
        storage.ensureTasksDir("tasklist-1");

        // 新建任务 owner 恒 null（对齐 TaskCreateTool.ts:86 owner: undefined）
        Task task = new Task("t-1", "subject-1", "desc-1", "Running tests", null,
            Task.TaskStatus.PENDING, List.of(), List.of(), Map.of());
        storage.writeTaskFile("tasklist-1", "t-1", task);

        String json = Files.readString(storage.getTaskPath("tasklist-1", "t-1"));

        // (a) owner=null 省略键 + 无 null 值（G5 后磁盘为美化格式，null 写盘即 ": null"）
        assertThat(json).doesNotContain("\"owner\"");
        assertThat(json).doesNotContain(": null");

        // (b) CC 显式传 [] 的字段必须保留（NON_NULL 不删空数组，且 G5 空容器仍为 [] 非 [ ]）；
        //    必填键存在。
        // 注：status 经 @JsonValue 序列化为小写 "pending"（对齐 CC TASK_STATUSES，
        // utils/tasks.ts:69 + OPD-TS-05），不再写大写 "PENDING"（旧 Java ⊕ 偏差）。
        // DC-3：parseTask 读回同样严格仅接受小写（大写 PENDING 等被拒，对齐 CC z.enum 严格大小写敏感）。
        // 注：G5 后落盘为 JSON.stringify(task, null, 2) 2 空格缩进（"key": value，冒号后单空格）。
        assertThat(json).contains("\"status\": \"pending\"");
        assertThat(json).contains("\"subject\": \"subject-1\"");
        assertThat(json).contains("\"blocks\": []");
        assertThat(json).contains("\"blockedBy\": []");
        assertThat(json).contains("\"id\": \"t-1\"");
    }

    /**
     * G5：writeTaskFile 落盘 2 空格缩进美化 JSON · 对齐 CC jsonStringify(task, null, 2)
     *
     * <p><b>WHY (意图验证)</b>: CC 落盘用 {@code jsonStringify(task, null, 2)}
     * （tasks.ts:300 createTask / tasks.ts:365 updateTaskUnsafe = JSON.stringify 纯包装，
     * slowOperations.ts:189），2 空格缩进 + {@code "key": value}（冒号后单空格、无前导空格）。
     * Jackson 默认 DefaultPrettyPrinter 输出 {@code "key" : value}（冒号前后各一空格）与
     * 空容器 {@code [ ]}/{@code { }}——与 CC 磁盘字节形状不一致（G5 修复项）。本测试锁住
     * 2 空格缩进 + 冒号无前导空格意图：若回退紧凑/默认美化实现，断言失败。
     */
    @Test
    @DisplayName("G5：writeTaskFile 落盘 2 空格缩进、'key': value 无前导空格（对齐 CC jsonStringify(task, null, 2)）")
    void writeUsesTwoSpaceIndentation() throws Exception {
        TaskFileStorage storage = new TaskFileStorage(tempDir);
        storage.ensureTasksDir("tasklist-1");

        Task task = new Task("t-1", "subject-1", "desc-1", "Running tests", null,
            Task.TaskStatus.PENDING, List.of("t-2"), List.of("t-0"),
            Map.of("k", "v"));
        storage.writeTaskFile("tasklist-1", "t-1", task);

        String json = Files.readString(storage.getTaskPath("tasklist-1", "t-1"));

        // 2 空格缩进：顶层 { 后换行 + 2 空格 + 首个字段
        assertThat(json).startsWith("{\n  \"id\": ");
        // 多行（非紧凑单行）
        assertThat(json).contains("\n  \"subject\": \"subject-1\"");
        assertThat(json).contains("\n  \"blocks\": [\n    \"t-2\"\n  ]");
        // 冒号前无空格（Jackson 默认美化是 " : "，含前导空格）
        assertThat(json).doesNotContain("\" : \"");
    }

    /**
     * round-trip：读回 owner==null 且 status 保持 pending
     *
     * <p>对齐 CC: 省略键文件经 TaskSchema.safeParse（optional 接受 undefined→缺省 null）
     * 读入；Java 侧 readTaskFile 宽松反序列化（parseTask 将缺失 owner 视为 null），
     * 与紧凑构造默认值互补，round-trip 不丢语义。
     */
    /**
     * OPD-TS-12：activeForm 缺省省略键 · 写一个 activeForm=null 的任务 → 磁盘 JSON 不含 "activeForm" 键
     *
     * <p><b>WHY (意图验证)</b>: CC TaskSchema activeForm 为 {@code z.string().optional()}
     * （tasks.ts:81，无默认值）——TaskCreateTool 未提供 activeForm 时（TaskCreateTool.ts:22
     * optional）传 undefined，jsonStringify 省略该键（tasks.ts:300，slowOperations.ts:189
     * JSON.stringify 纯包装）。Java record {@code @JsonInclude(NON_NULL)} 使 activeForm=null
     * 不再写出 {@code "activeForm":"subject"}——旧紧凑构造 {@code if (activeForm==null)
     * activeForm=subject}（Task.java:156）把缺省物化为 subject，磁盘多写一个 CC 不存在的键。
     * 本测试锁住「缺省 activeForm 不落盘」意图：构造器/工具层必须 null 透传。
     */
    @Test
    @DisplayName("OPD-TS-12：activeForm=null 省略 'activeForm' 键、无 ':null'（对齐 CC jsonStringify 省略 undefined 缺省键）")
    void writeOmitsNullActiveFormKey() throws Exception {
        TaskFileStorage storage = new TaskFileStorage(tempDir);
        storage.ensureTasksDir("tasklist-1");

        // 缺省 activeForm（未提供）→ null（对齐 CC TaskCreateTool.ts:22 optional）
        Task task = new Task("t-1", "subject-1", "desc-1", null, null,
            Task.TaskStatus.PENDING, List.of(), List.of(), Map.of());
        storage.writeTaskFile("tasklist-1", "t-1", task);

        String json = Files.readString(storage.getTaskPath("tasklist-1", "t-1"));

        // 省略键 + 无 null 值；缺省 activeForm 不物化为 subject
        assertThat(json).doesNotContain("\"activeForm\"");
        assertThat(json).doesNotContain(": null");
        // 对比键仍存在（证明写盘路径正常；G5 后为 2 空格缩进美化格式）
        assertThat(json).contains("\"subject\": \"subject-1\"");
    }

    @Test
    @DisplayName("readTaskFile round-trip：owner==null、status 保持 pending（省略键文件可正常读入）")
    void readRoundTripsNullOwner() throws Exception {
        TaskFileStorage storage = new TaskFileStorage(tempDir);
        storage.ensureTasksDir("tasklist-1");

        Task task = new Task("t-1", "subject-1", "desc-1", "Running tests", null,
            Task.TaskStatus.PENDING, List.of(), List.of(), Map.of());
        storage.writeTaskFile("tasklist-1", "t-1", task);

        Optional<Task> readBack = storage.readTaskFile("tasklist-1", "t-1");
        assertThat(readBack).isPresent();
        assertThat(readBack.get().owner()).isNull();
        assertThat(readBack.get().status()).isEqualTo(Task.TaskStatus.PENDING);
        assertThat(readBack.get().subject()).isEqualTo("subject-1");
    }

    /**
     * metadata 键序保真 round-trip · 对齐 CC JSON.stringify 保字符串键插入序
     *
     * <p><b>WHY (意图验证)</b>: CC 落盘用 {@code jsonStringify(task, null, 2)}（slowOperations.ts:189
     * 纯 JSON.stringify 包装），ES OrdinaryOwnPropertyKeys 对普通对象字符串键按插入序输出——CC 磁盘
     * metadata 键序 = 插入序（tasks.ts:300 createTask 对象字面量）。Java 旧实现 Task 紧凑构造器用
     * {@code new HashMap<>(metadata)} 防御性复制（Task.java:152），HashMap 迭代序无保证，Jackson
     * 序列化 metadata 按 entrySet 迭代序输出（TaskFileStorage.java:149 JSON.writeValueAsString(task)），
     * 会打乱磁盘键序。本测试锁住 "Task 构造 → 写盘 → 读回 → 再写盘" 全链路键序保真意图：构造器/merge
     * 必须 LinkedHashMap 复制，否则断言失败。
     *
     * <p>参考 CC 源码（grep 自验，非注释）：
     * <ul>
     *   <li>{@code const task: Task = { id, ...taskData }; writeFile(path, jsonStringify(task, null, 2))}
     *       — tasks.ts:298-300 createTask</li>
     *   <li>{@code return JSON.stringify(...)} — slowOperations.ts:189 jsonStringify 纯包装</li>
     *   <li>{@code const merged = { ...(existingTask.metadata ?? {}) }} — TaskUpdateTool.ts:222 spread 保序合并</li>
     * </ul>
     */
    @Test
    @DisplayName("metadata 键序保真：Task 构造→写盘→读回→再写盘 全链路键序=插入序（对齐 CC JSON.stringify）")
    void metadataKeyOrderPreservedOnRoundTrip() throws Exception {
        TaskFileStorage storage = new TaskFileStorage(tempDir);
        storage.ensureTasksDir("tasklist-1");

        // 以 z→a→m 特定插入序构造 metadata：
        // 若构造器/merge 用 HashMap 复制（无迭代序保证），磁盘键序会被打乱，断言失败。
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("z", 1);
        ordered.put("a", 2);
        ordered.put("m", 3);
        Task task = new Task("t-1", "subject-1", "desc-1", "Running tests", null,
            Task.TaskStatus.PENDING, List.of(), List.of(), ordered);

        // (a) 构造 → 写盘：磁盘原始 JSON 的 metadata 键序 = 插入序 z→a→m
        storage.writeTaskFile("tasklist-1", "t-1", task);
        assertMetadataKeyOrder(Files.readString(storage.getTaskPath("tasklist-1", "t-1")),
            List.of("z", "a", "m"));

        // (b) 读回 → 再写盘：Jackson 默认将 Map 反序列化为 LinkedHashMap（保读入序），
        //    紧凑构造器 LinkedHashMap 复制不丢序 → 再写盘键序仍保序（round-trip）。
        Optional<Task> readBack = storage.readTaskFile("tasklist-1", "t-1");
        assertThat(readBack).isPresent();
        storage.writeTaskFile("tasklist-1", "t-1", readBack.get());
        assertMetadataKeyOrder(Files.readString(storage.getTaskPath("tasklist-1", "t-1")),
            List.of("z", "a", "m"));
    }

    /** 断言磁盘 JSON 中 metadata 对象键序与期望插入序完全一致 */
    private static void assertMetadataKeyOrder(String json, List<String> expectedKeys) throws Exception {
        JsonNode metadata = TaskFileStorage.JSON.readTree(json).get("metadata");
        assertThat(metadata).isNotNull();
        List<String> actualKeys = new ArrayList<>();
        metadata.fieldNames().forEachRemaining(actualKeys::add);
        assertThat(actualKeys).containsExactlyElementsOf(expectedKeys);
    }
}
