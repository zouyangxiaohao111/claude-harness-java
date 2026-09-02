package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * getTask ant 旧状态迁移定向测试 · 对齐 CC tasks.ts:317-339
 *
 * <p><b>WHY (意图验证)</b>: CC 在 getTask 读文件后、TaskSchema().safeParse 之前，当
 * USER_TYPE==='ant' 时将遗留状态迁移（open→pending / resolved→completed /
 * planning|implementing|reviewing|verifying→in_progress，tasks.ts:320-331），否则遗留
 * 状态文件会被 zod 严格 z.enum 校验拒绝（tasks.ts:71-74 + 334-339 → return null）。Java
 * 侧若不迁移，遗留状态直接 Jackson 反序列化失败 → Optional.empty（任务被当作不存在）——
 * 与 CC『ant 用户读到历史会话任务』的行为不一致。本测试验证 ant 迁移生效、非 ant 用户
 * 行为不变。
 *
 * <p>参考 CC 源码（grep 自验，非注释）：
 * <ul>
 *   <li>{@code if (process.env.USER_TYPE === 'ant')} — tasks.ts:320</li>
 *   <li>{@code if (data.status === 'open') data.status = 'pending'} — tasks.ts:321</li>
 *   <li>{@code else if (data.status === 'resolved') data.status = 'completed'} — tasks.ts:322</li>
 *   <li>{@code ['planning', 'implementing', 'reviewing', 'verifying'].includes(data.status)}
 *       → {@code data.status = 'in_progress'} — tasks.ts:326/330</li>
 *   <li>{@code const parsed = TaskSchema().safeParse(data)} — tasks.ts:333</li>
 * </ul>
 *
 * <p>注：Java 迁移作用于原始 JSON 文本的 status 字段（反序列化之前，对齐 tasks.ts:317），
 * 通过 {@code readTaskFileMigrated(..., antUser)} 测试缝注入（避免依赖真实
 * System.getenv("USER_TYPE")，对齐 codebase StuckSkillRegistrar.java:24 /
 * RememberSkillRegistrar.java:18 参数注入测试惯例）。
 */
class TaskServiceAntMigrationTest {

    @TempDir
    Path tempDir;

    private TaskService service() {
        return new TaskService(tempDir);
    }

    private Path taskFile(String taskListId, String taskId) {
        return tempDir.resolve("tasks").resolve(taskListId).resolve(taskId + ".json");
    }

    private void writeTask(String taskListId, String taskId, String status) throws IOException {
        Files.createDirectories(taskFile(taskListId, taskId).getParent());
        Files.writeString(taskFile(taskListId, taskId),
            "{\n"
                + "  \"id\": \"" + taskId + "\",\n"
                + "  \"subject\": \"遗留任务\",\n"
                + "  \"description\": \"ant 旧状态迁移测试\",\n"
                + "  \"status\": \"" + status + "\",\n"
                + "  \"blocks\": [],\n"
                + "  \"blockedBy\": []\n"
                + "}");
    }

    // ────────────────────────────────────────────────────────────────────────
    // ant 用户：遗留状态迁移
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("antUser=true + status=open → 迁移为 PENDING（对齐 CC tasks.ts:321）")
    void antOpenMigratesToPending() throws IOException {
        writeTask("list-1", "1", "open");

        Optional<Task> task = service().readTaskFileMigrated("list-1", "1", true);

        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo(Task.TaskStatus.PENDING);
        assertThat(task.get().id()).isEqualTo("1");
    }

    @Test
    @DisplayName("antUser=true + status=resolved → 迁移为 COMPLETED（对齐 CC tasks.ts:322）")
    void antResolvedMigratesToCompleted() throws IOException {
        writeTask("list-1", "1", "resolved");

        Optional<Task> task = service().readTaskFileMigrated("list-1", "1", true);

        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo(Task.TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("antUser=true + 开发态 planning/implementing/reviewing/verifying → 迁移为 IN_PROGRESS（对齐 CC tasks.ts:326/330）")
    void antDevStatusesMigrateToInProgress() throws IOException {
        String[] dev = {"planning", "implementing", "reviewing", "verifying"};
        TaskService service = service();
        for (int i = 0; i < dev.length; i++) {
            String id = String.valueOf(i + 1);
            writeTask("list-1", id, dev[i]);

            assertThat(service.readTaskFileMigrated("list-1", id, true))
                .as("status=%s 应迁移为 IN_PROGRESS", dev[i])
                .hasValueSatisfying(t -> assertThat(t.status()).isEqualTo(Task.TaskStatus.IN_PROGRESS));
        }
    }

    @Test
    @DisplayName("antUser=true + 合法 pending 保持不变（迁移不命中则原样返回）")
    void antValidStatusUnchanged() throws IOException {
        writeTask("list-1", "1", "pending");

        Optional<Task> task = service().readTaskFileMigrated("list-1", "1", true);

        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo(Task.TaskStatus.PENDING);
    }

    @Test
    @DisplayName("antUser=true + 大写 Java 遗留状态 IN_PROGRESS → 拒绝（DC-3 移除大写容忍，迁移表仅小写遗留值）")
    void antUpperCaseJavaLegacyRejected() throws IOException {
        // DC-3：parseStatusStrict 大写容忍已移除（对齐 CC tasks.ts:71-74 严格 z.enum 小写）。
        // 大写 IN_PROGRESS 不在 ant 迁移表（仅 open/resolved/planning/implementing/reviewing/verifying），
        // 迁移不命中 → parseStatusStrict("IN_PROGRESS") → null → 校验失败 Optional.empty（对齐 CC zod 失败）。
        writeTask("list-1", "1", "IN_PROGRESS");

        Optional<Task> task = service().readTaskFileMigrated("list-1", "1", true);

        assertThat(task).isEmpty();
    }

    @Test
    @DisplayName("antUser=true + status 非遗留值（如 done）→ 迁移不命中 → 校验失败 Optional.empty（等价 CC zod 失败）")
    void antUnmigratableRejected() throws IOException {
        writeTask("list-1", "1", "done");

        Optional<Task> task = service().readTaskFileMigrated("list-1", "1", true);

        assertThat(task).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────
    // 非 ant 用户：行为不变（遗留状态读失败 → Optional.empty，等价 zod 失败 → null）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("antUser=false + status=open → 不迁移 → Optional.empty（对齐 CC 非 ant 用户 zod 拒绝）")
    void nonAntOpenRejected() throws IOException {
        writeTask("list-1", "1", "open");

        Optional<Task> task = service().readTaskFileMigrated("list-1", "1", false);

        assertThat(task).isEmpty();
    }

    @Test
    @DisplayName("antUser=false + 合法 pending → 正常读取 PENDING（非 ant 用户行为零变化）")
    void nonAntValidStatusReads() throws IOException {
        writeTask("list-1", "1", "pending");

        Optional<Task> task = service().readTaskFileMigrated("list-1", "1", false);

        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo(Task.TaskStatus.PENDING);
    }

    @Test
    @DisplayName("antUser=false + 小写 CC 状态 in_progress → 正常读取 IN_PROGRESS")
    void nonAntLowerCaseCcReads() throws IOException {
        writeTask("list-1", "1", "in_progress");

        Optional<Task> task = service().readTaskFileMigrated("list-1", "1", false);

        assertThat(task).isPresent();
        assertThat(task.get().status()).isEqualTo(Task.TaskStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("文件不存在 → Optional.empty（对齐 CC ENOENT → null，tasks.ts:341-344）")
    void missingFileEmpty() {
        assertThat(service().readTaskFileMigrated("list-1", "nope", true)).isEmpty();
        assertThat(service().readTaskFileMigrated("list-1", "nope", false)).isEmpty();
    }

    // ────────────────────────────────────────────────────────────────────────
    // listTaskFiles 读路径复用迁移（FIX-G4 结构对齐：CC listTasks 经 getTask）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listTaskFiles(antUser=true)：列表内遗留状态文件经迁移全部读出（对齐 CC listTasks 经 getTask 的 ant 迁移）")
    void listTaskFilesAntMigratesLegacyStatuses() throws IOException {
        writeTask("list-1", "1", "open");
        writeTask("list-1", "2", "resolved");
        writeTask("list-1", "3", "planning");
        writeTask("list-1", "4", "pending");

        TaskFileStorage fs = new TaskFileStorage(tempDir);
        List<Task> tasks = fs.listTaskFiles("list-1", true);

        // WHY：getTask 与 listTaskFiles 读路径分叉时，同一 ant 用户旧状态文件 getTask 可读、
        // listTasks 却因 schema 校验失败丢弃——FIX-G4 统一读入口后应全部读出且迁移生效。
        assertThat(tasks).extracting(Task::id).containsExactlyInAnyOrder("1", "2", "3", "4");
        assertThat(tasks).extracting(t -> t.id() + "=" + t.status())
            .containsExactlyInAnyOrder("1=" + Task.TaskStatus.PENDING,
                "2=" + Task.TaskStatus.COMPLETED,
                "3=" + Task.TaskStatus.IN_PROGRESS,
                "4=" + Task.TaskStatus.PENDING);
    }

    @Test
    @DisplayName("listTaskFiles(antUser=false)：遗留状态文件不迁移被剔除，合法文件照常列出（非 ant 行为不变）")
    void listTaskFilesNonAntSkipsLegacyStatuses() throws IOException {
        writeTask("list-1", "1", "open");
        writeTask("list-1", "2", "pending");

        TaskFileStorage fs = new TaskFileStorage(tempDir);
        List<Task> tasks = fs.listTaskFiles("list-1", false);

        // WHY：非 ant 用户不迁移（tasks.ts:320 条件不成立），遗留状态 open 应被严格校验
        // 剔除（等价 zod 失败 → null），合法 pending 文件照常列出——行为与迁移前完全一致。
        assertThat(tasks).extracting(Task::id).containsExactly("2");
    }

    // ────────────────────────────────────────────────────────────────────────
    // FIX-G4b：变更路径（updateTask/blockTask/claimTask）统一迁移读
    // ────────────────────────────────────────────────────────────────────────
    //
    // WHY：FIX-G4 已把 getTask/listTasks 统一到 readTaskFileMigrated（含 ant 迁移），但
    // updateTask/blockTask/claimTask 的变更路径仍走非迁移 readTaskFile——ant 用户 + 遗留
    // 状态文件（status=open）时 schema 校验失败返回 empty，任务被当作不存在。CC 真源
    // （utils/tasks.ts:359/:379/:464-465/:551/:569 grep 实证）全部经 getTask（含迁移
    // tasks.ts:320-331）读取，Java 侧变更路径必须同源。System.getenv 在 JVM 内只读，经
    // {@link TaskService#ENV_READER} 注入缝控制 USER_TYPE（对齐 ErrorClassifier.java:61）。
    //
    // CC 源码（grep 自验，非注释）：
    //   - updateTask 前置无锁检查：const taskBeforeLock = await getTask(...) — utils/tasks.ts:379
    //   - updateTask 锁内重读（updateTaskUnsafe）：const existing = await getTask(...) — utils/tasks.ts:359
    //   - blockTask：Promise.all([getTask(fromTaskId), getTask(toTaskId)]) — utils/tasks.ts:464-465
    //   - claimTask 前置无锁检查：const taskBeforeLock = await getTask(...) — utils/tasks.ts:551
    //   - claimTask 锁内重读：const task = await getTask(...) — utils/tasks.ts:569

    private Function<String, String> originalEnvReader;

    @BeforeEach
    void captureEnvReader() {
        originalEnvReader = TaskService.ENV_READER;
    }

    @AfterEach
    void restoreEnvReader() {
        TaskService.ENV_READER = originalEnvReader;
    }

    /** 强制 ant 用户（USER_TYPE 读缝注入，确定性，与宿主 shell 环境无关） */
    private void withAntUser() {
        TaskService.ENV_READER = name -> "ant";
    }

    /** 强制非 ant 用户（确定性，与宿主 shell USER_TYPE 无关） */
    private void withNonAntUser() {
        TaskService.ENV_READER = name -> null;
    }

    @Test
    @DisplayName("antUser + 遗留 open → updateTask 前置检查经迁移读通过并返回 PENDING（对齐 CC utils/tasks.ts:359/:379）")
    void updateTaskAntLegacyMigrates() throws IOException {
        withAntUser();
        writeTask("list-1", "1", "open");

        Optional<Task> result = service().updateTask("list-1", "1", Map.of("description", "已更新"));

        // WHY：FIX-G4b 前 updateTask 前置无锁存在性检查（镜像 CC tasks.ts:379 taskBeforeLock =
        // await getTask）用非迁移 readTaskFile，ant 用户 + 遗留 open 文件 schema 校验失败 →
        // Optional.empty，与 CC 经 getTask（含迁移 tasks.ts:320-331）读到历史任务不一致。
        // 迁移读后前置检查应通过、锁内重读（tasks.ts:359）返回已迁移 PENDING。
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(Task.TaskStatus.PENDING);
        assertThat(result.get().description()).isEqualTo("已更新");
    }

    @Test
    @DisplayName("非 ant + 遗留 open → updateTask 前置检查仍 empty（非 ant 行为零变化）")
    void updateTaskNonAntLegacyStillEmpty() throws IOException {
        withNonAntUser();
        writeTask("list-1", "1", "open");

        Optional<Task> result = service().updateTask("list-1", "1", Map.of("description", "x"));

        // WHY：非 ant 用户不迁移（CC tasks.ts:320 条件不成立），遗留 open 被严格校验拒绝 → empty，
        // 迁移前与迁移后非 ant 行为必须完全一致（防 ant 修复破坏非 ant 语义）。
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("antUser + 两任务均遗留 open → blockTask 经迁移读建立阻塞关系（对齐 CC utils/tasks.ts:464-465）")
    void blockTaskAntLegacyMigrates() throws IOException {
        withAntUser();
        writeTask("list-1", "1", "open");
        writeTask("list-1", "2", "open");

        boolean ok = service().blockTask("list-1", "1", "2");

        // WHY：FIX-G4b 前 blockTask 用非迁移 readTaskFile，ant 用户 + 遗留 open 文件 from/to
        // 读取均 empty → 返回 false，与 CC 经 getTask（含迁移 tasks.ts:464-465）读到任务并
        // 建立阻塞关系不一致。迁移读后应能读到两任务并落盘 blocks/blockedBy。
        assertThat(ok).isTrue();
        Optional<Task> from = service().readTaskFileMigrated("list-1", "1", true);
        Optional<Task> to = service().readTaskFileMigrated("list-1", "2", true);
        assertThat(from).hasValueSatisfying(t -> assertThat(t.blocks()).contains("2"));
        assertThat(to).hasValueSatisfying(t -> assertThat(t.blockedBy()).contains("1"));
    }

    @Test
    @DisplayName("antUser + 遗留 open → claimTask 前置检查经迁移读通过并认领成功（对齐 CC utils/tasks.ts:551/:569）")
    void claimTaskAntLegacyMigrates() throws IOException {
        withAntUser();
        writeTask("list-1", "1", "open");

        ClaimTaskResult result = service().claimTask("list-1", "1", "agent-1");

        // WHY：FIX-G4b 前 claimTask 前置无锁存在性检查（镜像 CC tasks.ts:551 taskBeforeLock =
        // await getTask）用非迁移读，ant 用户 + 遗留 open 文件 → task_not_found，与 CC 迁移读
        // 读到任务并认领不一致（锁内重读 tasks.ts:569 同理）。迁移读后前置检查应通过并认领成功。
        assertThat(result).isInstanceOfSatisfying(ClaimTaskResult.Success.class,
            s -> assertThat(s.task().owner()).isEqualTo("agent-1"));
    }

    // ────────────────────────────────────────────────────────────────────────
    // migrateLegacyStatus 纯函数（可脱离 env 直接测 6 种映射）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("migrateLegacyStatus 纯函数：6 种映射 + 原值不变")
    void migratePureFunction() throws JsonProcessingException {
        assertThat(statusOf(migrate("open"))).isEqualTo("pending");
        assertThat(statusOf(migrate("resolved"))).isEqualTo("completed");
        assertThat(statusOf(migrate("planning"))).isEqualTo("in_progress");
        assertThat(statusOf(migrate("implementing"))).isEqualTo("in_progress");
        assertThat(statusOf(migrate("reviewing"))).isEqualTo("in_progress");
        assertThat(statusOf(migrate("verifying"))).isEqualTo("in_progress");
        // 非映射值原样返回
        assertThat(statusOf(migrate("pending"))).isEqualTo("pending");
        assertThat(statusOf(migrate("completed"))).isEqualTo("completed");
        assertThat(statusOf(migrate("done"))).isEqualTo("done");
    }

    @Test
    @DisplayName("migrateLegacyStatus 纯函数：status 缺失/非字符串/非法 JSON → 原样返回")
    void migrateEdgeCases() throws JsonProcessingException {
        // status 缺失
        assertThat(TaskFileStorage.migrateLegacyStatus("{\"id\":\"1\"}")).isEqualTo("{\"id\":\"1\"}");
        // 非对象 JSON
        assertThat(TaskFileStorage.migrateLegacyStatus("[1,2,3]")).isEqualTo("[1,2,3]");
        // 合法 JSON 且迁移（结构保持可解析）
        String migrated = TaskFileStorage.migrateLegacyStatus("{\"status\":\"open\",\"id\":\"1\"}");
        assertThat(statusOf(migrated)).isEqualTo("pending");
        assertThat(migrated).contains("\"id\"");
    }

    /** 将 status 值包装为合法 JSON 再调 migrateLegacyStatus（FIX-G4 后迁移实现归 storage 统一读入口） */
    private static String migrate(String status) throws JsonProcessingException {
        return TaskFileStorage.migrateLegacyStatus("{\"status\":\"" + status + "\"}");
    }

    private static String statusOf(String json) throws JsonProcessingException {
        JsonNode node = TaskFileStorage.JSON.readTree(json);
        return node.get("status").textValue();
    }
}
