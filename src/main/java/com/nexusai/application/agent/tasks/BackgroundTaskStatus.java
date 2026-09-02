package com.nexusai.application.agent.tasks;

/**
 * 后台任务状态枚举 — 5 种值对齐 CC Task.ts:15-20
 *
 * <p>CC 源码 (Task.ts:15-20):
 * <pre>
 * export enum TaskStatus {
 *   PENDING = 'pending',
 *   RUNNING = 'running',
 *   COMPLETED = 'completed',
 *   FAILED = 'failed',
 *   KILLED = 'killed',
 * }
 * </pre>
 *
 * <p>CC isTerminalTaskStatus (Task.ts:27-29): completed, failed, killed
 */
public enum BackgroundTaskStatus {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    KILLED("killed");

    private final String statusString;

    BackgroundTaskStatus(String statusString) {
        this.statusString = statusString;
    }

    /** CC Task.ts:15-20 中的字符串值 */
    public String getStatusString() {
        return statusString;
    }

    /**
     * 是否为终态 — 对齐 CC Task.ts:27-29 isTerminalTaskStatus
     * <pre>
     * return status === 'completed' || status === 'failed' || status === 'killed'
     * </pre>
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == KILLED;
    }
}
