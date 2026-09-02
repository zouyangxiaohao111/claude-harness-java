package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPD-TS-21 offset-evict 语义定向测试 · 对齐 CC framework.ts generateTaskAttachments /
 * applyTaskOffsetsAndEvictions / evictTerminalTask + diskOutput.ts getTaskOutputDelta。
 *
 * <p><b>WHY（意图验证，规则九）</b>: CC 的 framework offset-evict 是 task_progress 增量推送与终态 GC 的核心：
 * <ul>
 *   <li><b>offset 增量读</b>（diskOutput.ts:304-330）——只读"上次通知后新增"字节，避免每次 poll 重读整文件；
 *       若 offset 从不推进，每次 poll 重复消费同一批字节（双发）</li>
 *   <li><b>attachments 恒空</b>（framework.ts:158-206）——完成通知由各 task 类型 enqueuePendingNotification
 *       自管，generateTaskAttachments 再产附件会双发</li>
 *   <li><b>fresh state 重查</b>（framework.ts:213-249 TOCTOU）——delta 磁盘读期间任务可能 completed，
 *       旧快照 spread 会覆盖该转变（zombify）；resume 可能替换任务</li>
 *   <li><b>notified 闸</b>（framework.ts:124-147）——终态 && notified 才 evict；not-notified 终态保留
 *       （父还没消费），避免丢失</li>
 * </ul>
 *
 * <p>RED 证据：实施前 grep 全仓 generateTaskAttachments/applyTaskOffsetsAndEvictions/getTaskOutputDelta
 * 零命中（EVD-W302-01），本测试编译即失败。
 */
@DisplayName("[OPD-TS-21] framework offset-evict 语义")
class TaskFrameworkServiceOffsetEvictTest {

    @TempDir
    Path tempDir;

    private final TaskFrameworkService service = new TaskFrameworkService();

    private BackgroundTask runningTask(String id, String outputFile, long offset) {
        return new BackgroundTask(id, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            "desc", null, System.currentTimeMillis(), null, null, outputFile, offset, false);
    }

    private Path writeOutput(String content) throws IOException {
        Path p = tempDir.resolve("t" + Math.abs(System.nanoTime()) + ".out");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    // ════════════════════════════════════════════════════════════════
    // getTaskOutputDelta — offset 增量读（diskOutput.ts:304-330）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("增量读：仅返回 offset 之后新增字节，newOffset 推进")
    void getTaskOutputDelta_returnsOnlyNewBytesAndAdvancesOffset() throws IOException {
        // WHY: 只读 delta 而非全量 —— 第二次 read 从 newOffset 起，若实现重读全量，task_progress
        //   会重复推送已消费字节（CC diskOutput.ts:312-317 readFileRange 从 offset 读起）。
        Path out = writeOutput("hello world");
        service.registerTask(runningTask("t1", out.toString(), 0));

        TaskFrameworkService.TaskOutputDelta first = service.getTaskOutputDelta("t1", 0);
        assertThat(first.content()).isEqualTo("hello world");
        assertThat(first.newOffset()).isEqualTo(11L);

        TaskFrameworkService.TaskOutputDelta second = service.getTaskOutputDelta("t1", first.newOffset());
        assertThat(second.content()).isEmpty();
        assertThat(second.newOffset()).isEqualTo(11L);
    }

    @Test
    @DisplayName("ENOENT：输出文件不存在 → 空增量 + offset 不变（CC catch 全量吞掉）")
    void getTaskOutputDelta_enoent_returnsEmptyAndKeepsOffset() throws IOException {
        // WHY: 后台任务 output 文件由 worker 异步写入，poll 早于首个 chunk 落盘时不能当作故障；
        //   CC diskOutput.ts:322-329 ENOENT → {content:'', newOffset: fromOffset}。
        service.registerTask(runningTask("t-missing", tempDir.resolve("missing.out").toString(), 5));

        TaskFrameworkService.TaskOutputDelta delta = service.getTaskOutputDelta("t-missing", 5);
        assertThat(delta.content()).isEmpty();
        assertThat(delta.newOffset()).isEqualTo(5L);
    }

    @Test
    @DisplayName("size<=offset：无新内容 → 空增量 + offset 不变（readFileRange null）")
    void getTaskOutputDelta_sizeBelowOffset_returnsEmpty() throws IOException {
        // WHY: offset 已读至文件末尾 → 无新数据，offset 不前移（fsOperations.ts:652 size<=offset → null）。
        Path out = writeOutput("abc");
        service.registerTask(runningTask("t2", out.toString(), 3));

        TaskFrameworkService.TaskOutputDelta delta = service.getTaskOutputDelta("t2", 3);
        assertThat(delta.content()).isEmpty();
        assertThat(delta.newOffset()).isEqualTo(3L);
    }

    @Test
    @DisplayName("8MB cap：单次读取最多 maxBytes，newOffset 仅推进已读字节")
    void getTaskOutputDelta_capsAtMaxBytes() throws IOException {
        // WHY: 多 GB 输出不能整读入内存 —— CC diskOutput.ts:23 DEFAULT_MAX_READ_BYTES=8MB；
        //   newOffset = fromOffset + 实际读到的字节数，剩余留待下次 poll。
        Path out = writeOutput("a".repeat(20));
        service.registerTask(runningTask("t3", out.toString(), 0));

        TaskFrameworkService.TaskOutputDelta delta = service.getTaskOutputDelta("t3", 0, 10);
        assertThat(delta.content()).isEqualTo("a".repeat(10));
        assertThat(delta.newOffset()).isEqualTo(10L);
    }

    // ════════════════════════════════════════════════════════════════
    // generateTaskAttachments + applyTaskOffsetsAndEvictions
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("running 有新输出 → 推进 offset；attachments 恒空（不产附件通知）")
    void generateTaskAttachments_advancesOffset_attachmentsAlwaysEmpty() throws IOException {
        // WHY: attachments 恒空是防双发 —— 完成通知由各 task 类型 enqueuePendingNotification 自管
        //   （framework.ts:199-202），这里再产附件会重复投递；offset 推进则保证下次只读增量。
        Path out = writeOutput("progress line\n");
        service.registerTask(runningTask("t4", out.toString(), 0));

        TaskFrameworkService.TaskAttachmentsResult r = service.generateTaskAttachments();

        assertThat(r.attachments()).isEmpty();
        assertThat(r.updatedTaskOffsets()).containsEntry("t4", 14L);
        assertThat(r.evictedTaskIds()).isEmpty();

        // 应用后 offset 落地，再 poll 无新内容 → 不再推进（幂等，不双发）
        service.applyTaskOffsetsAndEvictions(r.updatedTaskOffsets(), r.evictedTaskIds());
        TaskFrameworkService.TaskAttachmentsResult r2 = service.generateTaskAttachments();
        assertThat(r2.updatedTaskOffsets()).isEmpty();
    }

    @Test
    @DisplayName("applyTaskOffsetsAndEvictions 对 fresh state 重查：任务已终态则 offset 不落地（TOCTOU）")
    void applyTaskOffsets_skipsWhenFreshTaskNoLongerRunning() throws IOException {
        // WHY: generateTaskAttachments 与 apply 之间（CC await 磁盘读窗口）任务可能 completed；
        //   旧快照 spread 会覆盖该转变（framework.ts:224-232 fresh?.status==='running' 才更新）。
        Path out = writeOutput("delta");
        service.registerTask(runningTask("t5", out.toString(), 0));
        Map<String, Long> offsets = Map.of("t5", 5L);

        // 模拟窗口内任务终态（CAS 推进 + notified）
        BackgroundTask terminal = service.getTask("t5").orElseThrow()
            .withStatus(BackgroundTaskStatus.COMPLETED)
            .withNotified();
        service.updateTaskState("t5", terminal);

        service.applyTaskOffsetsAndEvictions(offsets, List.of());

        assertThat(service.getTask("t5").orElseThrow().outputOffset())
            .as("终态任务的 offset patch 应被丢弃，不覆盖终态转变")
            .isEqualTo(0L);
    }

    @Test
    @DisplayName("终态 && notified → 惰性 evict（generateTaskAttachments 兜底 GC）")
    void generateTaskAttachments_evictsTerminalNotified() throws IOException {
        // WHY: framework.ts:123 lazy GC as safety net —— 饿式 evictTerminalTask 未走到的终态任务，
        //   下次 poll 时惰性回收，避免 store 无限增长。
        service.registerTask(runningTask("t6", writeOutput("x").toString(), 0));
        BackgroundTask terminal = service.getTask("t6").orElseThrow()
            .withStatus(BackgroundTaskStatus.COMPLETED)
            .withNotified();
        service.updateTaskState("t6", terminal);

        TaskFrameworkService.TaskAttachmentsResult r = service.generateTaskAttachments();
        assertThat(r.evictedTaskIds()).containsExactly("t6");
        assertThat(r.updatedTaskOffsets()).isEmpty();

        service.applyTaskOffsetsAndEvictions(Map.of(), r.evictedTaskIds());
        assertThat(service.getTask("t6")).isEmpty();
    }

    @Test
    @DisplayName("终态 && not-notified → 不 evict（父还没消费，防丢失）")
    void generateTaskAttachments_keepsTerminalNotNotified() throws IOException {
        // WHY: CC framework.ts:174-179 eviction 仅当 notified —— 未通知的终态任务父尚未消费，
        //   删除即丢失结果。
        service.registerTask(runningTask("t7", writeOutput("x").toString(), 0));
        BackgroundTask terminal = service.getTask("t7").orElseThrow()
            .withStatus(BackgroundTaskStatus.FAILED);
        service.updateTaskState("t7", terminal);

        TaskFrameworkService.TaskAttachmentsResult r = service.generateTaskAttachments();
        assertThat(r.evictedTaskIds()).isEmpty();

        service.applyTaskOffsetsAndEvictions(Map.of(), r.evictedTaskIds());
        assertThat(service.getTask("t7")).isPresent();
    }

    @Test
    @DisplayName("空 patch → 早退（无 offsets 无 evictions 不触碰 store）")
    void applyTaskOffsetsAndEvictions_noopWhenBothEmpty() throws IOException {
        service.registerTask(runningTask("t8", writeOutput("x").toString(), 0));
        BackgroundTask before = service.getTask("t8").orElseThrow();

        service.applyTaskOffsetsAndEvictions(Map.of(), List.of());

        assertThat(service.getTask("t8")).containsSame(before);
    }

    // ════════════════════════════════════════════════════════════════
    // evictTerminalTask — 饿式 evict 三闸（framework.ts:124-147）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("evictTerminalTask 需 notified：not-notified 终态不删，notified 终态删")
    void evictTerminalTask_requiresNotified() throws IOException {
        // WHY: 终态但未 notified = 完成通知还没发，删了父就永远收不到（framework.ts:133-134 双闸）。
        service.registerTask(runningTask("t9", writeOutput("x").toString(), 0));
        service.updateTaskState("t9", service.getTask("t9").orElseThrow()
            .withStatus(BackgroundTaskStatus.COMPLETED));

        service.evictTerminalTask("t9");
        assertThat(service.getTask("t9")).as("not-notified 终态不应被饿式 evict").isPresent();

        service.updateTaskState("t9", service.getTask("t9").orElseThrow().withNotified());
        service.evictTerminalTask("t9");
        assertThat(service.getTask("t9")).as("notified 终态应被饿式 evict").isEmpty();
    }

    @Test
    @DisplayName("evictTerminalTask 对 running 任务不生效")
    void evictTerminalTask_skipsRunning() throws IOException {
        service.registerTask(runningTask("t10", writeOutput("x").toString(), 0));
        service.evictTerminalTask("t10");
        assertThat(service.getTask("t10")).isPresent();
    }

    // ════════════════════════════════════════════════════════════════
    // pollTasks — 保留定义（OPD-TP-04 两端对称）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("pollTasks 保留定义：返回终态 && not-notified 任务")
    void pollTasks_keptDefinition() throws IOException {
        service.registerTask(runningTask("t11", writeOutput("x").toString(), 0));
        service.updateTaskState("t11", service.getTask("t11").orElseThrow()
            .withStatus(BackgroundTaskStatus.KILLED));

        assertThat(service.pollTasks()).hasSize(1);
        assertThat(service.pollTasks().get(0).id()).isEqualTo("t11");
    }
}
