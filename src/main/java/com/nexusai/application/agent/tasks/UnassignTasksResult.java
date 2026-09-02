package com.nexusai.application.agent.tasks;

import java.util.List;

/**
 * 释放 teammate 任务的结果 · 对齐 CC tasks.ts:803-806 UnassignTasksResult
 *
 * <p>CC 真源（grep 自验，非注释）：
 * <pre>
 * export type UnassignTasksResult = {
 *   unassignedTasks: Array&lt;{ id: string; subject: string }&gt;   // tasks.ts:804
 *   notificationMessage: string                              // tasks.ts:805
 * }
 * </pre>
 *
 * <p>由 {@link TaskService#unassignTeammateTasks} 返回：被释放任务的 id/subject 快照 +
 * 通知消息。CC 原名 {@code unassignedTasks} / {@code notificationMessage}
 * （Open-ClaudeCode/src/utils/tasks.ts:803-806）。
 *
 * @param unassignedTasks 被释放任务的 id/subject 快照（CC original: unassignedTasks, tasks.ts:804）
 * @param notificationMessage 释放通知消息（CC original: notificationMessage, tasks.ts:805）
 */
public record UnassignTasksResult(
    List<UnassignedTask> unassignedTasks,
    String notificationMessage
) {

    /**
     * 单个被释放任务 · 对齐 CC tasks.ts:804 {@code { id: string; subject: string }}
     *
     * @param id      任务 ID（CC original: id, tasks.ts:804）
     * @param subject 任务标题（CC original: subject, tasks.ts:804）
     */
    public record UnassignedTask(String id, String subject) {}
}
