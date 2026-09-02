package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * hwm-write 定向测试 · 对齐 CC tasks.ts createTask 不写 HWM（△-3 修复）
 *
 * <p><b>WHY (意图验证)</b>: CC 把「任务文件本身」作为最高 ID 的权威——
 * {@code createTask}（tasks.ts:283-308）只算 {@code id = max(findHighestTaskIdFromFiles, readHighWaterMark) + 1}
 * 后写任务文件，<b>从不写 HWM</b>（grep 实证 tasks.ts:283-308 无 writeHighWaterMark）；
 * HWM 仅在 {@code deleteTask}（tasks.ts:405，删文件前提升防复用）与
 * {@code resetTaskList}（tasks.ts:161，重置前提升防复用）时写入。
 *
 * <p>本测试验证 4 个意图：
 * <ol>
 *   <li><b>create 不写 HWM</b>：连续 createTask 后 .highwatermark 文件不存在 / readHighWaterMark()==0，
 *       且两次 ID 为 1、2（由文件最高驱动，而非 HWM 驱动）。</li>
 *   <li><b>HWM 作下限仍生效</b>：先设高 HWM 再 createTask，新 ID = HWM+1
 *       （对齐 CC findHighestTaskId = max(fromFiles, fromMark)，tasks.ts:271-277）。</li>
 *   <li><b>delete 提升 HWM 兜底</b>：deleteTask 删除最高任务后 createTask 不复用被删 ID
 *       （CC deleteTask tasks.ts:405 删前提升，防文件删除后 ID 复用）。</li>
 *   <li><b>reset 提升 HWM</b>：resetTaskList 后 readHighWaterMark()== 重置前文件最高
 *       （CC resetTaskList tasks.ts:161，仅当 currentHighest &gt; existingMark）。</li>
 * </ol>
 *
 * <p>回归锚点：旧实现（删除前）createTask 在写文件后额外写 HWM（脏代码），
 * 本测试用例 1 即防该行为回退（回归后 .highwatermark 文件会存在、HWM 非 0）。
 *
 * <p>参考 CC 源码（grep 自验，非注释）：
 * <ul>
 *   <li>{@code createTask: const id = String(highestId + 1); ... await writeFile(path, ...)} — tasks.ts:296-300</li>
 *   <li>{@code findHighestTaskId = Math.max(fromFiles, fromMark)} — tasks.ts:271-277</li>
 *   <li>{@code deleteTask: if (numericId > currentMark) await writeHighWaterMark(...)} — tasks.ts:401-407</li>
 *   <li>{@code resetTaskList: if (currentHighest > existingMark) await writeHighWaterMark(...)} — tasks.ts:161-166</li>
 * </ul>
 */
class TaskServiceCreateTaskHwmTest {

    @TempDir
    Path tempDir;

    private TaskService newService() {
        // 显式 configHome 构造器：隔离真实 ~/.claude 目录，逐用例独立 @TempDir
        return new TaskService(tempDir);
    }

    @Test
    @DisplayName("createTask 不写 HWM：连续创建两次 ID=1,2，.highwatermark 不存在 / readHighWaterMark()==0")
    void createTaskDoesNotWriteHighWaterMark() {
        TaskService service = newService();
        String listId = "hwm-create-list";

        String id1 = service.createTask(listId, Task.create("任务一", "第一个任务"));
        String id2 = service.createTask(listId, Task.create("任务二", "第二个任务"));

        // 文件最高驱动：ID 连续 1、2（对齐 CC createTask tasks.ts:296-297）
        assertThat(id1).isEqualTo("1");
        assertThat(id2).isEqualTo("2");

        // create 不写 HWM：.highwatermark 文件不存在，readHighWaterMark()==0
        Path hwmPath = service.getHighWaterMarkPath(listId);
        assertThat(Files.exists(hwmPath)).as(".highwatermark 文件不得由 createTask 创建").isFalse();
        assertThat(service.readHighWaterMark(listId)).as("HWM 保持 0").isEqualTo(0L);
    }

    @Test
    @DisplayName("HWM 作下限仍生效：先设高 HWM=100，createTask 返回 101（max(fromFiles, fromMark)+1）")
    void highWaterMarkActsAsFloor() throws Exception {
        TaskService service = newService();
        String listId = "hwm-floor-list";

        // 预置高 HWM（模拟外部/重置后留下的 mark）。
        // 与生产一致：HWM 只在任务目录已存在时写入（writeHighWaterMark 与 CC writeFile
        // 均不创建父目录，CC writeFile 对不存在的父目录抛 ENOENT），故先 ensureTasksDir。
        service.ensureTasksDir(listId);
        service.writeHighWaterMark(listId, 100L);

        String id = service.createTask(listId, Task.create("高 HWM 下创建", "desc"));

        // 对齐 CC findHighestTaskId = max(fromFiles, fromMark)（tasks.ts:271-277）：HWM=100 > 文件最高 0 → 101
        assertThat(id).isEqualTo("101");
        // HWM 本身仍未被 createTask 改写（仍为预置的 100，而非被推到 101）
        assertThat(service.readHighWaterMark(listId)).isEqualTo(100L);
    }

    @Test
    @DisplayName("deleteTask 提升 HWM 兜底：删最高任务(3)后 createTask 不复用 ID，返回 4")
    void deleteTaskRaisesHwmPreventsIdReuse() {
        TaskService service = newService();
        String listId = "hwm-delete-list";

        service.createTask(listId, Task.create("一", ""));
        service.createTask(listId, Task.create("二", ""));
        String id3 = service.createTask(listId, Task.create("三", ""));

        // deleteTask 删除最高任务（对齐 CC deleteTask tasks.ts:405：numericId > currentMark 时写 HWM）
        assertThat(service.deleteTask(listId, id3)).isTrue();

        // createTask 不得复用被删 ID 3（HWM 已提升为 3 → 新 ID 4）
        String newId = service.createTask(listId, Task.create("四", ""));
        assertThat(newId).isEqualTo("4");
    }

    @Test
    @DisplayName("resetTaskList 提升 HWM：重置前文件最高=3，reset 后 readHighWaterMark()==3，后续 createTask 返回 4")
    void resetTaskListRaisesHwm() {
        TaskService service = newService();
        String listId = "hwm-reset-list";

        service.createTask(listId, Task.create("一", ""));
        service.createTask(listId, Task.create("二", ""));
        service.createTask(listId, Task.create("三", ""));

        // resetTaskList：currentHighest=3 > existingMark=0 → 写 HWM=3（对齐 CC tasks.ts:161-166）
        service.resetTaskList(listId);

        assertThat(service.readHighWaterMark(listId)).as("reset 后 HWM 提升为重置前文件最高").isEqualTo(3L);
        assertThat(service.listTasks(listId)).as("reset 后任务文件清空").isEmpty();

        // 清空后 createTask 从 HWM 下限继续：max(3, 0)+1 = 4，不回到 1
        String newId = service.createTask(listId, Task.create("重置后首个", ""));
        assertThat(newId).isEqualTo("4");
    }

    @Test
    @DisplayName("resetTaskList 跳过点文件：.X.json 不被删除（对齐 CC tasks.ts:173 !file.startsWith('.')）")
    void resetTaskListSkipsDotFiles() throws Exception {
        TaskService service = newService();
        String listId = "hwm-reset-dotfile-list";

        service.createTask(listId, Task.create("一", ""));

        // 预置点文件：CC resetTaskList 仅删除 endsWith('.json') && !startsWith('.') 的文件
        // （tasks.ts:173），.X.json 属点文件，必须保留
        Path dotFile = service.getTasksDir(listId).resolve(".hidden.json");
        Files.writeString(dotFile, "{\"keep\":true}");

        service.resetTaskList(listId);

        assertThat(Files.exists(dotFile)).as(".X.json 点文件不得被 resetTaskList 删除").isTrue();
        assertThat(service.listTasks(listId)).as("普通任务文件仍被清空").isEmpty();
    }
}
