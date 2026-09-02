package com.nexusai.application.agent.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * s20 P1-3: 后台任务结果收集器 · Java 独有（CC 无 collectBackgroundResults 对应物）
 *
 * <p>CC 行为: 每轮 turn 主动 drain 一次后台任务完成通知 + 输出, 合并注入 messages.
 *
 * <p>与 NotificationQueue mid-turn drain (s13-P1-1b) 的差异:
 * <ul>
 *   <li>NotificationQueue: 每轮 turn 顶部 drain 通知 XML, 注入 user message</li>
 *   <li>BackgroundResultsCollector: 主动 pull 完成任务的累积输出, 用于 model 主动查询</li>
 * </ul>
 */
public class BackgroundResultsCollector {

    private static final Logger log = LoggerFactory.getLogger(BackgroundResultsCollector.class);

    private final BackgroundTaskRunner runner;

    public BackgroundResultsCollector(BackgroundTaskRunner runner) {
        this.runner = runner;
    }

    /**
     * 收集所有已完成任务的累积输出 · Java 独有（CC 无对应物）
     *
     * @return BackgroundResult 列表, 每个含 task_id / status / output / exit_code
     */
    public List<BackgroundResult> collectCompletedOutputs() {
        if (runner == null) return List.of();
        List<BackgroundResult> results = new ArrayList<>();
        for (BackgroundTask task : runner.listTasks()) {
            if (task.status() != BackgroundTaskStatus.COMPLETED
                && task.status() != BackgroundTaskStatus.FAILED
                && task.status() != BackgroundTaskStatus.KILLED) {
                continue;
            }
            String output = runner.readTaskOutput(task.id());
            // CC: exit code 仅对 COMPLETED/FAILED 有意义
            int exitCode = task.status() == BackgroundTaskStatus.COMPLETED ? 0 : -1;
            results.add(new BackgroundResult(
                task.id(), task.status().getStatusString(), output, exitCode));
        }
        if (log.isDebugEnabled()) {
            log.debug("BackgroundResultsCollector: collected {} results", results.size());
        }
        return results;
    }

    /**
     * 收集单个任务的输出 · 对齐 CC collectBackgroundResults(taskId)
     */
    public Optional<BackgroundResult> collectOutputFor(String taskId) {
        if (runner == null) return Optional.empty();
        Optional<BackgroundTask> taskOpt = runner.getTask(taskId);
        if (taskOpt.isEmpty()) return Optional.empty();
        BackgroundTask task = taskOpt.get();
        String output = runner.readTaskOutput(taskId);
        return Optional.of(new BackgroundResult(
            task.id(), task.status().getStatusString(), output,
            task.status() == BackgroundTaskStatus.COMPLETED ? 0 : -1));
    }

    /**
     * 后台任务结果 record · 对齐 CC BackgroundResult shape
     */
    public record BackgroundResult(
        String taskId,
        String status,
        String output,
        int exitCode
    ) {}
}