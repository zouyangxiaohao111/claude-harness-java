package com.nexusai.application.agent.remote;

import java.util.Optional;

/**
 * Remote task 类型枚举 · 对齐 CC RemoteAgentTask/RemoteAgentTask.tsx:60-64。
 *
 * <p>CC original（src/tasks/RemoteAgentTask/RemoteAgentTask.tsx:60-64）:
 * <pre>
 * const REMOTE_TASK_TYPES = ['remote-agent', 'ultraplan', 'ultrareview', 'autofix-pr', 'background-pr'] as const;
 * export type RemoteTaskType = (typeof REMOTE_TASK_TYPES)[number];
 * function isRemoteTaskType(v: string | undefined): v is RemoteTaskType {
 *   return (REMOTE_TASK_TYPES as readonly string[]).includes(v ?? '');
 * }
 * </pre>
 *
 * <p><b>WHY（规则九）</b>: completionCheckers 注册表（:78）按 RemoteTaskType 键分发 completionChecker；
 * restoreRemoteAgentTasks（:514）从 sidecar 元数据读取<b>未经校验的字符串</b> remoteTaskType，
 * 脏值/老版本缺字段（undefined）若不拦下会被直接写入任务状态，破坏 poll 期 completionChecker 分发与状态机。
 * isRemoteTaskType 守卫是恢复路径的安全闸，脏值按 CC :514 回退 'remote-agent'。
 */
public enum RemoteTaskType {

    /** CC original: 'remote-agent' — 默认最保守类型（:514 回退目标）. */
    REMOTE_AGENT("remote-agent"),
    /** CC original: 'ultraplan'. */
    ULTRAPLAN("ultraplan"),
    /** CC original: 'ultrareview'. */
    ULTRAREVIEW("ultrareview"),
    /** CC original: 'autofix-pr'（含 RemoteTaskMetadata owner/repo/prNumber）. */
    AUTOFIX_PR("autofix-pr"),
    /** CC original: 'background-pr'. */
    BACKGROUND_PR("background-pr");

    private final String value;

    RemoteTaskType(String value) {
        this.value = value;
    }

    /** CC wire 值（小写连字符字面量，:60）. */
    public String value() {
        return value;
    }

    /** CC isRemoteTaskType type guard（:62-64）— 未知/空/null 返回 false. */
    public static boolean isRemoteTaskType(String v) {
        if (v == null) {
            return false;
        }
        for (RemoteTaskType t : values()) {
            if (t.value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    /** 解析 wire 值 → enum；未知/空/null 返回 {@link Optional#empty()}（调用方按 CC :514 回退 REMOTE_AGENT）. */
    public static Optional<RemoteTaskType> fromValue(String v) {
        if (v == null) {
            return Optional.empty();
        }
        for (RemoteTaskType t : values()) {
            if (t.value.equals(v)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
