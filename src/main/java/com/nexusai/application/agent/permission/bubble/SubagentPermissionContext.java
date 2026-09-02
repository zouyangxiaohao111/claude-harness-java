package com.nexusai.application.agent.permission.bubble;

/**
 * 子 agent 权限上下文 · 对齐 CC forkSubagent.ts FORK_AGENT
 *
 * <p>封装子 agent 在 fork 链中的身份与冒泡权限模式。
 * <ul>
 *   <li>{@link #agentId} — 子 agent ID（必填）</li>
 *   <li>{@link #parentAgentId} — 父 agent ID（可为 {@code null}，表示顶层 agent）</li>
 *   <li>{@link #mode} — 冒泡权限模式（默认 {@link BubblePermissionMode#BUBBLE}，对齐 CC
 *       fork 子 agent 的 {@code permissionMode: 'bubble'}，forkSubagent.ts:67）</li>
 * </ul>
 *
 * <p>历史 Java 扩展（递归深度 {@code depth}、工具黑名单 {@code deniedTools} 及
 * {@code isInChild/canFork/fork/isToolDenied}）CC 无对应概念，已删除 —— CC 的
 * 防递归 fork 守卫是 {@code forkSubagent.ts:78-89 isInForkChild}（按对话历史特征检测），
 * 非深度计数。
 *
 * @see BubblePermissionMode
 * @see PermissionBubbleService
 */
public record SubagentPermissionContext(
        String agentId,
        String parentAgentId,
        BubblePermissionMode mode
) {

    /**
     * Compact constructor：不变量保护。
     *
     * <p>WHY:
     * <ul>
     *   <li>{@code agentId=null} 会让冒泡日志丢失 agent 身份，无法审计</li>
     *   <li>{@code mode=null} 默认为 {@link BubblePermissionMode#BUBBLE}（CC 默认行为，
     *       forkSubagent.ts:67）</li>
     * </ul>
     */
    public SubagentPermissionContext {
        if (agentId == null) {
            throw new IllegalArgumentException("agentId is null");
        }
        if (mode == null) {
            mode = BubblePermissionMode.BUBBLE;
        }
    }
}
