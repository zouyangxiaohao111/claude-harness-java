package com.nexusai.application.agent.tasks;

import java.util.List;

/**
 * 任务认领结果 · 对齐 CC tasks.ts:488-505 ClaimTaskResult
 *
 * <h2>CC 对齐</h2>
 * <p>CC ClaimTaskResult type（tasks.ts:488-505）是 discriminated union：
 * <pre>
 * type ClaimTaskResult =
 *   | { success: true, task: Task }
 *   | { success: false, reason: 'task_not_found' }
 *   | { success: false, reason: 'already_claimed', task: Task }
 *   | { success: false, reason: 'already_resolved', task: Task }
 *   | { success: false, reason: 'blocked', task: Task, blockedByTasks: string[] }
 *   | { success: false, reason: 'agent_busy', task: Task, busyWithTasks: string[] }
 * </pre>
 *
 * <p>Java 使用 sealed interface + 6 record 实现，pattern matching 替代 discriminated union。
 * CC 有 5 个失败 reason + 1 个成功 case = 6 个 case。
 *
 * <h2>6 个 case</h2>
 * <ol>
 *   <li>{@link Success} — 认领成功，包含已认领的任务</li>
 *   <li>{@link TaskNotFound} — 任务不存在</li>
 *   <li>{@link AlreadyClaimed} — 任务已被其他 agent 认领，包含当前 owner</li>
 *   <li>{@link AlreadyResolved} — 任务已完成</li>
 *   <li>{@link Blocked} — 任务被其他未完成任务阻塞，包含阻塞任务 ID 列表</li>
 *   <li>{@link AgentBusy} — 认领 agent 已有未完成任务，包含正在处理的任务 ID 列表</li>
 * </ol>
 *
 * @see TaskService#claimTask(String, String, String)
 */
public sealed interface ClaimTaskResult {

    /**
     * 认领成功 · 对齐 CC { success: true, task: Task }
     */
    record Success(Task task) implements ClaimTaskResult {}

    /**
     * 任务不存在 · 对齐 CC { success: false, reason: 'task_not_found' }
     */
    record TaskNotFound() implements ClaimTaskResult {}

    /**
     * 已被其他 agent 认领 · 对齐 CC { success: false, reason: 'already_claimed', task }
     *
     * @param currentOwner 当前持有者 agent ID（认领时 task.owner 快照，CC original: task.owner, tasks.ts:576）
     * @param task         认领时的任务快照（CC original: task, tasks.ts:576）
     */
    record AlreadyClaimed(String currentOwner, Task task) implements ClaimTaskResult {}

    /**
     * 任务已完成 · 对齐 CC { success: false, reason: 'already_resolved', task }
     *
     * @param task 认领时的任务快照（CC original: task, tasks.ts:581）
     */
    record AlreadyResolved(Task task) implements ClaimTaskResult {}

    /**
     * 被未完成任务阻塞 · 对齐 CC { success: false, reason: 'blocked', task, blockedByTasks }
     *
     * @param task           认领时的任务快照（CC original: task, tasks.ts:593）
     * @param blockedByTasks 阻塞此任务的任务 ID 列表（CC original: blockedByTasks, tasks.ts:593）
     */
    record Blocked(Task task, List<String> blockedByTasks) implements ClaimTaskResult {}

    /**
     * agent 正忙 · 对齐 CC { success: false, reason: 'agent_busy', task, busyWithTasks }
     *
     * @param task          认领时的任务快照（CC original: task, tasks.ts:669）
     * @param busyWithTasks agent 正在处理的任务 ID 列表（CC original: busyWithTasks, tasks.ts:669）
     */
    record AgentBusy(Task task, List<String> busyWithTasks) implements ClaimTaskResult {}

    // ════════════════════════════════════════════════════════════════════════
    // 静态工厂方法
    // ════════════════════════════════════════════════════════════════════════

    /** 创建成功结果 */
    static ClaimTaskResult success(Task task) {
        return new Success(task);
    }

    /** 创建任务不存在结果 */
    static ClaimTaskResult taskNotFound() {
        return new TaskNotFound();
    }

    /** 创建已被认领结果 */
    static ClaimTaskResult alreadyClaimed(String currentOwner, Task task) {
        return new AlreadyClaimed(currentOwner, task);
    }

    /** 创建已完成结果 */
    static ClaimTaskResult alreadyResolved(Task task) {
        return new AlreadyResolved(task);
    }

    /** 创建被阻塞结果 */
    static ClaimTaskResult blocked(Task task, List<String> blockedByTasks) {
        return new Blocked(task, blockedByTasks);
    }

    /** 创建 agent 正忙结果 */
    static ClaimTaskResult agentBusy(Task task, List<String> busyWithTasks) {
        return new AgentBusy(task, busyWithTasks);
    }
}
