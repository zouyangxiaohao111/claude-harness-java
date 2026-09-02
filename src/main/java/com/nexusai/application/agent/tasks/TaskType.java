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
