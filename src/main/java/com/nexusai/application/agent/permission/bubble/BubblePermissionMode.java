package com.nexusai.application.agent.permission.bubble;

/**
 * 冒泡权限模式 · 对齐 CC 字面量 'bubble'
 *
 * <p>CC 真源（已 grep 实证）：{@code types/permissions.ts:22-24}
 * {@code InternalPermissionMode = ExternalPermissionMode | 'auto' | 'bubble'} ——
 * bubble 是 PermissionMode 字符串联合的一个字面量，CC 无独立枚举、无 INHERIT/ISOLATED
 * 概念。fork 子 agent 定义 {@code forkSubagent.ts:67} {@code permissionMode: 'bubble'}；
 * 语义（{@code runAgent.ts:440-446}）为 bubble 模式必弹窗（{@code shouldAvoidPrompts = false}），
 * Ask 决策冒泡到父终端 interactive 弹窗。
 *
 * <p>Java 以单值枚举表达该字面量，仅保留 {@link #BUBBLE}；历史 Java 扩展
 * INHERIT / ISOLATED（继承/独立权限）CC 无对应，已删除。
 *
 * @see SubagentPermissionContext
 * @see PermissionBubbleService
 */
public enum BubblePermissionMode {

    /**
     * Ask 决策冒泡到父 agent（对齐 CC 'bubble' 字面量）。
     * <p>子 agent 遇到 Ask 时，请求冒泡到父 agent 的用户弹窗。
     */
    BUBBLE;
}
