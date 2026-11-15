package com.nexusai.application.agent.tasks;

/**
 * 后台任务类型枚举 — 7 种值对齐 CC Task.ts:7-13
 *
 * <p>CC 源码 (Task.ts:7-13):
 * <pre>
 * export enum TaskType {
 *   LOCAL_BASH = 'local_bash',
 *   LOCAL_AGENT = 'local_agent',
 *   REMOTE_AGENT = 'remote_agent',
 *   IN_PROCESS_TEAMMATE = 'in_process_teammate',
 *   LOCAL_WORKFLOW = 'local_workflow',
 *   MONITOR_MCP = 'monitor_mcp',
 *   DREAM = 'dream',
 * }
 * </pre>
 *
 * <p>ID 前缀对齐 CC Task.ts:79-87 TASK_ID_PREFIXES: b/a/r/t/w/m/d
 */
public enum TaskType {
    LOCAL_BASH("local_bash", "b"),
    LOCAL_AGENT("local_agent", "a"),
    REMOTE_AGENT("remote_agent", "r"),
    IN_PROCESS_TEAMMATE("in_process_teammate", "t"),
    LOCAL_WORKFLOW("local_workflow", "w"),
    MONITOR_MCP("monitor_mcp", "m"),
    /**
     * Monitor 工具的命令 / WebSocket 任务（CC v2.1.325 的 kind=monitor）。
     *
     * <p>CC 把 monitor 存在 local_bash 的 {@code kind} 字段上；本仓 BackgroundTask 无 kind，
     * 因此用独立 type 让任务面板能显示「监控」而不是「命令」。id 前缀 'o' 避开
     * MONITOR_MCP 已占用的 'm'（前缀只影响 id 可读性，不影响行为）。
     */
    MONITOR("monitor", "o"),
    DREAM("dream", "d");

    private final String typeString;
    private final String idPrefix;

    TaskType(String typeString, String idPrefix) {
        this.typeString = typeString;
        this.idPrefix = idPrefix;
    }

    /** CC Task.ts:7-13 中的字符串值 */
    public String getTypeString() {
        return typeString;
    }

    /** CC Task.ts:79-87 TASK_ID_PREFIXES 单字符前缀 */
    public String getIdPrefix() {
        return idPrefix;
    }
}
