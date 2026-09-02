package com.nexusai.application.agent.tool;

/**
 * UI notification payload · 对齐 CC toolExecution.ts 中的 notification push 接口
 * (React 集成由 nexusai 前端负责, Java 端仅作为类型契约 + payload transport).
 */
public record Notification(
        // WHY: 通知唯一 ID, 前端 React 用作 React key + dedupe + audit log 关联
        String id,
        // WHY: 通知标题, UI top-level 展示 (CC notification.title)
        String title,
        // WHY: 通知正文, 用户展开后查看详情
        String body,
        // WHY: 通知级别 (INFO/SUCCESS/WARNING/ERROR), UI 据此选 icon + color (CC notification.level)
        Level level
) {
    public enum Level { INFO, SUCCESS, WARNING, ERROR }
}
