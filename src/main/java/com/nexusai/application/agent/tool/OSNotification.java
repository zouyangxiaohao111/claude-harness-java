package com.nexusai.application.agent.tool;

/**
 * UI OS-level notification payload · 对齐 CC {@code Tool.ts:211-214 sendOSNotification}
 * (桌面通知由 nexusai 前端 / Electron 壳触发, Java 端仅作为类型契约 + payload transport).
 *
 * <p><b>CC 真源</b> (探查 EV-H3-023): {@code Tool.ts:211-214}
 * <pre>
 * sendOSNotification?: (opts: {
 *   message: string
 *   notificationType: string
 * }) => void
 * </pre>
 * 实参证据 {@code utils/computerUse/wrapper.tsx:221-224}:
 * {@code sendOSNotification?.({message: …, notificationType: 'computer_use_enter'})}.
 *
 * <p><b>IMP-H4 对齐</b>: 字段由 Java 旧自定义 {@code (title, body, icon)} 收敛为 CC
 * {@code (message, notificationType)} (前端契约同步 nexusai 前端按 CC 形状渲染).
 */
public record OSNotification(
        // CC original: message (Tool.ts:212)
        String message,
        // CC original: notificationType (Tool.ts:213)
        String notificationType
) { }
