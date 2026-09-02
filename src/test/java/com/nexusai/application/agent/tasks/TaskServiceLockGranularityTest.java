package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * lock-test · 定向验证既有改造的锁粒度对齐 CC tasks.ts（文件级 vs 列表级）
 *
 * <p><b>WHY (意图验证)</b>: CC tasks.ts 的锁粒度是<b>分操作</b>的——
 * 列表级锁（tasksDir/.lock）仅用于 {@code createTask}（tasks.ts:293 lockfile.lock(lockPath)）
 * 与 {@code resetTaskList}（tasks.ts:154 lockfile.lock(lockPath)）；
 * 文件级锁（{taskId}.json.lock，镜像 proper-lockfile 的 {@code ${file}.lock}）仅用于
 * {@code updateTask}（tasks.ts:386 lockfile.lock(path)）与 {@code claimTask}
 * （tasks.ts:566 lockfile.lock(taskPath)）；{@code getTask}（tasks.ts:310-349 raw readFile）、
 * {@code listTasks}（tasks.ts:443-456 readdir）、{@code deleteTask}（tasks.ts:393-441 级联走
 * updateTask 文件锁）、{@code blockTask}（tasks.ts:458-486 两次 updateTask 文件锁）均
 * <b>无列表锁</b>。CC 列表锁路径 = {@code tasksDir/.lock}（tasks.ts:504-505 getTaskListLockPath）。
 *
 * <p>本测试通过<b>锁文件存在性</b>断言各操作实际走到的锁类型：
 * {@link TaskLock#withRetryLoop} 在取锁前经 ensureLockFile / ensureFileLockFile
 * <b>预建锁文件</b>（TaskLock.java:120-189），因此「锁文件存在」=「该锁类型被实际走到」的
 * 证据；再以负向/正向互斥断言证明两类锁粒度互相不阻塞 / 同类锁互相阻塞。
 *
 * <p>本测试<b>不改任何主代码</b>——全部断言基于既有改造（TaskService.java / TaskLock.java
 * 现状），若断言暴露主代码与 CC 不符，仅上报 concerns，不擅自改主代码。
 *
 * <p>回归锚点：旧的统一列表级 {@code TaskLock.withLockAndReturn} 脏代码已删——
 * 若 updateTask/claimTask 回退到列表级锁（tasksDir/.lock），测试 c/d 的「.lock 不存在」
 * 断言变红；若 createTask/resetTaskList 回退到文件级锁，测试 a/b 的「.lock 存在」断言变红。
 *
 * <p>参考 CC 源码（grep 自验，非注释）：
 * <ul>
 *   <li>{@code resetTaskList: release = await lockfile.lock(lockPath, ...)} — tasks.ts:154</li>
 *   <li>{@code createTask: release = await lockfile.lock(lockPath, ...)} — tasks.ts:293</li>
 *   <li>{@code getTaskListLockPath = join(getTasksDir(taskListId), '.lock')} — tasks.ts:504-505</li>
 *   <li>{@code getTask: await readFile(path)}（无锁）— tasks.ts:314-316</li>
 *   <li>{@code updateTask: release = await lockfile.lock(path, ...)}（path=任务文件）— tasks.ts:386</li>
 *   <li>{@code deleteTask: unlink + 级联 updateTask}（无列表锁）— tasks.ts:393-441</li>
 *   <li>{@code listTasks: readdir}（无锁）— tasks.ts:443-456</li>
 *   <li>{@code blockTask: 两次 updateTask}（无列表锁）— tasks.ts:458-486</li>
 *   <li>{@code claimTask: release = await lockfile.lock(taskPath, ...)} — tasks.ts:566</li>
 * </ul>
 */
class TaskServiceLockGranularityTest {

    @TempDir
    Path tempDir;

    private TaskService newService() {
        // 显式 configHome 构造器：隔离真实 ~/.claude 目录，逐用例独立 @TempDir
        return new TaskService(tempDir);
    }

    private Path listLockPath(TaskService service, String listId) {
        return TaskLock.getLockPath(service.getTasksDir(listId));
    }

    private Path fileLockPath(TaskService service, String listId, String taskId) {
        return TaskLock.getFileLockPath(service.getTaskPath(listId, taskId));
    }

    /**
     * 测试线程持锁工具：对 lockPath 打开 WRITE channel 并 tryLock。
     *
     * <p>返回持有的 {@link FileLock}（channel 由调用方负责关闭以释放锁）。
     * 若锁文件不存在先创建（对齐 TaskLock ensureLockFile / ensureFileLockFile 的预建语义）。
     *
     * @throws IOException 文件创建/打开失败
     */
    private static FileLock holdLock(Path lockPath) throws IOException {
        if (!Files.exists(lockPath)) {
            Files.createFile(lockPath);
        }
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
        return channel.tryLock();
    }

    @Test
    @DisplayName("a) createTask 走列表级锁：tasksDir/.lock 存在、{id}.json.lock 不存在")
    void createTaskUsesListLockNotFileLock() {
        TaskService service = newService();
        String listId = "lock-create-list";

        String id = service.createTask(listId, Task.create("任务一", "第一个任务"));

        // createTask 用 withLockAndReturn(tasksDir)（TaskService.java:328）→ ensureLockFile 预建 .lock
        assertThat(listLockPath(service, listId)).as("createTask 应创建列表级锁 tasksDir/.lock").exists();
        // createTask 不碰文件级锁（{id}.json.lock）
        assertThat(fileLockPath(service, listId, id)).as("createTask 不得创建文件级锁 {id}.json.lock").doesNotExist();
    }

    @Test
    @DisplayName("b) resetTaskList 走列表级锁：空列表 reset 后 tasksDir/.lock 存在")
    void resetTaskListUsesListLock() {
        TaskService service = newService();
        String listId = "lock-reset-list";

        service.resetTaskList(listId);

        // resetTaskList 用 withLock(tasksDir)（TaskService.java:797）→ ensureLockFile 预建 .lock
        // 对齐 CC resetTaskList tasks.ts:154 lockfile.lock(lockPath)
        assertThat(listLockPath(service, listId)).as("resetTaskList 应创建列表级锁 tasksDir/.lock").exists();
    }

    @Test
    @DisplayName("c) updateTask 走文件级锁：{1}.json.lock 存在、.lock 不存在")
    void updateTaskUsesFileLockNotListLock() throws IOException {
        TaskService service = newService();
        String listId = "lock-update-list";

        service.createTask(listId, Task.create("任务一", ""));
        // 清列表锁：排除 createTask 预建的 .lock 干扰
        Files.deleteIfExists(listLockPath(service, listId));

        Optional<Task> updated = service.updateTask(listId, "1", Map.of("subject", "改标题"));

        assertThat(updated).isPresent();
        // updateTask(Map) 用 withFileLockAndReturn(taskPath)（TaskService.java:656）→ 预建 {1}.json.lock
        // 对齐 CC updateTask tasks.ts:386 lockfile.lock(path)
        assertThat(fileLockPath(service, listId, "1")).as("updateTask 应创建文件级锁 {1}.json.lock").exists();
        assertThat(listLockPath(service, listId)).as("updateTask 不得创建列表级锁 tasksDir/.lock").doesNotExist();
    }

    @Test
    @DisplayName("d) claimTask 走文件级锁：result=Success、{1}.json.lock 存在、.lock 不存在")
    void claimTaskUsesFileLockNotListLock() throws IOException {
        TaskService service = newService();
        String listId = "lock-claim-list";

        service.createTask(listId, Task.create("任务一", ""));
        // 清列表锁：排除 createTask 预建的 .lock 干扰
        Files.deleteIfExists(listLockPath(service, listId));

        ClaimTaskResult result = service.claimTask(listId, "1", "agent-a");

        // 新建任务 owner=null / status=pending / blockedBy 空 → 走 Success 分支
        assertThat(result).isInstanceOf(ClaimTaskResult.Success.class);
        // claimTask 用 withFileLockAndReturn(taskPath)（TaskService.java:968）→ 预建 {1}.json.lock
        // 对齐 CC claimTask tasks.ts:566 lockfile.lock(taskPath)
        assertThat(fileLockPath(service, listId, "1")).as("claimTask 应创建文件级锁 {1}.json.lock").exists();
        assertThat(listLockPath(service, listId)).as("claimTask 不得创建列表级锁 tasksDir/.lock").doesNotExist();
    }

    @Test
    @DisplayName("e) getTask 无锁：调用前后 .lock 与 {1}.json.lock 均不存在")
    void getTaskTakesNoLock() throws IOException {
        TaskService service = newService();
        String listId = "lock-get-list";

        service.createTask(listId, Task.create("任务一", ""));
        // 清掉 createTask 可能预建的锁文件，验证 getTask 不再创建
        Files.deleteIfExists(listLockPath(service, listId));
        Files.deleteIfExists(fileLockPath(service, listId, "1"));

        Optional<Task> task = service.getTask(listId, "1");

        assertThat(task).isPresent();
        // 对齐 CC getTask tasks.ts:314-316 raw readFile（无锁）
        assertThat(listLockPath(service, listId)).as("getTask 不得创建列表级锁").doesNotExist();
        assertThat(fileLockPath(service, listId, "1")).as("getTask 不得创建文件级锁").doesNotExist();
    }

    @Test
    @DisplayName("f) listTasks 无锁：调用前后 .lock 与 {1}.json.lock 均不存在")
    void listTasksTakesNoLock() throws IOException {
        TaskService service = newService();
        String listId = "lock-list-list";

        service.createTask(listId, Task.create("任务一", ""));
        Files.deleteIfExists(listLockPath(service, listId));
        Files.deleteIfExists(fileLockPath(service, listId, "1"));

        var tasks = service.listTasks(listId);

        assertThat(tasks).hasSize(1);
        // 对齐 CC listTasks tasks.ts:443-456 readdir（无锁）
        assertThat(listLockPath(service, listId)).as("listTasks 不得创建列表级锁").doesNotExist();
        assertThat(fileLockPath(service, listId, "1")).as("listTasks 不得创建文件级锁").doesNotExist();
    }

    @Test
    @DisplayName("g) deleteTask 无列表锁：删唯一任务后 .lock 与 {1}.json.lock 均不存在")
    void deleteTaskTakesNoListLock() throws IOException {
        TaskService service = newService();
        String listId = "lock-delete-list";

        service.createTask(listId, Task.create("任务一", ""));
        // 清列表锁：排除 createTask 预建的 .lock 干扰
        Files.deleteIfExists(listLockPath(service, listId));

        boolean deleted = service.deleteTask(listId, "1");

        assertThat(deleted).isTrue();
        // deleteTask 自身 unlink 无锁（对齐 CC tasks.ts:393-441）；级联走 updateTask 文件锁，
        // 但本用例是唯一任务、删除后 listTasks 为空 → 无级联 → 不创建任何锁文件
        assertThat(listLockPath(service, listId)).as("deleteTask 不得创建列表级锁").doesNotExist();
        assertThat(fileLockPath(service, listId, "1")).as("deleteTask 不得创建文件级锁").doesNotExist();
    }

    @Test
    @DisplayName("h) blockTask 无列表锁：.lock 不存在、{1}.json.lock 与 {2}.json.lock 存在且关系写入")
    void blockTaskTakesNoListLock() throws IOException {
        TaskService service = newService();
        String listId = "lock-block-list";

        service.createTask(listId, Task.create("任务一", ""));
        service.createTask(listId, Task.create("任务二", ""));
        Files.deleteIfExists(listLockPath(service, listId));

        boolean blocked = service.blockTask(listId, "1", "2");

        assertThat(blocked).isTrue();
        // blockTask 无列表锁（对齐 CC tasks.ts:458-486）；两次 updateTask 各走文件级锁 → 预建两个锁文件
        assertThat(listLockPath(service, listId)).as("blockTask 不得创建列表级锁").doesNotExist();
        assertThat(fileLockPath(service, listId, "1")).as("blockTask 对任务1应走文件级锁").exists();
        assertThat(fileLockPath(service, listId, "2")).as("blockTask 对任务2应走文件级锁").exists();

        // 关系断言：task1.blocks 含 2、task2.blockedBy 含 1
        Task task1 = service.getTask(listId, "1").orElseThrow();
        Task task2 = service.getTask(listId, "2").orElseThrow();
        assertThat(task1.blocks()).contains("2");
        assertThat(task2.blockedBy()).contains("1");
    }

    @Test
    @DisplayName("i) updateTask 忽略列表锁（负向强断言）：测试线程持 .lock 后 updateTask 仍成功")
    void updateTaskIgnoresListLock() throws Exception {
        TaskService service = newService();
        String listId = "lock-ignore-list-update";

        service.createTask(listId, Task.create("任务一", ""));
        Path listLock = listLockPath(service, listId);

        FileLock held = holdLock(listLock);
        try {
            // 测试线程持有列表级锁 .lock，updateTask 走文件级锁 {1}.json.lock（不同锁文件）→ 不被阻塞
            // 粒度区分证明：文件级操作不受列表锁阻塞
            Optional<Task> updated = service.updateTask(listId, "1", Map.of("subject", "列表锁下更新"));
            assertThat(updated).isPresent();
            assertThat(updated.get().subject()).isEqualTo("列表锁下更新");
        } finally {
            held.channel().close(); // 释放测试持有的列表锁
        }
    }

    @Test
    @DisplayName("j) createTask 忽略文件锁（负向强断言）：测试线程持 {1}.json.lock 后 createTask 仍成功")
    void createTaskIgnoresFileLock() throws Exception {
        TaskService service = newService();
        String listId = "lock-ignore-file-create";

        // 预置任务目录 + 文件级锁文件 {1}.json.lock（对齐 TaskLock ensureFileLockFile 预建语义）
        service.ensureTasksDir(listId);
        Path fileLock = fileLockPath(service, listId, "1");
        FileLock held = holdLock(fileLock);
        try {
            // 测试线程持有文件级锁 {1}.json.lock，createTask 走列表级锁 .lock（不同锁文件）→ 不被阻塞
            String id = service.createTask(listId, Task.create("文件锁下创建", ""));
            assertThat(id).isEqualTo("1");
        } finally {
            held.channel().close(); // 释放测试持有的文件锁
        }
    }

    @Test
    @DisplayName("k) createTask 被列表锁阻塞（正向互斥证明）：测试线程持 .lock 后 createTask 抛 LockAcquisitionException")
    void createTaskBlockedByListLock() throws Exception {
        TaskService service = newService();
        String listId = "lock-block-create";

        // 预置任务目录 + 列表级锁文件（对齐 TaskLock ensureLockFile 预建语义）
        service.ensureTasksDir(listId);
        Path listLock = listLockPath(service, listId);
        FileLock held = holdLock(listLock);
        try {
            // 同类锁互斥：createTask 走列表级锁 .lock，与测试线程持有的 .lock 重叠
            // 同 JVM tryLock 抛 OverlappingFileLockException → withRetryLoop 捕获退避 30 次（~2.7s）
            // → 确定性抛 LockAcquisitionException
            assertThatThrownBy(() -> service.createTask(listId, Task.create("阻塞创建", "")))
                .isInstanceOf(TaskLock.LockAcquisitionException.class);
        } finally {
            held.channel().close();
        }
    }

    @Test
    @DisplayName("l) updateTask 被文件锁阻塞（正向互斥证明）：测试线程持 {1}.json.lock 后 updateTask 抛 LockAcquisitionException")
    void updateTaskBlockedByFileLock() throws Exception {
        TaskService service = newService();
        String listId = "lock-block-update";

        service.createTask(listId, Task.create("任务一", ""));
        Path fileLock = fileLockPath(service, listId, "1");
        FileLock held = holdLock(fileLock);
        try {
            // 同类锁互斥：updateTask(Map) 走文件级锁 {1}.json.lock，与测试线程持有的锁重叠
            // → OverlappingFileLockException → 退避 30 次 → LockAcquisitionException
            assertThatThrownBy(() -> service.updateTask(listId, "1", Map.of("subject", "阻塞更新")))
                .isInstanceOf(TaskLock.LockAcquisitionException.class);
        } finally {
            held.channel().close();
        }
    }

    @Test
    @DisplayName("m) claimTask 锁耗尽不抛异常 → task_not_found（对齐 CC tasks.ts:601-606 catch）：测试线程持 {1}.json.lock 后 claimTask 返回 TaskNotFound")
    void claimTaskBlockedByFileLockReturnsTaskNotFound() throws Exception {
        TaskService service = newService();
        String listId = "lock-block-claim";

        service.createTask(listId, Task.create("任务一", ""));
        Path fileLock = fileLockPath(service, listId, "1");
        FileLock held = holdLock(fileLock);
        try {
            // 文件级锁被测试线程持有 → withFileLockAndReturn 内 tryLock 重叠
            // （OverlappingFileLockException → withRetryLoop 退避 30 次 ~2.7s）→ 锁耗尽
            // 对齐 CC tasks.ts:601-606：catch(error) → logForDebugging + logError + task_not_found，
            // 不得向上抛 LockAcquisitionException
            ClaimTaskResult result = service.claimTask(listId, "1", "agent-a");
            assertThat(result).isInstanceOf(ClaimTaskResult.TaskNotFound.class);
        } finally {
            held.channel().close();
        }
    }

    @Test
    @DisplayName("n) claimTask(checkAgentBusy=true) 锁耗尽不抛异常 → task_not_found（对齐 CC tasks.ts:681-686 catch）：测试线程持 .lock 后返回 TaskNotFound")
    void claimTaskWithBusyCheckLockBlockedReturnsTaskNotFound() throws Exception {
        TaskService service = newService();
        String listId = "lock-block-claim-busy";

        service.createTask(listId, Task.create("任务一", ""));
        Path listLock = listLockPath(service, listId);
        FileLock held = holdLock(listLock);
        try {
            // 列表级锁被测试线程持有 → claimTaskWithBusyCheck 的 withLockAndReturn 内 tryLock 重叠
            // → 退避 30 次后锁耗尽；对齐 CC tasks.ts:681-686 catch → task_not_found，不得上抛
            ClaimTaskResult result = service.claimTask(listId, "1", "agent-a", true);
            assertThat(result).isInstanceOf(ClaimTaskResult.TaskNotFound.class);
        } finally {
            held.channel().close();
        }
    }

    /**
     * o/p) claimTask 两变体认领成功后必触发 notifyTasksUpdated。
     *
     * <p><b>WHY (意图验证)</b>: CC 认领成功写 owner 走的 updateTaskUnsafe / updateTask
     * 写盘后必 {@code notifyTasksUpdated()}（tasks.ts:366），因此 claimTask 认领成功必然
     * 触发 tasksUpdated signal（tasks.ts:596-600 文件锁变体 + tasks.ts:677-680 busy 变体），
     * 供 UI/消费者刷新任务列表；而失败分支（already_claimed / agent_busy）未走 updateTask，
     * 不得触发 notify（tasks.ts:575-577 / :667-674 直接 return）。
     *
     * <p>监听器为 {@link TaskService} 静态注册表，故 finally 中必须退订，避免跨用例污染。
     * createTask 自身也会 notify（TaskService.java:349），因此监听器在 createTask 完成后注册，
     * 计数仅统计 claimTask 触发的通知。
     */
    @Test
    @DisplayName("o) claimTask 文件锁变体认领成功必触发 notifyTasksUpdated：成功 +1、already_claimed 不 +1")
    void claimTaskSuccessNotifiesTasksUpdated() {
        TaskService service = newService();
        String listId = "notify-claim-list";

        service.createTask(listId, Task.create("任务一", ""));
        AtomicInteger notifyCount = new AtomicInteger(0);
        Runnable unsubscribe = TaskService.addListener(notifyCount::incrementAndGet);
        try {
            // 文件锁变体认领成功（镜像 CC tasks.ts:596-600 updateTaskUnsafe → :366 notifyTasksUpdated）
            ClaimTaskResult r1 = service.claimTask(listId, "1", "agent-a");
            assertThat(r1).isInstanceOf(ClaimTaskResult.Success.class);
            assertThat(notifyCount.get()).as("claimTask 认领成功必须触发 notifyTasksUpdated").isEqualTo(1);

            // 已被其他 agent 认领失败（镜像 CC tasks.ts:575-577 直接 return，未走 updateTask → 不 notify）
            ClaimTaskResult r2 = service.claimTask(listId, "1", "agent-b");
            assertThat(r2).isInstanceOf(ClaimTaskResult.AlreadyClaimed.class);
            // WHY: CC already_claimed 携带认领时的任务快照 task（tasks.ts:576 { task }），供调用方
            // UI 提示"任务已被谁认领" / 重试决策；Java 端 must 同步 task 字段（OPD-TS-11）
            ClaimTaskResult.AlreadyClaimed ac = (ClaimTaskResult.AlreadyClaimed) r2;
            assertThat(ac.task()).as("already_claimed 必须携带 task 快照（对齐 CC tasks.ts:576）").isNotNull();
            assertThat(ac.task().id()).isEqualTo("1");
            assertThat(ac.currentOwner()).as("currentOwner = 认领时 task.owner 快照").isEqualTo("agent-a");
            assertThat(notifyCount.get()).as("already_claimed 失败路径不得触发 notifyTasksUpdated").isEqualTo(1);
        } finally {
            unsubscribe.run();
        }
    }

    @Test
    @DisplayName("p) claimTask(checkAgentBusy=true) 列表锁变体认领成功必触发 notifyTasksUpdated：成功 +1、agent_busy 不 +1")
    void claimTaskWithBusyCheckSuccessNotifiesTasksUpdated() {
        TaskService service = newService();
        String listId = "notify-claim-busy-list";

        service.createTask(listId, Task.create("任务一", ""));
        service.createTask(listId, Task.create("任务二", ""));
        AtomicInteger notifyCount = new AtomicInteger(0);
        Runnable unsubscribe = TaskService.addListener(notifyCount::incrementAndGet);
        try {
            // busy 变体认领成功（镜像 CC tasks.ts:677-680 updateTask → updateTaskUnsafe → :366 notifyTasksUpdated）
            ClaimTaskResult r1 = service.claimTask(listId, "1", "agent-a", true);
            assertThat(r1).isInstanceOf(ClaimTaskResult.Success.class);
            assertThat(notifyCount.get()).as("claimTask(checkAgentBusy=true) 认领成功必须触发 notifyTasksUpdated").isEqualTo(1);

            // agent 已持有其他未完成任务 → agent_busy 失败（镜像 CC tasks.ts:667-674 直接 return，未走 updateTask → 不 notify）
            ClaimTaskResult r2 = service.claimTask(listId, "2", "agent-a", true);
            assertThat(r2).isInstanceOf(ClaimTaskResult.AgentBusy.class);
            // WHY: CC agent_busy 携带 task + busyWithTasks（tasks.ts:669-673 { task, busyWithTasks }），
            // 供调用方 UI 提示"agent 正忙哪些任务" / 重试决策；Java 端 must 同步（OPD-TS-11）
            ClaimTaskResult.AgentBusy ab = (ClaimTaskResult.AgentBusy) r2;
            assertThat(ab.task()).as("agent_busy 必须携带 task 快照（对齐 CC tasks.ts:669）").isNotNull();
            assertThat(ab.task().id()).isEqualTo("2");
            assertThat(ab.busyWithTasks()).as("busyWithTasks = agent 已持有的其他未完成任务 ID").containsExactly("1");
            assertThat(notifyCount.get()).as("agent_busy 失败路径不得触发 notifyTasksUpdated").isEqualTo(1);
        } finally {
            unsubscribe.run();
        }
    }

    @Test
    @DisplayName("q) 失败 case already_resolved / blocked 均携带 task 快照（对齐 CC tasks.ts:581/:593，OPD-TS-11）")
    void claimTaskFailureResolvedAndBlockedCarryTaskSnapshot() {
        TaskService service = newService();
        String listId = "claim-failure-snapshot-list";

        // 任务1：创建后标记 completed（后续认领 → already_resolved）
        service.createTask(listId, Task.create("已完成任务", ""));
        service.updateTask(listId, "1", Map.of("status", Task.TaskStatus.COMPLETED));
        // 任务2：未完成（in_progress）任务，作为阻塞源——CC 仅非 completed 任务阻塞（tasks.ts:586-588）
        service.createTask(listId, Task.create("阻塞源任务", ""));
        service.updateTask(listId, "2", Map.of("status", Task.TaskStatus.IN_PROGRESS));
        // 任务3：blockedBy=["2"]，任务2 未完成 → 阻塞（后续认领 → blocked）
        service.createTask(listId, Task.create("被阻塞任务", "").withBlockedByList(List.of("2")));

        // already_resolved：认领已完成任务（镜像 CC tasks.ts:579-582 → { reason:'already_resolved', task }）
        ClaimTaskResult r1 = service.claimTask(listId, "1", "agent-a");
        assertThat(r1).isInstanceOf(ClaimTaskResult.AlreadyResolved.class);
        // WHY: CC already_resolved 携带 task 快照（tasks.ts:581），供调用方提示"任务已完成不可认领"
        ClaimTaskResult.AlreadyResolved ar = (ClaimTaskResult.AlreadyResolved) r1;
        assertThat(ar.task()).as("already_resolved 必须携带 task 快照（对齐 CC tasks.ts:581）").isNotNull();
        assertThat(ar.task().id()).isEqualTo("1");

        // blocked：认领被未完成任务阻塞的任务（镜像 CC tasks.ts:589-594 → { reason:'blocked', task, blockedByTasks }）
        ClaimTaskResult r2 = service.claimTask(listId, "3", "agent-b");
        assertThat(r2).isInstanceOf(ClaimTaskResult.Blocked.class);
        // WHY: CC blocked 携带 task + blockedByTasks（tasks.ts:593），供调用方提示"被哪些任务阻塞"
        ClaimTaskResult.Blocked b = (ClaimTaskResult.Blocked) r2;
        assertThat(b.task()).as("blocked 必须携带 task 快照（对齐 CC tasks.ts:593）").isNotNull();
        assertThat(b.task().id()).isEqualTo("3");
        assertThat(b.blockedByTasks()).as("blocked 必须携带 blockedByTasks").containsExactly("2");
    }
}
