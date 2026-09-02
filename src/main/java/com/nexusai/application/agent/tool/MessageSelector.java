package com.nexusai.application.agent.tool;

import java.util.List;

/**
 * UI message selector payload · 对齐 CC toolExecution.ts 中的 openMessageSelector 接口
 * (React 集成由 nexusai 前端负责, Java 端仅作为类型契约 + payload transport).
 *
 * <p>同步语义: Java 端调用 ctx.openMessageSelector().accept(sel) 后**阻塞**等待 UI 响应,
 * 超时由前端 React 负责兜底 (默认 30s, 返回 null).
 */
public record MessageSelector(
        // WHY: selector 唯一 ID, 前端 React 用作 dedupe key
        String id,
        // WHY: selector 标题, UI 弹窗顶部展示 (CC messageSelector.title)
        String title,
        // WHY: 候选消息 ID 列表, UI 展示选项; null → 空不可变 List, UI 展示空状态
        List<String> options
) {
    public MessageSelector {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
